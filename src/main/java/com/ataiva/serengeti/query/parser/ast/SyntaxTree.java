package com.ataiva.serengeti.query.parser.ast;

/**
 * Represents a complete syntax tree for a SQL query.
 */
public class SyntaxTree {
    private final QueryNode root;
    
    /**
     * Constructor
     * @param root Root node of the syntax tree
     */
    public SyntaxTree(QueryNode root) {
        this.root = root;
    }
    
    /**
     * Get the root node of the syntax tree
     * @return Root node
     */
    public QueryNode getRoot() {
        return root;
    }
    
    /**
     * Accept a visitor for the visitor pattern
     * @param visitor Visitor to accept
     * @param <T> Return type of the visitor
     * @return Result of the visitor's visit method
     */
    public <T> T accept(NodeVisitor<T> visitor) {
        return root.accept(visitor);
    }
    
    @Override
    public String toString() {
        return "SyntaxTree{" + root + "}";
    }
}