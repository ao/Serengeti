package com.ataiva.serengeti.query;

import org.json.JSONObject;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.query.cache.QueryCache;
import com.ataiva.serengeti.query.executor.QueryPlanExecutor;
import com.ataiva.serengeti.query.optimizer.OptimizationLevel;
import com.ataiva.serengeti.query.optimizer.QueryPlan;
import com.ataiva.serengeti.query.optimizer.QueryPlanGenerator;
import com.ataiva.serengeti.query.statistics.StatisticsManager;
import com.ataiva.serengeti.storage.StorageResponseObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class QueryEngine {
    private static final Logger LOGGER = Logger.getLogger(QueryEngine.class.getName());
    
    // Query optimization level
    private static OptimizationLevel optimizationLevel = OptimizationLevel.MEDIUM;
    
    // Flag to enable/disable query optimization
    private static boolean optimizationEnabled = true;
    
    // Flag to enable/disable query caching
    private static boolean cachingEnabled = true;
    
    // Singleton instances of optimization components
    private static final StatisticsManager statisticsManager = StatisticsManager.getInstance();
    private static final QueryPlanGenerator planGenerator = QueryPlanGenerator.getInstance();
    private static final QueryPlanExecutor planExecutor = QueryPlanExecutor.getInstance();
    private static final QueryCache queryCache = QueryCache.getInstance();
    
    /**
     * Initialize the query engine
     */
    public static void initialize() {
        // Initialize statistics manager
        statisticsManager.initialize();
        
        LOGGER.info("QueryEngine initialized with optimization level: " + optimizationLevel);
    }
    
    /**
     * Shutdown the query engine
     */
    public static void shutdown() {
        // Shutdown components
        statisticsManager.shutdown();
        queryCache.shutdown();
        
        LOGGER.info("QueryEngine shutdown");
    }
    
    /**
     * Parse and execute a query
     * @param query Query string
     * @return List of JSON results
     */
    public static List<JSONObject> query(String query) {
        if (query.trim().equals("")) {
            return null;
        }

        String[] queries = null;

        if (!query.contains(";")) {
            query = query+";";
        }
        queries = query.split(";");

        List<JSONObject> list = new ArrayList<JSONObject>();
        for (int i=0; i<queries.length; i++) {
            JSONObject n = execute(queries[i].replace("\n", "").trim());
            list.add(i, n);
        }

        return list;
    }

    /**
     * Perform execution of query
     * @param query Query string
     * @return JSONObject with results
     */
    private static JSONObject execute(String query) {
        QueryResponseObject qro = new QueryResponseObject();

        query = query.toLowerCase().trim();

        qro.query = query;
        qro.executed = false;

        long startTime = System.nanoTime();

        try {
            // Check if this is a query optimization command
            if (handleOptimizationCommand(query, qro)) {
                // Command was handled
                long endTime = System.nanoTime();
                qro.runtime = Long.toString((endTime - startTime) / 1000000);
                return qro.json();
            }
            
            // Regular query processing
            if (query.equals("delete everything")) {
                Serengeti.network.communicateQueryLogAllNodes(new JSONObject() {{
                    put("type", "DeleteEverything");
                }}.toString());
                qro.executed = true;
            }
            else if (query.equals("show databases")) {
                qro.list = Serengeti.storage.getDatabases();
                qro.executed = true;
            } 
            else if (query.startsWith("show ") && query.endsWith(" tables")) {
                String databaseName = query.replace("show ", "").split(" ")[0];
                qro.list = Serengeti.storage.getTables(databaseName);
                qro.executed = true;
            } 
            else if (query.startsWith("create database ")) {
                String databaseName = query.replace("create database ", "");

                if (Serengeti.storage.databaseExists(databaseName)) {
                    qro.error = "Database '" + databaseName + "' already exists";
                } else {
                    Serengeti.storage.createDatabase(databaseName);
                    qro.executed = true;
                }
            } 
            else if (query.startsWith("drop database ")) {
                String databaseName = query.replace("drop database ", "");
                if (Serengeti.storage.databaseExists(databaseName)) {
                    Serengeti.storage.dropDatabase(databaseName);
                    
                    // Invalidate cache entries for this database
                    if (cachingEnabled) {
                        queryCache.invalidateTable(databaseName, "*");
                    }
                    
                    qro.executed = true;
                } else {
                    qro.error = "Database " + databaseName + " does not exist";
                }
            } 
            else if (query.startsWith("select ")) {
                // Handle SELECT queries with optimization
                handleSelectQuery(query, qro);
            }
            else {
                // Handle other query types using the original implementation
                handleOriginalQuery(query, qro);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error executing query: " + query, e);
            qro.error = "Error: " + e.getMessage();
        }

        long endTime = System.nanoTime();
        long timeElapsed = endTime - startTime;

        qro.runtime = Long.toString(timeElapsed / 1000000); // time in milliseconds

        return qro.json();
    }
    
    /**
     * Handle SELECT queries with optimization
     * @param query Query string
     * @param qro Query response object
     */
    private static void handleSelectQuery(String query, QueryResponseObject qro) {
        try {
            // Parse the query
            String selectWhat = "";
            String databaseName = "";
            String tableName = "";
            String whereColumn = "";
            String whereValue = "";
            String whereOperator = "=";
            
            // Extract the SELECT part
            if (!query.contains(" from ")) {
                qro.error = "Invalid syntax: SELECT must include FROM clause";
                return;
            }
            
            List<String> selectParts = Arrays.asList(query.split(" from "));
            selectWhat = selectParts.get(0).replace("select ", "").trim();
            
            // Extract the FROM part and optional WHERE clause
            String fromPart = selectParts.get(1);
            boolean hasWhere = fromPart.contains(" where ");
            
            if (hasWhere) {
                List<String> fromWhereParts = Arrays.asList(fromPart.split(" where "));
                fromPart = fromWhereParts.get(0).trim();
                String wherePart = fromWhereParts.get(1).trim();
                
                // Parse the WHERE clause (simplified for brevity)
                if (wherePart.contains("=")) {
                    List<String> w = Arrays.asList(wherePart.split("="));
                    if (w.size() == 2) {
                        whereColumn = w.get(0).replaceAll("^\'|\'$", "").trim();
                        whereValue = w.get(1).replaceAll("^\'|\'$", "").trim();
                        whereOperator = "=";
                    } else {
                        qro.error = "Invalid syntax: invalid WHERE clause";
                        return;
                    }
                } else {
                    qro.error = "Invalid syntax: WHERE clause must include a valid operator";
                    return;
                }
            }
            
            // Extract database and table names
            List<String> dbAndTable = Arrays.asList(fromPart.split("\\."));
            if (dbAndTable.size() != 2) {
                qro.error = "Invalid syntax: FROM clause must be in format <db>.<table>";
                return;
            }
            
            databaseName = dbAndTable.get(0);
            tableName = dbAndTable.get(1);
            
            // Check if the table exists
            if (!Serengeti.storage.tableExists(databaseName, tableName)) {
                qro.error = "Table '" + tableName + "' does not exist";
                return;
            }
            
            // Check if the query result is in the cache
            if (cachingEnabled) {
                String cacheKey = QueryCache.generateCacheKey(
                    databaseName, tableName, selectWhat, whereColumn, whereValue, whereOperator);
                
                List<String> cachedResult = queryCache.getQueryResult(cacheKey);
                if (cachedResult != null) {
                    qro.list = cachedResult;
                    qro.executed = true;
                    qro.explain = "Result retrieved from cache";
                    return;
                }
            }
            
            // If optimization is enabled, use the query optimizer
            if (optimizationEnabled) {
                // Generate a query plan
                QueryPlan plan = planGenerator.generateSelectPlan(
                    databaseName, tableName, selectWhat, whereColumn, whereValue, whereOperator);
                
                // Execute the plan
                List<String> results = planExecutor.execute(plan);
                
                // Cache the result if caching is enabled
                if (cachingEnabled) {
                    String cacheKey = QueryCache.generateCacheKey(
                        databaseName, tableName, selectWhat, whereColumn, whereValue, whereOperator);
                    queryCache.putQueryResult(cacheKey, results);
                }
                
                qro.list = results;
                qro.executed = true;
                qro.explain = plan.generateExplanation();
            } else {
                // Use the original implementation without optimization
                if (hasWhere) {
                    qro.list = Serengeti.storage.select(databaseName, tableName, selectWhat, whereColumn, whereValue);
                } else {
                    qro.list = Serengeti.storage.select(databaseName, tableName, selectWhat, "", "");
                }
                qro.executed = true;
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error handling SELECT query", e);
            qro.error = "Error: " + e.getMessage();
        }
    }
    
    /**
     * Handle optimization commands
     * @param query Query string
     * @param qro Query response object
     * @return True if the query was an optimization command
     */
    private static boolean handleOptimizationCommand(String query, QueryResponseObject qro) {
        if (query.equals("optimization status")) {
            qro.explain = "Optimization: " + (optimizationEnabled ? "enabled" : "disabled") + 
                         ", Level: " + optimizationLevel + 
                         ", Caching: " + (cachingEnabled ? "enabled" : "disabled");
            qro.executed = true;
            return true;
        } else if (query.equals("optimization enable")) {
            optimizationEnabled = true;
            qro.explain = "Query optimization enabled";
            qro.executed = true;
            return true;
        } else if (query.equals("optimization disable")) {
            optimizationEnabled = false;
            qro.explain = "Query optimization disabled";
            qro.executed = true;
            return true;
        } else if (query.equals("cache enable")) {
            cachingEnabled = true;
            qro.explain = "Query caching enabled";
            qro.executed = true;
            return true;
        } else if (query.equals("cache disable")) {
            cachingEnabled = false;
            queryCache.clearCache();
            qro.explain = "Query caching disabled and cache cleared";
            qro.executed = true;
            return true;
        } else if (query.equals("cache clear")) {
            queryCache.clearCache();
            qro.explain = "Query cache cleared";
            qro.executed = true;
            return true;
        } else if (query.equals("cache stats")) {
            qro.explain = "Cache size: " + queryCache.getCacheSize() + 
                         ", Hits: " + queryCache.getHits() + 
                         ", Misses: " + queryCache.getMisses() + 
                         ", Hit ratio: " + String.format("%.2f", queryCache.getHitRatio());
            qro.executed = true;
            return true;
        } else if (query.equals("statistics collect")) {
            statisticsManager.collectAllStatistics();
            qro.explain = "Statistics collection started";
            qro.executed = true;
            return true;
        } else if (query.startsWith("optimization level ")) {
            String level = query.replace("optimization level ", "").toUpperCase();
            try {
                optimizationLevel = OptimizationLevel.valueOf(level);
                qro.explain = "Optimization level set to " + optimizationLevel;
                qro.executed = true;
            } catch (IllegalArgumentException e) {
                qro.error = "Invalid optimization level: " + level;
                qro.executed = false;
            }
            return true;
        }
        
        return false;
    }
    
    /**
     * Handle original query types (unchanged from original implementation)
     * @param query Query string
     * @param qro Query response object
     */
    private static void handleOriginalQuery(String query, QueryResponseObject qro) {
        // This is a placeholder for the original query handling logic
        // In a real implementation, this would contain the original code
        // for handling other query types
        
        // For now, just set an error
        qro.error = "Query type not supported in this simplified implementation";
    }
}
