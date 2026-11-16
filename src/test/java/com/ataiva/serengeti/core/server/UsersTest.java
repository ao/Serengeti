package com.ataiva.serengeti.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsersTest {
    private static final String PW = "correct horse battery";

    @TempDir
    Path dir;

    private final AtomicLong now = new AtomicLong(0);

    @Test
    void startsWithNoAccountsAndNoDefaultLogin() throws IOException {
        Users u = Users.open(dir, 4, now::get);
        assertFalse(u.isSetUp());
        assertEquals(Optional.empty(), u.verify("admin", "admin"));
        assertEquals(Optional.empty(), u.verify("admin", ""));
        assertFalse(Files.exists(dir.resolve(Users.FILE)));
    }

    @Test
    void passwordsSurviveARestartAsBcryptHashesInAPrivateFile() throws IOException {
        Users.open(dir, 4, now::get).setPassword("admin", PW, Users.Role.ADMIN);
        Path f = dir.resolve(Users.FILE);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
        String text = Files.readString(f);
        assertFalse(text.contains(PW));
        assertTrue(text.contains("$2a$04$"), text);

        Users again = Users.open(dir, 4, now::get);
        assertTrue(again.isSetUp());
        assertEquals(Optional.of(Users.Role.ADMIN), again.verify("admin", PW));
        assertEquals(Optional.empty(), again.verify("admin", PW + " "));
        assertEquals(Optional.empty(), again.verify("Admin", PW));
    }

    @Test
    void aReadableUsersFileIsRefused() throws IOException {
        Users.open(dir, 4, now::get).setPassword("admin", PW, Users.Role.ADMIN);
        Files.setPosixFilePermissions(dir.resolve(Users.FILE), PosixFilePermissions.fromString("rw-r--r--"));
        IOException e = assertThrows(IOException.class, () -> Users.open(dir, 4, now::get));
        assertTrue(e.getMessage().contains("chmod 600"));
    }

    @Test
    void malformedFilesAreRefused() throws IOException {
        Users.open(dir, 4, now::get).setPassword("admin", PW, Users.Role.ADMIN);
        PrivateFiles.writeAtomically(dir.resolve(Users.FILE), "{\"users\": {\"admin\": {\"role\": \"admin\"}}}");
        assertThrows(IOException.class, () -> Users.open(dir, 4, now::get));
        PrivateFiles.writeAtomically(dir.resolve(Users.FILE), "{}");
        assertThrows(IOException.class, () -> Users.open(dir, 4, now::get));
    }

    @Test
    void passwordRules() throws IOException {
        Users u = Users.open(dir, 4, now::get);
        assertNotNull(Users.passwordProblem(null));
        assertNotNull(Users.passwordProblem("elevenchars"));
        assertNull(Users.passwordProblem("twelve chars"));
        assertNull(Users.passwordProblem("x".repeat(72)));
        assertNotNull(Users.passwordProblem("x".repeat(73)));
        // 24 three-byte characters are 72 bytes; 25 are too many even though they are 25 characters.
        assertNull(Users.passwordProblem("€".repeat(24)));
        assertNotNull(Users.passwordProblem("€".repeat(25)));
        assertThrows(IllegalArgumentException.class, () -> u.setPassword("admin", "short", Users.Role.ADMIN));
        assertFalse(u.isSetUp());
    }

    @Test
    void passwordsThatDifferAfter72BytesAreNotConfused() throws IOException {
        Users u = Users.open(dir, 4, now::get);
        u.setPassword("admin", "x".repeat(72), Users.Role.ADMIN);
        assertEquals(Optional.empty(), u.verify("admin", "x".repeat(73)));
    }

    @Test
    void theVerificationCacheExpiresAndIsClearedByAPasswordChange() throws IOException {
        Users u = Users.open(dir, 4, now::get);
        u.setPassword("admin", PW, Users.Role.ADMIN);
        assertTrue(u.verify("admin", PW).isPresent());
        now.addAndGet(Users.CACHE_MILLIS + 1);
        assertTrue(u.verify("admin", PW).isPresent());
        u.setPassword("admin", "another long password", Users.Role.ADMIN);
        assertEquals(Optional.empty(), u.verify("admin", PW));
        assertTrue(u.verify("admin", "another long password").isPresent());
    }

    @Test
    void setupTokensArePrivateRandomAndStableAcrossRestarts() throws IOException {
        SetupToken a = SetupToken.create(dir);
        Path f = dir.resolve(SetupToken.FILE);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
        String text = Files.readString(f).trim();
        assertEquals(43, text.length());
        assertTrue(a.matches(text));
        assertTrue(a.matches(text + "\n"));
        assertFalse(a.matches(text.substring(1)));
        assertFalse(a.matches(""));
        assertFalse(a.matches(null));
        assertTrue(SetupToken.create(dir).matches(text), "a restart keeps the token");
        a.delete();
        assertFalse(Files.exists(f));
        assertFalse(SetupToken.create(dir).matches(text), "a new token after deletion");
    }

    @Test
    void loginLimiterCountsPerAddressInAWindow() {
        LoginLimiter l = new LoginLimiter(now::get);
        for (int i = 0; i < LoginLimiter.MAX_FAILURES - 1; i++) {
            l.failed("10.0.0.1");
        }
        assertEquals(0, l.retryAfterSeconds("10.0.0.1"));
        l.failed("10.0.0.1");
        assertEquals(60, l.retryAfterSeconds("10.0.0.1"));
        assertEquals(0, l.retryAfterSeconds("10.0.0.2"));
        now.addAndGet(30_000);
        assertEquals(30, l.retryAfterSeconds("10.0.0.1"));
        now.addAndGet(30_000);
        assertEquals(0, l.retryAfterSeconds("10.0.0.1"));
        l.failed("10.0.0.1");
        assertEquals(0, l.retryAfterSeconds("10.0.0.1"), "a new window starts from one failure");
    }

    private long tick = 100;

    private Users node(String name) throws IOException {
        Path d = dir.resolve(name);
        Files.createDirectories(d);
        Users u = Users.open(d, 4, now::get);
        u.setStamps(() -> new com.ataiva.serengeti.core.storage.Stamp(tick++, 0, name));
        return u;
    }

    @Test
    void rolesAreAdminWriteAndReadAndTheOldUserRoleMeansWrite() {
        assertEquals(Users.Role.ADMIN, Users.Role.parse("admin"));
        assertEquals(Users.Role.WRITE, Users.Role.parse("WRITE"));
        assertEquals(Users.Role.WRITE, Users.Role.parse("user"));
        assertEquals(Users.Role.READ, Users.Role.parse("read"));
        assertEquals(Users.Role.METRICS, Users.Role.parse("Metrics"));
        assertThrows(IllegalArgumentException.class, () -> Users.Role.parse("root"));
        assertFalse(Users.Role.READ.mayWrite());
        assertTrue(Users.Role.WRITE.mayWrite());
        assertTrue(Users.Role.ADMIN.mayWrite());
        assertFalse(Users.Role.METRICS.mayWrite());
        assertTrue(Users.Role.READ.mayRead());
        assertFalse(Users.Role.METRICS.mayRead());
        for (Users.Role r : Users.Role.values()) {
            assertEquals(r, Users.Role.parse(r.label()));
        }
    }

    @Test
    void namesAreShortAndPlain() throws IOException {
        Users u = node("a");
        for (String bad : new String[] {"", "a b", "a:b", "ä", "x".repeat(65), "a/b", null}) {
            assertNotNull(Users.nameProblem(bad), String.valueOf(bad));
        }
        assertNull(Users.nameProblem("ops.reader-1_x"));
        assertThrows(IllegalArgumentException.class, () -> u.setPassword("a:b", PW, Users.Role.READ));
    }

    @Test
    void theLastAdminCannotBeDeletedOrDemoted() throws IOException {
        Users u = node("a");
        u.setPassword("admin", PW, Users.Role.ADMIN);
        assertThrows(IllegalStateException.class, () -> u.delete("admin"));
        assertThrows(IllegalStateException.class, () -> u.setRole("admin", Users.Role.READ));
        assertThrows(IllegalStateException.class, () -> u.setPassword("admin", PW, Users.Role.WRITE));
        u.setPassword("second", PW, Users.Role.ADMIN);
        assertTrue(u.delete("admin"));
        assertFalse(u.delete("admin"), "already deleted");
        assertFalse(u.setRole("nobody", Users.Role.READ));
        assertTrue(u.isSetUp());
    }

    @Test
    void aDeletedAccountCannotLogInEvenFromTheCache() throws IOException {
        Users u = node("a");
        u.setPassword("admin", PW, Users.Role.ADMIN);
        u.setPassword("reader", PW, Users.Role.READ);
        assertEquals(Optional.of(Users.Role.READ), u.verify("reader", PW));
        assertTrue(u.delete("reader"));
        assertEquals(Optional.empty(), u.verify("reader", PW));
        assertEquals(java.util.Map.of("admin", Users.Role.ADMIN), u.list());
        Users again = Users.open(dir.resolve("a"), 4, now::get);
        assertEquals(Optional.empty(), again.verify("reader", PW), "the deletion survives a restart");
    }

    @Test
    void accountsMergeTheSameWayOnEveryNodeInAnyOrder() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        b.setPassword("writer", PW, Users.Role.WRITE); // stamp 101
        a.setPassword("writer", "another password 1", Users.Role.READ); // stamp 102, newer
        b.setPassword("gone", PW, Users.Role.WRITE);
        assertTrue(b.delete("gone"));
        var fromA = a.entries();
        var fromB = b.entries();
        assertEquals(1, a.merge(fromB), "only the tombstone of gone is new to a: its writer is newer");
        assertFalse(a.list().containsKey("gone"));
        b.merge(fromA);
        assertEquals(a.digest(), b.digest());
        assertEquals(a.list(), b.list());
        assertEquals(Users.Role.READ, b.list().get("writer"), "the newer change won");
        assertEquals(Optional.of(Users.Role.READ), b.verify("writer", "another password 1"));
        assertEquals(Optional.empty(), b.verify("writer", PW));
        assertEquals(0, a.merge(fromB), "merging again changes nothing");
        assertEquals(0, b.merge(a.entries()));
        assertTrue(b.isSetUp(), "the admin made on a reached b");
    }

    @Test
    void twoBootstrapAdminsResolveToTheSameHashEverywhere() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.bootstrapAdmin(PW);
        b.bootstrapAdmin(PW);
        a.merge(b.entries());
        b.merge(a.entries());
        assertEquals(a.digest(), b.digest());
        assertEquals(Optional.of(Users.Role.ADMIN), a.verify("admin", PW));
        // Any real change is newer than a bootstrap account.
        b.setPassword("admin", "changed password 1", Users.Role.ADMIN);
        a.merge(b.entries());
        assertEquals(Optional.of(Users.Role.ADMIN), a.verify("admin", "changed password 1"));
    }

    @Test
    void malformedEntriesFromAPeerAreRefusedWhole() throws IOException {
        Users a = node("a");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        String before = a.digest();
        var good = new java.util.LinkedHashMap<String, Object>(a.entries().get(0));
        good.put("user", "ok");
        for (var bad : java.util.List.<java.util.Map<String, Object>>of(
                java.util.Map.of("user", "x", "at", "5.0.n", "hash", "plain", "role", "write"),
                java.util.Map.of("user", "a b", "at", "5.0.n", "deleted", true),
                java.util.Map.of("user", "x", "hash", a.entries().get(0).get("hash"), "role", "write"),
                java.util.Map.of("user", "x", "at", "5.0.n", "hash", a.entries().get(0).get("hash"), "role", "root"))) {
            assertThrows(IllegalArgumentException.class, () -> a.merge(java.util.List.of(good, bad)), bad.toString());
        }
        assertEquals(before, a.digest(), "nothing of a refused batch was merged");
    }

    @Test
    void theFileStaysReadableByOlderVersions() throws IOException {
        Users a = node("a");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("writer", PW, Users.Role.WRITE);
        a.setPassword("reader", PW, Users.Role.READ);
        a.setPassword("scraper", PW, Users.Role.METRICS);
        a.setPassword("gone", PW, Users.Role.WRITE);
        a.delete("gone");
        a.change("shopper", PW, Users.Role.WRITE, Users.Limit.to(List.of("shop")));
        var root = com.ataiva.serengeti.core.json.Json.parseObject(Files.readString(dir.resolve("a").resolve(Users.FILE)));
        @SuppressWarnings("unchecked")
        var users = (java.util.Map<String, java.util.Map<String, Object>>) root.get("users");
        // 2.0 and 2.1 read "users" and know only the roles admin and user (they would take any other role
        // for user); read and metrics accounts and tombstones are only in accounts.json, which they do not read.
        // Nor is a write account limited to some databases: they would ignore the list and let it reach all.
        assertEquals(java.util.Set.of("admin", "writer"), users.keySet());
        assertFalse(Files.readString(dir.resolve("a").resolve(Users.FILE)).contains("shop"));
        assertEquals("user", users.get("writer").get("role"));
        assertEquals(java.util.Set.of("_v", "users"), root.keySet());
        assertEquals("rw-------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(dir.resolve("a").resolve(Users.ACCOUNTS_FILE))));
        Users again = Users.open(dir.resolve("a"), 4, now::get);
        assertEquals(a.list(), again.list());
        assertEquals(a.digest(), again.digest());
        // 2.1 rewrites users.json with what it knows: no save count, no read accounts.
        PrivateFiles.writeAtomically(dir.resolve("a").resolve(Users.FILE), "{\"users\": {\"admin\": {\"hash\": \""
                + users.get("admin").get("hash") + "\", \"role\": \"admin\"}, \"writer\": {\"hash\": \""
                + users.get("writer").get("hash") + "\", \"role\": \"user\"}}}");
        Users old = Users.open(dir.resolve("a"), 4, now::get);
        assertEquals(Optional.of(Users.Role.ADMIN), old.verify("admin", PW));
        assertEquals(a.digest(), old.digest(), "nothing changed there: every stamp and the read account are kept");
        assertEquals(Users.Role.READ, old.list().get("reader"));
        assertEquals(Users.Role.METRICS, old.list().get("scraper"));
        assertFalse(old.list().containsKey("gone"));
        assertEquals(new Users.Access(Users.Role.WRITE, List.of("shop")), old.accounts().get("shopper"),
                "the limited account and its list are kept too");
        // An existing write account that gets a list leaves users.json at that save; removing the list puts it
        // back, so 2.1 then finds it again with its password.
        assertTrue(old.setDatabases("writer", Users.Limit.to(List.of("blog"))));
        assertEquals(java.util.Set.of("admin"), legacyUsers(dir.resolve("a")).keySet());
        assertTrue(old.change("writer", null, Users.Role.READ, Users.Limit.KEEP).access().limited(),
                "a role change keeps the list");
        assertTrue(old.change("writer", null, Users.Role.WRITE, Users.Limit.NONE) != null);
        assertEquals(java.util.Set.of("admin", "writer"), legacyUsers(dir.resolve("a")).keySet());
        assertEquals("user", legacyUsers(dir.resolve("a")).get("writer").get("role"));
        assertEquals(users.get("writer").get("hash"), legacyUsers(dir.resolve("a")).get("writer").get("hash"));
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, java.util.Map<String, Object>> legacyUsers(Path d) throws IOException {
        return (java.util.Map<String, java.util.Map<String, Object>>) com.ataiva.serengeti.core.json.Json
                .parseObject(Files.readString(d.resolve(Users.FILE))).get("users");
    }

    @Test
    void anAccountMayBeLimitedToSomeDatabasesAndKeepsTheListAcrossARestart() throws IOException {
        Users a = node("a");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        Users.Changed c = a.change("app", PW, null, Users.Limit.to(List.of("Shop", "blog")));
        assertTrue(c.created());
        assertEquals(new Users.Access(Users.Role.WRITE, List.of("blog", "shop")), c.access(),
                "a new account gets write; names are folded as the parser folds them, and sorted");
        assertEquals(Optional.of(c.access()), a.authenticate("app", PW));
        assertEquals(Optional.of(new Users.Access(Users.Role.ADMIN)), a.authenticate("admin", PW));
        Users again = Users.open(dir.resolve("a"), 4, now::get);
        assertEquals(Optional.of(c.access()), again.authenticate("app", PW));
        assertEquals(a.digest(), again.digest());
        // Left out, the list stays: a password, a role, or the account's own password change.
        assertEquals(c.access(), a.change("app", "another password 1", null, Users.Limit.KEEP).access());
        assertEquals(List.of("blog", "shop"), a.change("app", null, Users.Role.READ, Users.Limit.KEEP).access().databases());
        assertTrue(a.changeOwnPassword("app", "another password 1", "another password 2"));
        assertEquals(new Users.Access(Users.Role.READ, List.of("blog", "shop")), a.accounts().get("app"));
        assertTrue(a.setRole("app", Users.Role.WRITE));
        assertEquals(List.of("blog", "shop"), a.accounts().get("app").databases());
        // A new list replaces it; NONE removes it.
        assertTrue(a.setDatabases("app", Users.Limit.to(List.of("shop"))));
        assertEquals(List.of("shop"), a.accounts().get("app").databases());
        assertTrue(a.setDatabases("app", Users.Limit.NONE));
        assertFalse(a.accounts().get("app").limited());
        assertFalse(a.setDatabases("nobody", Users.Limit.NONE), "no such account");
        assertThrows(Users.NoSuchAccount.class, () -> a.change("nobody", null, Users.Role.READ, Users.Limit.KEEP));
        assertThrows(IllegalArgumentException.class, () -> a.change("app", null, null, Users.Limit.KEEP),
                "nothing to change");
        assertEquals(java.util.Map.of("admin", Users.Role.ADMIN, "app", Users.Role.WRITE), a.list());
    }

    @Test
    void listsAreOneToAHundredValidNamesWithNoneTwiceAndOnlyForReadAndWriteAccounts() throws IOException {
        Users a = node("a");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        List<String> hundred = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            hundred.add("db" + i);
        }
        assertEquals(100, Users.Limit.to(hundred).databases().size());
        List<String> tooMany = new java.util.ArrayList<>(hundred);
        tooMany.add("db100");
        for (List<?> bad : List.<List<?>>of(List.of(), tooMany, List.of("shop", "SHOP"), List.of("shop", "shop"),
                List.of("1x"), List.of("a-b"), List.of("select"), List.of(""), List.of("x".repeat(64)), List.of("café"),
                List.of("\u212Aeep"), List.of(1), java.util.Arrays.asList("shop", null), List.of(List.of("shop")))) {
            assertThrows(IllegalArgumentException.class, () -> Users.Limit.to(bad), bad.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> Users.Limit.to(null));
        // Only read and write accounts: admin accounts reach everything, metrics accounts no data.
        Users.Limit shop = Users.Limit.to(List.of("shop"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> a.change("boss", PW, Users.Role.ADMIN, shop));
        assertTrue(e.getMessage().contains("admin account reaches every database"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> a.change("scraper", PW, Users.Role.METRICS, shop));
        assertTrue(e.getMessage().contains("metrics account reads no data"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> a.change("admin", null, null, shop));
        a.change("app", PW, Users.Role.READ, shop);
        for (Users.Role r : List.of(Users.Role.ADMIN, Users.Role.METRICS)) {
            e = assertThrows(IllegalArgumentException.class, () -> a.setRole("app", r));
            assertTrue(e.getMessage().contains("\"databases\": null"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> a.setPassword("app", PW, r));
            assertThrows(IllegalArgumentException.class, () -> a.change("app", null, r, Users.Limit.KEEP));
        }
        assertEquals(new Users.Access(Users.Role.READ, List.of("shop")), a.accounts().get("app"), "nothing changed");
        assertEquals(java.util.Set.of("admin", "app"), a.list().keySet());
        // With the list removed in the same change, it may be promoted.
        assertEquals(new Users.Access(Users.Role.ADMIN), a.change("app", null, Users.Role.ADMIN, Users.Limit.NONE).access());
    }

    @Test
    void aChangeToTheListIsSeenAtOnceThroughTheCacheHereAndOnPeersOnceMerged() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("app", PW, Users.Role.WRITE);
        b.merge(a.entries());
        assertEquals(Optional.of(new Users.Access(Users.Role.WRITE)), a.authenticate("app", PW));
        assertEquals(Optional.of(new Users.Access(Users.Role.WRITE)), b.authenticate("app", PW)); // both cached now
        a.setDatabases("app", Users.Limit.to(List.of("shop")));
        Users.Access limited = new Users.Access(Users.Role.WRITE, List.of("shop"));
        assertEquals(Optional.of(limited), a.authenticate("app", PW), "at once on the node that made it");
        assertEquals(Optional.of(new Users.Access(Users.Role.WRITE)), b.authenticate("app", PW), "b has not heard");
        b.merge(a.entries());
        assertEquals(Optional.of(limited), b.authenticate("app", PW), "at once once merged");
        a.setDatabases("app", Users.Limit.NONE);
        b.merge(a.entries());
        assertEquals(Optional.of(new Users.Access(Users.Role.WRITE)), b.authenticate("app", PW), "the removal too");
        assertEquals(a.digest(), b.digest());
    }

    @Test
    void listChangesMergeLikeRoleChanges() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("app", PW, Users.Role.WRITE);
        a.setPassword("old", PW, Users.Role.WRITE);
        b.merge(a.entries());
        // The newest change wins: a limits app, then b (later) gives it another list.
        a.setDatabases("app", Users.Limit.to(List.of("shop")));
        b.setDatabases("app", Users.Limit.to(List.of("blog")));
        a.merge(b.entries());
        b.merge(a.entries());
        assertEquals(List.of("blog"), a.accounts().get("app").databases());
        assertEquals(a.digest(), b.digest());
        // A list given on one node and an own password change on a node that has not heard of it both stand:
        // they change different parts of the account.
        b.merge(a.entries());
        a.setDatabases("app", Users.Limit.to(List.of("shop")));
        assertTrue(b.changeOwnPassword("app", PW, "my own password 1"));
        a.merge(b.entries());
        b.merge(a.entries());
        assertEquals(a.digest(), b.digest());
        for (Users u : List.of(a, b)) {
            assertEquals(Optional.of(new Users.Access(Users.Role.WRITE, List.of("shop"))),
                    u.authenticate("app", "my own password 1"), "the list stands, and so does the new password");
            assertEquals(Optional.empty(), u.authenticate("app", PW));
        }
        // An own password change made on top of the list keeps it everywhere.
        assertTrue(b.changeOwnPassword("app", "my own password 1", "my own password 2"));
        a.merge(b.entries());
        assertEquals(Optional.of(new Users.Access(Users.Role.WRITE, List.of("shop"))), a.authenticate("app", "my own password 2"));
        // A delete after a list change wins on both, in either order.
        b.setDatabases("old", Users.Limit.to(List.of("shop")));
        assertTrue(a.delete("old")); // later
        b.merge(a.entries());
        a.merge(b.entries());
        assertFalse(a.list().containsKey("old"));
        assertFalse(b.list().containsKey("old"));
        assertEquals(a.digest(), b.digest());
        // Removing the list spreads like setting it.
        a.setDatabases("app", Users.Limit.NONE);
        b.merge(a.entries());
        assertFalse(b.accounts().get("app").limited());
        assertEquals(a.digest(), b.digest());
    }

    @Test
    void aMalformedListFromAPeerIsRefusedWholeAndNeverReadAsNoLimit() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.change("app", PW, Users.Role.WRITE, Users.Limit.to(List.of("shop")));
        b.merge(a.entries());
        String before = b.digest();
        var good = new java.util.LinkedHashMap<String, Object>(a.entries().get(1));
        assertEquals(List.of("shop"), good.get("databases"));
        good.put("at", "5000.0.a");
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(String.format("db%03d", i));
        }
        List<Object> bads = new java.util.ArrayList<>(List.of("shop", List.of(), List.of("shop", "blog"), List.of("a", "a"),
                List.of("Shop"), List.of("1x"), List.of(1), many, java.util.Map.of("shop", true), true));
        bads.add(null);
        for (Object bad : bads) {
            var entry = new java.util.LinkedHashMap<String, Object>(good);
            entry.put("databases", bad);
            assertThrows(IllegalArgumentException.class, () -> b.merge(List.of(a.entries().get(0), entry)), String.valueOf(bad));
        }
        // A list on a tombstone.
        var tomb = new java.util.LinkedHashMap<String, Object>();
        tomb.put("user", "app");
        tomb.put("at", "5000.0.a");
        tomb.put("deleted", true);
        tomb.put("databases", List.of("shop"));
        assertThrows(IllegalArgumentException.class, () -> b.merge(List.of(tomb)));
        assertEquals(before, b.digest(), "nothing of a refused batch was merged");
        assertEquals(Optional.of(new Users.Access(Users.Role.WRITE, List.of("shop"))), b.authenticate("app", PW));
        // The same holds for this node's own file: it does not start rather than read the account as unlimited.
        Path f = dir.resolve("b").resolve(Users.ACCOUNTS_FILE);
        String text = Files.readString(f);
        assertTrue(text.contains("\"databases\":[\"shop\"]"), text);
        PrivateFiles.writeAtomically(f, text.replace("\"databases\":[\"shop\"]", "\"databases\":\"shop\""));
        IOException e = assertThrows(IOException.class, () -> Users.open(dir.resolve("b"), 4, now::get));
        assertTrue(e.getMessage().contains("databases"), e.getMessage());
        // A list on an admin or metrics account is taken (a role and a list given on two nodes at once can meet
        // that way) and does not apply: the role reaches what it always does.
        for (String role : List.of("admin", "metrics")) {
            Users c = node("c" + role);
            var entry = new java.util.LinkedHashMap<String, Object>(good);
            entry.put("role", role);
            assertEquals(1, c.merge(List.of(entry)), role);
            assertEquals(new Users.Access(Users.Role.parse(role)), c.accounts().get("app"));
            assertEquals(entry, c.entries().get(0), "kept as it came");
        }
        assertEquals(java.util.Set.of("app"), legacyUsers(dir.resolve("cadmin")).keySet());
    }

    @Test
    void aPasswordChangedByAnOlderVersionWinsWithTheFilesTime() throws IOException {
        now.set(System.currentTimeMillis()); // stamps from the file's time must not look like the future
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("svc", PW, Users.Role.WRITE);
        b.merge(a.entries());
        // a is rolled back to 2.1, svc changes its password there (2.1 writes users.json alone), and a is
        // upgraded again.
        var root = com.ataiva.serengeti.core.json.Json.parseObject(Files.readString(dir.resolve("a").resolve(Users.FILE)));
        @SuppressWarnings("unchecked")
        var users = (java.util.Map<String, java.util.Map<String, Object>>) root.get("users");
        String rotated = org.mindrot.jbcrypt.BCrypt.hashpw("rotated password 1", org.mindrot.jbcrypt.BCrypt.gensalt(4));
        PrivateFiles.writeAtomically(dir.resolve("a").resolve(Users.FILE), "{\"users\": {\"admin\": {\"hash\": \""
                + users.get("admin").get("hash") + "\", \"role\": \"admin\"}, \"svc\": {\"hash\": \"" + rotated
                + "\", \"role\": \"user\"}}}");
        Users upgraded = Users.open(dir.resolve("a"), 4, now::get);
        assertEquals(Optional.of(Users.Role.WRITE), upgraded.verify("svc", "rotated password 1"));
        b.merge(upgraded.entries());
        assertEquals(Optional.of(Users.Role.WRITE), b.verify("svc", "rotated password 1"), "the newer change wins");
        assertEquals(Optional.empty(), b.verify("svc", PW));
        upgraded.merge(b.entries());
        assertEquals(upgraded.digest(), b.digest());
        // A node made from the environment later does not undo it either: its bootstrap stamp is older.
        Users c = node("c");
        c.bootstrapAdmin("from the environment 1");
        c.merge(b.entries());
        assertEquals(Optional.of(Users.Role.ADMIN), c.verify("admin", PW));
    }

    @Test
    void aStopBetweenTheTwoFilesKeepsTheNewerOne() throws IOException {
        Users a = node("a");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        String oldUsers = Files.readString(dir.resolve("a").resolve(Users.FILE));
        a.setPassword("reader", PW, Users.Role.READ);
        a.delete("reader");
        // As if the node stopped after writing accounts.json and before users.json, two saves ago.
        String digest = a.digest();
        PrivateFiles.writeAtomically(dir.resolve("a").resolve(Users.FILE), oldUsers.replaceFirst("\"_v\":\\d+",
                "\"_v\":" + (versionIn(dir.resolve("a")) - 1)));
        assertEquals(digest, Users.open(dir.resolve("a"), 4, now::get).digest());
        var after = com.ataiva.serengeti.core.json.Json.parseObject(Files.readString(dir.resolve("a").resolve(Users.FILE)));
        assertEquals(versionIn(dir.resolve("a")), ((Number) after.get("_v")).longValue(),
                "users.json is brought up to date at once, so a rollback to 2.1 reads the newer one");
    }

    private static long versionIn(Path d) throws IOException {
        return ((Number) com.ataiva.serengeti.core.json.Json.parseObject(
                Files.readString(d.resolve(Users.ACCOUNTS_FILE))).get("v")).longValue();
    }

    @Test
    void anAccountsOwnPasswordChangeNeverUndoesAnAdminsDeleteOrDemotion() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("mallory", PW, Users.Role.WRITE);
        a.setPassword("eve", PW, Users.Role.WRITE);
        b.merge(a.entries());
        // On a: mallory deleted, eve made read-only. b has not heard, and both change their passwords there.
        assertTrue(a.delete("mallory"));
        assertTrue(a.setRole("eve", Users.Role.READ));
        assertTrue(b.changeOwnPassword("mallory", PW, "a new password 1"));
        assertTrue(b.changeOwnPassword("eve", PW, "a new password 2"));
        a.merge(b.entries());
        b.merge(a.entries());
        assertEquals(a.digest(), b.digest());
        for (Users u : List.of(a, b)) {
            assertFalse(u.list().containsKey("mallory"), "the delete stands");
            assertEquals(Users.Role.READ, u.list().get("eve"), "the demotion stands");
            assertEquals(Optional.empty(), u.verify("mallory", "a new password 1"));
            assertEquals(Optional.empty(), u.verify("mallory", PW));
            assertEquals(Optional.of(Users.Role.READ), u.verify("eve", "a new password 2"),
                    "eve's own password change stands too, under the role the admin gave");
        }
        // The own change is checked against the account as it is: a wrong current password, or a deleted
        // account, changes nothing.
        assertFalse(a.changeOwnPassword("eve", "not her password", "a new password 3"));
        assertFalse(a.changeOwnPassword("mallory", PW, "a new password 3"));
        // And it keeps the role it has now.
        assertTrue(a.changeOwnPassword("eve", "a new password 2", "a new password 4"));
        assertEquals(Users.Role.READ, a.list().get("eve"));
        assertEquals(Optional.of(Users.Role.READ), a.verify("eve", "a new password 4"));
        // Two own changes on two nodes: the later one wins on both.
        b.merge(a.entries());
        assertTrue(b.changeOwnPassword("eve", "a new password 4", "a new password 5"));
        a.merge(b.entries());
        assertEquals(Optional.of(Users.Role.READ), a.verify("eve", "a new password 5"));
    }

    @Test
    void aChangeOnANodeWhoseClockIsBehindStillComesAfterWhatItChanges() throws IOException {
        now.set(990_000); // the wall clock both nodes check peers' stamps against
        Users a = node("a");
        Users b = node("b");
        b.setStamps(() -> new com.ataiva.serengeti.core.storage.Stamp(1_000_000, 0, "b")); // its clock is ahead
        b.setPassword("admin", PW, Users.Role.ADMIN);
        b.setPassword("temp", PW, Users.Role.WRITE);
        a.merge(b.entries());
        assertTrue(a.delete("temp")); // a's own stamps read about 100
        b.merge(a.entries());
        assertFalse(b.list().containsKey("temp"), "the delete is after the create although a's clock is behind");
        assertEquals(a.digest(), b.digest());
        // A change stamped more than a minute ahead of the receiving node's clock is left out, not taken, and
        // the rest of the batch is: it would otherwise move that node's clock as far ahead as the stamp.
        Users c = node("c");
        now.set(900_000);
        Users.Merged m = c.mergeFrom(b.entries());
        assertEquals(2, m.ahead(), m.toString());
        assertEquals(0, m.changed());
        now.set(990_000);
        assertEquals(2, c.mergeFrom(b.entries()).changed(), "taken once the clocks agree");
    }

    @Test
    void withNoAdminLeftOtherAccountsWorkAndTheEnvironmentMakesItAgain() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("ops", PW, Users.Role.ADMIN);
        a.setPassword("app", PW, Users.Role.WRITE);
        b.merge(a.entries());
        assertTrue(a.delete("admin")); // each node still sees another admin
        assertTrue(b.delete("ops"));
        a.merge(b.entries());
        b.merge(a.entries());
        assertFalse(a.isSetUp(), "no admin is left");
        assertTrue(a.hasAccounts());
        assertEquals(Optional.of(Users.Role.WRITE), a.verify("app", PW), "the other accounts still log in");
        // A restart with SERENGETI_ADMIN_PASSWORD: stamped after the tombstone, so it wins on every node.
        Users again = Users.open(dir.resolve("a"), 4, now::get);
        again.setStamps(() -> new com.ataiva.serengeti.core.storage.Stamp(tick++, 0, "a"));
        assertEquals(Users.Bootstrap.RECOVERED, again.bootstrapAdmin("the admin again 1"));
        b.merge(again.entries());
        assertEquals(Optional.of(Users.Role.ADMIN), b.verify("admin", "the admin again 1"));
        // At first start, the bootstrap admin is older than anything.
        assertEquals(Users.Bootstrap.FIRST, node("fresh").bootstrapAdmin(PW));
        assertEquals(Users.Bootstrap.SKIPPED, b.bootstrapAdmin("not used 12345"), "an admin exists: nothing changes");
        assertEquals(Optional.of(Users.Role.ADMIN), b.verify("admin", "the admin again 1"));
    }

    @Test
    void aPeerCannotSendACostlyHashOrAStampFromTheFuture() throws IOException {
        Users a = node("a");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        String before = a.digest();
        String salt = "a".repeat(53);
        // Refused whole: a hash too costly to check, one this library cannot read ($2b$, $2y$), or malformed.
        for (var bad : java.util.List.<java.util.Map<String, Object>>of(
                java.util.Map.of("user", "x", "at", "5.0.n", "hash", "$2a$31$" + salt, "role", "write"),
                java.util.Map.of("user", "x", "at", "5.0.n", "hash", "$2b$12$" + salt, "role", "write"),
                java.util.Map.of("user", "x", "at", "5.0.n", "hash", "$2y$12$" + salt, "role", "write"),
                java.util.Map.of("user", "x", "at", "5.0.n", "hash", "$2a$12$" + "!".repeat(53), "role", "write"))) {
            assertThrows(IllegalArgumentException.class, () -> a.merge(java.util.List.of(bad)), bad.toString());
        }
        // Left out, with the rest of the batch taken: stamps from the future, also one at the counter's limit.
        Users.Merged m = a.mergeFrom(java.util.List.of(
                java.util.Map.of("user", "x", "at", "9999999999999.0.evil", "deleted", true),
                java.util.Map.of("user", "admin", "made_at", "9999999999999.0.evil", "at", "5.0.n", "deleted", true),
                java.util.Map.of("user", "y", "at", "3600000.2147483647.evil", "deleted", true)));
        assertEquals(new Users.Merged(0, 3), m);
        assertEquals(before, a.digest());
        // A stamp at the counter's limit within the bound is taken, and the next change still comes after it.
        a.mergeFrom(java.util.List.of(java.util.Map.of("user", "z", "at", "5000.2147483647.evil", "deleted", true)));
        a.setPassword("z", PW, Users.Role.WRITE);
        assertEquals(Optional.of(Users.Role.WRITE), a.verify("z", PW));
    }

    @Test
    void aPasswordChangeOnAnOlderVersionDoesNotUndoAnAdminsChange() throws IOException {
        now.set(System.currentTimeMillis());
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("mallory", PW, Users.Role.WRITE);
        a.setPassword("boss", PW, Users.Role.ADMIN);
        b.merge(a.entries());
        assertTrue(a.delete("mallory"));
        assertTrue(a.setRole("boss", Users.Role.READ));
        // b runs 2.1 for a while, where both change their own passwords: 2.1 writes users.json alone.
        String m2 = org.mindrot.jbcrypt.BCrypt.hashpw("mallory again 1", org.mindrot.jbcrypt.BCrypt.gensalt(4));
        String b2 = org.mindrot.jbcrypt.BCrypt.hashpw("boss again 123", org.mindrot.jbcrypt.BCrypt.gensalt(4));
        var root = com.ataiva.serengeti.core.json.Json.parseObject(Files.readString(dir.resolve("b").resolve(Users.FILE)));
        @SuppressWarnings("unchecked")
        var users = (java.util.Map<String, java.util.Map<String, Object>>) root.get("users");
        PrivateFiles.writeAtomically(dir.resolve("b").resolve(Users.FILE), "{\"users\": {\"admin\": {\"hash\": \""
                + users.get("admin").get("hash") + "\", \"role\": \"admin\"}, \"mallory\": {\"hash\": \"" + m2
                + "\", \"role\": \"user\"}, \"boss\": {\"hash\": \"" + b2 + "\", \"role\": \"admin\"}}}");
        Users back = Users.open(dir.resolve("b"), 4, now::get);
        back.merge(a.entries());
        a.merge(back.entries());
        assertEquals(a.digest(), back.digest());
        for (Users u : List.of(a, back)) {
            assertFalse(u.list().containsKey("mallory"), "the delete stands");
            assertEquals(Users.Role.READ, u.list().get("boss"), "the demotion stands");
            assertEquals(Optional.empty(), u.verify("mallory", "mallory again 1"));
        }
    }

    /**
     * Review 22d, finding 6: two admins on two nodes, within the seconds a change takes to spread, one limiting
     * shop-app to shop and the other giving it a new password. Both stand, on both nodes, in either order.
     */
    @Test
    void aConcurrentPasswordChangeOnAnotherNodeDoesNotDropTheList() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("shop-app", PW, Users.Role.WRITE);
        b.merge(a.entries());
        assertEquals(Optional.of(new Users.Access(Users.Role.WRITE)), a.authenticate("shop-app", PW)); // cached
        assertEquals(Optional.of(new Users.Access(Users.Role.WRITE)), b.authenticate("shop-app", PW));
        // On b: limit it. On a, before b's change arrives (and stamped after it): a new password, nothing else.
        assertEquals(true, b.setDatabases("shop-app", Users.Limit.to(List.of("shop"))));
        a.change("shop-app", "a new password 1", null, Users.Limit.KEEP);
        a.merge(b.entries());
        b.merge(a.entries());
        assertEquals(a.digest(), b.digest());
        Users.Access limited = new Users.Access(Users.Role.WRITE, List.of("shop"));
        for (Users u : List.of(a, b)) {
            assertEquals(List.of("shop"), u.accounts().get("shop-app").databases(), "the list set on b stands");
            assertEquals(Optional.of(limited), u.authenticate("shop-app", "a new password 1"), "so does a's password");
            assertEquals(Optional.empty(), u.authenticate("shop-app", PW), "the cache was cleared by the merge");
        }
        // The other way round: the password set first, the list stamped after it.
        Users c = node("c");
        c.merge(a.entries());
        c.change("shop-app", "a new password 2", null, Users.Limit.KEEP);
        a.setDatabases("shop-app", Users.Limit.to(List.of("blog", "shop")));
        a.merge(c.entries());
        c.merge(a.entries());
        assertEquals(a.digest(), c.digest());
        assertEquals(Optional.of(new Users.Access(Users.Role.WRITE, List.of("blog", "shop"))),
                c.authenticate("shop-app", "a new password 2"));
    }

    @Test
    void aConcurrentPasswordChangeOnAnotherNodeDoesNotUndoADemotion() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("ops", PW, Users.Role.ADMIN);
        b.merge(a.entries());
        assertTrue(b.setRole("ops", Users.Role.READ)); // b demotes ops
        a.change("ops", "a new password 1", null, Users.Limit.KEEP); // a resets its password, stamped later
        b.merge(a.entries());
        a.merge(b.entries());
        assertEquals(a.digest(), b.digest());
        for (Users u : List.of(a, b)) {
            assertEquals(Optional.of(Users.Role.READ), u.verify("ops", "a new password 1"));
            assertEquals(Optional.empty(), u.verify("ops", PW));
        }
        // A change that names both a password and a role moves both parts: the newer one wins whole.
        a.merge(b.entries());
        b.setPassword("ops", "set by b 12345", Users.Role.WRITE);
        a.setPassword("ops", "set by a 12345", Users.Role.ADMIN); // later
        b.merge(a.entries());
        a.merge(b.entries());
        assertEquals(a.digest(), b.digest());
        assertEquals(Optional.of(Users.Role.ADMIN), b.verify("ops", "set by a 12345"));
        assertEquals(Optional.empty(), b.verify("ops", "set by b 12345"));
    }

    @Test
    void anAdminsPasswordResetBeatsAnOwnChangeMadeBeforeTheNodeSawIt() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("app", PW, Users.Role.WRITE);
        b.merge(a.entries());
        a.change("app", "reset by the admin", null, Users.Limit.KEEP);
        assertTrue(b.changeOwnPassword("app", PW, "chosen by the app 1")); // stamped after the reset
        a.merge(b.entries());
        b.merge(a.entries());
        assertEquals(a.digest(), b.digest());
        for (Users u : List.of(a, b)) {
            assertEquals(Optional.of(Users.Role.WRITE), u.verify("app", "reset by the admin"), "cred_at decides first");
            assertEquals(Optional.empty(), u.verify("app", "chosen by the app 1"));
        }
        // An own change made after the node saw the reset wins over it.
        assertTrue(b.changeOwnPassword("app", "reset by the admin", "chosen by the app 2"));
        a.merge(b.entries());
        assertEquals(Optional.of(Users.Role.WRITE), a.verify("app", "chosen by the app 2"));
        // Two own changes on two nodes on top of the same reset: the later one wins on both.
        assertTrue(a.changeOwnPassword("app", "chosen by the app 2", "chosen by the app 3"));
        assertTrue(b.changeOwnPassword("app", "chosen by the app 2", "chosen by the app 4"));
        a.merge(b.entries());
        b.merge(a.entries());
        assertEquals(Optional.of(Users.Role.WRITE), a.verify("app", "chosen by the app 4"));
        assertEquals(a.digest(), b.digest());
    }

    @Test
    void aDeleteBeatsAConcurrentPasswordResetWhicheverIsStampedLater() throws IOException {
        for (boolean resetLater : List.of(true, false)) {
            Users a = node("a" + resetLater);
            Users b = node("b" + resetLater);
            a.setPassword("admin", PW, Users.Role.ADMIN);
            a.setPassword("app", PW, Users.Role.WRITE);
            b.merge(a.entries());
            assertTrue(b.authenticate("app", PW).isPresent()); // cached on b
            assertEquals(java.util.Set.of("admin", "app"), legacyUsers(dir.resolve("b" + resetLater)).keySet());
            if (resetLater) {
                assertTrue(a.delete("app"));
                b.change("app", "reset meanwhile 1", null, Users.Limit.KEEP);
            } else {
                b.change("app", "reset meanwhile 1", null, Users.Limit.KEEP);
                assertTrue(a.delete("app"));
            }
            a.merge(b.entries());
            b.merge(a.entries());
            assertEquals(a.digest(), b.digest());
            for (Users u : List.of(a, b)) {
                assertFalse(u.list().containsKey("app"), "deleted");
                assertEquals(Optional.empty(), u.authenticate("app", "reset meanwhile 1"));
                assertEquals(Optional.empty(), u.authenticate("app", PW));
                assertFalse(u.setDatabases("app", Users.Limit.NONE), "no such account");
                assertFalse(u.changeOwnPassword("app", "reset meanwhile 1", "chosen by the app 1"));
            }
            assertEquals(java.util.Set.of("admin"), legacyUsers(dir.resolve("b" + resetLater)).keySet());
            // Made again, it starts after the delete: no list, the new password, on both nodes.
            Users.Changed c = b.change("app", "made again 123", Users.Role.READ, Users.Limit.KEEP);
            assertTrue(c.created());
            a.merge(b.entries());
            assertEquals(Optional.of(new Users.Access(Users.Role.READ)), a.authenticate("app", "made again 123"));
            assertEquals(a.digest(), b.digest());
        }
    }

    /**
     * A delete starts a deleted incarnation. So a role or list given on another node at the same moment, even
     * stamped later, belongs to the account deleted and leaves it deleted, instead of bringing it back with the
     * password it had.
     */
    @Test
    void aDeleteBeatsAConcurrentRoleOrListChangeStampedLater() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("app", PW, Users.Role.WRITE);
        a.setPassword("svc", PW, Users.Role.WRITE);
        b.merge(a.entries());
        assertTrue(a.delete("app"));
        assertTrue(a.delete("svc"));
        assertTrue(b.setDatabases("app", Users.Limit.to(List.of("shop")))); // later
        assertTrue(b.setRole("svc", Users.Role.READ)); // later
        a.merge(b.entries());
        b.merge(a.entries());
        assertEquals(a.digest(), b.digest());
        for (Users u : List.of(a, b)) {
            assertEquals(java.util.Set.of("admin"), u.list().keySet());
            assertEquals(Optional.empty(), u.authenticate("app", PW));
            assertEquals(Optional.empty(), u.authenticate("svc", PW));
            assertFalse(u.setRole("svc", Users.Role.WRITE), "no such account: a change without a password");
        }
        // Peers carry the tombstone alone, in the form a delete always had.
        var app = b.entries().stream().filter(e -> e.get("user").equals("app")).findFirst().orElseThrow();
        assertEquals(java.util.Set.of("user", "at", "deleted"), app.keySet(), app.toString());
        // Making it again with a password brings it back, without the list (it is a new account).
        assertTrue(a.change("app", "made again 123", null, Users.Limit.KEEP).created());
        b.merge(a.entries());
        assertEquals(Optional.of(new Users.Access(Users.Role.WRITE)), b.authenticate("app", "made again 123"));
        assertEquals(a.digest(), b.digest());
        // That holds across a restart.
        assertEquals(a.digest(), Users.open(dir.resolve("a"), 4, now::get).digest());
    }

    /**
     * An admin deleted on one node and given the admin role on another at the same moment stays deleted, so it
     * does not count as an admin: with the other admin deleted too, the recovery path makes the admin again.
     */
    @Test
    void aRoleGivenToADeletedAdminDoesNotCountAsAnAdmin() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("ops", PW, Users.Role.ADMIN);
        a.setPassword("app", PW, Users.Role.WRITE);
        b.merge(a.entries());
        assertTrue(a.delete("admin")); // ops is left on a
        assertTrue(b.delete("ops")); // admin is left on b
        assertTrue(b.setRole("admin", Users.Role.ADMIN)); // later than a's delete
        a.merge(b.entries());
        b.merge(a.entries());
        assertEquals(a.digest(), b.digest());
        assertFalse(a.isSetUp(), "no admin can sign in");
        assertTrue(a.hasAccounts());
        assertEquals(Optional.of(Users.Role.WRITE), a.verify("app", PW));
        Users again = Users.open(dir.resolve("a"), 4, now::get);
        again.setStamps(() -> new com.ataiva.serengeti.core.storage.Stamp(tick++, 0, "a"));
        assertEquals(Users.Bootstrap.RECOVERED, again.bootstrapAdmin("the admin again 1"));
        b.merge(again.entries());
        assertEquals(Optional.of(Users.Role.ADMIN), b.verify("admin", "the admin again 1"));
        assertTrue(b.isSetUp());
    }

    /**
     * Entries in the form 2.2 wrote before accounts merged in parts (only at and pw_at: at versioned the whole
     * account), from this node's file or from a peer, read as made at their at, with every part versioned by it.
     */
    @Test
    void entriesInTheOldFormAreReadAsMadeAtTheirAt() throws IOException {
        Path d = dir.resolve("old");
        Files.createDirectories(d);
        String ha = org.mindrot.jbcrypt.BCrypt.hashpw(PW, org.mindrot.jbcrypt.BCrypt.gensalt(4));
        String hb = org.mindrot.jbcrypt.BCrypt.hashpw("own password 1", org.mindrot.jbcrypt.BCrypt.gensalt(4));
        String accounts = "{\"v\":3,\"accounts\":["
                + "{\"user\":\"admin\",\"at\":\"100.0.a\",\"hash\":\"" + ha + "\",\"role\":\"admin\"},"
                + "{\"user\":\"app\",\"at\":\"105.0.a\",\"pw_at\":\"107.0.b\",\"hash\":\"" + hb
                + "\",\"role\":\"write\",\"databases\":[\"shop\"]},"
                + "{\"user\":\"gone\",\"at\":\"110.0.a\",\"deleted\":true}]}\n";
        PrivateFiles.writeAtomically(d.resolve(Users.ACCOUNTS_FILE), accounts);
        PrivateFiles.writeAtomically(d.resolve(Users.FILE), "{\"_v\":3,\"users\":{\"admin\":{\"hash\":\"" + ha
                + "\",\"role\":\"admin\"}}}\n");
        Users old = Users.open(d, 4, now::get);
        old.setStamps(() -> new com.ataiva.serengeti.core.storage.Stamp(tick++, 0, "old"));
        assertEquals(Optional.of(Users.Role.ADMIN), old.verify("admin", PW));
        assertEquals(Optional.of(new Users.Access(Users.Role.WRITE, List.of("shop"))), old.authenticate("app", "own password 1"));
        assertFalse(old.list().containsKey("gone"));
        // Written out as they were read: nothing about these accounts changed.
        @SuppressWarnings("unchecked")
        var written = (List<Object>) com.ataiva.serengeti.core.json.Json.parseObject(accounts).get("accounts");
        assertEquals(written, new java.util.ArrayList<Object>(old.entries()));
        // The same entries from a peer.
        Users b = node("b");
        b.merge(old.entries());
        assertEquals(old.digest(), b.digest());
        // An own change in the old form (pw_at 107 on at 105) loses to a password an admin set at 106 or later
        // (cred_at decides first), and the list made at 105 stays.
        b.change("app", "reset by an admin", null, Users.Limit.KEEP); // stamped just after 107, b's clock behind
        old.merge(b.entries());
        assertEquals(Optional.of(new Users.Access(Users.Role.WRITE, List.of("shop"))), old.authenticate("app", "reset by an admin"));
        // An old-form change from a peer replaced the whole account, so it counts as the account made at its at:
        // a role set there at 200 wins over the reset (stamped earlier), password included, as it did before.
        var roleChange = new java.util.LinkedHashMap<String, Object>(java.util.Map.of("user", "app", "at", "200000.0.c",
                "hash", ha, "role", "read"));
        now.set(150_000);
        old.merge(List.of(roleChange));
        assertEquals(Optional.of(new Users.Access(Users.Role.READ)), old.authenticate("app", PW));
        assertEquals(Optional.empty(), old.authenticate("app", "reset by an admin"));
        // A tombstone in the old form is a deleted incarnation at its at.
        old.mergeFrom(List.of(java.util.Map.of("user", "app", "at", "200001.0.c", "deleted", true)));
        assertFalse(old.list().containsKey("app"));
        assertEquals(java.util.Map.of("user", "app", "at", "200001.0.c", "deleted", true),
                old.entries().stream().filter(e -> e.get("user").equals("app")).findFirst().orElseThrow());
        // A single-part change made after it to the account it deleted (on a node that had not heard of the
        // delete) belongs to that account, and loses to the delete however late it is stamped.
        var reset = new java.util.LinkedHashMap<String, Object>(java.util.Map.of("user", "app", "made_at", "200000.0.c",
                "at", "200000.0.c", "cred_at", "200009.0.d", "hash", ha, "role", "read"));
        assertEquals(0, old.merge(List.of(reset)));
        assertFalse(old.list().containsKey("app"));
        // And an old-form account made again after an old-form delete wins over it, as it did.
        old.merge(List.of(java.util.Map.of("user", "app", "at", "200002.0.c", "hash", ha, "role", "write")));
        assertEquals(Optional.of(new Users.Access(Users.Role.WRITE)), old.authenticate("app", PW));
    }

    @Test
    void malformedOrFutureStampsAreRefusedOrLeftOut() throws IOException {
        Users a = node("a");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        String before = a.digest();
        Object hash = a.entries().get(0).get("hash");
        for (var bad : List.<java.util.Map<String, Object>>of(
                java.util.Map.of("user", "x", "at", "5.0.n", "cred_at", 5, "hash", hash, "role", "write"),
                java.util.Map.of("user", "x", "at", "5.0.n", "cred_at", "five", "hash", hash, "role", "write"),
                java.util.Map.of("user", "x", "at", "5.0.n", "made_at", "five", "hash", hash, "role", "write"),
                java.util.Map.of("user", "x", "at", "5.0.n", "db_at", 5, "hash", hash, "role", "write"),
                java.util.Map.of("user", "x", "at", "5.0.n", "pw_at", List.of(), "hash", hash, "role", "write"),
                java.util.Map.of("user", "x", "at", "5.0.n", "made_at", true, "deleted", true),
                java.util.Map.of("user", "x", "at", "5.0.n", "role", "write"), // no hash
                java.util.Map.of("user", "x", "at", "5.0.n", "cred_at", "6.0.n", "role", "write"), // no hash either
                java.util.Map.of("user", "x", "at", "5.0.n", "cred_at", "6.0.n", "hash", false, "role", "write"))) {
            assertThrows(IllegalArgumentException.class, () -> a.merge(List.of(bad)), bad.toString());
        }
        assertEquals(before, a.digest());
        for (String key : List.of("made_at", "at", "db_at", "cred_at", "pw_at")) {
            var ahead = new java.util.LinkedHashMap<String, Object>(java.util.Map.of("user", "x", "at", "5.0.n",
                    "hash", hash, "role", "write"));
            ahead.put(key, "9999999999999.0.evil");
            assertEquals(new Users.Merged(0, 1), a.mergeFrom(List.of(ahead)), key);
        }
        assertEquals(new Users.Merged(0, 1), a.mergeFrom(List.of(
                java.util.Map.of("user", "x", "at", "5.0.n", "made_at", "9999999999999.0.evil", "deleted", true))));
        assertEquals(before, a.digest());
        // A deleted entry keeps only its stamps (an earlier 2.2 build kept a hash with some).
        a.merge(List.of(java.util.Map.of("user", "x", "at", "5.0.n", "deleted", true, "hash", "plain", "pw_at", "6.0.n")));
        assertEquals(List.of(java.util.Map.of("user", "x", "at", "5.0.n", "deleted", true)),
                a.entries().stream().filter(e -> e.get("user").equals("x")).toList());
    }

    private static void syncAll(List<Users> nodes) throws IOException {
        for (int round = 0; round < 2; round++) {
            for (Users x : nodes) {
                for (Users y : nodes) {
                    if (x != y) {
                        x.merge(y.entries());
                    }
                }
            }
        }
    }

    // Review 22e, findings 1, 3 and 6: each of the next six failed on the commit that first merged accounts in
    // parts (2e22317); the seventh is the reviewer's check of the merge.

    /**
     * a deletes app. b and c have not heard of it: b resets app's password, c limits it to shop. Neither change
     * makes app again, so it stays deleted, whatever their stamps.
     */
    @Test
    void aResetAndAListChangeOnTwoOtherNodesDoNotBringADeletedAccountBack() throws IOException {
        Users a = node("a");
        Users b = node("b");
        Users c = node("c");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("app", PW, Users.Role.WRITE);
        syncAll(List.of(a, b, c));
        assertEquals(true, a.delete("app"));
        b.change("app", "reset on b 12345", null, Users.Limit.KEEP); // stamped after the delete
        c.setDatabases("app", Users.Limit.to(List.of("shop"))); // stamped after the delete
        syncAll(List.of(a, b, c));
        assertEquals(a.digest(), c.digest());
        for (Users u : List.of(a, b, c)) {
            assertFalse(u.list().containsKey("app"), "deleted on a, and no node made it again: " + u.accounts());
            assertEquals(Optional.empty(), u.authenticate("app", "reset on b 12345"));
        }
    }

    /** The same with both changes made on b, one after the other (two changes, neither of them makes app). */
    @Test
    void aResetThenARoleChangeOnANodeThatHadNotSeenTheDeleteDoNotBringItBack() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("app", PW, Users.Role.WRITE);
        syncAll(List.of(a, b));
        assertEquals(true, a.delete("app"));
        b.change("app", "reset on b 12345", null, Users.Limit.KEEP);
        b.setRole("app", Users.Role.READ);
        syncAll(List.of(a, b));
        assertFalse(a.list().containsKey("app"), "deleted on a: " + a.accounts());
    }

    /**
     * a deletes app (write, every database) and makes it again as read on shop with a new password. b, which has
     * heard of neither, resets the old app's password, stamped after both. The account made again keeps the
     * password set when it was made, and b's reset of the deleted one signs in to nothing.
     */
    @Test
    void anAccountMadeAgainDoesNotTakeAResetMadeToTheOneDeleted() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("app", PW, Users.Role.WRITE);
        syncAll(List.of(a, b));
        assertEquals(true, a.delete("app"));
        a.change("app", "made again on a 1", Users.Role.READ, Users.Limit.to(List.of("shop")));
        b.change("app", "reset on b 12345", null, Users.Limit.KEEP); // stamped after a's two changes
        syncAll(List.of(a, b));
        for (Users u : List.of(a, b)) {
            assertEquals(Optional.of(new Users.Access(Users.Role.READ, List.of("shop"))),
                    u.authenticate("app", "made again on a 1"), "the password set when it was made again");
            assertEquals(Optional.empty(), u.authenticate("app", "reset on b 12345"), "a reset of the deleted account");
        }
    }

    /** The same for the access: b's role change to the old account does not widen the account made again. */
    @Test
    void anAccountMadeAgainDoesNotTakeARoleGivenToTheOneDeleted() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("app", PW, Users.Role.READ);
        syncAll(List.of(a, b));
        assertEquals(true, a.delete("app"));
        a.change("app", "made again on a 1", Users.Role.READ, Users.Limit.to(List.of("shop")));
        b.setRole("app", Users.Role.WRITE); // to the old app, stamped after a's two changes
        syncAll(List.of(a, b));
        assertEquals(Optional.of(new Users.Access(Users.Role.READ, List.of("shop"))),
                a.authenticate("app", "made again on a 1"));
    }

    /** A role change on one node and a list given on another at the same moment both stand. */
    @Test
    void aConcurrentRoleChangeDoesNotDropTheList() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("shop-app", PW, Users.Role.READ);
        syncAll(List.of(a, b));
        b.setDatabases("shop-app", Users.Limit.to(List.of("shop")));
        a.setRole("shop-app", Users.Role.WRITE); // stamped later, list left as it is
        syncAll(List.of(a, b));
        assertEquals(new Users.Access(Users.Role.WRITE, List.of("shop")), a.accounts().get("shop-app"));
    }

    /** And a list change on one node does not undo a demotion made on another at the same moment. */
    @Test
    void aConcurrentListChangeDoesNotUndoADemotion() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("shop-app", PW, Users.Role.WRITE);
        a.setDatabases("shop-app", Users.Limit.to(List.of("blog", "shop")));
        syncAll(List.of(a, b));
        b.setRole("shop-app", Users.Role.READ);
        a.setDatabases("shop-app", Users.Limit.to(List.of("shop"))); // narrower, stamped later
        syncAll(List.of(a, b));
        assertEquals(Users.Role.READ, a.accounts().get("shop-app").role(), "the demotion on b stands");
        assertEquals(new Users.Access(Users.Role.READ, List.of("shop")), b.accounts().get("shop-app"), "so does the list");
    }

    /**
     * Random changes on three nodes with random partial merges converge to one digest once they have all
     * merged, and every node reads the same digest back from its files.
     */
    @Test
    void randomChangesConvergeAndSurviveARestart() throws IOException {
        java.util.Random r = new java.util.Random(22);
        String[] names = {"admin", "app", "ops"};
        String[] pws = {"password number 0", "password number 1", "password number 2"};
        for (int run = 0; run < 150; run++) {
            List<Users> nodes = new java.util.ArrayList<>();
            List<Path> dirs = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String n = "r" + run + "n" + i;
                nodes.add(node(n));
                dirs.add(dir.resolve(n));
            }
            nodes.get(0).setPassword("admin", pws[0], Users.Role.ADMIN);
            nodes.get(0).setPassword("root", pws[0], Users.Role.ADMIN);
            syncAll(nodes);
            for (int step = 0; step < 25; step++) {
                Users u = nodes.get(r.nextInt(3));
                String name = names[r.nextInt(3)];
                String pw = pws[r.nextInt(3)];
                try {
                    switch (r.nextInt(8)) {
                        case 0 -> u.change(name, pw, null, Users.Limit.KEEP);
                        case 1 -> u.setRole(name, Users.Role.values()[1 + r.nextInt(2)]);
                        case 2 -> u.setDatabases(name, r.nextBoolean() ? Users.Limit.NONE : Users.Limit.to(List.of("shop")));
                        case 3 -> u.change(name, pw, Users.Role.values()[1 + r.nextInt(2)], Users.Limit.KEEP);
                        case 4 -> u.delete(name);
                        case 5 -> u.changeOwnPassword(name, pws[r.nextInt(3)], pw);
                        default -> u.merge(nodes.get(r.nextInt(3)).entries());
                    }
                } catch (IllegalArgumentException | IllegalStateException e) {
                    // not allowed on that node now (last admin, list on admin): skip
                }
            }
            syncAll(nodes);
            String d0 = nodes.get(0).digest();
            for (int i = 0; i < 3; i++) {
                assertEquals(d0, nodes.get(i).digest(), "run " + run + " node " + i);
                assertEquals(d0, Users.open(dirs.get(i), 4, now::get).digest(), "run " + run + " restart " + i);
            }
        }
    }

    /**
     * Admin and metrics accounts reach every database. An account made admin on one node while given a list on
     * another, the list stamped later, is an admin with every database on both; the list it holds comes back
     * only if a later change makes it read or write and leaves the list alone.
     */
    @Test
    void aListGivenWhileTheAccountWasMadeAdminElsewhereDoesNotApplyToTheAdmin() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.change("ops", PW, Users.Role.WRITE, Users.Limit.to(List.of("shop")));
        syncAll(List.of(a, b));
        a.change("ops", null, Users.Role.ADMIN, Users.Limit.NONE);
        b.setDatabases("ops", Users.Limit.to(List.of("blog"))); // later
        syncAll(List.of(a, b));
        assertEquals(a.digest(), b.digest());
        for (Users u : List.of(a, b)) {
            assertEquals(new Users.Access(Users.Role.ADMIN), u.accounts().get("ops"));
            assertEquals(Optional.of(new Users.Access(Users.Role.ADMIN)), u.authenticate("ops", PW));
            assertTrue(u.isSetUp());
        }
        assertEquals(java.util.Set.of("admin", "ops"), legacyUsers(dir.resolve("b")).keySet(), "2.1 finds the admin");
        // It is still an admin for every change that keeps that role: a password, or the same role.
        b.change("ops", "a new password 1", null, Users.Limit.KEEP);
        assertTrue(b.setRole("ops", Users.Role.ADMIN));
        assertEquals(new Users.Access(Users.Role.ADMIN), b.accounts().get("ops"));
        // A list given to it directly is refused as before.
        assertThrows(IllegalArgumentException.class, () -> b.setDatabases("ops", Users.Limit.to(List.of("shop"))));
        // Made write without naming the databases, it gets the newest list given to it.
        assertEquals(new Users.Access(Users.Role.WRITE, List.of("blog")),
                b.change("ops", null, Users.Role.WRITE, Users.Limit.KEEP).access());
        a.merge(b.entries());
        assertEquals(Optional.of(new Users.Access(Users.Role.WRITE, List.of("blog"))), a.authenticate("ops", "a new password 1"));
        assertEquals(java.util.Set.of("admin"), legacyUsers(dir.resolve("a")).keySet());
        // The file holds it and reads back the same.
        assertEquals(a.digest(), Users.open(dir.resolve("a"), 4, now::get).digest());
    }

    /**
     * The recovery path makes the admin as a new incarnation: a node that had not heard of it, resetting the
     * password of the account named admin it replaced, or demoting it, changes nothing of the admin made.
     */
    @Test
    void theAdminMadeByRecoveryTakesNothingFromTheAccountItReplaced() throws IOException {
        Users b = node("b");
        Users c = node("c");
        b.setPassword("admin", PW, Users.Role.ADMIN);
        b.setPassword("ops", PW, Users.Role.ADMIN);
        assertTrue(b.setRole("admin", Users.Role.WRITE)); // admin is a write account now; ops the only admin
        c.merge(b.entries());
        // ops deleted on another node (the last admin there was another one, deleted elsewhere in turn): c has
        // no admin, and its write account named admin is made the admin again by the recovery path.
        c.mergeFrom(List.of(java.util.Map.of("user", "ops", "at", tick++ + ".0.z", "deleted", true)));
        assertFalse(c.isSetUp());
        assertEquals(Users.Bootstrap.RECOVERED, c.bootstrapAdmin("the admin again 1"));
        // Meanwhile on b, which has heard of neither the delete nor the recovery: a reset and a list for the
        // write account named admin, both stamped after the recovery.
        b.change("admin", "reset on b 12345", null, Users.Limit.KEEP);
        assertTrue(b.setDatabases("admin", Users.Limit.to(List.of("shop"))));
        syncAll(List.of(b, c));
        assertEquals(b.digest(), c.digest());
        for (Users u : List.of(b, c)) {
            assertEquals(Optional.of(new Users.Access(Users.Role.ADMIN)), u.authenticate("admin", "the admin again 1"));
            assertEquals(Optional.empty(), u.authenticate("admin", "reset on b 12345"));
            assertTrue(u.isSetUp());
        }
    }

    /** setup (POST /v1/setup) makes the admin a new incarnation too, so it takes no list or role from before. */
    @Test
    void setupMakesTheAdminANewAccount() throws IOException {
        Users a = node("a");
        Users b = node("b");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.setPassword("ops", PW, Users.Role.ADMIN);
        a.change("admin", null, Users.Role.READ, Users.Limit.to(List.of("shop")));
        b.merge(a.entries());
        // ops deleted on another node: no admin on a, whose account named admin is read on shop.
        a.mergeFrom(List.of(java.util.Map.of("user", "ops", "at", tick++ + ".0.z", "deleted", true)));
        assertFalse(a.isSetUp());
        a.setUpAdmin("set up again 123");
        b.setRole("admin", Users.Role.WRITE); // to the read account, on a node that had not heard of any of it
        syncAll(List.of(a, b));
        assertEquals(a.digest(), b.digest());
        assertEquals(Optional.of(new Users.Access(Users.Role.ADMIN)), b.authenticate("admin", "set up again 123"));
        assertEquals(java.util.Set.of("user", "at", "hash", "role"), entryOf(a, "admin").keySet(),
                "made in one change: " + entryOf(a, "admin"));
    }

    /** The form of an entry: each stamp written only where it differs from at. */
    @Test
    void anEntryCarriesOnlyTheStampsThatDifferFromAt() throws IOException {
        Users a = node("a");
        a.setPassword("admin", PW, Users.Role.ADMIN);
        a.change("app", PW, Users.Role.READ, Users.Limit.to(List.of("shop")));
        assertEquals(java.util.Set.of("user", "at", "hash", "role", "databases"), entryOf(a, "app").keySet());
        a.setRole("app", Users.Role.WRITE);
        assertEquals(java.util.Set.of("user", "made_at", "at", "db_at", "cred_at", "hash", "role", "databases"),
                entryOf(a, "app").keySet());
        a.change("app", null, Users.Role.READ, Users.Limit.NONE);
        assertEquals(java.util.Set.of("user", "made_at", "at", "cred_at", "hash", "role"), entryOf(a, "app").keySet());
        assertTrue(a.changeOwnPassword("app", PW, "own password 12"));
        assertEquals(java.util.Set.of("user", "made_at", "at", "cred_at", "pw_at", "hash", "role"),
                entryOf(a, "app").keySet());
        var e = entryOf(a, "app");
        assertTrue(com.ataiva.serengeti.core.storage.Stamp.parse((String) e.get("pw_at"))
                .after(com.ataiva.serengeti.core.storage.Stamp.parse((String) e.get("at"))));
        assertTrue(a.delete("app"));
        assertEquals(java.util.Set.of("user", "at", "deleted"), entryOf(a, "app").keySet());
    }

    private static java.util.Map<String, Object> entryOf(Users u, String name) {
        return u.entries().stream().filter(e -> e.get("user").equals(name)).findFirst().orElseThrow();
    }
}
