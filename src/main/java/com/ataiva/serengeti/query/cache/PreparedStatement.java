package com.ataiva.serengeti.query.cache;

import com.ataiva.serengeti.query.optimizer.QueryPlan;
import com.ataiva.serengeti.query.optimizer.QueryPlanGenerator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * PreparedStatement represents a pre-compiled SQL statement that can be executed
 * multiple times with different parameter values.
 */
public class PreparedStatement {
    private static final Logger LOGGER = Logger.getLogger(PreparedStatement.class.getName());
    
    // Registry of all prepared statements
    private static final Map<String, PreparedStatement> statements = new HashMap<>();
    
    // Statement ID
    private final String id;
    
    // Original query string
    private final String query;
    
    // Parameter placeholders in the query
    private final List<String> parameterPlaceholders;
    
    // Pre-generated query plan (without parameter values)
    private final QueryPlan basePlan;
    
    // Database name
    private final String database;
    
    // Table name
    private final String table;
    
    // Columns to select
    private final String selectColumns;
    
    // Column in WHERE clause
    private final String whereColumn;
    
    // Operator in WHERE clause
    private final String whereOperator;
    
    // Usage count
    private int usageCount;
    
    // Last used timestamp
    private long lastUsed;
    
    /**
     * Constructor
     * @param query Original query string
     * @param parameterPlaceholders Parameter placeholders in the query
     * @param database Database name
     * @param table Table name
     * @param selectColumns Columns to select
     * @param whereColumn Column in WHERE clause
     * @param whereOperator Operator in WHERE clause
     */
    public PreparedStatement(String query, List<String> parameterPlaceholders,
                            String database, String table, String selectColumns,
                            String whereColumn, String whereOperator) {
        this.id = UUID.randomUUID().toString();
        this.query = query;
        this.parameterPlaceholders = parameterPlaceholders;
        this.database = database;
        this.table = table;
        this.selectColumns = selectColumns;
        this.whereColumn = whereColumn;
        this.whereOperator = whereOperator;
        this.usageCount = 0;
        this.lastUsed = System.currentTimeMillis();
        
        // Generate a base query plan (without parameter values)
        this.basePlan = QueryPlanGenerator.getInstance().generateSelectPlan(
            database, table, selectColumns, whereColumn, "", whereOperator);
        
        // Register this statement
        statements.put(id, this);
        
        LOGGER.info("Created prepared statement: " + id);
    }
    
    /**
     * Execute the prepared statement with parameter values
     * @param parameterValues Parameter values
     * @return Query result
     */
    public List<String> execute(Object[] parameterValues) {
        if (parameterValues.length != parameterPlaceholders.size()) {
            throw new IllegalArgumentException("Number of parameter values does not match number of placeholders");
        }
        
        // Update usage statistics
        usageCount++;
        lastUsed = System.currentTimeMillis();
        
        // Check if the result is in the cache
        String cacheKey = QueryCache.generatePreparedStatementCacheKey(id, parameterValues);
        QueryCache cache = QueryCache.getInstance();
        
        List<String> cachedResult = cache.getQueryResult(cacheKey);
        if (cachedResult != null) {
            return cachedResult;
        }
        
        // Convert parameter values to strings
        String whereValue = (parameterValues[0] != null) ? parameterValues[0].toString() : "";
        
        // Clone the base plan and update it with parameter values
        QueryPlan plan = cloneBasePlan();
        plan.setWhereValue(whereValue);
        
        // Execute the plan
        List<String> result = com.ataiva.serengeti.query.executor.QueryPlanExecutor.getInstance().execute(plan);
        
        // Cache the result
        cache.putQueryResult(cacheKey, result);
        
        return result;
    }
    
    /**
     * Clone the base query plan
     * @return Cloned query plan
     */
    private QueryPlan cloneBasePlan() {
        // Create a new plan with the same properties as the base plan
        QueryPlan plan = new QueryPlan();
        plan.setPlanType(basePlan.getPlanType());
        plan.setDatabase(database);
        plan.setTable(table);
        plan.setSelectColumns(selectColumns);
        plan.setWhereColumn(whereColumn);
        plan.setWhereOperator(whereOperator);
        
        // Copy operations from the base plan
        basePlan.getOperations().forEach(plan::addOperation);
        
        return plan;
    }
    
    /**
     * Get a prepared statement by ID
     * @param id Statement ID
     * @return PreparedStatement or null if not found
     */
    public static PreparedStatement getStatement(String id) {
        return statements.get(id);
    }
    
    /**
     * Create a new prepared statement
     * @param query Original query string
     * @param parameterPlaceholders Parameter placeholders in the query
     * @param database Database name
     * @param table Table name
     * @param selectColumns Columns to select
     * @param whereColumn Column in WHERE clause
     * @param whereOperator Operator in WHERE clause
     * @return New PreparedStatement
     */
    public static PreparedStatement create(String query, List<String> parameterPlaceholders,
                                          String database, String table, String selectColumns,
                                          String whereColumn, String whereOperator) {
        return new PreparedStatement(query, parameterPlaceholders, database, table,
                                    selectColumns, whereColumn, whereOperator);
    }
    
    /**
     * Get the statement ID
     * @return Statement ID
     */
    public String getId() {
        return id;
    }
    
    /**
     * Get the original query string
     * @return Query string
     */
    public String getQuery() {
        return query;
    }
    
    /**
     * Get the parameter placeholders
     * @return Parameter placeholders
     */
    public List<String> getParameterPlaceholders() {
        return parameterPlaceholders;
    }
    
    /**
     * Get the usage count
     * @return Usage count
     */
    public int getUsageCount() {
        return usageCount;
    }
    
    /**
     * Get the last used timestamp
     * @return Last used timestamp
     */
    public long getLastUsed() {
        return lastUsed;
    }
    
    /**
     * Close the prepared statement and remove it from the registry
     */
    public void close() {
        statements.remove(id);
        LOGGER.info("Closed prepared statement: " + id);
    }
    
    /**
     * Get all prepared statements
     * @return Map of statement IDs to PreparedStatements
     */
    public static Map<String, PreparedStatement> getAllStatements() {
        return new HashMap<>(statements);
    }
    
    /**
     * Close all prepared statements
     */
    public static void closeAll() {
        statements.clear();
        LOGGER.info("Closed all prepared statements");
    }
    
    /**
     * Close prepared statements that haven't been used for a while
     * @param maxIdleTimeMs Maximum idle time in milliseconds
     */
    public static void closeIdleStatements(long maxIdleTimeMs) {
        long now = System.currentTimeMillis();
        statements.entrySet().removeIf(entry -> {
            PreparedStatement stmt = entry.getValue();
            return now - stmt.getLastUsed() > maxIdleTimeMs;
        });
    }
}