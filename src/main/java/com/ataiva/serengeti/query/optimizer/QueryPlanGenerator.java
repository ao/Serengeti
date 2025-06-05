package com.ataiva.serengeti.query.optimizer;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.query.statistics.ColumnStatistics;
import com.ataiva.serengeti.query.statistics.StatisticsManager;
import com.ataiva.serengeti.query.statistics.TableStatistics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * QueryPlanGenerator creates and evaluates alternative query execution plans
 * based on statistics and cost estimates.
 */
public class QueryPlanGenerator {
    private static final Logger LOGGER = Logger.getLogger(QueryPlanGenerator.class.getName());
    
    // Singleton instance
    private static QueryPlanGenerator instance;
    
    // Reference to the statistics manager
    private final StatisticsManager statisticsManager;
    
    /**
     * Private constructor for singleton pattern
     */
    private QueryPlanGenerator() {
        this.statisticsManager = StatisticsManager.getInstance();
    }
    
    /**
     * Get the singleton instance of QueryPlanGenerator
     * @return QueryPlanGenerator instance
     */
    public static synchronized QueryPlanGenerator getInstance() {
        if (instance == null) {
            instance = new QueryPlanGenerator();
        }
        return instance;
    }
    
    /**
     * Generate a query plan for a SELECT query
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
            // Check if we have an index on the WHERE column
            boolean hasIndex = Serengeti.indexManager.hasIndex(database, table, whereColumn);
            
            if (hasIndex) {
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
        
        // Log the chosen plan
        LOGGER.info("Selected query plan: " + bestPlan.getPlanType() + " with estimated cost: " + bestPlan.getEstimatedCost());
        
        return bestPlan;
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
                    selectivity = colStats.calculateRangeSelectivity(Double.parseDouble(whereValue), null);
                } else if (whereOperator.equals("<") || whereOperator.equals("<=")) {
                    selectivity = colStats.calculateRangeSelectivity(null, Double.parseDouble(whereValue));
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
                selectivity = colStats.calculateRangeSelectivity(Double.parseDouble(whereValue), null);
            } else if (whereOperator.equals("<") || whereOperator.equals("<=")) {
                selectivity = colStats.calculateRangeSelectivity(null, Double.parseDouble(whereValue));
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
     * Find the plan with the lowest estimated cost
     * @param plans List of candidate plans
     * @return The plan with the lowest cost
     */
    private QueryPlan findLowestCostPlan(List<QueryPlan> plans) {
        if (plans.isEmpty()) {
            throw new IllegalArgumentException("No plans provided");
        }
        
        QueryPlan bestPlan = plans.get(0);
        for (int i = 1; i < plans.size(); i++) {
            if (plans.get(i).getEstimatedCost() < bestPlan.getEstimatedCost()) {
                bestPlan = plans.get(i);
            }
        }
        
        return bestPlan;
    }
    
    /**
     * Generate a query plan for a JOIN query
     * @param database Database name
     * @param leftTable Left table name
     * @param rightTable Right table name
     * @param joinColumn Join column name
     * @param selectColumns Columns to select
     * @param whereColumn Column in WHERE clause (or empty if none)
     * @param whereValue Value in WHERE clause (or empty if none)
     * @return The optimal QueryPlan
     */
    public QueryPlan generateJoinPlan(String database, String leftTable, String rightTable,
                                     String joinColumn, String selectColumns,
                                     String whereColumn, String whereValue) {
        List<QueryPlan> candidatePlans = new ArrayList<>();
        
        // Get table statistics
        TableStatistics leftStats = statisticsManager.getTableStatistics(database, leftTable);
        TableStatistics rightStats = statisticsManager.getTableStatistics(database, rightTable);
        
        if (leftStats == null || rightStats == null) {
            // No statistics available, use a default plan
            return createDefaultJoinPlan(database, leftTable, rightTable, joinColumn, selectColumns, whereColumn, whereValue);
        }
        
        // Create a nested loop join plan
        QueryPlan nestedLoopPlan = createNestedLoopJoinPlan(database, leftTable, rightTable, joinColumn, selectColumns, whereColumn, whereValue);
        candidatePlans.add(nestedLoopPlan);
        
        // Create a hash join plan
        QueryPlan hashJoinPlan = createHashJoinPlan(database, leftTable, rightTable, joinColumn, selectColumns, whereColumn, whereValue);
        candidatePlans.add(hashJoinPlan);
        
        // Find the plan with the lowest cost
        QueryPlan bestPlan = findLowestCostPlan(candidatePlans);
        
        // Log the chosen plan
        LOGGER.info("Selected join plan: " + bestPlan.getPlanType() + " with estimated cost: " + bestPlan.getEstimatedCost());
        
        return bestPlan;
    }
    
