package com.ataiva.serengeti.core.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The one-time token that lets the first admin password be set over HTTP. It is written to
 * {@code setup-token} in the data directory (mode 0600), so only someone who can read the node's files can
 * use it, and it is deleted once the password is set.
 */
public final class SetupToken {
    public static final String FILE = "setup-token";

    private final Path file;
    private final byte[] token;

    private SetupToken(Path file, byte[] token) {
        this.file = file;
        this.token = token;
    }

    /** Reuses the token from an earlier start if there is one, so a restart does not invalidate it. */
    public static SetupToken create(Path dataDir) throws IOException {
        Path f = dataDir.resolve(FILE);
        if (Files.exists(f) && PrivateFiles.isPrivate(f)) {
            String existing = Files.readString(f, StandardCharsets.UTF_8).trim();
            if (existing.length() >= 40) {
                return new SetupToken(f, existing.getBytes(StandardCharsets.UTF_8));
            }
        }
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String text = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        PrivateFiles.writeAtomically(f, text + "\n");
        return new SetupToken(f, text.getBytes(StandardCharsets.UTF_8));
    }

    public Path file() {
        return file;
    }

    /** Constant-time comparison. */
    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(token, candidate.trim().getBytes(StandardCharsets.UTF_8));
    }

    public void delete() throws IOException {
        Files.deleteIfExists(file);
    }
}
