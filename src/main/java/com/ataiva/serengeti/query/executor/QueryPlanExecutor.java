package com.ataiva.serengeti.query.executor;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.query.optimizer.QueryOperation;
import com.ataiva.serengeti.query.optimizer.QueryOperationType;
import com.ataiva.serengeti.query.optimizer.QueryPlan;
import com.ataiva.serengeti.query.optimizer.QueryPlanType;
import com.ataiva.serengeti.schema.TableStorageObject;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * QueryPlanExecutor executes query plans generated by the query optimizer.
 * It translates the abstract plan into concrete operations against the storage layer.
 */
public class QueryPlanExecutor {
    private static final Logger LOGGER = Logger.getLogger(QueryPlanExecutor.class.getName());
    
    // Singleton instance
    private static QueryPlanExecutor instance;
    
    // Execution metrics for monitoring
    private final Map<String, ExecutionMetrics> executionMetrics;
    
    /**
     * Private constructor for singleton pattern
     */
    private QueryPlanExecutor() {
        this.executionMetrics = new HashMap<>();
    }
    
    /**
     * Get the singleton instance of QueryPlanExecutor
     * @return QueryPlanExecutor instance
     */
    public static synchronized QueryPlanExecutor getInstance() {
        if (instance == null) {
            instance = new QueryPlanExecutor();
        }
        return instance;
    }
    
    /**
     * Execute a query plan and return the results
     * @param plan Query plan to execute
     * @return List of result rows as JSON strings
     */
    public List<String> execute(QueryPlan plan) {
        long startTime = System.nanoTime();
        String planId = generatePlanId(plan);
        
        try {
            LOGGER.info("Executing query plan: " + plan.getPlanType());
            
            // Initialize execution metrics
            ExecutionMetrics metrics = new ExecutionMetrics();
            metrics.planType = plan.getPlanType();
            metrics.startTime = startTime;
            
            // Execute the plan based on its type
            List<String> results;
            switch (plan.getPlanType()) {
                case FULL_TABLE_SCAN:
                    results = executeFullTableScan(plan);
                    break;
                case INDEX_SCAN:
                    results = executeIndexScan(plan);
                    break;
                case RANGE_SCAN:
                    results = executeRangeScan(plan);
                    break;
                case NESTED_LOOP_JOIN:
                    results = executeNestedLoopJoin(plan);
                    break;
                case HASH_JOIN:
                    results = executeHashJoin(plan);
                    break;
                default:
                    LOGGER.warning("Unsupported plan type: " + plan.getPlanType());
                    results = new ArrayList<>();
            }
            
            // Update execution metrics
            long endTime = System.nanoTime();
            metrics.endTime = endTime;
            metrics.executionTimeMs = (endTime - startTime) / 1_000_000;
            metrics.resultRowCount = results.size();
            executionMetrics.put(planId, metrics);
            
            LOGGER.info("Query plan executed in " + metrics.executionTimeMs + 
                       " ms, returned " + results.size() + " rows");
            
            return results;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error executing query plan", e);
            
            // Update execution metrics for failed execution
            long endTime = System.nanoTime();
            ExecutionMetrics metrics = new ExecutionMetrics();
            metrics.planType = plan.getPlanType();
            metrics.startTime = startTime;
            metrics.endTime = endTime;
            metrics.executionTimeMs = (endTime - startTime) / 1_000_000;
            metrics.resultRowCount = 0;
            metrics.error = e.getMessage();
            executionMetrics.put(planId, metrics);
            
            return new ArrayList<>();
        }
    }
    
    /**
     * Execute a full table scan plan
     * @param plan Query plan
     * @return List of result rows
     */
    private List<String> executeFullTableScan(QueryPlan plan) {
        String database = plan.getDatabase();
        String table = plan.getTable();
        String selectColumns = plan.getSelectColumns();
        String whereColumn = plan.getWhereColumn();
        String whereValue = plan.getWhereValue();
        
        // Use the storage layer to perform the scan
        List<String> results = Serengeti.storage.select(database, table, selectColumns, whereColumn, whereValue);
        
        // Apply any additional operations from the plan
        return applyOperations(results, plan.getOperations());
    }
    
