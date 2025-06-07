package com.ataiva.serengeti.query.optimizer;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.performance.PerformanceProfiler;
import com.ataiva.serengeti.query.memory.QueryMemoryManager;
import com.ataiva.serengeti.query.statistics.ColumnStatistics;
import com.ataiva.serengeti.query.statistics.StatisticsManager;
import com.ataiva.serengeti.query.statistics.TableStatistics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * EnhancedQueryPlanGenerator extends the basic query plan generator with advanced
 * optimization techniques including join order optimization, index utilization,
 * and memory management.
 */
public class EnhancedQueryPlanGenerator {
    private static final Logger LOGGER = Logger.getLogger(EnhancedQueryPlanGenerator.class.getName());
    
    // Singleton instance
    private static EnhancedQueryPlanGenerator instance;
    
    // Reference to the statistics manager
    private final StatisticsManager statisticsManager;
    
    // Reference to the performance profiler
    private final PerformanceProfiler profiler;
    
    // Reference to the query memory manager
    private final QueryMemoryManager memoryManager;
    
    // Specialized optimizers
    private final JoinOrderOptimizer joinOrderOptimizer;
    private final IndexUtilizationOptimizer indexUtilizationOptimizer;
    
    // Current optimization level
    private OptimizationLevel optimizationLevel;
    
    /**
     * Private constructor for singleton pattern
     */
    private EnhancedQueryPlanGenerator() {
        this.statisticsManager = StatisticsManager.getInstance();
        this.profiler = PerformanceProfiler.getInstance();
        this.memoryManager = QueryMemoryManager.getInstance();
        this.joinOrderOptimizer = new JoinOrderOptimizer();
        this.indexUtilizationOptimizer = new IndexUtilizationOptimizer();
        this.optimizationLevel = OptimizationLevel.MEDIUM;
    }
    
    /**
     * Get the singleton instance of EnhancedQueryPlanGenerator
     * @return EnhancedQueryPlanGenerator instance
     */
    public static synchronized EnhancedQueryPlanGenerator getInstance() {
        if (instance == null) {
            instance = new EnhancedQueryPlanGenerator();
        }
        return instance;
    }
    
    /**
     * Set the optimization level
     * @param level Optimization level
     */
    public void setOptimizationLevel(OptimizationLevel level) {
        this.optimizationLevel = level;
        LOGGER.info("Optimization level set to " + level);
    }
    
    /**
     * Get the current optimization level
     * @return Current optimization level
     */
    public OptimizationLevel getOptimizationLevel() {
        return optimizationLevel;
    }
    
    /**
     * Generate an optimized query plan for a SELECT query
     * 
     * @param database Database name
     * @param table Table name
     * @param selectColumns Columns to select
     * @param whereColumn Column in WHERE clause (or empty if none)
     * @param whereValue Value in WHERE clause (or empty if none)
     * @param whereOperator Operator in WHERE clause (=, >, <, etc.)
     * @return The optimal QueryPlan
     */
    public QueryPlan generateSelectPlan(String database, String table, 
                                       String selectColumns, String whereColumn, 
                                       String whereValue, String whereOperator) {
        String queryId = UUID.randomUUID().toString();
        String timerId = profiler.startTimer("query", "optimize", queryId);
        
        try {
            List<QueryPlan> candidatePlans = new ArrayList<>();
            
            // Get table statistics
            TableStatistics tableStats = statisticsManager.getTableStatistics(database, table);
            if (tableStats == null) {
                // No statistics available, use a default plan
                return createDefaultSelectPlan(database, table, selectColumns, whereColumn, whereValue, whereOperator);
            }
            
            // Create a full table scan plan as the baseline
            QueryPlan fullScanPlan = createFullTableScanPlan(database, table, selectColumns, whereColumn, whereValue, whereOperator);
            candidatePlans.add(fullScanPlan);
            
            // If there's a WHERE clause, consider index-based plans
            if (!whereColumn.isEmpty() && !whereValue.isEmpty()) {
                // Check if we should use an index based on our enhanced optimizer
                boolean shouldUseIndex = indexUtilizationOptimizer.shouldUseIndex(
                    database, table, whereColumn, whereOperator, whereValue);
                
                if (shouldUseIndex) {
                    // Create an index scan plan
                    QueryPlan indexPlan = createIndexScanPlan(database, table, selectColumns, whereColumn, whereValue, whereOperator);
                    candidatePlans.add(indexPlan);
                }
                
                // For range queries, consider using an index even if it's not exact
                if (whereOperator.equals(">") || whereOperator.equals("<") || 
                    whereOperator.equals(">=") || whereOperator.equals("<=")) {
                    // Check if we have column statistics
                    ColumnStatistics colStats = statisticsManager.getColumnStatistics(database, table, whereColumn);
                    
                    if (colStats != null) {
                        // Create a range scan plan
                        QueryPlan rangePlan = createRangeScanPlan(database, table, selectColumns, whereColumn, whereValue, whereOperator, colStats);
                        candidatePlans.add(rangePlan);
                    }
                }
            }
            
            // Find the plan with the lowest cost
            QueryPlan bestPlan = findLowestCostPlan(candidatePlans);
            
            // Apply memory optimization if needed
            if (optimizationLevel.ordinal() >= OptimizationLevel.HIGH.ordinal()) {
                bestPlan = memoryManager.optimizeMemoryUsage(bestPlan);
            }
            
            // Generate a detailed explanation
            bestPlan.setExplanation(generatePlanExplanation(bestPlan));
            
            // Log the chosen plan
            LOGGER.info("Selected query plan: " + bestPlan.getPlanType() + 
                       " with estimated cost: " + bestPlan.getEstimatedCost());
            
            return bestPlan;
        } finally {
            profiler.stopTimer(timerId, "query.optimization.time");
        }
    }
    
