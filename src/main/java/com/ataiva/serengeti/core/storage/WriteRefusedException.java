package com.ataiva.serengeti.core.storage;

/** A write the node will not take now (for example, its memory is full). Shown to the client with status 503. */
public final class WriteRefusedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public WriteRefusedException(String message) {
        super(message, null, false, false);
    }
}