    /**
     * Execute an index scan plan
     * @param plan Query plan
     * @return List of result rows
     */
    private List<String> executeIndexScan(QueryPlan plan) {
        String database = plan.getDatabase();
        String table = plan.getTable();
        String whereColumn = plan.getWhereColumn();
        String whereValue = plan.getWhereValue();
        
        // Check if we have an index on the column
        if (!Serengeti.indexManager.hasIndex(database, table, whereColumn)) {
            LOGGER.warning("Index not found for " + database + "." + table + "(" + whereColumn + ")");
            // Fall back to full table scan
            return executeFullTableScan(plan);
        }
        
        // Use the index to find matching rows
        Set<String> rowIds = Serengeti.indexManager.findRows(database, table, whereColumn, whereValue);
        
        if (rowIds == null || rowIds.isEmpty()) {
            return new ArrayList<>();
        }
        
        // Get the actual row data
        List<String> results = new ArrayList<>();
        TableStorageObject tso = new TableStorageObject(database, table);
        
        for (String rowId : rowIds) {
            JSONObject json = tso.getJsonFromRowId(rowId);
            if (json != null) {
                json.put("__uuid", rowId);
                results.add(json.toString());
            }
        }
        
        // Apply any additional operations from the plan
        return applyOperations(results, plan.getOperations());
    }
    
    /**
     * Execute a range scan plan
     * @param plan Query plan
     * @return List of result rows
     */
    private List<String> executeRangeScan(QueryPlan plan) {
        String database = plan.getDatabase();
        String table = plan.getTable();
        String whereColumn = plan.getWhereColumn();
        String whereValue = plan.getWhereValue();
        String whereOperator = plan.getWhereOperator();
        
        // Check if we have an index on the column
        if (!Serengeti.indexManager.hasIndex(database, table, whereColumn)) {
            LOGGER.warning("Index not found for " + database + "." + table + "(" + whereColumn + ")");
            // Fall back to full table scan
            return executeFullTableScan(plan);
        }
        
        // Convert value to appropriate type if it's a number
        Object valueObj = whereValue;
        try {
            if (whereValue.contains(".")) {
                valueObj = Double.parseDouble(whereValue);
            } else {
                valueObj = Integer.parseInt(whereValue);
            }
        } catch (NumberFormatException e) {
            // Keep as string if not a valid number
        }
        
        // Use the index for range query
        Set<String> rowIds;
        if (whereOperator.equals(">") || whereOperator.equals(">=")) {
            rowIds = Serengeti.indexManager.findRowsInRange(database, table, whereColumn, valueObj, null);
        } else if (whereOperator.equals("<") || whereOperator.equals("<=")) {
            rowIds = Serengeti.indexManager.findRowsInRange(database, table, whereColumn, null, valueObj);
        } else {
            // Fall back to equality search for other operators
            rowIds = Serengeti.indexManager.findRows(database, table, whereColumn, whereValue);
        }
        
        if (rowIds == null || rowIds.isEmpty()) {
            return new ArrayList<>();
        }
        
        // Get the actual row data
        List<String> results = new ArrayList<>();
        TableStorageObject tso = new TableStorageObject(database, table);
        
        for (String rowId : rowIds) {
            JSONObject json = tso.getJsonFromRowId(rowId);
            if (json != null) {
                // Apply additional filtering for exact operator semantics
                Object fieldValue = json.opt(whereColumn);
                boolean include = false;
                
                if (fieldValue != null) {
                    if (fieldValue instanceof Number && valueObj instanceof Number) {
                        double fieldDouble = ((Number) fieldValue).doubleValue();
                        double valueDouble = ((Number) valueObj).doubleValue();
                        
                        if (whereOperator.equals(">")) include = fieldDouble > valueDouble;
                        else if (whereOperator.equals(">=")) include = fieldDouble >= valueDouble;
                        else if (whereOperator.equals("<")) include = fieldDouble < valueDouble;
                        else if (whereOperator.equals("<=")) include = fieldDouble <= valueDouble;
                        else include = fieldDouble == valueDouble;
                    } else {
                        // String comparison
                        String fieldStr = fieldValue.toString();
                        String valueStr = valueObj.toString();
                        int comparison = fieldStr.compareTo(valueStr);
                        
                        if (whereOperator.equals(">")) include = comparison > 0;
                        else if (whereOperator.equals(">=")) include = comparison >= 0;
                        else if (whereOperator.equals("<")) include = comparison < 0;
                        else if (whereOperator.equals("<=")) include = comparison <= 0;
                        else include = comparison == 0;
                    }
                    
                    if (include) {
                        json.put("__uuid", rowId);
                        results.add(json.toString());
                    }
                }
            }
        }
        
        // Apply any additional operations from the plan
        return applyOperations(results, plan.getOperations());
    }
    