    /**
     * Generate an optimized query plan for a JOIN query
     * 
     * @param database Database name
     * @param tables List of tables to join
     * @param joinColumns Map of join columns (table name -> join column)
     * @param selectColumns Columns to select
     * @param whereColumns Map of where conditions (table name -> column name)
     * @param whereValues Map of where values (table name -> value)
     * @return The optimal QueryPlan
     */
    public QueryPlan generateJoinPlan(String database, List<String> tables,
                                     Map<String, String> joinColumns,
                                     String selectColumns,
                                     Map<String, String> whereColumns,
                                     Map<String, String> whereValues) {
        String queryId = UUID.randomUUID().toString();
        String timerId = profiler.startTimer("query", "optimize", queryId);
        
        try {
            List<QueryPlan> candidatePlans = new ArrayList<>();
            
            // Optimize join order if we have more than one table
            if (tables.size() > 1 && optimizationLevel.ordinal() >= OptimizationLevel.MEDIUM.ordinal()) {
                List<String> optimizedOrder = joinOrderOptimizer.optimizeJoinOrder(
                    database, tables, joinColumns, whereColumns, whereValues);
                
                // Create a plan with the optimized join order
                QueryPlan optimizedJoinPlan = createOptimizedJoinPlan(
                    database, optimizedOrder, joinColumns, selectColumns, whereColumns, whereValues);
                candidatePlans.add(optimizedJoinPlan);
            }
            
            // Create a nested loop join plan as baseline
            QueryPlan nestedLoopPlan = createNestedLoopJoinPlan(
                database, tables, joinColumns, selectColumns, whereColumns, whereValues);
            candidatePlans.add(nestedLoopPlan);
            
            // Create a hash join plan
            QueryPlan hashJoinPlan = createHashJoinPlan(
                database, tables, joinColumns, selectColumns, whereColumns, whereValues);
            candidatePlans.add(hashJoinPlan);
            
            // Find the plan with the lowest cost
            QueryPlan bestPlan = findLowestCostPlan(candidatePlans);
            
            // Apply memory optimization if needed
            if (optimizationLevel.ordinal() >= OptimizationLevel.HIGH.ordinal()) {
                bestPlan = memoryManager.optimizeMemoryUsage(bestPlan);
            }
            
            // Generate a detailed explanation
            bestPlan.setExplanation(generatePlanExplanation(bestPlan));
            
            // Log the chosen plan
            LOGGER.info("Selected join plan: " + bestPlan.getPlanType() + 
                       " with estimated cost: " + bestPlan.getEstimatedCost());
            
            return bestPlan;
        } finally {
            profiler.stopTimer(timerId, "query.optimization.time");
        }
    }
    
    /**
     * Create a default query plan when no statistics are available
     */
    private QueryPlan createDefaultSelectPlan(String database, String table, 
                                             String selectColumns, String whereColumn, 
                                             String whereValue, String whereOperator) {
        // Default to a full table scan
        QueryPlan plan = new QueryPlan();
        plan.setPlanType(QueryPlanType.FULL_TABLE_SCAN);
        plan.setDatabase(database);
        plan.setTable(table);
        plan.setSelectColumns(selectColumns);
        plan.setWhereColumn(whereColumn);
        plan.setWhereValue(whereValue);
        plan.setWhereOperator(whereOperator);
        plan.setEstimatedCost(1000); // Default high cost
        plan.setEstimatedRows(1000); // Default row estimate
        
        // Add a scan operation
        QueryOperation scanOp = new QueryOperation(QueryOperationType.TABLE_SCAN);
        scanOp.setTargetTable(table);
        plan.addOperation(scanOp);
        
        // Add a filter operation if there's a WHERE clause
        if (!whereColumn.isEmpty() && !whereValue.isEmpty()) {
            QueryOperation filterOp = new QueryOperation(QueryOperationType.FILTER);
            filterOp.setFilterColumn(whereColumn);
            filterOp.setFilterValue(whereValue);
            filterOp.setFilterOperator(whereOperator);
            plan.addOperation(filterOp);
        }
        
        // Add a projection operation if not selecting all columns
        if (!selectColumns.equals("*")) {
            QueryOperation projectOp = new QueryOperation(QueryOperationType.PROJECT);
            projectOp.setProjectColumns(selectColumns.split(","));
            plan.addOperation(projectOp);
        }
        
        return plan;
    }
    
