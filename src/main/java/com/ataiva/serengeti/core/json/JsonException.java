package com.ataiva.serengeti.core.json;

/** Malformed JSON, or a value that cannot be written as JSON. */
public final class JsonException extends RuntimeException {
    private final int position;

    public JsonException(String message, int position) {
        super(position >= 0 ? message + " at position " + position : message);
        this.position = position;
    }

    /** 0-based character offset of the problem, or -1. */
    public int position() {
        return position;
    }
}