    /**
     * Execute a nested loop join plan
     * @param plan Query plan
     * @return List of result rows
     */
    private List<String> executeNestedLoopJoin(QueryPlan plan) {
        // Parse the tables from the plan
        String[] tables = plan.getTable().split(",");
        if (tables.length != 2) {
            LOGGER.warning("Nested loop join requires exactly two tables");
            return new ArrayList<>();
        }
        
        String database = plan.getDatabase();
        String leftTable = tables[0];
        String rightTable = tables[1];
        String joinColumn = plan.getJoinColumn();
        
        // Get all rows from both tables
        List<String> leftRows = Serengeti.storage.select(database, leftTable, "*", "", "");
        List<String> rightRows = Serengeti.storage.select(database, rightTable, "*", "", "");
        
        // Perform the join
        List<String> results = new ArrayList<>();
        for (String leftRowStr : leftRows) {
            JSONObject leftRow = new JSONObject(leftRowStr);
            if (!leftRow.has(joinColumn)) {
                continue;
            }
            
            Object leftValue = leftRow.get(joinColumn);
            
            for (String rightRowStr : rightRows) {
                JSONObject rightRow = new JSONObject(rightRowStr);
                if (!rightRow.has(joinColumn)) {
                    continue;
                }
                
                Object rightValue = rightRow.get(joinColumn);
                
                // Check if the join values match
                if ((leftValue == null && rightValue == null) || 
                    (leftValue != null && leftValue.equals(rightValue))) {
                    // Merge the rows
                    JSONObject joinedRow = new JSONObject(leftRow.toString());
                    
                    // Add right row fields with table prefix to avoid conflicts
                    for (String key : rightRow.keySet()) {
                        if (!key.startsWith("__")) { // Skip internal fields
                            joinedRow.put(rightTable + "_" + key, rightRow.get(key));
                        }
                    }
                    
                    results.add(joinedRow.toString());
                }
            }
        }
        
        // Apply any additional operations from the plan
        return applyOperations(results, plan.getOperations());
    }
    
    /**
     * Execute a hash join plan
     * @param plan Query plan
     * @return List of result rows
     */
    private List<String> executeHashJoin(QueryPlan plan) {
        // Parse the tables from the plan
        String[] tables = plan.getTable().split(",");
        if (tables.length != 2) {
            LOGGER.warning("Hash join requires exactly two tables");
            return new ArrayList<>();
        }
        
        String database = plan.getDatabase();
        String leftTable = tables[0];
        String rightTable = tables[1];
        String joinColumn = plan.getJoinColumn();
        
        // Get all rows from both tables
        List<String> leftRows = Serengeti.storage.select(database, leftTable, "*", "", "");
        List<String> rightRows = Serengeti.storage.select(database, rightTable, "*", "", "");
        
        // Determine which table to use for building the hash table (smaller one)
        List<String> buildRows, probeRows;
        String buildTable, probeTable;
        
        if (leftRows.size() <= rightRows.size()) {
            buildRows = leftRows;
            probeRows = rightRows;
            buildTable = leftTable;
            probeTable = rightTable;
        } else {
            buildRows = rightRows;
            probeRows = leftRows;
            buildTable = rightTable;
            probeTable = leftTable;
        }
        
        // Build hash table
        Map<Object, List<JSONObject>> hashTable = new HashMap<>();
        for (String rowStr : buildRows) {
            JSONObject row = new JSONObject(rowStr);
            if (!row.has(joinColumn)) {
                continue;
            }
            
            Object joinValue = row.get(joinColumn);
            if (joinValue != null) {
                hashTable.computeIfAbsent(joinValue, k -> new ArrayList<>()).add(row);
            }
        }
        
        // Probe phase
        List<String> results = new ArrayList<>();
        for (String rowStr : probeRows) {
            JSONObject probeRow = new JSONObject(rowStr);
            if (!probeRow.has(joinColumn)) {
                continue;
            }
            
            Object probeValue = probeRow.get(joinColumn);
            if (probeValue != null && hashTable.containsKey(probeValue)) {
                // Join with all matching rows from the build table
                for (JSONObject buildRow : hashTable.get(probeValue)) {
                    // Merge the rows
                    JSONObject joinedRow = new JSONObject(probeRow.toString());
                    
                    // Add build row fields with table prefix to avoid conflicts
                    for (String key : buildRow.keySet()) {
                        if (!key.startsWith("__")) { // Skip internal fields
                            joinedRow.put(buildTable + "_" + key, buildRow.get(key));
                        }
                    }
                    
                    results.add(joinedRow.toString());
                }
            }
        }
        
        // Apply any additional operations from the plan
        return applyOperations(results, plan.getOperations());
    }
    