    /**
     * Create a full table scan plan
     */
    private QueryPlan createFullTableScanPlan(String database, String table, 
                                             String selectColumns, String whereColumn, 
                                             String whereValue, String whereOperator) {
        QueryPlan plan = new QueryPlan();
        plan.setPlanType(QueryPlanType.FULL_TABLE_SCAN);
        plan.setDatabase(database);
        plan.setTable(table);
        plan.setSelectColumns(selectColumns);
        plan.setWhereColumn(whereColumn);
        plan.setWhereValue(whereValue);
        plan.setWhereOperator(whereOperator);
        
        // Get table statistics for cost estimation
        TableStatistics tableStats = statisticsManager.getTableStatistics(database, table);
        long rowCount = (tableStats != null) ? tableStats.getRowCount() : 1000;
        
        // Estimate selectivity if there's a WHERE clause
        double selectivity = 1.0;
        if (!whereColumn.isEmpty() && !whereValue.isEmpty()) {
            ColumnStatistics colStats = statisticsManager.getColumnStatistics(database, table, whereColumn);
            if (colStats != null) {
                if (whereOperator.equals("=")) {
                    selectivity = colStats.calculateEqualitySelectivity();
                } else if (whereOperator.equals(">") || whereOperator.equals(">=")) {
                    try {
                        selectivity = colStats.calculateRangeSelectivity(Double.parseDouble(whereValue), null);
                    } catch (NumberFormatException e) {
                        selectivity = 0.5; // Default for non-numeric values
                    }
                } else if (whereOperator.equals("<") || whereOperator.equals("<=")) {
                    try {
                        selectivity = colStats.calculateRangeSelectivity(null, Double.parseDouble(whereValue));
                    } catch (NumberFormatException e) {
                        selectivity = 0.5; // Default for non-numeric values
                    }
                }
            } else {
                // Default selectivity if no column statistics
                selectivity = 0.1;
            }
        }
        
        // Estimate rows and cost
        long estimatedRows = Math.round(rowCount * selectivity);
        double estimatedCost = rowCount; // Cost proportional to number of rows scanned
        
        plan.setEstimatedRows(estimatedRows);
        plan.setEstimatedCost(estimatedCost);
        
        // Add a scan operation
        QueryOperation scanOp = new QueryOperation(QueryOperationType.TABLE_SCAN);
        scanOp.setTargetTable(table);
        plan.addOperation(scanOp);
        
        // Add a filter operation if there's a WHERE clause
        if (!whereColumn.isEmpty() && !whereValue.isEmpty()) {
            QueryOperation filterOp = new QueryOperation(QueryOperationType.FILTER);
            filterOp.setFilterColumn(whereColumn);
            filterOp.setFilterValue(whereValue);
            filterOp.setFilterOperator(whereOperator);
            plan.addOperation(filterOp);
        }
        
        // Add a projection operation if not selecting all columns
        if (!selectColumns.equals("*")) {
            QueryOperation projectOp = new QueryOperation(QueryOperationType.PROJECT);
            projectOp.setProjectColumns(selectColumns.split(","));
            plan.addOperation(projectOp);
        }
        
        return plan;
    }
    
