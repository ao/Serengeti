package com.ataiva.serengeti.query.parser.ast;

/**
 * Represents a LIMIT clause in a SQL query.
 */
public class LimitNode extends Node {
    private final int offset;
    private final int limit;
    
    /**
     * Constructor with just a limit
     * @param limit Maximum number of rows to return
     */
    public LimitNode(int limit) {
        this(0, limit);
    }
    
    /**
     * Constructor with offset and limit
     * @param offset Number of rows to skip
     * @param limit Maximum number of rows to return
     */
    public LimitNode(int offset, int limit) {
        this.offset = offset;
        this.limit = limit;
    }
    
    /**
     * Get the offset
     * @return Number of rows to skip
     */
    public int getOffset() {
        return offset;
    }
    
    /**
     * Get the limit
     * @return Maximum number of rows to return
     */
    public int getLimit() {
        return limit;
    }
    
    /**
     * Check if this LIMIT clause has an offset
     * @return True if it has an offset, false otherwise
     */
    public boolean hasOffset() {
        return offset > 0;
    }
    
    @Override
    public String toString() {
        if (hasOffset()) {
            return "LIMIT " + offset + ", " + limit;
        } else {
            return "LIMIT " + limit;
        }
    }
}