    /**
     * Apply additional operations from the plan to the results
     * @param results Initial results
     * @param operations List of operations to apply
     * @return Modified results
     */
    private List<String> applyOperations(List<String> results, List<QueryOperation> operations) {
        List<String> currentResults = results;
        
        for (QueryOperation operation : operations) {
            switch (operation.getOperationType()) {
                case FILTER:
                    currentResults = applyFilter(currentResults, operation);
                    break;
                case PROJECT:
                    currentResults = applyProjection(currentResults, operation);
                    break;
                case SORT:
                    currentResults = applySort(currentResults, operation);
                    break;
                case LIMIT:
                    currentResults = applyLimit(currentResults, operation);
                    break;
                case DISTINCT:
                    currentResults = applyDistinct(currentResults);
                    break;
                default:
                    // Skip operations that are handled elsewhere or not supported
                    break;
            }
        }
        
        return currentResults;
    }
    
    /**
     * Apply a filter operation to the results
     * @param results Results to filter
     * @param operation Filter operation
     * @return Filtered results
     */
    private List<String> applyFilter(List<String> results, QueryOperation operation) {
        String filterColumn = operation.getFilterColumn();
        String filterValue = operation.getFilterValue();
        String filterOperator = operation.getFilterOperator();
        
        List<String> filteredResults = new ArrayList<>();
        
        for (String rowStr : results) {
            JSONObject row = new JSONObject(rowStr);
            if (!row.has(filterColumn)) {
                continue;
            }
            
            Object fieldValue = row.get(filterColumn);
            Object queryValue = filterValue;
            
            // Try to convert to numbers if possible
            try {
                if (filterValue.contains(".")) {
                    queryValue = Double.parseDouble(filterValue);
                } else {
                    queryValue = Integer.parseInt(filterValue);
                }
                
                if (fieldValue instanceof String) {
                    try {
                        if (fieldValue.toString().contains(".")) {
                            fieldValue = Double.parseDouble(fieldValue.toString());
                        } else {
                            fieldValue = Integer.parseInt(fieldValue.toString());
                        }
                    } catch (NumberFormatException e) {
                        // Keep as string if not a valid number
                    }
                }
            } catch (NumberFormatException e) {
                // Keep as string if not a valid number
            }
            
            boolean include = false;
            
            if (fieldValue instanceof Number && queryValue instanceof Number) {
                double fieldDouble = ((Number) fieldValue).doubleValue();
                double valueDouble = ((Number) queryValue).doubleValue();
                
                if (filterOperator.equals(">")) include = fieldDouble > valueDouble;
                else if (filterOperator.equals(">=")) include = fieldDouble >= valueDouble;
                else if (filterOperator.equals("<")) include = fieldDouble < valueDouble;
                else if (filterOperator.equals("<=")) include = fieldDouble <= valueDouble;
                else include = fieldDouble == valueDouble;
            } else {
                // String comparison
                String fieldStr = fieldValue.toString();
                String valueStr = queryValue.toString();
                int comparison = fieldStr.compareTo(valueStr);
                
                if (filterOperator.equals(">")) include = comparison > 0;
                else if (filterOperator.equals(">=")) include = comparison >= 0;
                else if (filterOperator.equals("<")) include = comparison < 0;
                else if (filterOperator.equals("<=")) include = comparison <= 0;
                else include = comparison == 0;
            }
            
            if (include) {
                filteredResults.add(rowStr);
            }
        }
        
        return filteredResults;
    }
    
    /**
     * Apply a projection operation to the results
     * @param results Results to project
     * @param operation Projection operation
     * @return Projected results
     */
    private List<String> applyProjection(List<String> results, QueryOperation operation) {
        String[] projectColumns = operation.getProjectColumns();
        
        List<String> projectedResults = new ArrayList<>();
        
        for (String rowStr : results) {
            JSONObject row = new JSONObject(rowStr);
            JSONObject projectedRow = new JSONObject();
            
            // Copy only the specified columns
            for (String column : projectColumns) {
                column = column.trim();
                if (row.has(column)) {
                    projectedRow.put(column, row.get(column));
                }
            }
            
            // Copy internal fields
            for (String key : row.keySet()) {
                if (key.startsWith("__")) {
                    projectedRow.put(key, row.get(key));
                }
            }
            
            projectedResults.add(projectedRow.toString());
        }
        
        return projectedResults;
    }
    
