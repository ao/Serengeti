package com.ataiva.serengeti.query.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ataiva.serengeti.performance.PerformanceProfiler;
import com.ataiva.serengeti.query.parser.ast.*;

/**
 * QueryParser parses SQL queries into abstract syntax trees.
 * This is the main entry point for query parsing in the Serengeti system.
 */
public class QueryParser {
    private static final Logger LOGGER = Logger.getLogger(QueryParser.class.getName());
    
    // Singleton instance
    private static QueryParser instance;
    
    // Configuration
    private boolean normalizationEnabled = true;
    private boolean syntaxTreeOptimizationEnabled = true;
    
    /**
     * Private constructor for singleton pattern
     */
    private QueryParser() {
        // Private constructor
    }
    
    /**
     * Get the singleton instance of QueryParser
     * @return QueryParser instance
     */
    public static synchronized QueryParser getInstance() {
        if (instance == null) {
            instance = new QueryParser();
        }
        return instance;
    }
    
    /**
     * Parse a SQL query string into a syntax tree
     * @param query SQL query string
     * @return Syntax tree representing the query
     * @throws QueryParseException if the query cannot be parsed
     */
    public SyntaxTree parse(String query) throws QueryParseException {
        String timerId = PerformanceProfiler.getInstance().startTimer("query", "parse");
        try {
            LOGGER.fine("Parsing query: " + query);
            
            // Trim and normalize the query
            query = query.trim();
            if (query.endsWith(";")) {
                query = query.substring(0, query.length() - 1);
            }
            
            // Determine the query type
            QueryType queryType = determineQueryType(query);
            
            // Parse the query based on its type
            SyntaxTree syntaxTree;
            switch (queryType) {
                case SELECT:
                    syntaxTree = parseSelectQuery(query);
                    break;
                case INSERT:
                    syntaxTree = parseInsertQuery(query);
                    break;
                case UPDATE:
                    syntaxTree = parseUpdateQuery(query);
                    break;
                case DELETE:
                    syntaxTree = parseDeleteQuery(query);
                    break;
                case CREATE_TABLE:
                    syntaxTree = parseCreateTableQuery(query);
                    break;
                case CREATE_DATABASE:
                    syntaxTree = parseCreateDatabaseQuery(query);
                    break;
                case DROP_TABLE:
                    syntaxTree = parseDropTableQuery(query);
                    break;
                case DROP_DATABASE:
                    syntaxTree = parseDropDatabaseQuery(query);
                    break;
                case SHOW_TABLES:
                    syntaxTree = parseShowTablesQuery(query);
                    break;
                case SHOW_DATABASES:
                    syntaxTree = parseShowDatabasesQuery(query);
                    break;
                default:
                    throw new QueryParseException("Unsupported query type");
            }
            
            // Apply normalization if enabled
            if (normalizationEnabled) {
                syntaxTree = normalizeQuery(syntaxTree);
            }
            
            // Apply syntax tree optimization if enabled
            if (syntaxTreeOptimizationEnabled) {
                syntaxTree = optimizeSyntaxTree(syntaxTree);
            }
            
            return syntaxTree;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error parsing query: " + query, e);
            throw new QueryParseException("Error parsing query: " + e.getMessage(), e);
        } finally {
            PerformanceProfiler.getInstance().stopTimer(timerId, "query.parse-time");
        }
    }
    
    /**
     * Determine the type of a SQL query
     * @param query SQL query string
     * @return QueryType enum value
     * @throws QueryParseException if the query type cannot be determined
     */
    private QueryType determineQueryType(String query) throws QueryParseException {
        String lowerQuery = query.toLowerCase();
        
        if (lowerQuery.startsWith("select ")) {
            return QueryType.SELECT;
        } else if (lowerQuery.startsWith("insert ")) {
            return QueryType.INSERT;
        } else if (lowerQuery.startsWith("update ")) {
            return QueryType.UPDATE;
        } else if (lowerQuery.startsWith("delete ")) {
            return QueryType.DELETE;
        } else if (lowerQuery.startsWith("create table ")) {
            return QueryType.CREATE_TABLE;
        } else if (lowerQuery.startsWith("create database ")) {
            return QueryType.CREATE_DATABASE;
        } else if (lowerQuery.startsWith("drop table ")) {
            return QueryType.DROP_TABLE;
        } else if (lowerQuery.startsWith("drop database ")) {
            return QueryType.DROP_DATABASE;
        } else if (lowerQuery.startsWith("show tables")) {
            return QueryType.SHOW_TABLES;
        } else if (lowerQuery.startsWith("show databases")) {
            return QueryType.SHOW_DATABASES;
        } else {
            throw new QueryParseException("Unknown query type: " + query);
        }
    }
    
