package com.ataiva.serengeti.query.optimizer;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.query.statistics.ColumnStatistics;
import com.ataiva.serengeti.query.statistics.StatisticsManager;
import com.ataiva.serengeti.query.statistics.TableStatistics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * IndexUtilizationOptimizer analyzes queries to determine the optimal use of indexes.
 * It evaluates when to use indexes vs. table scans and which indexes to use for joins and filters.
 */
public class IndexUtilizationOptimizer {
    private static final Logger LOGGER = Logger.getLogger(IndexUtilizationOptimizer.class.getName());
    
    // Reference to the statistics manager
    private final StatisticsManager statisticsManager;
    
    // Threshold for using an index (percentage of table that would be accessed)
    private static final double INDEX_USAGE_THRESHOLD = 0.20; // 20%
    
    /**
     * Constructor
     */
    public IndexUtilizationOptimizer() {
        this.statisticsManager = StatisticsManager.getInstance();
    }
    
    /**
     * Determine if an index should be used for a query
     * 
     * @param database Database name
     * @param table Table name
     * @param column Column name
     * @param operator Operator (=, >, <, etc.)
     * @param value Value to compare against
     * @return True if an index should be used
     */
    public boolean shouldUseIndex(String database, String table, String column, String operator, String value) {
        // Check if an index exists for this column
        boolean hasIndex = Serengeti.indexManager.hasIndex(database, table, column);
        if (!hasIndex) {
            return false;
        }
        
        // Get table and column statistics
        TableStatistics tableStats = statisticsManager.getTableStatistics(database, table);
        ColumnStatistics colStats = statisticsManager.getColumnStatistics(database, table, column);
        
        if (tableStats == null || colStats == null) {
            // No statistics available, use index by default
            return true;
        }
        
        // Calculate selectivity based on the operator
        double selectivity;
        switch (operator) {
            case "=":
                selectivity = colStats.calculateEqualitySelectivity();
                break;
            case ">":
            case ">=":
                try {
                    selectivity = colStats.calculateRangeSelectivity(Double.parseDouble(value), null);
                } catch (NumberFormatException e) {
                    selectivity = 0.5; // Default for non-numeric values
                }
                break;
            case "<":
            case "<=":
                try {
                    selectivity = colStats.calculateRangeSelectivity(null, Double.parseDouble(value));
                } catch (NumberFormatException e) {
                    selectivity = 0.5; // Default for non-numeric values
                }
                break;
            default:
                selectivity = 0.5; // Default for other operators
        }
        
        // Use index if selectivity is below threshold
        boolean useIndex = selectivity < INDEX_USAGE_THRESHOLD;
        
        LOGGER.fine("Index usage decision for " + database + "." + table + "." + column + 
                   " with operator " + operator + ": " + useIndex + 
                   " (selectivity: " + selectivity + ")");
        
        return useIndex;
    }
    
    /**
     * Identify the best indexes to use for a query with multiple conditions
     * 
     * @param database Database name
     * @param table Table name
     * @param conditions Map of conditions (column -> condition details)
     * @return List of columns that should use indexes
     */
    public List<String> identifyBestIndexes(String database, String table, Map<String, ConditionInfo> conditions) {
        List<String> indexesToUse = new ArrayList<>();
        Map<String, Double> selectivities = new HashMap<>();
        
        // Calculate selectivity for each condition
        for (Map.Entry<String, ConditionInfo> entry : conditions.entrySet()) {
            String column = entry.getKey();
            ConditionInfo condition = entry.getValue();
            
            // Check if an index exists
            boolean hasIndex = Serengeti.indexManager.hasIndex(database, table, column);
            if (!hasIndex) {
                continue;
            }
            
            // Get column statistics
            ColumnStatistics colStats = statisticsManager.getColumnStatistics(database, table, column);
            if (colStats == null) {
                // No statistics, use a default selectivity
                selectivities.put(column, 0.1);
                continue;
            }
            
            // Calculate selectivity
            double selectivity;
            switch (condition.operator) {
                case "=":
                    selectivity = colStats.calculateEqualitySelectivity();
                    break;
                case ">":
                case ">=":
                    try {
                        selectivity = colStats.calculateRangeSelectivity(Double.parseDouble(condition.value), null);
                    } catch (NumberFormatException e) {
                        selectivity = 0.5;
                    }
                    break;
                case "<":
                case "<=":
                    try {
                        selectivity = colStats.calculateRangeSelectivity(null, Double.parseDouble(condition.value));
                    } catch (NumberFormatException e) {
                        selectivity = 0.5;
                    }
                    break;
                default:
                    selectivity = 0.5;
            }
            
            selectivities.put(column, selectivity);
        }
        
        // Sort conditions by selectivity (most selective first)
        selectivities.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .filter(entry -> entry.getValue() < INDEX_USAGE_THRESHOLD)
            .forEach(entry -> indexesToUse.add(entry.getKey()));
        
        LOGGER.fine("Best indexes for " + database + "." + table + ": " + indexesToUse);
        return indexesToUse;
    }
    
