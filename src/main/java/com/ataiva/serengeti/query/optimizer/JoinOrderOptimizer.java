package com.ataiva.serengeti.query.optimizer;

import com.ataiva.serengeti.query.statistics.ColumnStatistics;
import com.ataiva.serengeti.query.statistics.StatisticsManager;
import com.ataiva.serengeti.query.statistics.TableStatistics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * JoinOrderOptimizer determines the optimal order for joining tables in a query.
 * It uses statistics and cost-based analysis to find the most efficient join order.
 */
public class JoinOrderOptimizer {
    private static final Logger LOGGER = Logger.getLogger(JoinOrderOptimizer.class.getName());
    
    // Reference to the statistics manager
    private final StatisticsManager statisticsManager;
    
    /**
     * Constructor
     */
    public JoinOrderOptimizer() {
        this.statisticsManager = StatisticsManager.getInstance();
    }
    
    /**
     * Optimize the join order for a multi-table query
     * 
     * @param database Database name
     * @param tables List of tables to join
     * @param joinColumns Map of join columns (table name -> join column)
     * @param whereColumns Map of where conditions (table name -> column name)
     * @param whereValues Map of where values (table name -> value)
     * @return Optimized list of tables in the order they should be joined
     */
    public List<String> optimizeJoinOrder(
            String database, 
            List<String> tables, 
            Map<String, String> joinColumns,
            Map<String, String> whereColumns,
            Map<String, String> whereValues) {
        
        if (tables.size() <= 1) {
            return new ArrayList<>(tables); // No optimization needed for 0 or 1 table
        }
        
        // Calculate the cost of each table as a starting point
        Map<String, Double> tableCosts = new HashMap<>();
        Map<String, Long> tableCardinalities = new HashMap<>();
        
        for (String table : tables) {
            double cost = calculateTableCost(database, table, whereColumns.get(table), whereValues.get(table));
            long cardinality = estimateTableCardinality(database, table, whereColumns.get(table), whereValues.get(table));
            
            tableCosts.put(table, cost);
            tableCardinalities.put(table, cardinality);
        }
        
        // Start with the table that has the lowest cost
        List<String> joinOrder = new ArrayList<>();
        List<String> remainingTables = new ArrayList<>(tables);
        
        // Find the table with the lowest cost
        String startTable = Collections.min(remainingTables, Comparator.comparing(tableCosts::get));
        joinOrder.add(startTable);
        remainingTables.remove(startTable);
        
        // Iteratively add the next best table to join
        while (!remainingTables.isEmpty()) {
            String bestNextTable = findBestNextTable(
                database, joinOrder, remainingTables, joinColumns, tableCardinalities);
            
            joinOrder.add(bestNextTable);
            remainingTables.remove(bestNextTable);
        }
        
        LOGGER.info("Optimized join order: " + joinOrder);
        return joinOrder;
    }
    
    /**
     * Calculate the cost of starting with a particular table
     * 
     * @param database Database name
     * @param table Table name
     * @param whereColumn Where column (can be null)
     * @param whereValue Where value (can be null)
     * @return Estimated cost
     */
    private double calculateTableCost(String database, String table, String whereColumn, String whereValue) {
        TableStatistics tableStats = statisticsManager.getTableStatistics(database, table);
        if (tableStats == null) {
            return 1000.0; // Default high cost if no statistics
        }
        
        double cost = tableStats.getRowCount();
        
        // If there's a WHERE clause, adjust the cost based on selectivity
        if (whereColumn != null && whereValue != null) {
            ColumnStatistics colStats = statisticsManager.getColumnStatistics(database, table, whereColumn);
            if (colStats != null) {
                // Assume equality predicate for simplicity
                double selectivity = colStats.calculateEqualitySelectivity();
                cost *= selectivity;
            }
        }
        
        return cost;
    }
    