    /**
     * Parse a SELECT query
     * @param query SQL query string
     * @return Syntax tree for the SELECT query
     * @throws QueryParseException if the query cannot be parsed
     */
    private SyntaxTree parseSelectQuery(String query) throws QueryParseException {
        // Remove the "SELECT " prefix
        String remaining = query.substring(7).trim();
        
        // Parse the SELECT clause
        int fromIndex = findKeywordIndex(remaining, " from ");
        if (fromIndex == -1) {
            throw new QueryParseException("SELECT query must have a FROM clause");
        }
        
        String selectClause = remaining.substring(0, fromIndex).trim();
        remaining = remaining.substring(fromIndex + 6).trim();
        
        // Parse the column list
        List<String> columns = parseColumnList(selectClause);
        
        // Parse the FROM clause
        int whereIndex = findKeywordIndex(remaining, " where ");
        int joinIndex = findKeywordIndex(remaining, " join ");
        int orderByIndex = findKeywordIndex(remaining, " order by ");
        int limitIndex = findKeywordIndex(remaining, " limit ");
        
        String fromClause;
        if (whereIndex != -1) {
            fromClause = remaining.substring(0, whereIndex).trim();
            remaining = remaining.substring(whereIndex + 7).trim();
        } else if (joinIndex != -1) {
            fromClause = remaining.substring(0, joinIndex).trim();
            remaining = remaining.substring(joinIndex + 6).trim();
        } else if (orderByIndex != -1) {
            fromClause = remaining.substring(0, orderByIndex).trim();
            remaining = remaining.substring(orderByIndex + 9).trim();
        } else if (limitIndex != -1) {
            fromClause = remaining.substring(0, limitIndex).trim();
            remaining = remaining.substring(limitIndex + 7).trim();
        } else {
            fromClause = remaining;
            remaining = "";
        }
        
        // Parse the table reference
        TableReference tableRef = parseTableReference(fromClause);
        
        // Create the SELECT node
        SelectNode selectNode = new SelectNode(columns);
        
        // Create the FROM node
        FromNode fromNode = new FromNode(tableRef);
        
        // Parse the WHERE clause if present
        WhereNode whereNode = null;
        if (whereIndex != -1) {
            int nextClauseIndex = Math.min(
                Math.min(
                    orderByIndex != -1 ? orderByIndex : Integer.MAX_VALUE,
                    limitIndex != -1 ? limitIndex : Integer.MAX_VALUE
                ),
                Integer.MAX_VALUE
            );
            
            String whereClause;
            if (nextClauseIndex != Integer.MAX_VALUE) {
                whereClause = remaining.substring(0, nextClauseIndex).trim();
                remaining = remaining.substring(nextClauseIndex).trim();
            } else {
                whereClause = remaining;
                remaining = "";
            }
            
            whereNode = parseWhereClause(whereClause);
        }
        
        // Parse the ORDER BY clause if present
        OrderByNode orderByNode = null;
        if (orderByIndex != -1) {
            int nextClauseIndex = limitIndex != -1 ? limitIndex - orderByIndex - 9 : remaining.length();
            String orderByClause = remaining.substring(0, nextClauseIndex).trim();
            remaining = remaining.substring(nextClauseIndex).trim();
            
            orderByNode = parseOrderByClause(orderByClause);
        }
        
        // Parse the LIMIT clause if present
        LimitNode limitNode = null;
        if (limitIndex != -1) {
            limitNode = parseLimitClause(remaining);
        }
        
        // Create the query node
        QueryNode queryNode = new QueryNode(QueryType.SELECT);
        queryNode.addChild(selectNode);
        queryNode.addChild(fromNode);
        
        if (whereNode != null) {
            queryNode.addChild(whereNode);
        }
        
        if (orderByNode != null) {
            queryNode.addChild(orderByNode);
        }
        
        if (limitNode != null) {
            queryNode.addChild(limitNode);
        }
        
        // Create and return the syntax tree
        return new SyntaxTree(queryNode);
    }
    
