package com.ataiva.serengeti.core.server;

/** Runs one query for an authenticated caller. The HTTP layer knows nothing about the language. */
@FunctionalInterface
public interface QueryService {
    /**
     * Returns a value that {@link com.ataiva.serengeti.core.json.Json#write} can serialise. {@code access} is
     * what the caller may reach: its role, and the databases of an account limited to some.
     *
     * @throws Rejected if the query is malformed, refers to something that does not exist, or is not allowed
     *     for the caller (403); the message is shown to the client
     */
    Object run(String query, Users.Access access) throws Rejected;

    /**
     * As {@link #run(String, Users.Access)}; with {@code majority}, the node first syncs with enough peers to
     * form a majority, so the read sees every write acknowledged before it began.
     */
    default Object run(String query, Users.Access access, boolean majority) throws Rejected {
        return run(query, access);
    }

    /**
     * As above; {@code ack} is "majority", "local", or null for the node's default. With "local", a write is
     * acknowledged once it is in this node's log, and reaches the other nodes when they are reachable.
     */
    default Object run(String query, Users.Access access, boolean majority, String ack) throws Rejected {
        return run(query, access, majority);
    }

    /** A query the client got wrong, with the HTTP status to return. */
    final class Rejected extends Exception {
        private static final long serialVersionUID = 1L;
        private final int status;

        public Rejected(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
