package com.ataiva.serengeti.core.cluster;

/** Too few nodes are reachable for the request. The message is shown to the client with status 503. */
public final class UnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnavailableException(String message) {
        super(message, null, false, false);
    }
}
