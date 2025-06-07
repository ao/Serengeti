package com.ataiva.serengeti.query.parser.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a FROM clause in a SQL query.
 */
public class FromNode extends Node {
    private final List<TableReference> tables;
    
    /**
     * Constructor with a single table
     * @param table Table reference
     */
    public FromNode(TableReference table) {
        this.tables = new ArrayList<>();
        this.tables.add(table);
    }
    
    /**
     * Constructor with multiple tables
     * @param tables List of table references
     */
    public FromNode(List<TableReference> tables) {
        this.tables = new ArrayList<>(tables);
    }
    
    /**
     * Get the list of tables
     * @return List of table references
     */
    public List<TableReference> getTables() {
        return new ArrayList<>(tables);
    }
    
    /**
     * Get the first table reference
     * @return First table reference or null if none
     */
    public TableReference getFirstTable() {
        return tables.isEmpty() ? null : tables.get(0);
    }
    
    /**
     * Add a table reference
     * @param table Table reference to add
     */
    public void addTable(TableReference table) {
        tables.add(table);
    }
    
    /**
     * Check if this FROM clause has multiple tables (a join)
     * @return True if it has multiple tables, false otherwise
     */
    public boolean isJoin() {
        return tables.size() > 1;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("FROM ");
        for (int i = 0; i < tables.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(tables.get(i).toString());
        }
        return sb.toString();
    }
}