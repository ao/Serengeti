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
}
