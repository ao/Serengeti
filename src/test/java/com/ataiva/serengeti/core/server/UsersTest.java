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
        var root = com.ataiva.serengeti.core.json.Json.parseObject(Files.readString(dir.resolve("a").resolve(Users.FILE)));
        @SuppressWarnings("unchecked")
        var users = (java.util.Map<String, java.util.Map<String, Object>>) root.get("users");
        // 2.0 and 2.1 read "users" and know only the roles admin and user (they would take any other role
        // for user); read and metrics accounts and tombstones are only in accounts.json, which they do not read.
        assertEquals(java.util.Set.of("admin", "writer"), users.keySet());
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
        }
        // The own change is checked against the account as it is: a wrong current password, or a deleted
        // account, changes nothing.
        assertFalse(a.changeOwnPassword("eve", "not her password", "a new password 3"));
        assertFalse(a.changeOwnPassword("mallory", PW, "a new password 3"));
        // And it keeps the role it has now.
        assertTrue(a.changeOwnPassword("eve", PW, "a new password 4"));
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
                java.util.Map.of("user", "admin", "at", "5.0.n", "pw_at", "9999999999999.0.evil", "deleted", true),
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
}
