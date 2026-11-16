package com.ataiva.serengeti.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ataiva.serengeti.core.storage.Stamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Account changes made on three nodes that merge each other's accounts now and then, so that many changes
 * are concurrent. Every entry any node held, merged on three new nodes in three orders and batches, gives the
 * same accounts and digest each time, the same as the three nodes once they have merged each other's, and
 * merging it again changes nothing. And entries in the form 2.2 wrote before the two parts of an account
 * merged apart merge as they did then.
 */
class UsersProperties {
    static final List<String> NODES = List.of("a", "b", "c");
    static final List<String> NAMES = List.of("admin", "app", "ops");
    static final List<String> PASSWORDS = List.of("password number 0", "password number 1", "password number 2");

    /**
     * {@code kind}: 0 a password set by an admin, 1 a role, 2 a list (none, [shop] or [blog, shop]), 3 a
     * password and a role at once, 4 a delete, 5 the account's own password change, 6 node merges from
     * {@code other}, 7 both ways.
     */
    record Step(int kind, int node, int name, int pw, int role, int list, int other) {}

    @Provide
    Arbitrary<List<Step>> histories() {
        Arbitrary<Integer> three = Arbitraries.integers().between(0, 2);
        return Combinators.combine(Arbitraries.integers().between(0, 7), three, three, three,
                Arbitraries.integers().between(0, 3), three, three).as(Step::new).list().ofMinSize(1).ofMaxSize(20);
    }

