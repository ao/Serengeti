package com.ataiva.serengeti.query.parser.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents an ORDER BY clause in a SQL query.
 */
public class OrderByNode extends Node {
    private final List<OrderByItem> items;
    
    /**
     * Constructor with a single item
     * @param column Column name
     * @param ascending True for ascending order, false for descending
     */
    public OrderByNode(String column, boolean ascending) {
        this.items = new ArrayList<>();
        this.items.add(new OrderByItem(column, ascending));
    }
    
    /**
     * Constructor with multiple items
     * @param items List of ORDER BY items
     */
    public OrderByNode(List<OrderByItem> items) {
        this.items = new ArrayList<>(items);
    }
    
    /**
     * Get the list of ORDER BY items
     * @return List of ORDER BY items
     */
    public List<OrderByItem> getItems() {
        return new ArrayList<>(items);
    }
    
    /**
     * Add an ORDER BY item
     * @param column Column name
     * @param ascending True for ascending order, false for descending
     */
    public void addItem(String column, boolean ascending) {
        items.add(new OrderByItem(column, ascending));
    }
    
    /**
     * Add an ORDER BY item
     * @param item ORDER BY item to add
     */
    public void addItem(OrderByItem item) {
        items.add(item);
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ORDER BY ");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(items.get(i).toString());
        }
        return sb.toString();
    }
}