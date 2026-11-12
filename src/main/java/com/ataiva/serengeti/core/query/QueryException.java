package com.ataiva.serengeti.core.query;

/** A query that cannot be parsed or run. */
public class QueryException extends RuntimeException {
    private final int position;

    public QueryException(String message) {
        this(message, -1);
    }

    /** When {@code position} is 0 or more, " at position N" is appended to the message. */
    public QueryException(String message, int position) {
        super(position >= 0 ? message + " at position " + position : message);
        this.position = position >= 0 ? position : -1;
    }

    /** The 0-based character offset in the query text, or -1 if there is none. */
    public int position() {
        return position;
    }
}
