package com.ataiva.serengeti.query.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import com.ataiva.serengeti.performance.PerformanceProfiler;
import com.ataiva.serengeti.query.parser.ast.*;

/**
 * QueryNormalizer normalizes query syntax trees to a standard form.
 * This makes query optimization and execution more efficient and predictable.
 */
public class QueryNormalizer {
    private static final Logger LOGGER = Logger.getLogger(QueryNormalizer.class.getName());
    
    // Singleton instance
    private static QueryNormalizer instance;
    
    /**
     * Private constructor for singleton pattern
     */
    private QueryNormalizer() {
        // Private constructor
    }
    
    /**
     * Get the singleton instance of QueryNormalizer
     * @return QueryNormalizer instance
     */
    public static synchronized QueryNormalizer getInstance() {
        if (instance == null) {
            instance = new QueryNormalizer();
        }
        return instance;
    }
    
    /**
     * Normalize a query syntax tree
     * @param syntaxTree Original syntax tree
     * @return Normalized syntax tree
     */
    public SyntaxTree normalize(SyntaxTree syntaxTree) {
        String timerId = PerformanceProfiler.getInstance().startTimer("query", "normalize");
        try {
            LOGGER.fine("Normalizing query syntax tree");
            
            // Create a new query node with the same type
            QueryNode originalRoot = syntaxTree.getRoot();
            QueryNode normalizedRoot = new QueryNode(originalRoot.getQueryType());
            
            // Apply normalization rules based on query type
            switch (originalRoot.getQueryType()) {
                case SELECT:
                    normalizeSelectQuery(originalRoot, normalizedRoot);
                    break;
                case INSERT:
                    normalizeInsertQuery(originalRoot, normalizedRoot);
                    break;
                case UPDATE:
                    normalizeUpdateQuery(originalRoot, normalizedRoot);
                    break;
                case DELETE:
                    normalizeDeleteQuery(originalRoot, normalizedRoot);
                    break;
                case CREATE_TABLE:
                    normalizeCreateTableQuery(originalRoot, normalizedRoot);
                    break;
                case CREATE_DATABASE:
                    normalizeCreateDatabaseQuery(originalRoot, normalizedRoot);
                    break;
                case DROP_TABLE:
                    normalizeDropTableQuery(originalRoot, normalizedRoot);
                    break;
                case DROP_DATABASE:
                    normalizeDropDatabaseQuery(originalRoot, normalizedRoot);
                    break;
                case SHOW_TABLES:
                    normalizeShowTablesQuery(originalRoot, normalizedRoot);
                    break;
                case SHOW_DATABASES:
                    normalizeShowDatabasesQuery(originalRoot, normalizedRoot);
                    break;
                default:
                    // For unknown query types, just copy the original tree
                    for (Node child : originalRoot.getChildren()) {
                        normalizedRoot.addChild(child);
                    }
            }
            
            return new SyntaxTree(normalizedRoot);
        } finally {
            PerformanceProfiler.getInstance().stopTimer(timerId, "query.normalize-time");
        }
    }
    
    /**
     * Normalize a SELECT query
     * @param originalRoot Original query node
     * @param normalizedRoot Normalized query node
     */
    private void normalizeSelectQuery(QueryNode originalRoot, QueryNode normalizedRoot) {
        // Get the original nodes
        SelectNode originalSelectNode = originalRoot.getChild(SelectNode.class);
        FromNode originalFromNode = originalRoot.getChild(FromNode.class);
        WhereNode originalWhereNode = originalRoot.getChild(WhereNode.class);
        OrderByNode originalOrderByNode = originalRoot.getChild(OrderByNode.class);
        LimitNode originalLimitNode = originalRoot.getChild(LimitNode.class);
        
        // Normalize the SELECT clause
        if (originalSelectNode != null) {
            SelectNode normalizedSelectNode = normalizeSelectClause(originalSelectNode);
            normalizedRoot.addChild(normalizedSelectNode);
        }
        
        // Normalize the FROM clause
        if (originalFromNode != null) {
            FromNode normalizedFromNode = normalizeFromClause(originalFromNode);
            normalizedRoot.addChild(normalizedFromNode);
        }
        
        // Normalize the WHERE clause
        if (originalWhereNode != null) {
            WhereNode normalizedWhereNode = normalizeWhereClause(originalWhereNode);
            normalizedRoot.addChild(normalizedWhereNode);
        }
        
        // Normalize the ORDER BY clause
        if (originalOrderByNode != null) {
            OrderByNode normalizedOrderByNode = normalizeOrderByClause(originalOrderByNode);
            normalizedRoot.addChild(normalizedOrderByNode);
        }
        
        // Normalize the LIMIT clause
        if (originalLimitNode != null) {
            LimitNode normalizedLimitNode = normalizeLimitClause(originalLimitNode);
            normalizedRoot.addChild(normalizedLimitNode);
        }
    }
    