    /**
     * Estimate the cardinality (number of rows) of a table after applying filters
     * 
     * @param database Database name
     * @param table Table name
     * @param whereColumn Where column (can be null)
     * @param whereValue Where value (can be null)
     * @return Estimated cardinality
     */
    private long estimateTableCardinality(String database, String table, String whereColumn, String whereValue) {
        TableStatistics tableStats = statisticsManager.getTableStatistics(database, table);
        if (tableStats == null) {
            return 1000; // Default if no statistics
        }
        
        long cardinality = tableStats.getRowCount();
        
        // If there's a WHERE clause, adjust the cardinality based on selectivity
        if (whereColumn != null && whereValue != null) {
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
        
        return Math.max(1, cardinality); // Ensure at least 1 row
    }
    
    /**
     * Find the best next table to join with the current join order
     * 
     * @param database Database name
     * @param currentJoinOrder Current join order
     * @param remainingTables Tables not yet in the join order
     * @param joinColumns Map of join columns
     * @param tableCardinalities Map of table cardinalities
     * @return The best next table to join
     */
    private String findBestNextTable(
            String database,
            List<String> currentJoinOrder,
            List<String> remainingTables,
            Map<String, String> joinColumns,
            Map<String, Long> tableCardinalities) {
        
        String bestTable = null;
        double bestCost = Double.MAX_VALUE;
        
        for (String candidate : remainingTables) {
            // Calculate the cost of joining this candidate with the current join order
            double joinCost = calculateJoinCost(
                database, currentJoinOrder, candidate, joinColumns, tableCardinalities);
            
            if (joinCost < bestCost) {
                bestCost = joinCost;
                bestTable = candidate;
            }
        }
        
        // If no best table found (should not happen), return the first remaining table
        return bestTable != null ? bestTable : remainingTables.get(0);
    }
    
    /**
     * Calculate the cost of joining a candidate table with the current join order
     * 
     * @param database Database name
     * @param currentJoinOrder Current join order
     * @param candidateTable Candidate table to join next
     * @param joinColumns Map of join columns
     * @param tableCardinalities Map of table cardinalities
     * @return Estimated join cost
     */
    private double calculateJoinCost(
            String database,
            List<String> currentJoinOrder,
            String candidateTable,
            Map<String, String> joinColumns,
            Map<String, Long> tableCardinalities) {
        
        // Find tables in the current join order that join with the candidate
        List<String> joiningTables = new ArrayList<>();
        for (String table : currentJoinOrder) {
            if (canJoin(table, candidateTable, joinColumns)) {
                joiningTables.add(table);
            }
        }
        
        if (joiningTables.isEmpty()) {
            // No direct join possible, this is a Cartesian product (very expensive)
            long currentSize = 1;
            for (String table : currentJoinOrder) {
                currentSize *= tableCardinalities.getOrDefault(table, 1000L);
            }
            long candidateSize = tableCardinalities.getOrDefault(candidateTable, 1000L);
            return currentSize * candidateSize;
        }
        
        // Calculate join cost based on the best joining table
        double bestJoinCost = Double.MAX_VALUE;
        for (String joiningTable : joiningTables) {
            double joinCost = calculateSpecificJoinCost(
                database, joiningTable, candidateTable, joinColumns, tableCardinalities);
            bestJoinCost = Math.min(bestJoinCost, joinCost);
        }
        
        return bestJoinCost;
    }
    
    /**
     * Check if two tables can be joined directly
     * 
     * @param table1 First table
     * @param table2 Second table
     * @param joinColumns Map of join columns
     * @return True if the tables can be joined
     */
    private boolean canJoin(String table1, String table2, Map<String, String> joinColumns) {
        // For simplicity, assume tables can join if they have the same join column
        String joinColumn1 = joinColumns.get(table1);
        String joinColumn2 = joinColumns.get(table2);
        return joinColumn1 != null && joinColumn2 != null && joinColumn1.equals(joinColumn2);
    }
    
    /**
     * Calculate the cost of joining two specific tables
     * 
     * @param database Database name
     * @param table1 First table
     * @param table2 Second table
     * @param joinColumns Map of join columns
     * @param tableCardinalities Map of table cardinalities
     * @return Estimated join cost
     */
    private double calculateSpecificJoinCost(
            String database,
            String table1,
            String table2,
            Map<String, String> joinColumns,
            Map<String, Long> tableCardinalities) {
        
        String joinColumn = joinColumns.get(table1); // Same as joinColumns.get(table2)
        
        // Get cardinalities
        long cardinality1 = tableCardinalities.getOrDefault(table1, 1000L);
        long cardinality2 = tableCardinalities.getOrDefault(table2, 1000L);
        
        // Get join column statistics
        ColumnStatistics colStats1 = statisticsManager.getColumnStatistics(database, table1, joinColumn);
        ColumnStatistics colStats2 = statisticsManager.getColumnStatistics(database, table2, joinColumn);
        
        // Calculate join selectivity
        double joinSelectivity = 0.1; // Default
        if (colStats1 != null && colStats2 != null) {
            long distinct1 = Math.max(1, colStats1.getDistinctValues());
            long distinct2 = Math.max(1, colStats2.getDistinctValues());
            joinSelectivity = 1.0 / Math.max(distinct1, distinct2);
        }
        
        // Estimate result size
        double resultSize = cardinality1 * cardinality2 * joinSelectivity;
        
        // Cost model: smaller table for build phase + larger table for probe phase + result size
        double smallerCardinality = Math.min(cardinality1, cardinality2);
        double largerCardinality = Math.max(cardinality1, cardinality2);
        
        return smallerCardinality + largerCardinality + resultSize;
    }
}