    /**
     * Create an index scan plan
     */
    private QueryPlan createIndexScanPlan(String database, String table, 
                                         String selectColumns, String whereColumn, 
                                         String whereValue, String whereOperator) {
        QueryPlan plan = new QueryPlan();
        plan.setPlanType(QueryPlanType.INDEX_SCAN);
        plan.setDatabase(database);
        plan.setTable(table);
        plan.setSelectColumns(selectColumns);
        plan.setWhereColumn(whereColumn);
        plan.setWhereValue(whereValue);
        plan.setWhereOperator(whereOperator);
        
        // Get table statistics for cost estimation
        TableStatistics tableStats = statisticsManager.getTableStatistics(database, table);
        long rowCount = (tableStats != null) ? tableStats.getRowCount() : 1000;
        
        // Estimate selectivity
        double selectivity = 0.01; // Default for index scan
        ColumnStatistics colStats = statisticsManager.getColumnStatistics(database, table, whereColumn);
        if (colStats != null) {
            if (whereOperator.equals("=")) {
                selectivity = colStats.calculateEqualitySelectivity();
            } else if (whereOperator.equals(">") || whereOperator.equals(">=")) {
                try {
                    selectivity = colStats.calculateRangeSelectivity(Double.parseDouble(whereValue), null);
                } catch (NumberFormatException e) {
                    selectivity = 0.5; // Default for non-numeric values
                }
            } else if (whereOperator.equals("<") || whereOperator.equals("<=")) {
                try {
                    selectivity = colStats.calculateRangeSelectivity(null, Double.parseDouble(whereValue));
                } catch (NumberFormatException e) {
                    selectivity = 0.5; // Default for non-numeric values
                }
            }
        }
        
        // Estimate rows and cost
        long estimatedRows = Math.round(rowCount * selectivity);
        
        // Index scan cost model: log(n) for finding the first entry + number of matching rows
        double estimatedCost = Math.log(rowCount) / Math.log(2) + estimatedRows;
        
        plan.setEstimatedRows(estimatedRows);
        plan.setEstimatedCost(estimatedCost);
        
        // Add an index scan operation
        QueryOperation indexOp = new QueryOperation(QueryOperationType.INDEX_SCAN);
        indexOp.setTargetTable(table);
        indexOp.setIndexColumn(whereColumn);
        indexOp.setFilterValue(whereValue);
        indexOp.setFilterOperator(whereOperator);
        plan.addOperation(indexOp);
        
        // Add a projection operation if not selecting all columns
        if (!selectColumns.equals("*")) {
            QueryOperation projectOp = new QueryOperation(QueryOperationType.PROJECT);
            projectOp.setProjectColumns(selectColumns.split(","));
            plan.addOperation(projectOp);
        }
        
        return plan;
    }
    
    /**
     * Create a range scan plan
     */
    private QueryPlan createRangeScanPlan(String database, String table, 
                                         String selectColumns, String whereColumn, 
                                         String whereValue, String whereOperator,
                                         ColumnStatistics colStats) {
        QueryPlan plan = new QueryPlan();
        plan.setPlanType(QueryPlanType.RANGE_SCAN);
        plan.setDatabase(database);
        plan.setTable(table);
        plan.setSelectColumns(selectColumns);
        plan.setWhereColumn(whereColumn);
        plan.setWhereValue(whereValue);
        plan.setWhereOperator(whereOperator);
        
        // Get table statistics for cost estimation
        TableStatistics tableStats = statisticsManager.getTableStatistics(database, table);
        long rowCount = (tableStats != null) ? tableStats.getRowCount() : 1000;
        
        // Estimate selectivity for range query
        double selectivity;
        if (whereOperator.equals(">") || whereOperator.equals(">=")) {
            try {
                selectivity = colStats.calculateRangeSelectivity(Double.parseDouble(whereValue), null);
            } catch (NumberFormatException e) {
                // Default if value is not numeric
                selectivity = 0.5;
            }
        } else if (whereOperator.equals("<") || whereOperator.equals("<=")) {
            try {
                selectivity = colStats.calculateRangeSelectivity(null, Double.parseDouble(whereValue));
            } catch (NumberFormatException e) {
                // Default if value is not numeric
                selectivity = 0.5;
            }
        } else {
            selectivity = 0.1; // Default
        }
        
        // Estimate rows and cost
        long estimatedRows = Math.round(rowCount * selectivity);
        
        // Range scan cost model: log(n) for finding the range + number of matching rows
        double estimatedCost = Math.log(rowCount) / Math.log(2) + estimatedRows;
        
        plan.setEstimatedRows(estimatedRows);
        plan.setEstimatedCost(estimatedCost);
        
        // Add a range scan operation
        QueryOperation rangeOp = new QueryOperation(QueryOperationType.RANGE_SCAN);
        rangeOp.setTargetTable(table);
        rangeOp.setIndexColumn(whereColumn);
        rangeOp.setFilterValue(whereValue);
        rangeOp.setFilterOperator(whereOperator);
        plan.addOperation(rangeOp);
        
        // Add a projection operation if not selecting all columns
        if (!selectColumns.equals("*")) {
            QueryOperation projectOp = new QueryOperation(QueryOperationType.PROJECT);
            projectOp.setProjectColumns(selectColumns.split(","));
            plan.addOperation(projectOp);
        }
        
        return plan;
    }
    
