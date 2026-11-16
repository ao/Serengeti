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
 * Accounts, stored as bcrypt hashes in {@code users.json} in the data directory (mode 0600).
 *
 * <p>Each account carries the stamp of its last change, and a deleted account stays as a tombstone with the
 * stamp of its deletion, so the accounts of a cluster merge like its rows: the newest change to a name wins on
 * every node, in any order (see {@link #merge}). An account created from {@code SERENGETI_ADMIN_PASSWORD} at
 * first start has the oldest possible stamp, so any later change wins over it; two such accounts on different
 * nodes resolve the same way everywhere (by hash), which is why every node should get the same password.
 *
 * <p>bcrypt at cost 12 takes a few hundred milliseconds per check, so a successful check is remembered
 * for 60 seconds under the SHA-256 of the user name and password. Only the digest is kept in memory,
 * never the password, and any change to the accounts clears the cache.
 */
public final class Users {
    public static final String FILE = "users.json";
    public static final String ADMIN = "admin";
    public static final int MIN_PASSWORD = 12;
    /** bcrypt ignores everything after 72 bytes, so longer passwords are refused instead. */
    public static final int MAX_PASSWORD_BYTES = 72;
    static final long CACHE_MILLIS = 60_000;
    private static final int CACHE_MAX = 10_000;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    /** The stamp of an account made at first start from the environment: older than any change. */
    static final Stamp BOOTSTRAP = new Stamp(0, 0, "bootstrap");

    /** What an account may do. Each role includes the ones below it. */
    public enum Role {
        /** Everything: also accounts and backups. */
        ADMIN,
        /** Reads, writes and schema changes. */
        WRITE,
        /** SELECT and SHOW only. */
        READ;

        public String label() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** "admin", "write" or "read"; "user", the 2.0 name for an account that is not admin, means write. */
        public static Role parse(String s) {
            return switch (s.toLowerCase(java.util.Locale.ROOT)) {
                case "admin" -> ADMIN;
                case "write", "user" -> WRITE;
                case "read" -> READ;
                default -> throw new IllegalArgumentException("role must be admin, write or read");
            };
        }

        public boolean mayWrite() {
            return this != READ;
        }
    }

    /** One account, or the tombstone of a deleted one ({@code hash} and {@code role} null). */
    private record Account(String hash, Role role, Stamp at) {
        boolean deleted() {
            return hash == null;
        }

        /** The newer of two versions of a name, the same on every node: by stamp, then by hash. */
        static Account newer(Account a, Account b) {
            int c = a.at.compareTo(b.at);
            if (c != 0) {
                return c > 0 ? a : b;
            }
            String ka = a.deleted() ? "~" : a.hash;
            String kb = b.deleted() ? "~" : b.hash;
            return ka.compareTo(kb) >= 0 ? a : b;
        }
    }

    private record Cached(String user, Role role, long expires) {}

    private final Path file;
    private final int cost;
    private final LongSupplier clock;
    private final Map<String, Account> accounts = new TreeMap<>();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile Supplier<Stamp> stamps;
    private volatile Consumer<List<Map<String, Object>>> listener = changed -> { };

    private Users(Path file, int cost, LongSupplier clock) {
        this.file = file;
        this.cost = cost;
        this.clock = clock;
        this.stamps = () -> new Stamp(clock.getAsLong(), 0, "local");
    }

    public static Users open(Path dataDir) throws IOException {
        return open(dataDir, 12, System::currentTimeMillis);
    }

    static Users open(Path dataDir, int cost, LongSupplier clock) throws IOException {
        Users u = new Users(dataDir.resolve(FILE), cost, clock);
        if (Files.exists(u.file)) {
            if (!PrivateFiles.isPrivate(u.file)) {
                throw new IOException(u.file + " must be readable by its owner only (chmod 600)");
            }
            Map<String, Object> root = Json.parseObject(Files.readString(u.file, StandardCharsets.UTF_8));
            if (!(root.get("users") instanceof Map<?, ?> m)) {
                throw new IOException(u.file + ": no \"users\" object");
            }
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> a) || !(a.get("hash") instanceof String h)
                        || !(a.get("role") instanceof String r)) {
                    throw new IOException(u.file + ": malformed entry for " + e.getKey());
                }
                Stamp at = a.get("at") instanceof String s ? Stamp.parse(s) : Stamp.ZERO; // 2.0 and 2.1 files have none
                u.accounts.put((String) e.getKey(), new Account(h, Role.parse(r), at));
            }
            if (root.get("read_users") instanceof Map<?, ?> r) {
                for (Map.Entry<?, ?> e : r.entrySet()) {
                    if (!(e.getValue() instanceof Map<?, ?> a) || !(a.get("hash") instanceof String h)) {
                        throw new IOException(u.file + ": malformed entry for " + e.getKey());
                    }
                    Stamp at = a.get("at") instanceof String s ? Stamp.parse(s) : Stamp.ZERO;
                    u.accounts.put((String) e.getKey(), new Account(h, Role.READ, at));
                }
            }
            if (root.get("deleted") instanceof Map<?, ?> d) {
                for (Map.Entry<?, ?> e : d.entrySet()) {
                    if (!(e.getValue() instanceof String s)) {
                        throw new IOException(u.file + ": malformed deletion of " + e.getKey());
                    }
                    u.accounts.putIfAbsent((String) e.getKey(), new Account(null, null, Stamp.parse(s)));
                }
            }
        }
        return u;
    }

    /** Where the stamps of account changes come from: the node's clock, so they order with its writes. */
    public void setStamps(Supplier<Stamp> stamps) {
        this.stamps = stamps;
    }

    /** Called, outside any lock, with the entries of every change made on this node (for its peers). */
    public void setListener(Consumer<List<Map<String, Object>>> listener) {
        this.listener = listener == null ? changed -> { } : listener;
    }

    public synchronized boolean isSetUp() {
        return accounts.values().stream().anyMatch(a -> !a.deleted() && a.role() == Role.ADMIN);
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

    /** Creates or replaces an account and writes the file before returning. */
    public void setPassword(String user, String password, Role role) throws IOException {
        set(user, password, role, null);
    }

    /** The admin account made at first start from the environment, stamped older than any change. */
    public void bootstrapAdmin(String password) throws IOException {
        set(ADMIN, password, Role.ADMIN, BOOTSTRAP);
    }

    private void set(String user, String password, Role role, Stamp at) throws IOException {
        String problem = nameProblem(user);
        if (problem == null) {
            problem = passwordProblem(password);
        }
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(cost)); // slow: outside the lock
        Map<String, Object> entry;
        synchronized (this) {
            Account a = new Account(hash, role, at != null ? at : stamps.get());
            Account old = accounts.get(user);
            if (role != Role.ADMIN && old != null && !old.deleted() && old.role() == Role.ADMIN && admins() == 1) {
                throw new IllegalStateException("'" + user + "' is the only admin; make another admin first");
            }
            Map<String, Account> next = new TreeMap<>(accounts);
            next.put(user, a);
            save(next);
            accounts.put(user, a);
            cache.clear();
            entry = entry(user, a);
        }
        listener.accept(List.of(entry));
    }

    /** Changes the role of an existing account. False if there is no such account. */
    public boolean setRole(String user, Role role) throws IOException {
        Map<String, Object> entry;
        synchronized (this) {
            Account old = accounts.get(user);
            if (old == null || old.deleted()) {
                return false;
            }
            if (role != Role.ADMIN && old.role() == Role.ADMIN && admins() == 1) {
                throw new IllegalStateException("'" + user + "' is the only admin; make another admin first");
            }
            Account a = new Account(old.hash(), role, stamps.get());
            Map<String, Account> next = new TreeMap<>(accounts);
            next.put(user, a);
            save(next);
            accounts.put(user, a);
            cache.clear();
            entry = entry(user, a);
        }
        listener.accept(List.of(entry));
        return true;
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
            Account a = new Account(null, null, stamps.get());
            Map<String, Account> next = new TreeMap<>(accounts);
            next.put(user, a);
            save(next);
            accounts.put(user, a);
            cache.clear();
            entry = entry(user, a);
        }
        listener.accept(List.of(entry));
        return true;
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

    /** Every account and tombstone, as sent to peers: name, stamp, and hash and role or {@code deleted}. */
    public synchronized List<Map<String, Object>> entries() {
        List<Map<String, Object>> out = new ArrayList<>();
        accounts.forEach((name, a) -> out.add(entry(name, a)));
        return out;
    }

    private static Map<String, Object> entry(String name, Account a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", name);
        m.put("at", a.at().toString());
        if (a.deleted()) {
            m.put("deleted", true);
        } else {
            m.put("hash", a.hash());
            m.put("role", a.role().label());
        }
        return m;
    }

    /** Most entries taken in one merge, to bound what a peer can make a node hold. */
    static final int MAX_ENTRIES = 10_000;

    /**
     * Merges accounts from a peer: for each name the newer version wins (see {@link Account#newer}), so merging
     * is idempotent and order-independent. Returns how many names changed; writes the file when any did.
     *
     * @throws IllegalArgumentException if an entry is malformed; nothing is merged then
     */
    public int merge(List<?> entries) throws IOException {
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("at most " + MAX_ENTRIES + " accounts");
        }
        Map<String, Account> in = new HashMap<>();
        for (Object o : entries) {
            if (!(o instanceof Map<?, ?> m) || !(m.get("user") instanceof String user) || nameProblem(user) != null
                    || !(m.get("at") instanceof String at)) {
                throw new IllegalArgumentException("malformed account entry");
            }
            Account a;
            if (Boolean.TRUE.equals(m.get("deleted"))) {
                a = new Account(null, null, Stamp.parse(at));
            } else if (m.get("hash") instanceof String h && h.startsWith("$2") && h.length() == 60
                    && m.get("role") instanceof String r) {
                a = new Account(h, Role.parse(r), Stamp.parse(at));
            } else {
                throw new IllegalArgumentException("malformed account entry for " + user);
            }
            in.merge(user, a, Account::newer);
        }
        synchronized (this) {
            Map<String, Account> next = new TreeMap<>(accounts);
            int changed = 0;
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
            return changed;
        }
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
     * Writes the file so that 2.0 and 2.1 can still open it after a rollback: they know the roles "admin" and
     * "user" in {@code users} and ignore other keys, so write accounts are written as "user" (2.0's name for
     * them), read accounts go in {@code read_users}, and tombstones in {@code deleted}. After a rollback the
     * read accounts cannot log in, which is the safe way round.
     */
    private void save(Map<String, Account> next) throws IOException {
        Map<String, Object> users = new LinkedHashMap<>();
        Map<String, Object> readers = new LinkedHashMap<>();
        Map<String, Object> deleted = new LinkedHashMap<>();
        next.forEach((name, a) -> {
            if (a.deleted()) {
                deleted.put(name, a.at().toString());
                return;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hash", a.hash());
            m.put("role", a.role() == Role.WRITE ? "user" : a.role().label());
            m.put("at", a.at().toString());
            (a.role() == Role.READ ? readers : users).put(name, m);
        });
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("users", users);
        if (!readers.isEmpty()) {
            root.put("read_users", readers);
        }
        if (!deleted.isEmpty()) {
            root.put("deleted", deleted);
        }
        PrivateFiles.writeAtomically(file, Json.write(root) + "\n");
    }

    /** The role of a user whose password matches, or empty. Unknown users cost the same as wrong passwords. */
    public Optional<Role> verify(String user, String password) {
        if (user == null || password == null || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return Optional.empty();
        }
        String key = digest(user, password);
        long now = clock.getAsLong();
        Cached c = cache.get(key);
        if (c != null && c.expires() > now && c.user().equals(user)) {
            return Optional.of(c.role());
        }
        Account a = null;
        for (int attempt = 0; ; attempt++) {
            Account current;
            synchronized (this) {
                current = accounts.get(user);
            }
            if (a != null && a.equals(current)) {
                break; // checked against the account as it still is
            }
            if (current != null && current.deleted()) {
                current = null;
            }
            if (attempt == 3 || a != null && current == null) {
                return Optional.empty(); // deleted while being checked, or changing too fast to check
            }
            // Check against a fixed hash for unknown users, so response time does not reveal which names exist.
            String hash = current != null ? current.hash() : dummyHash();
            if (!BCrypt.checkpw(password, hash) || current == null) {
                return Optional.empty();
            }
            // The account may change while bcrypt runs (a new password, a merge from a peer): look again.
            a = current;
        }
        if (cache.size() >= CACHE_MAX) {
            cache.clear();
        }
        cache.put(key, new Cached(user, a.role(), now + CACHE_MILLIS));
        return Optional.of(a.role());
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
