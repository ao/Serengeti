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
        assertThrows(IllegalArgumentException.class, () -> Users.Role.parse("root"));
        assertFalse(Users.Role.READ.mayWrite());
        assertTrue(Users.Role.WRITE.mayWrite());
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
        a.setPassword("gone", PW, Users.Role.WRITE);
        a.delete("gone");
        var root = com.ataiva.serengeti.core.json.Json.parseObject(Files.readString(dir.resolve("a").resolve(Users.FILE)));
        @SuppressWarnings("unchecked")
        var users = (java.util.Map<String, java.util.Map<String, Object>>) root.get("users");
        // 2.0 and 2.1 read "users" and know only the roles admin and user.
        assertEquals(java.util.Set.of("admin", "writer"), users.keySet());
        assertEquals("user", users.get("writer").get("role"));
        assertTrue(((java.util.Map<?, ?>) root.get("read_users")).containsKey("reader"));
        assertTrue(((java.util.Map<?, ?>) root.get("deleted")).containsKey("gone"));
        Users again = Users.open(dir.resolve("a"), 4, now::get);
        assertEquals(a.list(), again.list());
        assertEquals(a.digest(), again.digest());
        // A 2.1 file: no stamps.
        PrivateFiles.writeAtomically(dir.resolve("a").resolve(Users.FILE), "{\"users\": {\"admin\": {\"hash\": \""
                + users.get("admin").get("hash") + "\", \"role\": \"admin\"}}}");
        Users old = Users.open(dir.resolve("a"), 4, now::get);
        assertEquals(Optional.of(Users.Role.ADMIN), old.verify("admin", PW));
    }
}
