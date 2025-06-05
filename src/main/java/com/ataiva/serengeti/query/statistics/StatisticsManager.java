package com.ataiva.serengeti.query.statistics;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.schema.TableStorageObject;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * StatisticsManager collects and maintains statistics about tables and columns
 * to support cost-based query optimization.
 */
public class StatisticsManager {
    private static final Logger LOGGER = Logger.getLogger(StatisticsManager.class.getName());
    
    // Singleton instance
    private static StatisticsManager instance;
    
    // Maps to store statistics
    private final Map<String, TableStatistics> tableStatistics;
    private final Map<String, Map<String, ColumnStatistics>> columnStatistics;
    
    // Executor for background statistics collection
    private final ScheduledExecutorService scheduler;
    
    // Statistics collection interval in minutes
    private static final int DEFAULT_COLLECTION_INTERVAL = 60;
    private int collectionIntervalMinutes;
    
    // Flag to indicate if statistics collection is enabled
    private boolean statisticsEnabled;
    
    /**
     * Private constructor for singleton pattern
     */
    private StatisticsManager() {
        tableStatistics = new ConcurrentHashMap<>();
        columnStatistics = new ConcurrentHashMap<>();
        scheduler = Executors.newScheduledThreadPool(1);
        collectionIntervalMinutes = DEFAULT_COLLECTION_INTERVAL;
        statisticsEnabled = true;
    }
    
    /**
     * Get the singleton instance of StatisticsManager
     * @return StatisticsManager instance
     */
    public static synchronized StatisticsManager getInstance() {
        if (instance == null) {
            instance = new StatisticsManager();
        }
        return instance;
    }
    
    /**
     * Initialize the statistics manager and start scheduled collection
     */
    public void initialize() {
        if (statisticsEnabled) {
            // Schedule periodic statistics collection
            scheduler.scheduleAtFixedRate(
                this::collectAllStatistics,
                0,
                collectionIntervalMinutes,
                TimeUnit.MINUTES
            );
            LOGGER.info("Statistics collection scheduled every " + collectionIntervalMinutes + " minutes");
        }
    }
    
    /**
     * Shutdown the statistics manager
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Set the statistics collection interval
     * @param minutes Collection interval in minutes
     */
    public void setCollectionInterval(int minutes) {
        if (minutes > 0) {
            this.collectionIntervalMinutes = minutes;
            // Restart the scheduler with the new interval
            if (statisticsEnabled) {
                scheduler.shutdown();
                scheduler.scheduleAtFixedRate(
                    this::collectAllStatistics,
                    0,
                    collectionIntervalMinutes,
                    TimeUnit.MINUTES
                );
            }
        }
    }
    
    /**
     * Enable or disable statistics collection
     * @param enabled True to enable, false to disable
     */
    public void setStatisticsEnabled(boolean enabled) {
        this.statisticsEnabled = enabled;
        if (enabled) {
            initialize();
        } else {
            scheduler.shutdown();
        }
    }
    
    /**
     * Collect statistics for all databases and tables
     */
    public void collectAllStatistics() {
        try {
            LOGGER.info("Starting collection of database statistics");
            List<String> databases = Serengeti.storage.getDatabases();
            
            for (String database : databases) {
                List<String> tables = Serengeti.storage.getTables(database);
                for (String table : tables) {
                    collectTableStatistics(database, table);
                }
            }
            LOGGER.info("Completed collection of database statistics");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error collecting statistics", e);
        }
    }
    
    /**
     * Collect statistics for a specific table
     * @param database Database name
     * @param table Table name
     */
    public void collectTableStatistics(String database, String table) {
        try {
            String tableKey = database + "." + table;
            TableStorageObject tso = new TableStorageObject(database, table);
            
            // Get all rows to analyze
            List<String> rows = Serengeti.storage.select(database, table, "*", "", "");
            
            // Create table statistics
            TableStatistics stats = new TableStatistics();
            stats.setRowCount(rows.size());
            stats.setLastUpdated(System.currentTimeMillis());
            
            // Store table statistics
            tableStatistics.put(tableKey, stats);
            
            // Initialize column statistics map for this table
            Map<String, ColumnStatistics> tableColumnStats = new HashMap<>();
            
            // Process each row to collect column statistics
            for (String rowStr : rows) {
                JSONObject row = new JSONObject(rowStr);
                
                // Process each column in the row
                for (String columnName : row.keySet()) {
                    // Skip internal fields
                    if (columnName.startsWith("__")) {
                        continue;
                    }
                    
                    // Get or create column statistics
                    ColumnStatistics colStats = tableColumnStats.computeIfAbsent(
                        columnName, k -> new ColumnStatistics());
                    
                    // Update column statistics with this value
                    Object value = row.get(columnName);
                    colStats.addValue(value);
                }
            }
            
            // Store column statistics
            columnStatistics.put(tableKey, tableColumnStats);
            
            LOGGER.info("Collected statistics for " + tableKey + ": " + rows.size() + " rows");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error collecting statistics for " + database + "." + table, e);
        }
    }
    
    /**
     * Get statistics for a specific table
     * @param database Database name
     * @param table Table name
     * @return TableStatistics object or null if not available
     */
    public TableStatistics getTableStatistics(String database, String table) {
        String tableKey = database + "." + table;
        return tableStatistics.get(tableKey);
    }
    
    /**
     * Get statistics for a specific column
     * @param database Database name
     * @param table Table name
     * @param column Column name
     * @return ColumnStatistics object or null if not available
     */
    public ColumnStatistics getColumnStatistics(String database, String table, String column) {
        String tableKey = database + "." + table;
        Map<String, ColumnStatistics> tableColumns = columnStatistics.get(tableKey);
        if (tableColumns != null) {
            return tableColumns.get(column);
        }
        return null;
    }
    
    /**
     * Check if statistics are available for a table
     * @param database Database name
     * @param table Table name
     * @return True if statistics are available
     */
    public boolean hasStatistics(String database, String table) {
        String tableKey = database + "." + table;
        return tableStatistics.containsKey(tableKey);
    }
    
    /**
     * Force immediate collection of statistics for a table
     * @param database Database name
     * @param table Table name
     */
    public void refreshStatistics(String database, String table) {
        collectTableStatistics(database, table);
    }
    
    /**
     * Get all table statistics
     * @return Map of table keys to TableStatistics objects
     */
    public Map<String, TableStatistics> getAllTableStatistics() {
        return new HashMap<>(tableStatistics);
    }
    
    /**
     * Get all column statistics for a table
     * @param database Database name
     * @param table Table name
     * @return Map of column names to ColumnStatistics objects
     */
    public Map<String, ColumnStatistics> getAllColumnStatistics(String database, String table) {
        String tableKey = database + "." + table;
        Map<String, ColumnStatistics> tableColumns = columnStatistics.get(tableKey);
        if (tableColumns != null) {
            return new HashMap<>(tableColumns);
        }
        return new HashMap<>();
    }
}