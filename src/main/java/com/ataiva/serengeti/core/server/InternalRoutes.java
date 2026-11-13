package com.ataiva.serengeti.core.server;

import java.util.Map;

/**
 * The node-to-node routes under {@code /internal/}. {@link HttpApi} checks the cluster key before calling
 * {@link #handle}; nothing here sees user credentials.
 */
public interface InternalRoutes {
    /** Header that carries the cluster key. */
    String KEY_HEADER = "X-Serengeti-Cluster-Key";

    /** Constant-time check of the cluster key sent by a peer. */
    boolean authorized(String key);

    /**
     * Handles one request and returns a value for {@link com.ataiva.serengeti.core.json.Json#write}.
     *
     * @param body the request body parsed as a JSON object, or an empty map for GET
     * @throws QueryService.Rejected for a bad request, with the status to send
     */
    Object handle(String method, String path, Map<String, String> query, Map<String, Object> body)
            throws QueryService.Rejected;
}
