package com.ataiva.serengeti.core.server;

import com.ataiva.serengeti.core.storage.Op;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * What the operational routes need from the node. {@link com.ataiva.serengeti.core.Node} supplies it; the
 * defaults serve a node with nothing extra, which is what the bare HTTP API is tested with.
 */
public interface NodeOps {
    /** Not available on a bare API. */
    NodeOps NONE = new NodeOps() {};

    /**
     * Checks for {@code GET /ready}. The node is ready when every check returns true. Checks run on each
     * request, so each must be cheap. A check that throws counts as false.
     */
    default List<BooleanSupplier> readiness() {
        return List.of();
    }

    /**
     * A consistent copy of the whole state for {@code GET /v1/backup}, taken under the storage lock. Empty if
     * this node cannot be backed up.
     */
    default Optional<List<Op>> snapshot() {
        return Optional.empty();
    }

    /** True once a write to disk has failed: the node then stops serving its data (StorageEngine.failed). */
    default boolean diskFailed() {
        return false;
    }

    /**
     * Writes a backup for {@code GET /v1/backup} to a new temporary file and returns it; the caller deletes it.
     * Empty if this node cannot be backed up. The default writes {@link #snapshot()}; a node streams its store
     * instead, so a backup needs no memory for a copy of the data.
     */
    default Optional<java.nio.file.Path> backup() throws java.io.IOException {
        Optional<List<Op>> ops = snapshot();
        if (ops.isEmpty()) {
            return Optional.empty();
        }
        java.nio.file.Path f = java.nio.file.Files.createTempFile("serengeti-backup-", ".jsonl");
        try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(f)) {
            com.ataiva.serengeti.core.storage.Snapshot.write(ops.get(), out);
        }
        return Optional.of(f);
    }

    /** Values read on each scrape of {@code GET /metrics}. */
    /** The change feed for GET /v1/changes, or null when the node keeps none. */
    default com.ataiva.serengeti.core.storage.ChangeFeed changes() {
        return null;
    }

    default Gauges gauges() {
        return Gauges.ZERO;
    }

    /**
     * Node values for the metrics. {@code walFsyncs} and {@code walFsyncNanos} count forces of the log since
     * start, so they are counters; the others are gauges.
     */
    record Gauges(long walBytes, long walFsyncs, long walFsyncNanos, long databases, long conflicts,
            long dataBytes, long dataBudget, boolean memoryFull) {
        public static final Gauges ZERO = new Gauges(0, 0, 0, 0, 0, 0, 0, false);
    }
}