    /**
     * Parse an INSERT query
     * @param query SQL query string
     * @return Syntax tree for the INSERT query
     * @throws QueryParseException if the query cannot be parsed
     */
    private SyntaxTree parseInsertQuery(String query) throws QueryParseException {
        // Placeholder implementation
        throw new QueryParseException("INSERT query parsing not yet implemented");
    }
    
    /**
     * Parse an UPDATE query
     * @param query SQL query string
     * @return Syntax tree for the UPDATE query
     * @throws QueryParseException if the query cannot be parsed
     */
    private SyntaxTree parseUpdateQuery(String query) throws QueryParseException {
        // Placeholder implementation
        throw new QueryParseException("UPDATE query parsing not yet implemented");
    }
    
    /**
     * Parse a DELETE query
     * @param query SQL query string
     * @return Syntax tree for the DELETE query
     * @throws QueryParseException if the query cannot be parsed
     */
    private SyntaxTree parseDeleteQuery(String query) throws QueryParseException {
        // Placeholder implementation
        throw new QueryParseException("DELETE query parsing not yet implemented");
    }
    
    /**
     * Parse a CREATE TABLE query
     * @param query SQL query string
     * @return Syntax tree for the CREATE TABLE query
     * @throws QueryParseException if the query cannot be parsed
     */
    private SyntaxTree parseCreateTableQuery(String query) throws QueryParseException {
        // Placeholder implementation
        throw new QueryParseException("CREATE TABLE query parsing not yet implemented");
    }
    
    /**
     * Parse a CREATE DATABASE query
     * @param query SQL query string
     * @return Syntax tree for the CREATE DATABASE query
     * @throws QueryParseException if the query cannot be parsed
     */
    private SyntaxTree parseCreateDatabaseQuery(String query) throws QueryParseException {
        // Extract the database name
        String dbName = query.substring(15).trim();
        
        // Create the query node
        QueryNode queryNode = new QueryNode(QueryType.CREATE_DATABASE);
        
        // Create the database node
        DatabaseNode dbNode = new DatabaseNode(dbName);
        queryNode.addChild(dbNode);
        
        // Create and return the syntax tree
        return new SyntaxTree(queryNode);
    }
    
    /**
     * Parse a DROP TABLE query
     * @param query SQL query string
     * @return Syntax tree for the DROP TABLE query
     * @throws QueryParseException if the query cannot be parsed
     */
    private SyntaxTree parseDropTableQuery(String query) throws QueryParseException {
        // Placeholder implementation
        throw new QueryParseException("DROP TABLE query parsing not yet implemented");
    }
    
    /**
     * Parse a DROP DATABASE query
     * @param query SQL query string
     * @return Syntax tree for the DROP DATABASE query
     * @throws QueryParseException if the query cannot be parsed
     */
    private SyntaxTree parseDropDatabaseQuery(String query) throws QueryParseException {
        // Extract the database name
        String dbName = query.substring(13).trim();
        
        // Create the query node
        QueryNode queryNode = new QueryNode(QueryType.DROP_DATABASE);
        
        // Create the database node
        DatabaseNode dbNode = new DatabaseNode(dbName);
        queryNode.addChild(dbNode);
        
        // Create and return the syntax tree
        return new SyntaxTree(queryNode);
    }
    