    /**
     * Create a nested loop join plan
     */
    private QueryPlan createNestedLoopJoinPlan(String database, List<String> tables,
                                              Map<String, String> joinColumns,
                                              String selectColumns,
                                              Map<String, String> whereColumns,
                                              Map<String, String> whereValues) {
        QueryPlan plan = new QueryPlan();
        plan.setPlanType(QueryPlanType.NESTED_LOOP_JOIN);
        plan.setDatabase(database);
        plan.setTable(String.join(",", tables));
        plan.setSelectColumns(selectColumns);
        
        // Get table statistics for cost estimation
        Map<String, TableStatistics> tableStatsMap = new HashMap<>();
        Map<String, Long> tableCardinalities = new HashMap<>();
        
        for (String table : tables) {
            TableStatistics stats = statisticsManager.getTableStatistics(database, table);
            tableStatsMap.put(table, stats);
            
            long cardinality = (stats != null) ? stats.getRowCount() : 1000;
            
            // Adjust cardinality based on where conditions
            String whereColumn = whereColumns.getOrDefault(table, "");
            String whereValue = whereValues.getOrDefault(table, "");
            
            if (!whereColumn.isEmpty() && !whereValue.isEmpty()) {
                ColumnStatistics colStats = statisticsManager.getColumnStatistics(database, table, whereColumn);
                if (colStats != null) {
                    // Assume equality predicate for simplicity
                    double selectivity = colStats.calculateEqualitySelectivity();
                    cardinality = Math.round(cardinality * selectivity);
                } else {
                    // Default selectivity if no column statistics
                    cardinality = Math.round(cardinality * 0.1);
                }
            }
            
            tableCardinalities.put(table, cardinality);
        }
        
        // Calculate join cost and result size
        double totalCost = 0;
        long resultSize = 1;
        
        for (int i = 0; i < tables.size(); i++) {
            String table = tables.get(i);
            long cardinality = tableCardinalities.getOrDefault(table, 1000L);
            
            if (i == 0) {
                // First table is scanned completely
                totalCost += cardinality;
                resultSize = cardinality;
            } else {
                // For each subsequent table, we do a nested loop join
                totalCost += resultSize * cardinality;
                
                // Calculate join selectivity
                String joinColumn = joinColumns.getOrDefault(table, "");
                if (!joinColumn.isEmpty()) {
                    double joinSelectivity = 0.1; // Default
                    
                    // Try to get better selectivity estimate from statistics
                    ColumnStatistics colStats = statisticsManager.getColumnStatistics(database, table, joinColumn);
                    if (colStats != null) {
                        long distinctValues = Math.max(1, colStats.getDistinctValues());
                        joinSelectivity = 1.0 / distinctValues;
                    }
                    
                    resultSize = Math.round(resultSize * cardinality * joinSelectivity);
                } else {
                    // Cartesian product
                    resultSize *= cardinality;
                }
            }
        }
        
        plan.setEstimatedRows(resultSize);
        plan.setEstimatedCost(totalCost);
        
        // Add operations
        for (int i = 0; i < tables.size(); i++) {
            String table = tables.get(i);
            
            if (i == 0) {
                // First table scan
                QueryOperation scanOp = new QueryOperation(QueryOperationType.TABLE_SCAN);
                scanOp.setTargetTable(table);
                plan.addOperation(scanOp);
                
                // Add filter if there's a WHERE clause for this table
                String whereColumn = whereColumns.getOrDefault(table, "");
                String whereValue = whereValues.getOrDefault(table, "");
                
                if (!whereColumn.isEmpty() && !whereValue.isEmpty()) {
                    QueryOperation filterOp = new QueryOperation(QueryOperationType.FILTER);
                    filterOp.setFilterColumn(whereColumn);
                    filterOp.setFilterValue(whereValue);
                    filterOp.setFilterOperator("="); // Assume equality for simplicity
                    plan.addOperation(filterOp);
                }
            } else {
                // Join with subsequent tables
                QueryOperation joinOp = new QueryOperation(QueryOperationType.NESTED_LOOP_JOIN);
                joinOp.setTargetTable(table);
                joinOp.setJoinColumn(joinColumns.getOrDefault(table, ""));
                plan.addOperation(joinOp);
                
                // Add filter if there's a WHERE clause for this table
                String whereColumn = whereColumns.getOrDefault(table, "");
                String whereValue = whereValues.getOrDefault(table, "");
                
                if (!whereColumn.isEmpty() && !whereValue.isEmpty()) {
                    QueryOperation filterOp = new QueryOperation(QueryOperationType.FILTER);
                    filterOp.setFilterColumn(whereColumn);
                    filterOp.setFilterValue(whereValue);
                    filterOp.setFilterOperator("="); // Assume equality for simplicity
                    plan.addOperation(filterOp);
                }
            }
        }
        
        // Add a projection operation if not selecting all columns
        if (!selectColumns.equals("*")) {
            QueryOperation projectOp = new QueryOperation(QueryOperationType.PROJECT);
            projectOp.setProjectColumns(selectColumns.split(","));
            plan.addOperation(projectOp);
        }
        
        return plan;
    }
    
    /**
     * Find the query plan with the lowest estimated cost
     *
     * @param candidatePlans List of candidate query plans
     * @return The plan with the lowest cost
     */
    private QueryPlan findLowestCostPlan(List<QueryPlan> candidatePlans) {
        if (candidatePlans.isEmpty()) {
            throw new IllegalArgumentException("No candidate plans provided");
        }
        
        QueryPlan lowestCostPlan = candidatePlans.get(0);
        
        for (QueryPlan plan : candidatePlans) {
            if (plan.getEstimatedCost() < lowestCostPlan.getEstimatedCost()) {
                lowestCostPlan = plan;
            }
        }
        
        return lowestCostPlan;
    }
    