    /**
     * Normalize a SELECT clause
     * @param originalSelectNode Original SELECT node
     * @return Normalized SELECT node
     */
    private SelectNode normalizeSelectClause(SelectNode originalSelectNode) {
        // Normalize column names (lowercase, trim whitespace)
        List<String> normalizedColumns = new ArrayList<>();
        for (String column : originalSelectNode.getColumns()) {
            // Special case for "*"
            if (column.equals("*")) {
                normalizedColumns.add("*");
            } else {
                // Normalize column name
                normalizedColumns.add(normalizeIdentifier(column));
            }
        }
        
        // Create a new SELECT node with normalized columns
        SelectNode normalizedSelectNode = new SelectNode(normalizedColumns);
        normalizedSelectNode.setDistinct(originalSelectNode.isDistinct());
        
        return normalizedSelectNode;
    }
    
    /**
     * Normalize a FROM clause
     * @param originalFromNode Original FROM node
     * @return Normalized FROM node
     */
    private FromNode normalizeFromClause(FromNode originalFromNode) {
        // Normalize table references
        List<TableReference> normalizedTables = new ArrayList<>();
        for (TableReference tableRef : originalFromNode.getTables()) {
            String database = tableRef.getDatabase();
            String table = tableRef.getTable();
            String alias = tableRef.getAlias();
            
            // Normalize database and table names
            if (database != null) {
                database = normalizeIdentifier(database);
            }
            table = normalizeIdentifier(table);
            
            // Normalize alias if present
            if (alias != null) {
                alias = normalizeIdentifier(alias);
            }
            
            normalizedTables.add(new TableReference(database, table, alias));
        }
        
        return new FromNode(normalizedTables);
    }
    
    /**
     * Normalize a WHERE clause
     * @param originalWhereNode Original WHERE node
     * @return Normalized WHERE node
     */
    private WhereNode normalizeWhereClause(WhereNode originalWhereNode) {
        // Normalize column name
        String normalizedColumn = normalizeIdentifier(originalWhereNode.getColumn());
        
        // Normalize operator (uppercase)
        String normalizedOperator = originalWhereNode.getOperator().toUpperCase();
        
        // Value normalization depends on the operator and value type
        String normalizedValue = normalizeValue(originalWhereNode.getValue(), normalizedOperator);
        
        return new WhereNode(normalizedColumn, normalizedOperator, normalizedValue);
    }
    
    /**
     * Normalize an ORDER BY clause
     * @param originalOrderByNode Original ORDER BY node
     * @return Normalized ORDER BY node
     */
    private OrderByNode normalizeOrderByClause(OrderByNode originalOrderByNode) {
        // Normalize ORDER BY items
        List<OrderByItem> normalizedItems = new ArrayList<>();
        for (OrderByItem item : originalOrderByNode.getItems()) {
            String normalizedColumn = normalizeIdentifier(item.getColumn());
            normalizedItems.add(new OrderByItem(normalizedColumn, item.isAscending()));
        }
        
        return new OrderByNode(normalizedItems);
    }
    
    /**
     * Normalize a LIMIT clause
     * @param originalLimitNode Original LIMIT node
     * @return Normalized LIMIT node
     */
    private LimitNode normalizeLimitClause(LimitNode originalLimitNode) {
        // LIMIT clause normalization is simple, just ensure values are non-negative
        int offset = Math.max(0, originalLimitNode.getOffset());
        int limit = Math.max(0, originalLimitNode.getLimit());
        
        return new LimitNode(offset, limit);
    }
    
    /**
     * Normalize an INSERT query
     * @param originalRoot Original query node
     * @param normalizedRoot Normalized query node
     */
    private void normalizeInsertQuery(QueryNode originalRoot, QueryNode normalizedRoot) {
        // Placeholder for INSERT query normalization
        // In a real implementation, this would normalize table names, column names, and values
        
        // For now, just copy the original nodes
        for (Node child : originalRoot.getChildren()) {
            normalizedRoot.addChild(child);
        }
    }
    
    /**
     * Normalize an UPDATE query
     * @param originalRoot Original query node
     * @param normalizedRoot Normalized query node
     */
    private void normalizeUpdateQuery(QueryNode originalRoot, QueryNode normalizedRoot) {
        // Placeholder for UPDATE query normalization
        // In a real implementation, this would normalize table names, column names, values, and WHERE clause
        
        // For now, just copy the original nodes
        for (Node child : originalRoot.getChildren()) {
            normalizedRoot.addChild(child);
        }
    }
    
    /**
     * Normalize a DELETE query
     * @param originalRoot Original query node
     * @param normalizedRoot Normalized query node
     */
    private void normalizeDeleteQuery(QueryNode originalRoot, QueryNode normalizedRoot) {
        // Placeholder for DELETE query normalization
        // In a real implementation, this would normalize table names and WHERE clause
        
        // For now, just copy the original nodes
        for (Node child : originalRoot.getChildren()) {
            normalizedRoot.addChild(child);
        }
    }
    
