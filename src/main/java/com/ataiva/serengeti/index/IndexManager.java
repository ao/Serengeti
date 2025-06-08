package com.ataiva.serengeti.index;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.schema.TableStorageObject;

/**
 * IndexManager is responsible for creating, maintaining, and using indexes
 * for the Serengeti database system. It tracks query patterns to identify
 * frequently queried columns and can automatically create indexes for them.
 */
public class IndexManager implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    // Map of database.table.column -> BTreeIndex
    private Map<String, BTreeIndex> indexes;
    
    // Map of database.table.column -> query count
    private Map<String, AtomicInteger> queryFrequency;
    
    // Configuration
    private boolean autoIndexingEnabled = true;
    private int autoIndexThreshold = 100; // Number of queries before auto-indexing
    private int maxIndexesPerTable = 5;   // Maximum number of indexes per table
    
    /**
     * Creates a new IndexManager
     */
    public IndexManager() {
        this.indexes = new ConcurrentHashMap<>();
        this.queryFrequency = new ConcurrentHashMap<>();
        loadIndexes();
    }
    
    /**
     * Creates an index for a specific column in a table
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param columnName The column name
     * @param tableData The table data to index
     * @return true if the index was created, false otherwise
     */
    public boolean createIndex(String databaseName, String tableName, String columnName, Map<String, String> tableData) {
        String indexKey = getIndexKey(databaseName, tableName, columnName);
        
        // Check if index already exists
        if (indexes.containsKey(indexKey)) {
            return false;
        }
        
        // Create the index
        BTreeIndex index = new BTreeIndex(databaseName, tableName, columnName);
        
        // Build the index from table data
        for (Map.Entry<String, String> entry : tableData.entrySet()) {
            String rowId = entry.getKey();
            String jsonStr = entry.getValue();
            
            try {
                JSONObject json = new JSONObject(jsonStr);
                if (json.has(columnName)) {
                    Object value = json.get(columnName);
                    if (value != null) {
                        if (value instanceof Number) {
                            index.insert((Comparable) value, rowId);
                        } else {
                            index.insert(value.toString(), rowId);
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("Error building index: " + e.getMessage());
            }
        }
        
        // Save the index
        indexes.put(indexKey, index);
        index.saveToDisk();
        saveIndexMetadata();
        
        System.out.println("Created index on " + databaseName + "." + tableName + "." + columnName);
        return true;
    }
    
    /**
     * Drops an index for a specific column in a table
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param columnName The column name
     * @return true if the index was dropped, false otherwise
     */
    public boolean dropIndex(String databaseName, String tableName, String columnName) {
        String indexKey = getIndexKey(databaseName, tableName, columnName);
        
        // Check if index exists
        if (!indexes.containsKey(indexKey)) {
            return false;
        }
        
        // Remove the index
        indexes.remove(indexKey);
        
        // Delete the index file
        try {
            String indexPath = Globals.data_path + databaseName + "/" + tableName + "/indexes/" + columnName + ".idx";
            Path path = Paths.get(indexPath);
            Files.deleteIfExists(path);
        } catch (Exception e) {
            System.out.println("Error deleting index file: " + e.getMessage());
        }
        
        saveIndexMetadata();
        System.out.println("Dropped index on " + databaseName + "." + tableName + "." + columnName);
        return true;
    }
    
    /**
     * Updates an index when a row is inserted
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param rowId The row ID
     * @param json The JSON data
     */
    public void handleInsert(String databaseName, String tableName, String rowId, JSONObject json) {
        // Get all indexes for this table
        List<BTreeIndex> tableIndexes = getTableIndexes(databaseName, tableName);
        
        // Update each index
        for (BTreeIndex index : tableIndexes) {
            String columnName = index.getColumnName();
            if (json.has(columnName)) {
                Object value = json.get(columnName);
                if (value != null) {
                    if (value instanceof Number) {
                        index.insert((Comparable) value, rowId);
                    } else {
                        index.insert(value.toString(), rowId);
                    }
                }
            }
        }
        
        // Save affected indexes
        for (BTreeIndex index : tableIndexes) {
            index.saveToDisk();
        }
    }
    
    /**
     * Updates indexes when a row is updated
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param rowId The row ID
     * @param oldJson The old JSON data
     * @param newJson The new JSON data
     */
    public void handleUpdate(String databaseName, String tableName, String rowId, JSONObject oldJson, JSONObject newJson) {
        // Get all indexes for this table
        List<BTreeIndex> tableIndexes = getTableIndexes(databaseName, tableName);
        
        // Update each index
        for (BTreeIndex index : tableIndexes) {
            String columnName = index.getColumnName();
            
            // Remove old value from index
            if (oldJson.has(columnName)) {
                Object oldValue = oldJson.get(columnName);
                if (oldValue != null) {
                    if (oldValue instanceof Number) {
                        index.remove((Comparable) oldValue, rowId);
                    } else {
                        index.remove(oldValue.toString(), rowId);
                    }
                }
            }
            
            // Add new value to index
            if (newJson.has(columnName)) {
                Object newValue = newJson.get(columnName);
                if (newValue != null) {
                    if (newValue instanceof Number) {
                        index.insert((Comparable) newValue, rowId);
                    } else {
                        index.insert(newValue.toString(), rowId);
                    }
                }
            }
        }
        
        // Save affected indexes
        for (BTreeIndex index : tableIndexes) {
            index.saveToDisk();
        }
    }
    
    /**
     * Updates indexes when a row is deleted
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param rowId The row ID
     * @param json The JSON data
     */
    public void handleDelete(String databaseName, String tableName, String rowId, JSONObject json) {
        // Get all indexes for this table
        List<BTreeIndex> tableIndexes = getTableIndexes(databaseName, tableName);
        
        // Update each index
        for (BTreeIndex index : tableIndexes) {
            String columnName = index.getColumnName();
            if (json.has(columnName)) {
                Object value = json.get(columnName);
                if (value != null) {
                    if (value instanceof Number) {
                        index.remove((Comparable) value, rowId);
                    } else {
                        index.remove(value.toString(), rowId);
                    }
                }
            }
        }
        
        // Save affected indexes
        for (BTreeIndex index : tableIndexes) {
            index.saveToDisk();
        }
    }
    
    /**
     * Finds row IDs matching a column value using an index
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param columnName The column name
     * @param value The value to search for
     * @return Set of matching row IDs, or null if no index exists
     */
    public Set<String> findRows(String databaseName, String tableName, String columnName, Object value) {
        // Track query frequency for this column
        trackQueryFrequency(databaseName, tableName, columnName);
        
        // Check if an index exists for this column
        String indexKey = getIndexKey(databaseName, tableName, columnName);
        BTreeIndex index = indexes.get(indexKey);
        
        if (index != null) {
            // Use the index to find matching rows
            if (value instanceof Number) {
                return index.find((Comparable) value);
            } else {
                return index.find(value.toString());
            }
        }
        
        return null; // No index exists
    }
    
    /**
     * Finds row IDs with values in a range using an index
     *
     * @param databaseName The database name
     * @param tableName The table name
     * @param columnName The column name
     * @param fromValue The lower bound (can be null for unbounded)
     * @param toValue The upper bound (can be null for unbounded)
     * @return Set of matching row IDs, or null if no index exists
     */
    public Set<String> findRowsInRange(String databaseName, String tableName, String columnName,
                                      Object fromValue, Object toValue) {
        // Track query frequency for this column
        trackQueryFrequency(databaseName, tableName, columnName);
        
        // Check if an index exists for this column
        String indexKey = getIndexKey(databaseName, tableName, columnName);
        BTreeIndex index = indexes.get(indexKey);
        
        if (index != null) {
            // Use the index to find matching rows
            if (fromValue == null && toValue == null) {
                // Return all values in the index
                return index.findAll();
            } else if (fromValue == null) {
                // All values less than or equal to toValue
                if (toValue instanceof Number) {
                    return index.findLessThanOrEqual((Comparable) toValue);
                } else {
                    return index.findLessThanOrEqual(toValue.toString());
                }
            } else if (toValue == null) {
                // All values greater than or equal to fromValue
                if (fromValue instanceof Number) {
                    return index.findGreaterThanOrEqual((Comparable) fromValue);
                } else {
                    return index.findGreaterThanOrEqual(fromValue.toString());
                }
            } else {
                // Values in range fromValue to toValue (inclusive)
                if (fromValue instanceof Number && toValue instanceof Number) {
                    return index.findRange((Comparable) fromValue, (Comparable) toValue);
                } else {
                    return index.findRange(fromValue.toString(), toValue.toString());
                }
            }
        }
        
        return null; // No index exists
    }
    
    /**
     * Checks if an index exists for a specific column
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param columnName The column name
     * @return true if an index exists, false otherwise
     */
    public boolean hasIndex(String databaseName, String tableName, String columnName) {
        String indexKey = getIndexKey(databaseName, tableName, columnName);
        return indexes.containsKey(indexKey);
    }
    
    /**
     * Checks if a compound index exists for the specified columns
     *
     * @param databaseName The database name
     * @param tableName The table name
     * @param columns The column names to check for compound index
     * @return true if a compound index exists, false otherwise
     */
    public boolean hasCompoundIndex(String databaseName, String tableName, String... columns) {
        // For now, this implementation checks if individual indexes exist for all columns
        // A true compound index implementation would require a different data structure
        for (String column : columns) {
            if (!hasIndex(databaseName, tableName, column)) {
                return false;
            }
        }
        return columns.length > 0;
    }
    
    /**
     * Gets all indexes for a specific table
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @return List of indexes for the table
     */
    public List<BTreeIndex> getTableIndexes(String databaseName, String tableName) {
        List<BTreeIndex> result = new ArrayList<>();
        String prefix = databaseName + "." + tableName + ".";
        
        for (Map.Entry<String, BTreeIndex> entry : indexes.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.add(entry.getValue());
            }
        }
        
        return result;
    }
    
    /**
     * Gets all indexed columns for a specific table
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @return List of column names that are indexed
     */
    public List<String> getIndexedColumns(String databaseName, String tableName) {
        List<String> result = new ArrayList<>();
        String prefix = databaseName + "." + tableName + ".";
        
        for (Map.Entry<String, BTreeIndex> entry : indexes.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                String columnName = entry.getValue().getColumnName();
                result.add(columnName);
            }
        }
        
        return result;
    }
    
    /**
     * Gets all indexes in the system
     * 
     * @return List of all indexes
     */
    public List<Map<String, String>> getAllIndexes() {
        List<Map<String, String>> result = new ArrayList<>();
        
        for (Map.Entry<String, BTreeIndex> entry : indexes.entrySet()) {
            String[] parts = entry.getKey().split("\\.");
            if (parts.length == 3) {
                Map<String, String> indexInfo = new HashMap<>();
                indexInfo.put("database", parts[0]);
                indexInfo.put("table", parts[1]);
                indexInfo.put("column", parts[2]);
                indexInfo.put("size", String.valueOf(entry.getValue().size()));
                result.add(indexInfo);
            }
        }
        
        return result;
    }
    
    /**
     * Rebuilds all indexes for a table
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param tableData The table data
     */
    public void rebuildTableIndexes(String databaseName, String tableName, Map<String, String> tableData) {
        List<BTreeIndex> tableIndexes = getTableIndexes(databaseName, tableName);
        
        for (BTreeIndex index : tableIndexes) {
            index.rebuild(tableData);
            index.saveToDisk();
        }
    }
    
    /**
     * Tracks query frequency for a column and creates an index if needed
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param columnName The column name
     */
    private void trackQueryFrequency(String databaseName, String tableName, String columnName) {
        if (!autoIndexingEnabled) {
            return;
        }
        
        String key = getIndexKey(databaseName, tableName, columnName);
        
        // If we already have an index for this column, no need to track
        if (indexes.containsKey(key)) {
            return;
        }
        
        // Increment query count
        AtomicInteger count = queryFrequency.computeIfAbsent(key, k -> new AtomicInteger(0));
        int newCount = count.incrementAndGet();
        
        // Check if we should create an index
        if (newCount >= autoIndexThreshold) {
            // Check if we have too many indexes for this table
            List<BTreeIndex> tableIndexes = getTableIndexes(databaseName, tableName);
            if (tableIndexes.size() < maxIndexesPerTable) {
                // Get table data and create the index
                try {
                    TableStorageObject tso = new TableStorageObject(databaseName, tableName);
                    createIndex(databaseName, tableName, columnName, tso.rows);
                    
                    // Reset the counter
                    queryFrequency.remove(key);
                } catch (Exception e) {
                    System.out.println("Error creating automatic index: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Gets the key used to identify an index
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param columnName The column name
     * @return The index key
     */
    private String getIndexKey(String databaseName, String tableName, String columnName) {
        return databaseName + "." + tableName + "." + columnName;
    }
    
    /**
     * Loads all indexes from disk
     */
    private void loadIndexes() {
        try {
            String metadataPath = Globals.data_path + "index_metadata.json";
            Path path = Paths.get(metadataPath);
            
            if (!Files.exists(path)) {
                return;
            }
            
            String content = new String(Files.readAllBytes(path));
            JSONObject metadata = new JSONObject(content);
            JSONArray indexList = metadata.getJSONArray("indexes");
            
            for (int i = 0; i < indexList.length(); i++) {
                JSONObject indexInfo = indexList.getJSONObject(i);
                String databaseName = indexInfo.getString("database");
                String tableName = indexInfo.getString("table");
                String columnName = indexInfo.getString("column");
                
                BTreeIndex index = BTreeIndex.loadFromDisk(databaseName, tableName, columnName);
                if (index != null) {
                    String key = getIndexKey(databaseName, tableName, columnName);
                    indexes.put(key, index);
                }
            }
            
            System.out.println("Loaded " + indexes.size() + " indexes");
        } catch (Exception e) {
            System.out.println("Error loading indexes: " + e.getMessage());
        }
    }
    
    /**
     * Saves index metadata to disk
     */
    private void saveIndexMetadata() {
        try {
            JSONObject metadata = new JSONObject();
            JSONArray indexList = new JSONArray();
            
            for (Map.Entry<String, BTreeIndex> entry : indexes.entrySet()) {
                String[] parts = entry.getKey().split("\\.");
                if (parts.length == 3) {
                    JSONObject indexInfo = new JSONObject();
                    indexInfo.put("database", parts[0]);
                    indexInfo.put("table", parts[1]);
                    indexInfo.put("column", parts[2]);
                    indexList.put(indexInfo);
                }
            }
            
            metadata.put("indexes", indexList);
            
            String metadataPath = Globals.data_path + "index_metadata.json";
            Files.write(Paths.get(metadataPath), metadata.toString().getBytes());
        } catch (Exception e) {
            System.out.println("Error saving index metadata: " + e.getMessage());
        }
    }
    
    /**
     * Sets whether automatic indexing is enabled
     * 
     * @param enabled true to enable, false to disable
     */
    public void setAutoIndexingEnabled(boolean enabled) {
        this.autoIndexingEnabled = enabled;
    }
    
    /**
     * Gets all indexes in the system
     *
     * Gets whether automatic indexing is enabled
     * 
     * @return true if enabled, false otherwise
     */
    public boolean isAutoIndexingEnabled() {
        return autoIndexingEnabled;
    }
    
    /**
     * Sets the threshold for automatic indexing
     * 
     * @param threshold The number of queries before auto-indexing
     */
    public void setAutoIndexThreshold(int threshold) {
        this.autoIndexThreshold = threshold;
    }
    
    /**
     * Gets the threshold for automatic indexing
     * 
     * @return The threshold
     */
    public int getAutoIndexThreshold() {
        return autoIndexThreshold;
    }
    
    /**
     * Sets the maximum number of indexes per table
     * 
     * @param max The maximum number
     */
    public void setMaxIndexesPerTable(int max) {
        this.maxIndexesPerTable = max;
    }
    
    /**
     * Gets the maximum number of indexes per table
     * 
     * @return The maximum number
     */
    public int getMaxIndexesPerTable() {
        return maxIndexesPerTable;
    }
}