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
}
