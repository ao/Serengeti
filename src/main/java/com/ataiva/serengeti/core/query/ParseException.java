package com.ataiva.serengeti.core.query;

/** Query text that does not follow the grammar or breaks one of its limits. */
public final class ParseException extends QueryException {
    public ParseException(String message, int position) {
        super(message, position);
    }
}
