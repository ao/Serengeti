package com.ataiva.serengeti.core.query;

/** A statement the caller's role does not allow. Shown to the client with status 403. */
public final class ForbiddenException extends QueryException {
    private static final long serialVersionUID = 1L;

    public ForbiddenException(String message) {
        super(message);
    }
}
