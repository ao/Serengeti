package com.ataiva.serengeti.core.server;

import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.storage.Stamp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Accounts, stored as bcrypt hashes in the data directory: {@code accounts.json} holds them all with the
 * stamps that order their changes, and {@code users.json} holds the admin and write accounts that reach every
 * database in the form 2.0 and 2.1 read, so a node can be rolled back. Both are mode 0600.
 *
 * <p>A read or write account may be limited to some databases ({@link Access#databases}). The list is part of
 * the account: an admin change, stamped and merged like a role.
 *
 * <p>Accounts merge across a cluster like rows: the newest change to a name wins on every node, in any order
 * (see {@link #merge}), and a deleted account stays as a tombstone. Two stamps order an account's changes.
 * {@code at} is the stamp of its last admin change: creating it, a password or role set by an admin, deleting
 * it. {@code pw_at} is the stamp of its own last password change on top of that version. A newer {@code at}
 * always wins, so an account cannot undo its deletion or demotion by changing its password on a node that
 * has not heard of it yet; that password change is lost instead.
 *
 * <p>The admin made at first start from {@code SERENGETI_ADMIN_PASSWORD} has the oldest possible stamp, so
 * any later change wins over it and a new node does not reset a cluster's admin password. If no admin is left
 * (two deleted on two nodes at once, which no node can stop alone), the next start with that setting makes
 * the admin again with a current stamp, and a start without it writes a setup token.
 *
 * <p>bcrypt at cost 12 takes a few hundred milliseconds per check, so a successful check is remembered
 * for 60 seconds under the SHA-256 of the user name and password. Only the digest is kept in memory,
 * never the password, and any change to the accounts clears the cache.
 */
public final class Users {
    /** The accounts in the form 2.0 and 2.1 read: admin and write accounts that reach every database. */
    public static final String FILE = "users.json";
    /** Every account and tombstone with its stamps. 2.0 and 2.1 do not know this file. */
    public static final String ACCOUNTS_FILE = "accounts.json";
    public static final String ADMIN = "admin";
    public static final int MIN_PASSWORD = 12;
    /** bcrypt ignores everything after 72 bytes, so longer passwords are refused instead. */
    public static final int MAX_PASSWORD_BYTES = 72;
    static final long CACHE_MILLIS = 60_000;
    private static final int CACHE_MAX = 10_000;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    /** The most databases one account may be limited to. */
    public static final int MAX_DATABASES = 100;
    /**
     * A database name in a stored list (from a peer or this node's file): the parser's form. Not checked
     * against the keywords, so that a word reserved by a later version does not make the file unreadable.
     */
    private static final Pattern STORED_DB = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    /**
     * A bcrypt hash this library can check ({@code $2a$}; it refuses {@code $2b$} and {@code $2y$}). From a
     * peer, at cost 4 to 14, so that a peer cannot make each login here cost hours; in this node's own files
     * any cost, as the operator chose it.
     */
    private static final Pattern PEER_HASH = Pattern.compile("\\$2a\\$(0[4-9]|1[0-4])\\$[./A-Za-z0-9]{53}");
    private static final Pattern LOCAL_HASH = Pattern.compile("\\$2a\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}");
    /**
     * A peer's account change stamped further ahead of this node's clock than this is left out, as row changes
     * are (Cluster.MAX_CLOCK_AHEAD_MILLIS), and taken once the clocks agree.
     */
    public static final long MAX_AHEAD_MILLIS = 60_000;
    /** The admin-change stamp of an account first seen in a users.json that 2.0 or 2.1 wrote: after BOOTSTRAP. */
    static final Stamp LEGACY = new Stamp(1, 0, "legacy");
    /** The stamp of an account made at first start from the environment: older than any change. */
    static final Stamp BOOTSTRAP = new Stamp(0, 0, "bootstrap");

    /** What an account may do. Each role includes the ones below it. */
    public enum Role {
        /** Everything: also accounts and backups. */
        ADMIN,
        /** Reads, writes and schema changes. */
        WRITE,
        /** SELECT and SHOW only. */
        READ,
        /** GET /metrics and /v1/status, and its own password: nothing of the data. */
        METRICS;

        public String label() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /**
         * "admin", "write", "read" or "metrics"; "user", the 2.0 name for an account that is not admin, means
         * write.
         */
        public static Role parse(String s) {
            return switch (s.toLowerCase(java.util.Locale.ROOT)) {
                case "admin" -> ADMIN;
                case "write", "user" -> WRITE;
                case "read" -> READ;
                case "metrics" -> METRICS;
                default -> throw new IllegalArgumentException("role must be admin, write, read or metrics");
            };
        }

        public boolean mayWrite() {
            return this == ADMIN || this == WRITE;
        }

        /** Whether the role may see data: queries, the change feed, the console. */
        public boolean mayRead() {
            return this != METRICS;
        }

        /** Whether an account with this role may be limited to some databases: read and write only. */
        public boolean mayBeLimited() {
            return this == READ || this == WRITE;
        }
    }

    /**
     * What an account may reach: its role, and the databases a limited read or write account may use (sorted,
     * as the parser writes names). {@code databases} is null for an account that reaches every database.
     */
    public record Access(Role role, List<String> databases) {
        public Access {
            java.util.Objects.requireNonNull(role, "role");
            databases = databases == null ? null : List.copyOf(databases);
        }

        public Access(Role role) {
            this(role, null);
        }

        public boolean limited() {
            return databases != null;
        }

        /** Whether the account may use database {@code db}: always without a list. */
        public boolean mayUse(String db) {
            return databases == null || databases.contains(db);
        }
    }

    /** What an admin change does to an account's databases: keep them, remove the limit, or set a list. */
    public static final class Limit {
        /** Leaves the list as it is (none for a new account). */
        public static final Limit KEEP = new Limit(true, null);
        /** Removes the list: the account reaches every database. */
        public static final Limit NONE = new Limit(false, null);

        private final boolean keep;
        private final List<String> databases;

        private Limit(boolean keep, List<String> databases) {
            this.keep = keep;
            this.databases = databases;
        }

        /**
         * A limit to {@code names}: 1 to {@link #MAX_DATABASES} database names as query text writes them,
         * folded to lowercase as the parser folds them, with none named twice.
         *
         * @throws IllegalArgumentException with the reason, if the list is not that
         */
        public static Limit to(List<?> names) {
            if (names == null || names.isEmpty() || names.size() > MAX_DATABASES) {
                throw new IllegalArgumentException("databases is a list of 1 to " + MAX_DATABASES + " database names");
            }
            java.util.TreeSet<String> out = new java.util.TreeSet<>();
            for (Object o : names) {
                if (!(o instanceof String s)) {
                    throw new IllegalArgumentException("databases must be a list of database names (strings)");
                }
                String n = com.ataiva.serengeti.core.query.Query.nameOf(s);
                if (n == null) {
                    throw new IllegalArgumentException("'" + (s.length() > 64 ? s.substring(0, 64) + "..." : s)
                            + "' is not a database name: letters, digits and _, not starting with a digit, at most"
                            + " 63 characters, not a keyword");
                }
                if (!out.add(n)) {
                    throw new IllegalArgumentException("database " + n + " is listed twice");
                }
            }
            return new Limit(false, List.copyOf(out));
        }

        public boolean keep() {
            return keep;
        }

        /** The list to set; null to remove it (or with {@link #keep}). */
        public List<String> databases() {
            return databases;
        }
    }

    /** The account after an admin change, and whether the change made it. */
    public record Changed(Access access, boolean created) {}

    /** An admin change without a password to a name that has no account. */
    public static final class NoSuchAccount extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        NoSuchAccount() {
            super("a new account needs a password", null);
        }
    }

    /**
     * One account, or the tombstone of a deleted one ({@code hash}, {@code role} and {@code databases} null).
     * {@code databases} is null for an account that reaches every database. {@code at} is the stamp of the last
     * admin change, {@code pwAt} of the account's own last password change after it ({@link Stamp#ZERO} if
     * none).
     */
    private record Account(String hash, Role role, List<String> databases, Stamp at, Stamp pwAt) {
        Account {
            pwAt = pwAt == null ? Stamp.ZERO : pwAt;
        }

        boolean deleted() {
            return hash == null;
        }

        Access access() {
            return new Access(role, databases);
        }

        /**
         * The newer of two versions of a name, the same on every node: by at, then pw_at, then hash, then the
         * list of databases.
         */
        static Account newer(Account a, Account b) {
            int c = a.at.compareTo(b.at);
            if (c == 0) {
                c = a.pwAt.compareTo(b.pwAt);
            }
            if (c != 0) {
                return c > 0 ? a : b;
            }
            String ka = a.deleted() ? "~" : a.hash;
            String kb = b.deleted() ? "~" : b.hash;
            c = ka.compareTo(kb);
            if (c == 0) {
                c = String.valueOf(a.databases).compareTo(String.valueOf(b.databases));
            }
            return c >= 0 ? a : b;
        }
    }

    private record Cached(String user, Access access, long expires) {}

    private final Path dir;
    private final int cost;
    private final LongSupplier clock;
    private final Map<String, Account> accounts = new TreeMap<>();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    /** Counts saves; both files carry it, so a load can tell whether an older version rewrote users.json. */
    private long version;
    private volatile Supplier<Stamp> stamps;
    private volatile Consumer<List<Map<String, Object>>> listener = changed -> { };

    private Users(Path dir, int cost, LongSupplier clock) {
        this.dir = dir;
        this.cost = cost;
        this.clock = clock;
        this.stamps = () -> new Stamp(clock.getAsLong(), 0, "local");
    }

    public static Users open(Path dataDir) throws IOException {
        return open(dataDir, 12, System::currentTimeMillis);
    }

    static Users open(Path dataDir, int cost, LongSupplier clock) throws IOException {
        Users u = new Users(dataDir, cost, clock);
        u.load();
        return u;
    }

    private void load() throws IOException {
        Path accountsFile = dir.resolve(ACCOUNTS_FILE);
        Path usersFile = dir.resolve(FILE);
        Map<String, Account> base = new TreeMap<>();
        long v = -1;
        if (Files.exists(accountsFile)) {
            Map<String, Object> root = readPrivate(accountsFile);
            if (!(root.get("v") instanceof Number n) || !(root.get("accounts") instanceof List<?> list)) {
                throw new IOException(accountsFile + ": no \"v\" and \"accounts\"");
            }
            v = n.longValue();
            for (Object o : list) {
                try {
                    Map.Entry<String, Account> e = parse(o, false);
                    base.put(e.getKey(), e.getValue());
                } catch (IllegalArgumentException e) {
                    throw new IOException(accountsFile + ": " + e.getMessage());
                }
            }
        }
        version = Math.max(v, 0);
        if (!Files.exists(usersFile)) {
            accounts.putAll(base);
            return;
        }
        Map<String, Object> root = readPrivate(usersFile);
        if (!(root.get("users") instanceof Map<?, ?> m)) {
            throw new IOException(usersFile + ": no \"users\" object");
        }
        long uv = root.get("_v") instanceof Number n ? n.longValue() : -1;
        // Written by this version at the same save as accounts.json, or one save behind it: accounts.json is
        // written first, so a stop between the two leaves users.json one behind, and accounts.json is right.
        if (v >= 0 && (uv == v || uv == v - 1)) {
            accounts.putAll(base);
            if (uv == v - 1) {
                // Bring users.json up to date now: a rollback to 2.1 would otherwise read the older one, with an
                // account deleted or demoted in the last save.
                save(new TreeMap<>(accounts));
            }
            return;
        }
        // Written or rewritten by 2.0 or 2.1, which know no stamps and cannot make or delete accounts or change
        // roles, only passwords. So an entry whose hash differs from accounts.json is the account's own
        // password change: it keeps its admin version (at), role and databases, and gets the file's time as
        // pw_at. An admin change made on 2.2 anywhere still wins over it. Read and metrics accounts, accounts
        // limited to some databases and tombstones are not in users.json and stay.
        Stamp legacy = new Stamp(Math.max(2, Files.getLastModifiedTime(usersFile).toMillis()), 0, "legacy");
        accounts.putAll(base);
        boolean changed = false;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String name) || nameProblem(name) != null
                    || !(e.getValue() instanceof Map<?, ?> a) || !(a.get("hash") instanceof String h)
                    || !(a.get("role") instanceof String r)) {
                throw new IOException(usersFile + ": malformed entry for " + e.getKey());
            }
            Role role;
            try {
                role = Role.parse(r);
            } catch (IllegalArgumentException ex) {
                throw new IOException(usersFile + ": " + ex.getMessage() + " for " + name);
            }
            if (!LOCAL_HASH.matcher(h).matches()) {
                throw new IOException(usersFile + ": the hash of " + name + " is not a bcrypt $2a$ hash this version"
                        + " can check");
            }
            Account b = base.get(name);
            if (b != null && !b.deleted() && b.hash().equals(h)) {
                continue;
            }
            if (b != null && b.deleted()) {
                continue; // deleted on 2.2; an older version cannot have made it again
            }
            Account acct = b == null
                    ? new Account(h, role, null, LEGACY, legacy)
                    : new Account(h, b.role(), b.databases(), b.at(),
                            Stamp.max(legacy, after(b.at(), b.pwAt(), legacy)));
            accounts.put(name, acct);
            changed = true;
        }
        if (changed || v < 0) {
            save(new TreeMap<>(accounts));
        }
    }

    private static Map<String, Object> readPrivate(Path f) throws IOException {
        if (!PrivateFiles.isPrivate(f)) {
            throw new IOException(f + " must be readable by its owner only (chmod 600)");
        }
        try {
            return Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException(f + " is not valid JSON: " + e.getMessage());
        }
    }

    /** Where the stamps of account changes come from: the node's clock, so they order with its writes. */
    public void setStamps(Supplier<Stamp> stamps) {
        this.stamps = stamps;
    }

    /** Called, outside any lock, with the entries of every change made on this node (for its peers). */
    public void setListener(Consumer<List<Map<String, Object>>> listener) {
        this.listener = listener == null ? changed -> { } : listener;
    }

    /** True while an admin exists: /v1/setup is refused then. */
    public synchronized boolean isSetUp() {
        return accounts.values().stream().anyMatch(a -> !a.deleted() && a.role() == Role.ADMIN);
    }

    /** True while any account exists: until then every route but /health, /ready and /v1/setup is refused. */
    public synchronized boolean hasAccounts() {
        return accounts.values().stream().anyMatch(a -> !a.deleted());
    }

    /** Checks a password against the rules. Returns an error message, or null if it is acceptable. */
    public static String passwordProblem(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            return "the password must be at least " + MIN_PASSWORD + " characters";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return "the password must be at most " + MAX_PASSWORD_BYTES + " bytes";
        }
        return null;
    }

    /** Checks a user name. Returns an error message, or null if it is acceptable. */
    public static String nameProblem(String user) {
        if (user == null || !NAME.matcher(user).matches()) {
            return "a user name is 1 to 64 letters, digits, '.', '_' or '-'";
        }
        return null;
    }

    /**
     * Creates an account or sets its password and role (an admin change) and writes the files before
     * returning. Its databases stay as they are.
     */
    public void setPassword(String user, String password, Role role) throws IOException {
        apply(user, password, role, Limit.KEEP, false, false);
    }

    /**
     * An admin's change to an account, written to the files before it returns: creates it (a password is
     * needed then) or changes its password, role and databases, any of them, as one change with one stamp. A
     * null password or role is left as it is; a new account without a role gets write.
     *
     * @throws NoSuchAccount if there is no account and no password
     * @throws IllegalArgumentException for a bad name or password, nothing to change, or databases on an admin
     *     or metrics account
     * @throws IllegalStateException if it would leave no admin
     */
    public Changed change(String user, String password, Role role, Limit limit) throws IOException {
        if (password == null && role == null && limit.keep()) {
            throw new IllegalArgumentException("give a password, a role or databases");
        }
        return apply(user, password, role, limit, false, false);
    }

    /** Limits an account to some databases, or with {@link Limit#NONE} removes the limit. False if there is no such account. */
    public boolean setDatabases(String user, Limit limit) throws IOException {
        try {
            apply(user, null, null, limit, false, false);
            return true;
        } catch (NoSuchAccount e) {
            return false;
        }
    }

    /** What {@link #bootstrapAdmin} did. */
    public enum Bootstrap {
        /** No accounts at all: the admin was made with the oldest stamp. */
        FIRST,
        /** Accounts but no admin: the admin was made with a current stamp, after every tombstone. */
        RECOVERED,
        /** An admin exists (one may have arrived from a peer meanwhile): nothing was changed. */
        SKIPPED
    }

    /** True while there is no entry at all, not even a tombstone: a first start. */
    public synchronized boolean isEmpty() {
        return accounts.isEmpty();
    }

    /**
     * Makes the admin from the environment when no admin exists. At first start (no accounts at all) it gets the
     * oldest stamp, so the cluster's own admin wins over it; when the admins were deleted, it gets a current
     * stamp, so it wins over their tombstones. If an admin exists when it comes to write (bcrypt runs first,
     * outside the lock), it changes nothing.
     */
    public Bootstrap bootstrapAdmin(String password) throws IOException {
        boolean recovering;
        synchronized (this) {
            recovering = !accounts.isEmpty();
        }
        // The admin reaches every database: an account named admin that was limited (demoted, then every admin
        // deleted) loses its list.
        if (apply(ADMIN, password, Role.ADMIN, Limit.NONE, !recovering, true) == null) {
            return Bootstrap.SKIPPED;
        }
        return recovering ? Bootstrap.RECOVERED : Bootstrap.FIRST;
    }

    /**
     * Makes the admin through {@code POST /v1/setup}, when no admin exists: as {@link #setPassword} with the admin
     * role, but an account named admin that was limited loses its list.
     */
    public void setUpAdmin(String password) throws IOException {
        apply(ADMIN, password, Role.ADMIN, Limit.NONE, false, false);
    }

    /**
     * An admin change: {@code password} or {@code role} null keeps it. Returns null, changing nothing, if
     * {@code onlyWithoutAdmin} and an admin exists by the time of the write.
     */
    private Changed apply(String user, String password, Role role, Limit limit, boolean bootstrap,
            boolean onlyWithoutAdmin) throws IOException {
        String problem = nameProblem(user);
        if (problem == null && password != null) {
            problem = passwordProblem(password);
        }
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        String hash = password == null ? null : BCrypt.hashpw(password, BCrypt.gensalt(cost)); // slow: outside the lock
        Map<String, Object> entry;
        Changed out;
        synchronized (this) {
            if (onlyWithoutAdmin && admins() > 0) {
                return null;
            }
            Account old = accounts.get(user);
            Account live = old == null || old.deleted() ? null : old;
            if (live == null && hash == null) {
                throw new NoSuchAccount();
            }
            Role to = role != null ? role : live != null ? live.role() : Role.WRITE;
            List<String> databases = limit.keep() ? live == null ? null : live.databases() : limit.databases();
            if (databases != null && !to.mayBeLimited()) {
                throw new IllegalArgumentException(limitProblem(to, limit.keep()));
            }
            if (to != Role.ADMIN && live != null && live.role() == Role.ADMIN && admins() == 1) {
                throw new IllegalStateException("'" + user + "' is the only admin; make another admin first");
            }
            Account a = new Account(hash != null ? hash : live.hash(), to, databases,
                    bootstrap ? BOOTSTRAP : next(old), Stamp.ZERO);
            entry = commit(user, a);
            out = new Changed(a.access(), live == null);
        }
        listener.accept(List.of(entry));
        return out;
    }

    private static String limitProblem(Role role, boolean kept) {
        String why = role == Role.ADMIN
                ? "an admin account reaches every database (accounts and backups)"
                : "a metrics account reads no data";
        return why + ", so it cannot be limited to databases; only read and write accounts can"
                + (kept ? ". This account has a list: give \"databases\": null with the new role" : "");
    }

    /**
     * An account changes its own password: {@code current} must still be its password. The role stays what it
     * is now (not what it was when the request was authenticated), and the change is stamped as the account's
     * own, so it never undoes an admin's delete or role change, here or on another node.
     *
     * @return false if the account does not exist (any more) or {@code current} is not its password
     */
    public boolean changeOwnPassword(String user, String current, String password) throws IOException {
        String problem = passwordProblem(password);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(cost)); // slow: outside the lock
        for (int attempt = 0; attempt < 3; attempt++) {
            Account was;
            synchronized (this) {
                was = accounts.get(user);
            }
            if (was == null || was.deleted() || current == null || !matches(current, was.hash())) {
                return false;
            }
            Map<String, Object> entry;
            synchronized (this) {
                if (!was.equals(accounts.get(user))) {
                    continue; // changed while bcrypt ran: check against what it is now
                }
                Account a = new Account(hash, was.role(), was.databases(), was.at(),
                        after(was.at(), was.pwAt(), stamps.get()));
                entry = commit(user, a);
            }
            listener.accept(List.of(entry));
            return true;
        }
        return false;
    }

    /**
     * Changes the role of an existing account; its databases stay. False if there is no such account.
     *
     * @throws IllegalArgumentException for the admin or metrics role on an account limited to databases
     */
    public boolean setRole(String user, Role role) throws IOException {
        try {
            apply(user, null, java.util.Objects.requireNonNull(role, "role"), Limit.KEEP, false, false);
            return true;
        } catch (NoSuchAccount e) {
            return false;
        }
    }

    /** Deletes an account, keeping its tombstone. False if there is no such account. */
    public boolean delete(String user) throws IOException {
        Map<String, Object> entry;
        synchronized (this) {
            Account old = accounts.get(user);
            if (old == null || old.deleted()) {
                return false;
            }
            if (old.role() == Role.ADMIN && admins() == 1) {
                throw new IllegalStateException("'" + user + "' is the only admin; make another admin first");
            }
            entry = commit(user, new Account(null, null, null, next(old), Stamp.ZERO));
        }
        listener.accept(List.of(entry));
        return true;
    }

    /** Under the lock: writes the files with {@code a} for {@code user}, then takes it. Returns its entry. */
    private Map<String, Object> commit(String user, Account a) throws IOException {
        Map<String, Account> next = new TreeMap<>(accounts);
        next.put(user, a);
        save(next);
        accounts.put(user, a);
        cache.clear();
        return entry(user, a);
    }

    /** A stamp for an admin change to a name: after every stamp it has, even if this node's clock is behind. */
    private Stamp next(Account old) {
        Stamp now = stamps.get();
        return old == null ? now : after(old.at(), old.pwAt(), now);
    }

    /** {@code now}, or just after the later of {@code a} and {@code b} if {@code now} is not after both. */
    private static Stamp after(Stamp a, Stamp b, Stamp now) {
        Stamp last = Stamp.max(a, b);
        if (now.after(last)) {
            return now;
        }
        // At the counter's limit (only a hostile or broken peer gets there), the next millisecond instead.
        return last.counter() == Integer.MAX_VALUE ? new Stamp(last.wall() + 1, 0, now.node())
                : new Stamp(last.wall(), last.counter() + 1, now.node());
    }

    private int admins() {
        int n = 0;
        for (Account a : accounts.values()) {
            if (!a.deleted() && a.role() == Role.ADMIN) {
                n++;
            }
        }
        return n;
    }

    /** The accounts that exist, by name, with their roles; never their hashes. */
    public synchronized Map<String, Role> list() {
        Map<String, Role> out = new TreeMap<>();
        accounts.forEach((name, a) -> {
            if (!a.deleted()) {
                out.put(name, a.role());
            }
        });
        return out;
    }

    /** The accounts that exist, by name, with what each may reach; never their hashes. */
    public synchronized Map<String, Access> accounts() {
        Map<String, Access> out = new TreeMap<>();
        accounts.forEach((name, a) -> {
            if (!a.deleted()) {
                out.put(name, a.access());
            }
        });
        return out;
    }

    /**
     * Every account and tombstone, as sent to peers: name, stamps, and hash, role and (for a limited account)
     * databases, or {@code deleted}.
     */
    public synchronized List<Map<String, Object>> entries() {
        List<Map<String, Object>> out = new ArrayList<>();
        accounts.forEach((name, a) -> out.add(entry(name, a)));
        return out;
    }

    private static Map<String, Object> entry(String name, Account a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", name);
        m.put("at", a.at().toString());
        if (a.pwAt().after(Stamp.ZERO)) {
            m.put("pw_at", a.pwAt().toString());
        }
        if (a.deleted()) {
            m.put("deleted", true);
        } else {
            m.put("hash", a.hash());
            m.put("role", a.role().label());
            if (a.databases() != null) {
                m.put("databases", a.databases());
            }
        }
        return m;
    }

    /**
     * One entry, from a peer ({@code fromPeer}, which bounds the bcrypt cost) or from this node's own file. A
     * {@code databases} key that is not a list as every node writes it (1 to 100 names, sorted, none twice), or
     * on an account that may not have one, makes the entry malformed: it is never read as no limit.
     */
    private Map.Entry<String, Account> parse(Object o, boolean fromPeer) {
        if (!(o instanceof Map<?, ?> m) || !(m.get("user") instanceof String user) || nameProblem(user) != null
                || !(m.get("at") instanceof String at)) {
            throw new IllegalArgumentException("malformed account entry");
        }
        Stamp stamp = Stamp.parse(at);
        Stamp pwStamp = m.get("pw_at") instanceof String p ? Stamp.parse(p) : Stamp.ZERO;
        Pattern hashes = fromPeer ? PEER_HASH : LOCAL_HASH;
        Account a;
        if (Boolean.TRUE.equals(m.get("deleted"))) {
            if (m.containsKey("databases")) {
                throw new IllegalArgumentException("malformed list of databases for " + user);
            }
            a = new Account(null, null, null, stamp, pwStamp);
        } else if (m.get("hash") instanceof String h && hashes.matcher(h).matches() && m.get("role") instanceof String r) {
            Role role = Role.parse(r);
            List<String> databases = null;
            if (m.containsKey("databases")) {
                databases = storedDatabases(m.get("databases"));
                if (databases == null || !role.mayBeLimited()) {
                    throw new IllegalArgumentException("malformed list of databases for " + user);
                }
            }
            a = new Account(h, role, databases, stamp, pwStamp);
        } else {
            throw new IllegalArgumentException("malformed account entry for " + user);
        }
        return Map.entry(user, a);
    }

    /** A list as every node writes it ({@link Limit#to}: 1 to 100 names, sorted, none twice), or null. */
    private static List<String> storedDatabases(Object o) {
        if (!(o instanceof List<?> list) || list.isEmpty() || list.size() > MAX_DATABASES) {
            return null;
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object x : list) {
            if (!(x instanceof String s) || !STORED_DB.matcher(s).matches()
                    || !out.isEmpty() && out.get(out.size() - 1).compareTo(s) >= 0) {
                return null;
            }
            out.add(s);
        }
        return List.copyOf(out);
    }

    /** Most entries taken in one merge, to bound what a peer can make a node hold. */
    static final int MAX_ENTRIES = 10_000;

    /** What a merge did: names changed, and entries left out for being stamped too far ahead. */
    public record Merged(int changed, int ahead) {}

    /** {@link #mergeFrom}, returning only how many names changed. */
    public int merge(List<?> entries) throws IOException {
        return mergeFrom(entries).changed();
    }

    /**
     * Merges accounts from a peer: for each name the newer version wins (see {@link Account#newer}), so merging
     * is idempotent and order-independent. Writes the files when any name changed. An entry stamped more than
     * {@link #MAX_AHEAD_MILLIS} ahead of this node's clock is left out (and counted), as row changes are: the
     * peer sends it again once the clocks agree.
     *
     * @throws IllegalArgumentException if an entry is malformed; nothing is merged then
     */
    public Merged mergeFrom(List<?> entries) throws IOException {
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("at most " + MAX_ENTRIES + " accounts");
        }
        Map<String, Account> in = new HashMap<>();
        long limit = clock.getAsLong() + MAX_AHEAD_MILLIS;
        int ahead = 0;
        for (Object o : entries) {
            Map.Entry<String, Account> e = parse(o, true);
            Account a = e.getValue();
            if (a.at().wall() > limit || a.pwAt().wall() > limit) {
                ahead++;
                continue;
            }
            in.merge(e.getKey(), a, Account::newer);
        }
        int changed = 0;
        synchronized (this) {
            Map<String, Account> next = new TreeMap<>(accounts);
            for (Map.Entry<String, Account> e : in.entrySet()) {
                Account old = next.get(e.getKey());
                Account won = old == null ? e.getValue() : Account.newer(old, e.getValue());
                if (!won.equals(old)) {
                    next.put(e.getKey(), won);
                    changed++;
                }
            }
            if (changed > 0) {
                save(next);
                accounts.clear();
                accounts.putAll(next);
                cache.clear();
            }
        }
        return new Merged(changed, ahead);
    }

    /** SHA-256 over the entries, to compare with a peer's cheaply. */
    public synchronized String digest() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map<String, Object> e : entries()) {
                md.update(Json.write(e).getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes accounts.json, then users.json, both with the next save count. users.json is what 2.0 and 2.1
     * read: they know the roles "admin" and "user" and ignore other keys, so write accounts are written as
     * "user" (2.0's name for them), and read and metrics accounts, accounts limited to some databases and
     * tombstones are left out. After a rollback those accounts cannot log in, which is the safe way round.
     */
    private void save(Map<String, Account> next) throws IOException {
        long v = version + 1;
        List<Object> all = new ArrayList<>();
        Map<String, Object> users = new LinkedHashMap<>();
        next.forEach((name, a) -> {
            all.add(entry(name, a));
            // Only the roles 2.0 and 2.1 know: they would take any other one for a write account. And no
            // limited account: they would ignore the list and let it reach every database.
            if (!a.deleted() && a.databases() == null && (a.role() == Role.ADMIN || a.role() == Role.WRITE)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("hash", a.hash());
                m.put("role", a.role() == Role.WRITE ? "user" : a.role().label());
                users.put(name, m);
            }
        });
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("v", v);
        root.put("accounts", all);
        PrivateFiles.writeAtomically(dir.resolve(ACCOUNTS_FILE), Json.write(root) + "\n");
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("_v", v);
        legacy.put("users", users);
        PrivateFiles.writeAtomically(dir.resolve(FILE), Json.write(legacy) + "\n");
        version = v;
    }

    /** The role of a user whose password matches, or empty. See {@link #authenticate}. */
    public Optional<Role> verify(String user, String password) {
        return authenticate(user, password).map(Access::role);
    }

    /**
     * What a user whose password matches may reach (role and databases, read together), or empty. Unknown
     * users cost the same as wrong passwords.
     */
    public Optional<Access> authenticate(String user, String password) {
        if (user == null || password == null || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return Optional.empty();
        }
        String key = digest(user, password);
        long now = clock.getAsLong();
        Cached c = cache.get(key);
        if (c != null && c.expires() > now && c.user().equals(user)) {
            return Optional.of(c.access());
        }
        Account a = null;
        for (int attempt = 0; ; attempt++) {
            Account current;
            synchronized (this) {
                current = accounts.get(user);
                if (a != null && a.equals(current)) {
                    // Checked against the account as it still is. Cached under the lock, which every change
                    // also takes to clear the cache, so a change cannot fall between the check and the put.
                    if (cache.size() >= CACHE_MAX) {
                        cache.clear();
                    }
                    cache.put(key, new Cached(user, a.access(), now + CACHE_MILLIS));
                    return Optional.of(a.access());
                }
            }
            if (current != null && current.deleted()) {
                current = null;
            }
            if (attempt == 3 || a != null && current == null) {
                return Optional.empty(); // deleted while being checked, or changing too fast to check
            }
            // Check against a fixed hash for unknown users, so response time does not reveal which names exist.
            String hash = current != null ? current.hash() : dummyHash();
            if (!matches(password, hash) || current == null) {
                return Optional.empty();
            }
            // The account may change while bcrypt runs (a new password, a merge from a peer): look again.
            a = current;
        }
    }

    /** bcrypt's check. A hash it cannot read (another revision, a broken salt) matches nothing. */
    private static boolean matches(String password, String hash) {
        try {
            return BCrypt.checkpw(password, hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String dummy;

    private synchronized String dummyHash() {
        if (dummy == null) {
            dummy = BCrypt.hashpw("not a real password, used for timing", BCrypt.gensalt(cost));
        }
        return dummy;
    }

    private static String digest(String user, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(user.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(password.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