    /**
     * Determine if a compound index would be beneficial
     * 
     * @param database Database name
     * @param table Table name
     * @param columns List of columns to consider for a compound index
     * @return True if a compound index would be beneficial
     */
    public boolean shouldUseCompoundIndex(String database, String table, List<String> columns) {
        // Check if a compound index exists
        boolean hasCompoundIndex = false;
        for (int i = 0; i < columns.size(); i++) {
            // Check for prefix compound indexes
            List<String> prefix = columns.subList(0, i + 1);
            String[] prefixArray = prefix.toArray(new String[0]);
            if (Serengeti.indexManager.hasCompoundIndex(database, table, prefixArray)) {
                hasCompoundIndex = true;
                break;
            }
        }
        
        if (!hasCompoundIndex) {
            return false;
        }
        
        // Get table statistics
        TableStatistics tableStats = statisticsManager.getTableStatistics(database, table);
        if (tableStats == null) {
            return true; // No statistics, use compound index by default
        }
        
        // Calculate combined selectivity
        double combinedSelectivity = 1.0;
        for (String column : columns) {
            ColumnStatistics colStats = statisticsManager.getColumnStatistics(database, table, column);
            if (colStats != null) {
                // Use equality selectivity as an approximation
                double selectivity = colStats.calculateEqualitySelectivity();
                combinedSelectivity *= selectivity;
            } else {
                combinedSelectivity *= 0.1; // Default selectivity
            }
        }
        
        // Use compound index if combined selectivity is below threshold
        boolean useCompoundIndex = combinedSelectivity < INDEX_USAGE_THRESHOLD;
        
        LOGGER.fine("Compound index usage decision for " + database + "." + table + 
                   " columns " + columns + ": " + useCompoundIndex + 
                   " (combined selectivity: " + combinedSelectivity + ")");
        
        return useCompoundIndex;
    }
    
    /**
     * Recommend indexes that should be created for a table based on query patterns
     * 
     * @param database Database name
     * @param table Table name
     * @param queryHistory List of recent queries on this table
     * @return List of columns that should have indexes
     */
    public List<String> recommendIndexes(String database, String table, List<String> queryHistory) {
        // This is a placeholder for a more sophisticated index recommendation algorithm
        // In a real implementation, we would analyze query patterns and column statistics
        
        // Get column statistics
        Map<String, ColumnStatistics> columnStats = statisticsManager.getAllColumnStatistics(database, table);
        List<String> recommendations = new ArrayList<>();
        
        // Recommend indexes for columns with good selectivity
        for (Map.Entry<String, ColumnStatistics> entry : columnStats.entrySet()) {
            String column = entry.getKey();
            ColumnStatistics stats = entry.getValue();
            
            // Skip columns that already have indexes
            if (Serengeti.indexManager.hasIndex(database, table, column)) {
                continue;
            }
            
            // Check if this column would benefit from an index
            if (stats.shouldCreateIndex()) {
                recommendations.add(column);
            }
        }
        
        LOGGER.info("Index recommendations for " + database + "." + table + ": " + recommendations);
        return recommendations;
    }
    
    /**
     * Class to hold condition information
     */
    public static class ConditionInfo {
        public String operator;
        public String value;
        
        public ConditionInfo(String operator, String value) {
            this.operator = operator;
            this.value = value;
        }
    }
}