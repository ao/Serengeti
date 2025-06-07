package com.ataiva.serengeti.query.parser.ast;

/**
 * Represents a database reference in a SQL query.
 */
public class DatabaseNode extends Node {
    private final String name;
    
    /**
     * Constructor
     * @param name Database name
     */
    public DatabaseNode(String name) {
        this.name = name;
    }
    
    /**
     * Get the database name
     * @return Database name
     */
    public String getName() {
        return name;
    }
    
    @Override
    public String toString() {
        return "DATABASE " + name;
    }
}