    /**
     * Parse a SHOW TABLES query
     * @param query SQL query string
     * @return Syntax tree for the SHOW TABLES query
     * @throws QueryParseException if the query cannot be parsed
     */
    private SyntaxTree parseShowTablesQuery(String query) throws QueryParseException {
        // Extract the database name if present
        String dbName = null;
        if (query.toLowerCase().contains(" in ")) {
            int inIndex = query.toLowerCase().indexOf(" in ");
            dbName = query.substring(inIndex + 4).trim();
        }
        
        // Create the query node
        QueryNode queryNode = new QueryNode(QueryType.SHOW_TABLES);
        
        // Create the database node if a database was specified
        if (dbName != null) {
            DatabaseNode dbNode = new DatabaseNode(dbName);
            queryNode.addChild(dbNode);
        }
        
        // Create and return the syntax tree
        return new SyntaxTree(queryNode);
    }
    
    /**
     * Parse a SHOW DATABASES query
     * @param query SQL query string
     * @return Syntax tree for the SHOW DATABASES query
     * @throws QueryParseException if the query cannot be parsed
     */
    private SyntaxTree parseShowDatabasesQuery(String query) throws QueryParseException {
        // Create the query node
        QueryNode queryNode = new QueryNode(QueryType.SHOW_DATABASES);
        
        // Create and return the syntax tree
        return new SyntaxTree(queryNode);
    }
    
    /**
     * Parse a list of columns
     * @param columnList Column list string
     * @return List of column names
     */
    private List<String> parseColumnList(String columnList) {
        List<String> columns = new ArrayList<>();
        
        // Handle the special case of "*"
        if (columnList.equals("*")) {
            columns.add("*");
            return columns;
        }
        
        // Split the column list by commas
        String[] parts = columnList.split(",");
        for (String part : parts) {
            columns.add(part.trim());
        }
        
        return columns;
    }
    
    /**
     * Parse a table reference
     * @param tableRef Table reference string
     * @return TableReference object
     * @throws QueryParseException if the table reference cannot be parsed
     */
    private TableReference parseTableReference(String tableRef) throws QueryParseException {
        // Check if the table reference contains a dot (database.table)
        if (tableRef.contains(".")) {
            String[] parts = tableRef.split("\\.");
            if (parts.length != 2) {
                throw new QueryParseException("Invalid table reference: " + tableRef);
            }
            return new TableReference(parts[0].trim(), parts[1].trim());
        } else {
            // No database specified, use the default database
            return new TableReference(null, tableRef.trim());
        }
    }
    
    /**
     * Parse a WHERE clause
     * @param whereClause WHERE clause string
     * @return WhereNode object
     * @throws QueryParseException if the WHERE clause cannot be parsed
     */
    private WhereNode parseWhereClause(String whereClause) throws QueryParseException {
        // This is a simplified implementation that only handles basic conditions
        // In a real implementation, this would parse complex conditions with AND, OR, etc.
        
        // Check for common operators
        String operator = null;
        int operatorIndex = -1;
        
        if (whereClause.contains("=")) {
            operator = "=";
            operatorIndex = whereClause.indexOf("=");
        } else if (whereClause.contains(">")) {
            operator = ">";
            operatorIndex = whereClause.indexOf(">");
        } else if (whereClause.contains("<")) {
            operator = "<";
            operatorIndex = whereClause.indexOf("<");
        } else if (whereClause.contains(">=")) {
            operator = ">=";
            operatorIndex = whereClause.indexOf(">=");
        } else if (whereClause.contains("<=")) {
            operator = "<=";
            operatorIndex = whereClause.indexOf("<=");
        } else if (whereClause.contains("<>")) {
            operator = "<>";
            operatorIndex = whereClause.indexOf("<>");
        } else if (whereClause.contains("!=")) {
            operator = "!=";
            operatorIndex = whereClause.indexOf("!=");
        } else if (whereClause.toLowerCase().contains(" like ")) {
            operator = "LIKE";
            operatorIndex = whereClause.toLowerCase().indexOf(" like ") + 1;
        } else if (whereClause.toLowerCase().contains(" in ")) {
            operator = "IN";
            operatorIndex = whereClause.toLowerCase().indexOf(" in ") + 1;
        } else {
            throw new QueryParseException("Unsupported operator in WHERE clause: " + whereClause);
        }
        
        // Extract the column and value
        String column = whereClause.substring(0, operatorIndex).trim();
        String value = whereClause.substring(operatorIndex + operator.length()).trim();
        
        // Remove quotes from the value if present
        if (value.startsWith("'") && value.endsWith("'")) {
            value = value.substring(1, value.length() - 1);
        } else if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        
        // Create and return the WHERE node
        return new WhereNode(column, operator, value);
    }
    