    /**
     * Normalize a CREATE TABLE query
     * @param originalRoot Original query node
     * @param normalizedRoot Normalized query node
     */
    private void normalizeCreateTableQuery(QueryNode originalRoot, QueryNode normalizedRoot) {
        // Placeholder for CREATE TABLE query normalization
        // In a real implementation, this would normalize database name, table name, and column definitions
        
        // For now, just copy the original nodes
        for (Node child : originalRoot.getChildren()) {
            normalizedRoot.addChild(child);
        }
    }
    
    /**
     * Normalize a CREATE DATABASE query
     * @param originalRoot Original query node
     * @param normalizedRoot Normalized query node
     */
    private void normalizeCreateDatabaseQuery(QueryNode originalRoot, QueryNode normalizedRoot) {
        // Get the original database node
        DatabaseNode originalDbNode = originalRoot.getChild(DatabaseNode.class);
        
        if (originalDbNode != null) {
            // Normalize database name
            String normalizedName = normalizeIdentifier(originalDbNode.getName());
            DatabaseNode normalizedDbNode = new DatabaseNode(normalizedName);
            normalizedRoot.addChild(normalizedDbNode);
        }
    }
    
    /**
     * Normalize a DROP TABLE query
     * @param originalRoot Original query node
     * @param normalizedRoot Normalized query node
     */
    private void normalizeDropTableQuery(QueryNode originalRoot, QueryNode normalizedRoot) {
        // Placeholder for DROP TABLE query normalization
        // In a real implementation, this would normalize database and table names
        
        // For now, just copy the original nodes
        for (Node child : originalRoot.getChildren()) {
            normalizedRoot.addChild(child);
        }
    }
    
    /**
     * Normalize a DROP DATABASE query
     * @param originalRoot Original query node
     * @param normalizedRoot Normalized query node
     */
    private void normalizeDropDatabaseQuery(QueryNode originalRoot, QueryNode normalizedRoot) {
        // Get the original database node
        DatabaseNode originalDbNode = originalRoot.getChild(DatabaseNode.class);
        
        if (originalDbNode != null) {
            // Normalize database name
            String normalizedName = normalizeIdentifier(originalDbNode.getName());
            DatabaseNode normalizedDbNode = new DatabaseNode(normalizedName);
            normalizedRoot.addChild(normalizedDbNode);
        }
    }
    
    /**
     * Normalize a SHOW TABLES query
     * @param originalRoot Original query node
     * @param normalizedRoot Normalized query node
     */
    private void normalizeShowTablesQuery(QueryNode originalRoot, QueryNode normalizedRoot) {
        // Get the original database node if present
        DatabaseNode originalDbNode = originalRoot.getChild(DatabaseNode.class);
        
        if (originalDbNode != null) {
            // Normalize database name
            String normalizedName = normalizeIdentifier(originalDbNode.getName());
            DatabaseNode normalizedDbNode = new DatabaseNode(normalizedName);
            normalizedRoot.addChild(normalizedDbNode);
        }
    }
    
    /**
     * Normalize a SHOW DATABASES query
     * @param originalRoot Original query node
     * @param normalizedRoot Normalized query node
     */
    private void normalizeShowDatabasesQuery(QueryNode originalRoot, QueryNode normalizedRoot) {
        // SHOW DATABASES has no parameters to normalize
        // Just copy any children (though there shouldn't be any)
        for (Node child : originalRoot.getChildren()) {
            normalizedRoot.addChild(child);
        }
    }
    
    /**
     * Normalize an identifier (table name, column name, etc.)
     * @param identifier Identifier to normalize
     * @return Normalized identifier
     */
    private String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        
        // Remove quotes if present
        String normalized = identifier;
        if ((normalized.startsWith("\"") && normalized.endsWith("\"")) ||
            (normalized.startsWith("`") && normalized.endsWith("`")) ||
            (normalized.startsWith("[") && normalized.endsWith("]"))) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        
        // Trim whitespace and convert to lowercase
        normalized = normalized.trim().toLowerCase();
        
        return normalized;
    }
    
    /**
     * Normalize a value based on its type and the operator
     * @param value Value to normalize
     * @param operator Operator being used with the value
     * @return Normalized value
     */
    private String normalizeValue(String value, String operator) {
        if (value == null) {
            return null;
        }
        
        // Remove quotes if present for string values
        String normalized = value;
        if (normalized.startsWith("'") && normalized.endsWith("'")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        } else if (normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        
        // For LIKE operator, ensure wildcards are properly formatted
        if ("LIKE".equals(operator)) {
            // Ensure % wildcards are at the beginning/end if needed
            // This is a simplified example - real normalization would be more complex
            if (!normalized.contains("%")) {
                normalized = "%" + normalized + "%";
            }
        }
        
        return normalized;
    }
}