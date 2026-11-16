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
 * <p>Accounts (accounts.json, users.json) are not part of a backup. A restored node has the accounts of its data directory,
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
    static void send(HttpExchange ex, Path file) throws IOException {
        String name = "serengeti-backup-" + STAMP.format(Instant.now()) + ".jsonl";
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/octet-stream");
        h.set("Content-Disposition", "attachment; filename=\"" + name + "\"");
        ex.sendResponseHeaders(200, Files.size(file));
        try (OutputStream out = ex.getResponseBody()) {
            Files.copy(file, out);
        }
    }

    /**
     * Loads a backup into an empty data directory and returns the number of operations it holds.
     *
     * <p>The file is read and checked completely before the directory is touched: its format, every operation,
     * the operation count, the checksum, and that a store can apply all of it. Any failure leaves the directory
     * as it was. The directory must not hold a write-ahead log or a snapshot already; existing account files are
     * kept.
     *
     * @throws IOException with the reason, when the file is missing, invalid or the directory is not empty
     */
    public static int restore(Path file, Path dataDir) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("no backup file " + file);
        }
        // Each op goes into a store as it is read, which also checks that a store can apply it; the ops are never
        // held as a list, so a restore needs about the memory of the node that will run on the result.
        Store store = new Store();
        long[] newest = {0};
        long n;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            n = Snapshot.read(in, true, op -> {
                store.apply(op);
                newest[0] = Math.max(newest[0], op.stamp().wall());
            });
        } catch (IOException | RuntimeException e) {
            throw new IOException("backup " + file + " is not valid, nothing was written: " + e.getMessage(), e);
        }
        // Checked before taking the lock, so a refused restore leaves the directory exactly as it was, and again
        // under it, as a node may have started meanwhile.
        refuseUnlessEmpty(dataDir);
        Files.createDirectories(dataDir);
        // Held while writing, so a node cannot start on the directory halfway through.
        try (com.ataiva.serengeti.core.storage.DirLock lock = com.ataiva.serengeti.core.storage.DirLock.acquire(dataDir)) {
            refuseUnlessEmpty(dataDir);
            return write(store, n, newest[0], dataDir);
        }
    }

    private static void refuseUnlessEmpty(Path dataDir) throws IOException {
        for (String name : List.of(StorageEngine.WAL_FILE, StorageEngine.SNAPSHOT_FILE, StorageEngine.INDEX_FILE)) {
            if (Files.exists(dataDir.resolve(name))) {
                throw new IOException(dataDir.toAbsolutePath() + " already holds " + name
                        + "; restore needs an empty data directory");
            }
        }
    }

    private static int write(Store store, long n, long newest, Path dataDir) throws IOException {
        // Index definitions go to their own file, as the engine keeps them (see StorageEngine.INDEX_FILE).
        boolean[] indexes = {false};
        store.forEachIndexOp(op -> indexes[0] = true);
        if (indexes[0]) {
            Snapshot.writeAtomically(store::forEachIndexOp, dataDir.resolve(StorageEngine.INDEX_FILE));
        }
        Snapshot.writeAtomically(sink -> store.forEachOp(op -> {
            if (Op.legacy(op)) {
                sink.accept(op);
            }
        }), dataDir.resolve(StorageEngine.SNAPSHOT_FILE));
        // The backup cannot have synced with a cluster after its newest change: record that as this node's last
        // sync, so a cluster node restored from a backup older than tombstone_ttl_hours (less 20 minutes) starts stale instead of
        // bringing back rows deleted since.
        if (newest > 0) {
            Files.writeString(dataDir.resolve(com.ataiva.serengeti.core.cluster.Cluster.LAST_SYNC_FILE), newest + "\n");
        }
        return (int) n;
    }
}