    /**
     * Generate a detailed explanation of the query plan
     *
     * @param plan The query plan to explain
     * @return A detailed explanation string
     */
    private String generatePlanExplanation(QueryPlan plan) {
        StringBuilder explanation = new StringBuilder();
        
        explanation.append("Query Plan: ").append(plan.getPlanType()).append("\n");
        explanation.append("Estimated Cost: ").append(plan.getEstimatedCost()).append("\n");
        explanation.append("Estimated Rows: ").append(plan.getEstimatedRows()).append("\n");
        explanation.append("Operations:\n");
        
        List<QueryOperation> operations = plan.getOperations();
        for (int i = 0; i < operations.size(); i++) {
            QueryOperation op = operations.get(i);
            explanation.append("  ").append(i + 1).append(". ").append(op.getType());
            
            switch (op.getType()) {
                case TABLE_SCAN:
                    explanation.append(" on table ").append(op.getTargetTable());
                    break;
                case INDEX_SCAN:
                    explanation.append(" on table ").append(op.getTargetTable())
                              .append(" using index on ").append(op.getIndexColumn())
                              .append(" with condition ").append(op.getFilterOperator())
                              .append(" ").append(op.getFilterValue());
                    break;
                case RANGE_SCAN:
                    explanation.append(" on table ").append(op.getTargetTable())
                              .append(" using index on ").append(op.getIndexColumn())
                              .append(" with range condition ").append(op.getFilterOperator())
                              .append(" ").append(op.getFilterValue());
                    break;
                case FILTER:
                    explanation.append(" on column ").append(op.getFilterColumn())
                              .append(" with condition ").append(op.getFilterOperator())
                              .append(" ").append(op.getFilterValue());
                    break;
                case PROJECT:
                    explanation.append(" columns: ").append(String.join(", ", op.getProjectColumns()));
                    break;
                case NESTED_LOOP_JOIN:
                    explanation.append(" with table ").append(op.getTargetTable())
                              .append(" on column ").append(op.getJoinColumn());
                    break;
                case BUILD_HASH_TABLE:
                    explanation.append(" on join column ").append(op.getJoinColumn());
                    break;
                case PROBE_HASH_TABLE:
                    explanation.append(" using join column ").append(op.getJoinColumn());
                    break;
                default:
                    // No additional info for other operation types
                    break;
            }
            
            explanation.append("\n");
        }
        
        // Add memory usage information if available
        if (plan.getEstimatedMemoryUsage() > 0) {
            explanation.append("Estimated Memory Usage: ")
                      .append(formatMemorySize(plan.getEstimatedMemoryUsage()))
                      .append("\n");
        }
        
        return explanation.toString();
    }
    
