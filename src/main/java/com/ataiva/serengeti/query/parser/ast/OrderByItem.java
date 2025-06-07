package com.ataiva.serengeti.query.parser.ast;

/**
 * Represents an item in an ORDER BY clause.
 */
public class OrderByItem {
    private final String column;
    private final boolean ascending;
    
    /**
     * Constructor
     * @param column Column name
     * @param ascending True for ascending order, false for descending
     */
    public OrderByItem(String column, boolean ascending) {
        this.column = column;
        this.ascending = ascending;
    }
    
    /**
     * Get the column name
     * @return Column name
     */
    public String getColumn() {
        return column;
    }
    
    /**
     * Check if the order is ascending
     * @return True for ascending order, false for descending
     */
    public boolean isAscending() {
        return ascending;
    }
    
    @Override
    public String toString() {
        return column + (ascending ? " ASC" : " DESC");
    }
}