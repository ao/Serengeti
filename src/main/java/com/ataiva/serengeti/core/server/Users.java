package com.ataiva.serengeti.core.server;

import com.ataiva.serengeti.core.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Accounts, stored as bcrypt hashes in {@code users.json} in the data directory (mode 0600).
 *
 * <p>bcrypt at cost 12 takes a few hundred milliseconds per check, so a successful check is remembered
 * for 60 seconds under the SHA-256 of the user name and password. Only the digest is kept in memory,
 * never the password, and changing a password clears the cache.
 */
public final class Users {
    public static final String FILE = "users.json";
    public static final String ADMIN = "admin";
    public static final int MIN_PASSWORD = 12;
    /** bcrypt ignores everything after 72 bytes, so longer passwords are refused instead. */
    public static final int MAX_PASSWORD_BYTES = 72;
    static final long CACHE_MILLIS = 60_000;
    private static final int CACHE_MAX = 10_000;

    public enum Role {
        ADMIN, USER
    }

    private record Account(String hash, Role role) {}

    private record Cached(String user, Role role, long expires) {}

    private final Path file;
    private final int cost;
    private final LongSupplier clock;
    private final Map<String, Account> accounts = new HashMap<>();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private Users(Path file, int cost, LongSupplier clock) {
        this.file = file;
        this.cost = cost;
        this.clock = clock;
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
            Object list = root.get("users");
            if (!(list instanceof Map<?, ?> m)) {
                throw new IOException(u.file + ": no \"users\" object");
            }
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> a) || !(a.get("hash") instanceof String h)
                        || !(a.get("role") instanceof String r)) {
                    throw new IOException(u.file + ": malformed entry for " + e.getKey());
                }
                u.accounts.put((String) e.getKey(), new Account(h, Role.valueOf(r.toUpperCase(java.util.Locale.ROOT))));
            }
        }
        return u;
    }

    public synchronized boolean isSetUp() {
        return accounts.values().stream().anyMatch(a -> a.role() == Role.ADMIN);
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

    /** Creates or replaces an account and writes the file before returning. */
    public synchronized void setPassword(String user, String password, Role role) throws IOException {
        String problem = passwordProblem(password);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        Map<String, Account> next = new HashMap<>(accounts);
        next.put(user, new Account(BCrypt.hashpw(password, BCrypt.gensalt(cost)), role));
        save(next);
        accounts.clear();
        accounts.putAll(next);
        cache.clear();
    }

    private void save(Map<String, Account> next) throws IOException {
        Map<String, Object> users = new LinkedHashMap<>();
        next.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("hash", e.getValue().hash());
            a.put("role", e.getValue().role().name().toLowerCase(java.util.Locale.ROOT));
            users.put(e.getKey(), a);
        });
        PrivateFiles.writeAtomically(file, Json.write(Map.of("users", users)) + "\n");
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
        Account a;
        synchronized (this) {
            a = accounts.get(user);
        }
        // Check against a fixed hash for unknown users, so response time does not reveal which names exist.
        String hash = a != null ? a.hash() : dummyHash();
        boolean ok = BCrypt.checkpw(password, hash) && a != null;
        if (!ok) {
            return Optional.empty();
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
