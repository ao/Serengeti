package com.ataiva.serengeti.core.server;

import java.util.List;
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

    /** Values read on each scrape of {@code GET /metrics}. */
    default Gauges gauges() {
        return Gauges.ZERO;
    }

    /**
     * Node values for the metrics. {@code walFsyncs} and {@code walFsyncNanos} count forces of the log since
     * start, so they are counters; the others are gauges.
     */
    record Gauges(long walBytes, long walFsyncs, long walFsyncNanos, long databases) {
        public static final Gauges ZERO = new Gauges(0, 0, 0, 0);
    }
}