    /**
     * Parse an ORDER BY clause
     * @param orderByClause ORDER BY clause string
     * @return OrderByNode object
     * @throws QueryParseException if the ORDER BY clause cannot be parsed
     */
    private OrderByNode parseOrderByClause(String orderByClause) throws QueryParseException {
        // Split the ORDER BY clause by commas
        String[] parts = orderByClause.split(",");
        List<OrderByItem> items = new ArrayList<>();
        
        for (String part : parts) {
            part = part.trim();
            boolean ascending = true;
            
            // Check for ASC/DESC
            if (part.toLowerCase().endsWith(" asc")) {
                part = part.substring(0, part.length() - 4).trim();
            } else if (part.toLowerCase().endsWith(" desc")) {
                part = part.substring(0, part.length() - 5).trim();
                ascending = false;
            }
            
            items.add(new OrderByItem(part, ascending));
        }
        
        // Create and return the ORDER BY node
        return new OrderByNode(items);
    }
    
    /**
     * Parse a LIMIT clause
     * @param limitClause LIMIT clause string
     * @return LimitNode object
     * @throws QueryParseException if the LIMIT clause cannot be parsed
     */
    private LimitNode parseLimitClause(String limitClause) throws QueryParseException {
        // Check if the LIMIT clause contains a comma (LIMIT offset, count)
        if (limitClause.contains(",")) {
            String[] parts = limitClause.split(",");
            if (parts.length != 2) {
                throw new QueryParseException("Invalid LIMIT clause: " + limitClause);
            }
            
            try {
                int offset = Integer.parseInt(parts[0].trim());
                int limit = Integer.parseInt(parts[1].trim());
                return new LimitNode(offset, limit);
            } catch (NumberFormatException e) {
                throw new QueryParseException("Invalid LIMIT values: " + limitClause);
            }
        } else {
            // No offset specified, just a limit
            try {
                int limit = Integer.parseInt(limitClause.trim());
                return new LimitNode(0, limit);
            } catch (NumberFormatException e) {
                throw new QueryParseException("Invalid LIMIT value: " + limitClause);
            }
        }
    }
    
    /**
     * Find the index of a keyword in a string, ignoring case
     * @param str String to search in
     * @param keyword Keyword to search for
     * @return Index of the keyword or -1 if not found
     */
    private int findKeywordIndex(String str, String keyword) {
        return str.toLowerCase().indexOf(keyword);
    }
    
    /**
     * Normalize a query syntax tree
     * @param syntaxTree Original syntax tree
     * @return Normalized syntax tree
     */
    private SyntaxTree normalizeQuery(SyntaxTree syntaxTree) {
        // Apply query normalization rules
        // 1. Standardize column names (e.g., remove quotes, normalize case)
        // 2. Standardize table references
        // 3. Simplify expressions
        // 4. Normalize operators
        
        // For now, just return the original tree
        // In a real implementation, this would apply normalization rules
        return syntaxTree;
    }
    
    /**
     * Optimize a query syntax tree
     * @param syntaxTree Original syntax tree
     * @return Optimized syntax tree
     */
    private SyntaxTree optimizeSyntaxTree(SyntaxTree syntaxTree) {
        // Apply syntax tree optimization rules
        // 1. Constant folding
        // 2. Predicate simplification
        // 3. Common subexpression elimination
        
        // For now, just return the original tree
        // In a real implementation, this would apply optimization rules
        return syntaxTree;
    }
    
    /**
     * Enable or disable query normalization
     * @param enabled True to enable, false to disable
     */
    public void setNormalizationEnabled(boolean enabled) {
        this.normalizationEnabled = enabled;
    }
    
    /**
     * Enable or disable syntax tree optimization
     * @param enabled True to enable, false to disable
     */
    public void setSyntaxTreeOptimizationEnabled(boolean enabled) {
        this.syntaxTreeOptimizationEnabled = enabled;
    }
}