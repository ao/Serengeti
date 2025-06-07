package com.ataiva.serengeti.query.parser.ast;

/**
 * Base class for all nodes in the query syntax tree.
 */
public abstract class Node {
    /**
     * Default constructor
     */
    protected Node() {
        // Default constructor
    }
    
    /**
     * Accept a visitor for the visitor pattern
     * @param visitor Visitor to accept
     * @param <T> Return type of the visitor
     * @return Result of the visitor's visit method
     */
    public <T> T accept(NodeVisitor<T> visitor) {
        return visitor.visit(this);
    }
}