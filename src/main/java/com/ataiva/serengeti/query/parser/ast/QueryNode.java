package com.ataiva.serengeti.query.parser.ast;

import java.util.ArrayList;
import java.util.List;

import com.ataiva.serengeti.query.parser.QueryType;

/**
 * Base class for a node in the query syntax tree.
 * Represents a query operation (SELECT, INSERT, etc.).
 */
public class QueryNode extends Node {
    private final QueryType queryType;
    private final List<Node> children;
    
    /**
     * Constructor
     * @param queryType Type of the query
     */
    public QueryNode(QueryType queryType) {
        this.queryType = queryType;
        this.children = new ArrayList<>();
    }
    
    /**
     * Get the query type
     * @return Query type
     */
    public QueryType getQueryType() {
        return queryType;
    }
    
    /**
     * Add a child node
     * @param child Child node to add
     */
    public void addChild(Node child) {
        children.add(child);
    }
    
    /**
     * Get all child nodes
     * @return List of child nodes
     */
    public List<Node> getChildren() {
        return new ArrayList<>(children);
    }
    
    /**
     * Get a child node of a specific type
     * @param <T> Type of the node
     * @param clazz Class of the node
     * @return First child node of the specified type, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T extends Node> T getChild(Class<T> clazz) {
        for (Node child : children) {
            if (clazz.isInstance(child)) {
                return (T) child;
            }
        }
        return null;
    }
    
    /**
     * Get all child nodes of a specific type
     * @param <T> Type of the node
     * @param clazz Class of the node
     * @return List of child nodes of the specified type
     */
    @SuppressWarnings("unchecked")
    public <T extends Node> List<T> getChildren(Class<T> clazz) {
        List<T> result = new ArrayList<>();
        for (Node child : children) {
            if (clazz.isInstance(child)) {
                result.add((T) child);
            }
        }
        return result;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("QueryNode{type=").append(queryType);
        sb.append(", children=[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(children.get(i).toString());
        }
        sb.append("]}");
        return sb.toString();
    }
}