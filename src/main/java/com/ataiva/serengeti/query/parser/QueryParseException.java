package com.ataiva.serengeti.query.parser;

/**
 * Exception thrown when a query cannot be parsed.
 */
public class QueryParseException extends Exception {
    private static final long serialVersionUID = 1L;

    /**
     * Constructor with error message
     * @param message Error message
     */
    public QueryParseException(String message) {
        super(message);
    }

    /**
     * Constructor with error message and cause
     * @param message Error message
     * @param cause Cause of the exception
     */
    public QueryParseException(String message, Throwable cause) {
        super(message, cause);
    }
}