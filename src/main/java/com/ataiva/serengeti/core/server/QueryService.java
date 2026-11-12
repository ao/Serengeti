package com.ataiva.serengeti.core.server;

/** Runs one query for an authenticated caller. The HTTP layer knows nothing about the language. */
@FunctionalInterface
public interface QueryService {
    /**
     * Returns a value that {@link com.ataiva.serengeti.core.json.Json#write} can serialise.
     *
     * @throws Rejected if the query is malformed or refers to something that does not exist; the message
     *     is shown to the client
     */
    Object run(String query, Users.Role role) throws Rejected;

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
