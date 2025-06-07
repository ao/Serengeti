package com.ataiva.serengeti.query.parser.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a SELECT clause in a SQL query.
 */
public class SelectNode extends Node {
    private final List<String> columns;
    private boolean distinct;
    
    /**
     * Constructor
     * @param columns List of columns to select
     */
    public SelectNode(List<String> columns) {
        this.columns = new ArrayList<>(columns);
        this.distinct = false;
    }
    
    /**
     * Get the list of columns to select
     * @return List of column names
     */
    public List<String> getColumns() {
        return new ArrayList<>(columns);
    }
    
    /**
     * Check if the SELECT is DISTINCT
     * @return True if DISTINCT, false otherwise
     */
    public boolean isDistinct() {
        return distinct;
    }
    
    /**
     * Set whether the SELECT is DISTINCT
     * @param distinct True if DISTINCT, false otherwise
     */
    public void setDistinct(boolean distinct) {
        this.distinct = distinct;
    }
    
    /**
     * Check if this is a SELECT *
     * @return True if selecting all columns, false otherwise
     */
    public boolean isSelectAll() {
        return columns.size() == 1 && columns.get(0).equals("*");
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ");
        if (distinct) {
            sb.append("DISTINCT ");
        }
        if (columns.size() == 1) {
            sb.append(columns.get(0));
        } else {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(columns.get(i));
            }
        }
        return sb.toString();
    }
}