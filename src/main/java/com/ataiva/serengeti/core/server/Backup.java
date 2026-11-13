package com.ataiva.serengeti.core.server;

import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Snapshot;
import com.ataiva.serengeti.core.storage.Store;
import com.ataiva.serengeti.core.storage.StorageEngine;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Backup and restore. {@code GET /v1/backup} streams the node's state in the snapshot format, which
 * {@link #restore} loads into an empty data directory.
 *
 * <p>Accounts (users.json) are not part of a backup. A restored node has the accounts of its data directory,
 * or none, in which case it asks for a setup token or the admin password again.
 */
public final class Backup {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private Backup() {}

    /**
     * Sends the snapshot as {@code application/octet-stream}. The operations are already a consistent copy, so
     * the response can take as long as the client needs. A client that disconnects part way gets a file with no
     * trailer, which {@link #restore} refuses.
     */
    static void send(HttpExchange ex, List<Op> ops) throws IOException {
        String name = "serengeti-backup-" + STAMP.format(Instant.now()) + ".jsonl";
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/octet-stream");
        h.set("Content-Disposition", "attachment; filename=\"" + name + "\"");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            Snapshot.write(ops, out);
        }
    }

    /**
     * Loads a backup into an empty data directory and returns the number of operations it holds.
     *
     * <p>The file is read and checked completely before the directory is touched: its format, every operation,
     * the operation count, the checksum, and that a store can apply all of it. Any failure leaves the directory
     * as it was. The directory must not hold a write-ahead log or a snapshot already; an existing users.json is
     * kept.
     *
     * @throws IOException with the reason, when the file is missing, invalid or the directory is not empty
     */
    public static int restore(Path file, Path dataDir) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("no backup file " + file);
        }
        List<Op> ops;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            ops = Snapshot.read(in, true);
            new Store().applyAll(ops);
        } catch (IOException | RuntimeException e) {
            throw new IOException("backup " + file + " is not valid, nothing was written: " + e.getMessage(), e);
        }
        for (String name : List.of(StorageEngine.WAL_FILE, StorageEngine.SNAPSHOT_FILE)) {
            if (Files.exists(dataDir.resolve(name))) {
                throw new IOException(dataDir.toAbsolutePath() + " already holds " + name
                        + "; restore needs an empty data directory");
            }
        }
        Files.createDirectories(dataDir);
        Snapshot.writeAtomically(ops, dataDir.resolve(StorageEngine.SNAPSHOT_FILE));
        return ops.size();
    }
}