    /**
     * Create a default join plan when no statistics are available
     */
    private QueryPlan createDefaultJoinPlan(String database, String leftTable, String rightTable,
                                           String joinColumn, String selectColumns,
                                           String whereColumn, String whereValue) {
        // Default to a nested loop join
        QueryPlan plan = new QueryPlan();
        plan.setPlanType(QueryPlanType.NESTED_LOOP_JOIN);
        plan.setDatabase(database);
        plan.setTable(leftTable + "," + rightTable);
        plan.setJoinColumn(joinColumn);
        plan.setSelectColumns(selectColumns);
        plan.setWhereColumn(whereColumn);
        plan.setWhereValue(whereValue);
        plan.setEstimatedCost(10000); // Default high cost
        plan.setEstimatedRows(1000); // Default row estimate
        
        // Add operations
        QueryOperation scanLeftOp = new QueryOperation(QueryOperationType.TABLE_SCAN);
        scanLeftOp.setTargetTable(leftTable);
        plan.addOperation(scanLeftOp);
        
        QueryOperation joinOp = new QueryOperation(QueryOperationType.NESTED_LOOP_JOIN);
        joinOp.setTargetTable(rightTable);
        joinOp.setJoinColumn(joinColumn);
        plan.addOperation(joinOp);
        
        // Add a filter operation if there's a WHERE clause
        if (!whereColumn.isEmpty() && !whereValue.isEmpty()) {
            QueryOperation filterOp = new QueryOperation(QueryOperationType.FILTER);
            filterOp.setFilterColumn(whereColumn);
            filterOp.setFilterValue(whereValue);
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
     * Create a nested loop join plan
     */
    private QueryPlan createNestedLoopJoinPlan(String database, String leftTable, String rightTable,
                                              String joinColumn, String selectColumns,
                                              String whereColumn, String whereValue) {
        QueryPlan plan = new QueryPlan();
        plan.setPlanType(QueryPlanType.NESTED_LOOP_JOIN);
        plan.setDatabase(database);
        plan.setTable(leftTable + "," + rightTable);
        plan.setJoinColumn(joinColumn);
        plan.setSelectColumns(selectColumns);
        plan.setWhereColumn(whereColumn);
        plan.setWhereValue(whereValue);
        
        // Get table statistics for cost estimation
        TableStatistics leftStats = statisticsManager.getTableStatistics(database, leftTable);
        TableStatistics rightStats = statisticsManager.getTableStatistics(database, rightTable);
        
        long leftRows = (leftStats != null) ? leftStats.getRowCount() : 1000;
        long rightRows = (rightStats != null) ? rightStats.getRowCount() : 1000;
        
        // Nested loop join cost model: outer table rows * inner table rows
        double estimatedCost = leftRows * rightRows;
        
        // Estimate output rows (assuming 10% join selectivity as default)
        double joinSelectivity = 0.1;
        ColumnStatistics leftColStats = statisticsManager.getColumnStatistics(database, leftTable, joinColumn);
        ColumnStatistics rightColStats = statisticsManager.getColumnStatistics(database, rightTable, joinColumn);
        
        if (leftColStats != null && rightColStats != null) {
            // Better estimate based on column statistics
            long leftDistinct = Math.max(1, leftColStats.getDistinctValues());
            long rightDistinct = Math.max(1, rightColStats.getDistinctValues());
            joinSelectivity = 1.0 / Math.max(leftDistinct, rightDistinct);
        }
        
        long estimatedRows = Math.round(leftRows * rightRows * joinSelectivity);
        
        plan.setEstimatedRows(estimatedRows);
        plan.setEstimatedCost(estimatedCost);
        
        // Add operations
        QueryOperation scanLeftOp = new QueryOperation(QueryOperationType.TABLE_SCAN);
        scanLeftOp.setTargetTable(leftTable);
        plan.addOperation(scanLeftOp);
        
        QueryOperation joinOp = new QueryOperation(QueryOperationType.NESTED_LOOP_JOIN);
        joinOp.setTargetTable(rightTable);
        joinOp.setJoinColumn(joinColumn);
        plan.addOperation(joinOp);
        
        // Add a filter operation if there's a WHERE clause
        if (!whereColumn.isEmpty() && !whereValue.isEmpty()) {
            QueryOperation filterOp = new QueryOperation(QueryOperationType.FILTER);
            filterOp.setFilterColumn(whereColumn);
            filterOp.setFilterValue(whereValue);
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
     * Create a hash join plan
     */
    private QueryPlan createHashJoinPlan(String database, String leftTable, String rightTable,
                                        String joinColumn, String selectColumns,
                                        String whereColumn, String whereValue) {
        QueryPlan plan = new QueryPlan();
        plan.setPlanType(QueryPlanType.HASH_JOIN);
        plan.setDatabase(database);
        plan.setTable(leftTable + "," + rightTable);
        plan.setJoinColumn(joinColumn);
        plan.setSelectColumns(selectColumns);
        plan.setWhereColumn(whereColumn);
        plan.setWhereValue(whereValue);
        
        // Get table statistics for cost estimation
        TableStatistics leftStats = statisticsManager.getTableStatistics(database, leftTable);
        TableStatistics rightStats = statisticsManager.getTableStatistics(database, rightTable);
        
        long leftRows = (leftStats != null) ? leftStats.getRowCount() : 1000;
        long rightRows = (rightStats != null) ? rightStats.getRowCount() : 1000;
        
        // Hash join cost model: build hash table (smaller table) + probe (larger table)
        long smallerTable = Math.min(leftRows, rightRows);
        long largerTable = Math.max(leftRows, rightRows);
        double estimatedCost = smallerTable + largerTable;
        
        // Estimate output rows (assuming 10% join selectivity as default)
        double joinSelectivity = 0.1;
        ColumnStatistics leftColStats = statisticsManager.getColumnStatistics(database, leftTable, joinColumn);
        ColumnStatistics rightColStats = statisticsManager.getColumnStatistics(database, rightTable, joinColumn);
        
        if (leftColStats != null && rightColStats != null) {
            // Better estimate based on column statistics
            long leftDistinct = Math.max(1, leftColStats.getDistinctValues());
            long rightDistinct = Math.max(1, rightColStats.getDistinctValues());
            joinSelectivity = 1.0 / Math.max(leftDistinct, rightDistinct);
        }
        
        long estimatedRows = Math.round(leftRows * rightRows * joinSelectivity);
        
        plan.setEstimatedRows(estimatedRows);
        plan.setEstimatedCost(estimatedCost);
        
        // Determine which table to use for building the hash table (smaller one)
        String buildTable = (leftRows <= rightRows) ? leftTable : rightTable;
        String probeTable = (leftRows <= rightRows) ? rightTable : leftTable;
        
        // Add operations
        QueryOperation buildOp = new QueryOperation(QueryOperationType.TABLE_SCAN);
        buildOp.setTargetTable(buildTable);
        plan.addOperation(buildOp);
        
        QueryOperation hashOp = new QueryOperation(QueryOperationType.BUILD_HASH_TABLE);
        hashOp.setJoinColumn(joinColumn);
        plan.addOperation(hashOp);
        
        QueryOperation probeOp = new QueryOperation(QueryOperationType.TABLE_SCAN);
        probeOp.setTargetTable(probeTable);
        plan.addOperation(probeOp);
        
        QueryOperation joinOp = new QueryOperation(QueryOperationType.HASH_JOIN_PROBE);
        joinOp.setJoinColumn(joinColumn);
        plan.addOperation(joinOp);
        
        // Add a filter operation if there's a WHERE clause
        if (!whereColumn.isEmpty() && !whereValue.isEmpty()) {
            QueryOperation filterOp = new QueryOperation(QueryOperationType.FILTER);
            filterOp.setFilterColumn(whereColumn);
            filterOp.setFilterValue(whereValue);
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
}