    /**
     * Apply a sort operation to the results
     * @param results Results to sort
     * @param operation Sort operation
     * @return Sorted results
     */
    private List<String> applySort(List<String> results, QueryOperation operation) {
        String sortColumn = operation.getFilterColumn();
        boolean ascending = true;
        
        // Check if the filter operator specifies the sort direction
        if (operation.getFilterOperator() != null) {
            ascending = !operation.getFilterOperator().equalsIgnoreCase("DESC");
        }
        
        // Create a final copy of the ascending variable for use in the lambda
        final boolean isAscending = ascending;
        
        LOGGER.info("Sorting results by " + sortColumn + " " + (isAscending ? "ASC" : "DESC"));
        
        // Convert results to JSONObjects for sorting
        List<JSONObject> jsonResults = results.stream()
            .map(JSONObject::new)
            .collect(Collectors.toList());
        
        // Sort the results
        jsonResults.sort((a, b) -> {
            Object valueA = a.opt(sortColumn);
            Object valueB = b.opt(sortColumn);
            
            // Handle null values
            if (valueA == null && valueB == null) return 0;
            if (valueA == null) return isAscending ? -1 : 1;
            if (valueB == null) return isAscending ? 1 : -1;
            
            // Compare based on type
            if (valueA instanceof Number && valueB instanceof Number) {
                double numA = ((Number) valueA).doubleValue();
                double numB = ((Number) valueB).doubleValue();
                return isAscending ? Double.compare(numA, numB) : Double.compare(numB, numA);
            } else {
                String strA = valueA.toString();
                String strB = valueB.toString();
                return isAscending ? strA.compareTo(strB) : strB.compareTo(strA);
            }
        });
        
        // Convert back to strings
        return jsonResults.stream()
            .map(JSONObject::toString)
            .collect(Collectors.toList());
    }
    
    /**
     * Apply a limit operation to the results
     * @param results Results to limit
     * @param operation Limit operation
     * @return Limited results
     */
    private List<String> applyLimit(List<String> results, QueryOperation operation) {
        // Get the limit value from the filter value
        String limitStr = operation.getFilterValue();
        int limit;
        
        try {
            limit = Integer.parseInt(limitStr);
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid limit value: " + limitStr);
            return results;
        }
        
        // Check if there's an offset
        int offset = 0;
        String offsetStr = operation.getFilterColumn();
        if (offsetStr != null && !offsetStr.isEmpty()) {
            try {
                offset = Integer.parseInt(offsetStr);
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid offset value: " + offsetStr);
                // Continue with offset = 0
            }
        }
        
        LOGGER.info("Applying limit " + limit + " with offset " + offset);
        
        // Apply offset and limit
        int fromIndex = Math.min(offset, results.size());
        int toIndex = Math.min(offset + limit, results.size());
        
        return results.subList(fromIndex, toIndex);
    }
    
    /**
     * Apply a distinct operation to the results
     * @param results Results to make distinct
     * @return Distinct results
     */
    private List<String> applyDistinct(List<String> results) {
        Set<String> distinctSet = new HashSet<>(results);
        return new ArrayList<>(distinctSet);
    }
    
    /**
     * Generate a unique ID for a query plan
     * @param plan Query plan
     * @return Plan ID
     */
    private String generatePlanId(QueryPlan plan) {
        return plan.getPlanType() + "-" + plan.getDatabase() + "-" + plan.getTable() + "-" + System.nanoTime();
    }
    
    /**
     * Get execution metrics for a specific plan ID
     * @param planId Plan ID
     * @return Execution metrics or null if not found
     */
    public ExecutionMetrics getExecutionMetrics(String planId) {
        return executionMetrics.get(planId);
    }
    
    /**
     * Get all execution metrics
     * @return Map of plan IDs to execution metrics
     */
    public Map<String, ExecutionMetrics> getAllExecutionMetrics() {
        return new HashMap<>(executionMetrics);
    }
    
    /**
     * Clear all execution metrics
     */
    public void clearExecutionMetrics() {
        executionMetrics.clear();
    }
    
    /**
     * Inner class to store execution metrics for a query plan
     */
    public static class ExecutionMetrics {
        public QueryPlanType planType;
        public long startTime;
        public long endTime;
        public long executionTimeMs;
        public long resultRowCount;
        public String error;
        
        @Override
        public String toString() {
            return "ExecutionMetrics{" +
                    "planType=" + planType +
                    ", executionTimeMs=" + executionTimeMs +
                    ", resultRowCount=" + resultRowCount +
                    ", error='" + error + '\'' +
                    '}';
        }
    }
}