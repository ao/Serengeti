package com.ataiva.serengeti.query.parser.ast;

/**
 * Visitor interface for the visitor pattern.
 * @param <T> Return type of the visitor
 */
public interface NodeVisitor<T> {
    /**
     * Visit a node in the syntax tree
     * @param node Node to visit
     * @return Result of the visit
     */
    T visit(Node node);
}