    @Property(tries = 200)
    void anyOrderOfTheSameEntriesGivesTheSameAccounts(@ForAll("histories") List<Step> steps, @ForAll Random random)
            throws IOException {
        Path base = tempDir();
        try {
            List<Users> nodes = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Path d = Files.createDirectories(base.resolve(NODES.get(i)));
                Users u = Users.open(d, 4, () -> 0L);
                long[] clock = {100 + random.nextInt(4)};
                String id = NODES.get(i);
                u.setStamps(() -> new Stamp(clock[0]++, 0, id));
                nodes.add(u);
            }
            nodes.get(0).setPassword("admin", PASSWORDS.get(0), Users.Role.ADMIN);
            nodes.get(1).merge(nodes.get(0).entries());
            nodes.get(2).merge(nodes.get(0).entries());
            Set<Map<String, Object>> seen = new LinkedHashSet<>();
            for (Step s : steps) {
                run(s, nodes);
                for (Users u : nodes) {
                    seen.addAll(u.entries());
                }
            }
            List<Map<String, Object>> pool = new ArrayList<>(seen);
            List<Users> fresh = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Users f = Users.open(Files.createDirectories(base.resolve("fresh" + i)), 4, () -> 0L);
                List<Map<String, Object>> order = new ArrayList<>(pool);
                Collections.shuffle(order, random);
                for (int at = 0; at < order.size(); ) {
                    int n = Math.min(order.size() - at, 1 + random.nextInt(4));
                    f.merge(order.subList(at, at + n));
                    at += n;
                }
                fresh.add(f);
            }
            String digest = fresh.get(0).digest();
            for (Users f : fresh) {
                assertEquals(digest, f.digest());
                assertEquals(fresh.get(0).accounts(), f.accounts());
                assertEquals(0, f.merge(pool), "merging again changes nothing");
            }
            for (int round = 0; round < 2; round++) {
                for (Users to : nodes) {
                    for (Users from : nodes) {
                        to.merge(from.entries());
                    }
                }
            }
            for (Users u : nodes) {
                assertEquals(digest, u.digest(), "the nodes end where any order of their entries ends");
            }
            // Every account that exists signs in with exactly one of the passwords set, with what it may reach.
            Users f = fresh.get(0);
            for (Map.Entry<String, Users.Access> e : f.accounts().entrySet()) {
                long n = PASSWORDS.stream().filter(p -> f.authenticate(e.getKey(), p).equals(java.util.Optional.of(e.getValue())))
                        .count();
                assertEquals(1, n, e.toString());
            }
        } finally {
            delete(base);
        }
    }

    private static void run(Step s, List<Users> nodes) throws IOException {
        Users u = nodes.get(s.node());
        String name = NAMES.get(s.name());
        String pw = PASSWORDS.get(s.pw());
        Users.Role role = Users.Role.values()[s.role()];
        Users.Limit limit = switch (s.list()) {
            case 0 -> Users.Limit.NONE;
            case 1 -> Users.Limit.to(List.of("shop"));
            default -> Users.Limit.to(List.of("blog", "shop"));
        };
        int other = s.other() == s.node() ? (s.node() + 1) % 3 : s.other();
        try {
            switch (s.kind()) {
                case 0 -> u.change(name, pw, null, Users.Limit.KEEP);
                case 1 -> u.change(name, null, role, Users.Limit.KEEP);
                case 2 -> u.change(name, null, null, limit);
                case 3 -> u.change(name, pw, role, Users.Limit.KEEP);
                case 4 -> u.delete(name);
                case 5 -> {
                    for (String current : PASSWORDS) {
                        if (u.authenticate(name, current).isPresent()) {
                            u.changeOwnPassword(name, current, pw);
                            break;
                        }
                    }
                }
                case 6 -> u.merge(nodes.get(other).entries());
                default -> {
                    u.merge(nodes.get(other).entries());
                    nodes.get(other).merge(u.entries());
                }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            // No such account, a list on an admin, the last admin: refused, and nothing changed.
        }
    }

    /**
     * Entries as 2.2 wrote them before the parts merged apart: at versioned the whole account, so entries with
     * one at share its access (one admin change made it) and differ in the account's own password changes.
     */
    @Property(tries = 500)
    void entriesInTheOldFormMergeAsTheyDidBefore(@ForAll Random random) throws IOException {
        List<Map<String, Object>> entries = new ArrayList<>();
        List<String> ats = List.of("0.0.bootstrap", "1.0.legacy", "100.0.a", "100.0.b", "101.0.a");
        List<String> pwAts = List.of("102.0.b", "103.0.a", "103.0.c");
        for (String name : List.of("x", "y")) {
            for (String at : ats) {
                if (random.nextInt(3) != 0) {
                    continue;
                }
                int kind = random.nextInt(6); // deleted, admin, write, read, metrics, limited write or read
                for (int n = 1 + random.nextInt(3); n > 0; n--) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("user", name);
                    m.put("at", at);
                    if (kind == 0) {
                        m.put("deleted", true);
                    } else {
                        if (random.nextBoolean()) {
                            m.put("pw_at", pwAts.get(random.nextInt(pwAts.size())));
                        }
                        m.put("hash", "$2a$04$" + String.valueOf((char) ('a' + random.nextInt(3))).repeat(53));
                        m.put("role", List.of("admin", "write", "read", "metrics", "read").get(kind - 1));
                        if (kind == 5) {
                            m.put("databases", List.of("shop"));
                        }
                    }
                    entries.add(m);
                }
            }
        }
        Map<String, Map<String, Object>> expected = new TreeMap<>();
        for (Map<String, Object> e : entries) {
            expected.merge((String) e.get("user"), e, UsersProperties::oldNewer);
        }
        Path base = tempDir();
        try {
            for (int i = 0; i < 2; i++) {
                Users u = Users.open(Files.createDirectories(base.resolve("n" + i)), 4, () -> 0L);
                List<Map<String, Object>> order = new ArrayList<>(entries);
                Collections.shuffle(order, random);
                if (i == 0) {
                    u.merge(order);
                } else {
                    for (Map<String, Object> e : order) {
                        u.merge(List.of(e));
                    }
                }
                assertEquals(new ArrayList<>(expected.values()), new ArrayList<Object>(u.entries()), entries.toString());
            }
        } finally {
            delete(base);
        }
    }

    /** How 2.2 merged two versions of an account before its parts merged apart (Users.Account.newer then). */
    static Map<String, Object> oldNewer(Map<String, Object> a, Map<String, Object> b) {
        int c = stamp(a, "at").compareTo(stamp(b, "at"));
        if (c == 0) {
            c = stamp(a, "pw_at").compareTo(stamp(b, "pw_at"));
        }
        if (c != 0) {
            return c > 0 ? a : b;
        }
        String ka = a.containsKey("deleted") ? "~" : (String) a.get("hash");
        String kb = b.containsKey("deleted") ? "~" : (String) b.get("hash");
        c = ka.compareTo(kb);
        if (c == 0) {
            c = String.valueOf(a.get("databases")).compareTo(String.valueOf(b.get("databases")));
        }
        return c >= 0 ? a : b;
    }

    private static Stamp stamp(Map<String, Object> m, String key) {
        return m.get(key) instanceof String s ? Stamp.parse(s) : Stamp.ZERO;
    }

    /**
     * On tmpfs where there is one: each save forces two files and the directory, and a try makes dozens of
     * saves.
     */
    private static Path tempDir() throws IOException {
        Path shm = Path.of("/dev/shm");
        return Files.isDirectory(shm) && Files.isWritable(shm) ? Files.createTempDirectory(shm, "users")
                : Files.createTempDirectory("users");
    }

    private static void delete(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
