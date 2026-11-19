package com.ataiva.serengeti.core.query;

/**
 * A statement names a database or table that does not exist on this node. In a cluster it may exist on the
 * peers already: a schema change acknowledged by a majority reaches the other nodes a moment later.
 */
public final class NoSuchPlaceException extends QueryException {
    private static final long serialVersionUID = 1L;
    private final String db;

    public NoSuchPlaceException(String db, String message) {
        super(message);
        this.db = db;
    }

    /** The database the statement names. */
    public String db() {
        return db;
    }
}