    /**
     * Format memory size in bytes to a human-readable string
     *
     * @param bytes Memory size in bytes
     * @return Formatted string (e.g., "1.5 MB")
     */
    private String formatMemorySize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    /**
     * Optimization levels for query planning
     */
    public enum OptimizationLevel {
        LOW,     // Basic optimizations only
        MEDIUM,  // Standard optimizations including join order and index selection
        HIGH,    // Advanced optimizations including memory management
        EXTREME  // All optimizations, may take longer to generate plans
    }
}
    
    /**
     * Create a hash join plan
     */
    private QueryPlan createHashJoinPlan(String database, List<String> tables,
                                        Map<String, String> joinColumns,
                                        String selectColumns,
                                        Map<String, String> whereColumns,
                                        Map<String, String> whereValues) {
        QueryPlan plan = new QueryPlan();
        plan.setPlanType(QueryPlanType.HASH_JOIN);
        plan.setDatabase(database);
        plan.setTable(String.join(",", tables));
        plan.setSelectColumns(selectColumns);
        
        // Get table statistics for cost estimation
        Map<String, TableStatistics> tableStatsMap = new HashMap<>();
        Map<String, Long> tableCardinalities = new HashMap<>();
        
        for (String table : tables) {
            TableStatistics stats = statisticsManager.getTableStatistics(database, table);
            tableStatsMap.put(table, stats);
            
            long cardinality = (stats != null) ? stats.getRowCount() : 1000;
            
            // Adjust cardinality based on where conditions
            String whereColumn = whereColumns.getOrDefault(table, "");
            String whereValue = whereValues.getOrDefault(table, "");
            
            if (!whereColumn.isEmpty() && !whereValue.isEmpty()) {
                ColumnStatistics colStats = statisticsManager.getColumnStatistics(database, table, whereColumn);
                if (colStats != null) {
                    // Assume equality predicate for simplicity
                    double selectivity = colStats.calculateEqualitySelectivity();
                    cardinality = Math.round(cardinality * selectivity);
                } else {
                    // Default selectivity if no column statistics
                    cardinality = Math.round(cardinality * 0.1);
                }
            }
            
            tableCardinalities.put(table, cardinality);
        }
        
        // For hash join, we build hash tables for all but the last table
        double totalCost = 0;
        long resultSize = 1;
        
        for (int i = 0; i < tables.size(); i++) {
            String table = tables.get(i);
            long cardinality = tableCardinalities.getOrDefault(table, 1000L);
            
            if (i == 0) {
                // First table is scanned and hashed
                totalCost += cardinality;
                resultSize = cardinality;
            } else {
                // For each subsequent table, we build a hash table and probe
                totalCost += cardinality + resultSize;
                
                // Calculate join selectivity
                String joinColumn = joinColumns.getOrDefault(table, "");
                if (!joinColumn.isEmpty()) {
                    double joinSelectivity = 0.1; // Default
                    
                    // Try to get better selectivity estimate from statistics
                    ColumnStatistics colStats = statisticsManager.getColumnStatistics(database, table, joinColumn);
                    if (colStats != null) {
                        long distinctValues = Math.max(1, colStats.getDistinctValues());
                        joinSelectivity = 1.0 / distinctValues;
                    }
                    
                    resultSize = Math.round(resultSize * cardinality * joinSelectivity);
                } else {
                    // Cartesian product
                    resultSize *= cardinality;
                }
            }
        }
        
        plan.setEstimatedRows(resultSize);
        plan.setEstimatedCost(totalCost);
        
        // Add operations - for hash join, we need to determine build and probe tables
        for (int i = 0; i < tables.size(); i++) {
            String table = tables.get(i);
            
            if (i == 0) {
                // First table scan
                QueryOperation scanOp = new QueryOperation(QueryOperationType.TABLE_SCAN);
                scanOp.setTargetTable(table);
                plan.addOperation(scanOp);
                
                // Add filter if there's a WHERE clause for this table
                String whereColumn = whereColumns.getOrDefault(table, "");
                String whereValue = whereValues.getOrDefault(table, "");
                
                if (!whereColumn.isEmpty() && !whereValue.isEmpty()) {
                    QueryOperation filterOp = new QueryOperation(QueryOperationType.FILTER);
                    filterOp.setFilterColumn(whereColumn);
                    filterOp.setFilterValue(whereValue);
                    filterOp.setFilterOperator("="); // Assume equality for simplicity
                    plan.addOperation(filterOp);
                }
                
                // Build hash table for the first table
                QueryOperation buildOp = new QueryOperation(QueryOperationType.BUILD_HASH_TABLE);
                buildOp.setJoinColumn(joinColumns.getOrDefault(table, ""));
                plan.addOperation(buildOp);
            } else {
                // For subsequent tables, scan and probe
                QueryOperation scanOp = new QueryOperation(QueryOperationType.TABLE_SCAN);
                scanOp.setTargetTable(table);
                plan.addOperation(scanOp);
                
                // Add filter if there's a WHERE clause for this table
                String whereColumn = whereColumns.getOrDefault(table, "");
                String whereValue = whereValues.getOrDefault(table, "");
                
                if (!whereColumn.isEmpty() && !whereValue.isEmpty()) {
                    QueryOperation filterOp = new QueryOperation(QueryOperationType.FILTER);
                    filterOp.setFilterColumn(whereColumn);
                    filterOp.setFilterValue(whereValue);
                    filterOp.setFilterOperator("="); // Assume equality for simplicity
                    plan.addOperation(filterOp);
                }
                
                // Probe the hash table
                QueryOperation probeOp = new QueryOperation(QueryOperationType.PROBE_HASH_TABLE);
                probeOp.setJoinColumn(joinColumns.getOrDefault(table, ""));
                plan.addOperation(probeOp);
            }
        }
        
        // Add a projection operation if not selecting all columns
        if (!selectColumns.equals("*")) {
            QueryOperation projectOp = new QueryOperation(QueryOperationType.PROJECT);
            projectOp.setProjectColumns(selectColumns.split(","));
            plan.addOperation(projectOp);
        }
        
        return plan;
    }
    
    /**
     * Create an optimized join plan with the given join order
     */
    private QueryPlan createOptimizedJoinPlan(String database, List<String> optimizedOrder,
                                             Map<String, String> joinColumns,
                                             String selectColumns,
                                             Map<String, String> whereColumns,
                                             Map<String, String> whereValues) {
        // For optimized join order, we'll use a hash join plan with the optimized order
        QueryPlan plan = new QueryPlan();
        plan.setPlanType(QueryPlanType.HASH_JOIN);
        plan.setDatabase(database);
        plan.setTable(String.join(",", optimizedOrder));
        plan.setSelectColumns(selectColumns);
        
        // Get table statistics for cost estimation
        Map<String, TableStatistics> tableStatsMap = new HashMap<>();
        Map<String, Long> tableCardinalities = new HashMap<>();
        
        for (String table : optimizedOrder) {
            TableStatistics stats = statisticsManager.getTableStatistics(database, table);
            tableStatsMap.put(table, stats);
            
            long cardinality = (stats != null) ? stats.getRowCount() : 1000;
            
            // Adjust cardinality based on where conditions
            String whereColumn = whereColumns.getOrDefault(table, "");
            String whereValue = whereValues.getOrDefault(table, "");
            
            if (!whereColumn.isEmpty() && !whereValue.isEmpty()) {
                ColumnStatistics colStats = statisticsManager.getColumnStatistics(database, table, whereColumn);
                if (colStats != null) {
                    // Assume equality predicate for simplicity
                    double selectivity = colStats.calculateEqualitySelectivity();
                    cardinality = Math.round(cardinality * selectivity);
                } else {
                    // Default selectivity if no column statistics
                    cardinality = Math.round(cardinality * 0.1);
                }
            }
            
            tableCardinalities.put(table, cardinality);
        }
        
        // Calculate join cost and result size with optimized order
        double totalCost = 0;
        long resultSize = 1;
        
        for (int i = 0; i < optimizedOrder.size(); i++) {
            String table = optimizedOrder.get(i);
            long cardinality = tableCardinalities.getOrDefault(table, 1000L);
            
            if (i == 0) {
                // First table is scanned completely
                totalCost += cardinality;
                resultSize = cardinality;
            } else {
                // For each subsequent table, we do a hash join
                totalCost += cardinality + resultSize;
                
                // Calculate join selectivity
                String joinColumn = joinColumns.getOrDefault(table, "");
                if (!joinColumn.isEmpty()) {
                    double joinSelectivity = 0.1; // Default
                    
                    // Try to get better selectivity estimate from statistics
                    ColumnStatistics colStats = statisticsManager.getColumnStatistics(database, table, joinColumn);
                    if (colStats != null) {
                        long distinctValues = Math.max(1, colStats.getDistinctValues());
                        joinSelectivity = 1.0 / distinctValues;
                    }
                    
                    resultSize = Math.round(resultSize * cardinality * joinSelectivity);
                } else {
                    // Cartesian product
                    resultSize *= cardinality;
                }
            }
        }
        
        plan.setEstimatedRows(resultSize);
        plan.setEstimatedCost(totalCost);
        
        // Add operations with optimized order
        for (int i = 0; i < optimizedOrder.size(); i++) {
            String table = optimizedOrder.get(i);
            
            if (i == 0) {
                // First table scan
                QueryOperation scanOp = new QueryOperation(QueryOperationType.TABLE_SCAN);
                scanOp.setTargetTable(table);
                plan.addOperation(scanOp);
                
                // Add filter if there's a WHERE clause for this table
                String whereColumn = whereColumns.getOrDefault(table, "");
                String whereValue = whereValues.getOrDefault(table, "");
                
                if (!whereColumn.isEmpty() && !whereValue.isEmpty()) {
                    QueryOperation filterOp = new QueryOperation(QueryOperationType.FILTER);
                    filterOp.setFilterColumn(whereColumn);
                    filterOp.setFilterValue(whereValue);
                    filterOp.setFilterOperator("="); // Assume equality for simplicity
                    plan.addOperation(filterOp);
                }
                
                // Build hash table for the first table
                QueryOperation buildOp = new QueryOperation(QueryOperationType.BUILD_HASH_TABLE);
                buildOp.setJoinColumn(joinColumns.getOrDefault(table, ""));
                plan.addOperation(buildOp);
            } else {
                // For subsequent tables, scan and probe
                QueryOperation scanOp = new QueryOperation(QueryOperationType.TABLE_SCAN);
                scanOp.setTargetTable(table);
                plan.addOperation(scanOp);
                
                // Add filter if there's a WHERE clause for this table
                String whereColumn = whereColumns.getOrDefault(table, "");
                String whereValue = whereValues.getOrDefault(table, "");
                
                if (!whereColumn.isEmpty() && !whereValue.isEmpty()) {
                    QueryOperation filterOp = new QueryOperation(QueryOperationType.FILTER);
                    filterOp.setFilterColumn(whereColumn);
                    filterOp.setFilterValue(whereValue);
                    filterOp.setFilterOperator("="); // Assume equality for simplicity
                    plan.addOperation(filterOp);
                }
                
                // Probe the hash table
                QueryOperation probeOp = new QueryOperation(QueryOperationType.PROBE_HASH_TABLE);
                probeOp.setJoinColumn(joinColumns.getOrDefault(table, ""));
                plan.addOperation(probeOp);
            }
        }
        
        // Add a projection operation if not selecting all columns
        if (!selectColumns.equals("*")) {
            QueryOperation projectOp = new QueryOperation(QueryOperationType.PROJECT);
            projectOp.setProjectColumns(selectColumns.split(","));
            plan.addOperation(projectOp);
        }
        
        return plan;
    }