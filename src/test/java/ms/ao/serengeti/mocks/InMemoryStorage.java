package ms.ao.serengeti.mocks;

import ms.ao.serengeti.storage.Storage;
import ms.ao.serengeti.storage.StorageResponseObject;
import org.json.JSONObject;

import java.util.*;

/**
 * Mock Storage implementation for fast tests.
 * This class extends Storage but overrides all methods to use in-memory data structures.
 */
public class InMemoryStorage extends Storage {
    
    private Map<String, Map<String, Map<String, JSONObject>>> inMemoryData = new HashMap<>();
    
    /**
     * Constructor that doesn't call the parent constructor.
     */
    public InMemoryStorage() {
        // Don't call super() to avoid file system operations
    }
    
    /**
     * Creates a database in memory.
     */
    public boolean createDatabase(String db) {
        return createDatabase(db, false);
    }
    
    /**
     * Creates a database in memory.
     */
    @Override
    public boolean createDatabase(String db, boolean isReplicationAction) {
        if (db == null || db.isEmpty()) {
            return false;
        }
        
        if (inMemoryData.containsKey(db)) {
            return false; // Database already exists
        }
        
        inMemoryData.put(db, new HashMap<>());
        return true;
    }
    
    /**
     * Creates a table in memory.
     */
    public boolean createTable(String db, String table) {
        return createTable(db, table, false);
    }
    
    /**
     * Creates a table in memory.
     */
    @Override
    public boolean createTable(String db, String table, boolean isReplicationAction) {
        if (db == null || table == null || db.isEmpty() || table.isEmpty()) {
            return false;
        }
        
        if (!inMemoryData.containsKey(db)) {
            return false; // Database doesn't exist
        }
        
        if (inMemoryData.get(db).containsKey(table)) {
            return false; // Table already exists
        }
        
        inMemoryData.get(db).put(table, new HashMap<>());
        return true;
    }
    
    /**
     * Drops a database from memory.
     */
    @Override
    public boolean dropDatabase(String db) {
        if (db == null || db.isEmpty()) {
            return false;
        }
        
        if (!inMemoryData.containsKey(db)) {
            return false; // Database doesn't exist
        }
        
        inMemoryData.remove(db);
        return true;
    }
    
    /**
     * Drops a table from memory.
     */
    @Override
    public boolean dropTable(String db, String table) {
        if (db == null || table == null || db.isEmpty() || table.isEmpty()) {
            return false;
        }
        
        if (!inMemoryData.containsKey(db)) {
            return false; // Database doesn't exist
        }
        
        if (!inMemoryData.get(db).containsKey(table)) {
            return false; // Table doesn't exist
        }
        
        inMemoryData.get(db).remove(table);
        return true;
    }
    
    /**
     * Checks if a database exists in memory.
     */
    @Override
    public boolean databaseExists(String db) {
        if (db == null || db.isEmpty()) {
            return false;
        }
        
        return inMemoryData.containsKey(db);
    }
    
    /**
     * Checks if a table exists in memory.
     */
    @Override
    public boolean tableExists(String db, String table) {
        if (db == null || table == null || db.isEmpty() || table.isEmpty()) {
            return false;
        }
        
        if (!inMemoryData.containsKey(db)) {
            return false; // Database doesn't exist
        }
        
        return inMemoryData.get(db).containsKey(table);
    }
    
    /**
     * Gets a list of databases in memory.
     */
    @Override
    public List<String> getDatabases() {
        return new ArrayList<>(inMemoryData.keySet());
    }
    
    /**
     * Gets a list of tables in a database in memory.
     */
    @Override
    public List<String> getTables(String db) {
        if (db == null || db.isEmpty() || !inMemoryData.containsKey(db)) {
            return new ArrayList<>();
        }
        
        return new ArrayList<>(inMemoryData.get(db).keySet());
    }
    
    /**
     * Inserts data into a table in memory.
     */
    @Override
    public StorageResponseObject insert(String db, String table, JSONObject json) {
        if (db == null || table == null || json == null || db.isEmpty() || table.isEmpty()) {
            StorageResponseObject sro = new StorageResponseObject();
            sro.success = false;
            return sro;
        }
        
        // Create database and table if they don't exist
        if (!inMemoryData.containsKey(db)) {
            createDatabase(db);
        }
        
        if (!inMemoryData.get(db).containsKey(table)) {
            createTable(db, table);
        }
        
        // Generate a UUID for the row
        String rowId = UUID.randomUUID().toString();
        
        // Add UUID to the data
        JSONObject jsonWithUuid = new JSONObject(json.toString());
        jsonWithUuid.put("__uuid", rowId);
        
        // Store the data
        inMemoryData.get(db).get(table).put(rowId, jsonWithUuid);
        
        // Create response
        StorageResponseObject sro = new StorageResponseObject();
        sro.success = true;
        sro.rowId = rowId;
        sro.primary = "primary-node";
        sro.secondary = "secondary-node";
        
        return sro;
    }
    
    /**
     * Selects data from a table in memory.
     */
    @Override
    public List<String> select(String db, String table, String columns, String whereColumn, String whereValue) {
        if (db == null || table == null || db.isEmpty() || table.isEmpty()) {
            return null;
        }
        
        if (!inMemoryData.containsKey(db) || !inMemoryData.get(db).containsKey(table)) {
            return null;
        }
        
        List<String> results = new ArrayList<>();
        
        // Get all rows if no where clause
        if (whereColumn == null || whereColumn.isEmpty()) {
            for (JSONObject json : inMemoryData.get(db).get(table).values()) {
                results.add(json.toString());
            }
            return results;
        }
        
        // Filter by where clause
        for (JSONObject json : inMemoryData.get(db).get(table).values()) {
            if (json.has(whereColumn) && json.get(whereColumn).toString().equals(whereValue)) {
                results.add(json.toString());
            }
        }
        
        return results;
    }
    
    /**
     * Updates data in a table in memory.
     */
    @Override
    public boolean update(String db, String table, String updateColumn, String updateValue, String whereColumn, String whereValue) {
        if (db == null || table == null || updateColumn == null || whereColumn == null ||
            db.isEmpty() || table.isEmpty() || updateColumn.isEmpty() || whereColumn.isEmpty()) {
            return false;
        }
        
        if (!inMemoryData.containsKey(db) || !inMemoryData.get(db).containsKey(table)) {
            return false;
        }
        
        boolean updated = false;
        
        // Update matching rows
        for (JSONObject json : inMemoryData.get(db).get(table).values()) {
            if (json.has(whereColumn) && json.get(whereColumn).toString().equals(whereValue)) {
                json.put(updateColumn, updateValue);
                updated = true;
            }
        }
        
        return updated;
    }
    
    /**
     * Deletes data from a table in memory.
     */
    @Override
    public boolean delete(String db, String table, String whereColumn, String whereValue) {
        if (db == null || table == null || whereColumn == null ||
            db.isEmpty() || table.isEmpty() || whereColumn.isEmpty()) {
            return false;
        }
        
        if (!inMemoryData.containsKey(db) || !inMemoryData.get(db).containsKey(table)) {
            return false;
        }
        
        // Find rows to delete
        List<String> rowsToDelete = new ArrayList<>();
        
        for (Map.Entry<String, JSONObject> entry : inMemoryData.get(db).get(table).entrySet()) {
            JSONObject json = entry.getValue();
            if (json.has(whereColumn) && json.get(whereColumn).toString().equals(whereValue)) {
                rowsToDelete.add(entry.getKey());
            }
        }
        
        // Delete the rows
        for (String rowId : rowsToDelete) {
            inMemoryData.get(db).get(table).remove(rowId);
        }
        
        return !rowsToDelete.isEmpty();
    }
    
    /**
     * Deletes all data in memory.
     */
    @Override
    public void deleteEverything() {
        inMemoryData.clear();
    }
    
    /**
     * Override loadMetaDatabasesToMemory to do nothing.
     */
    @Override
    public void loadMetaDatabasesToMemory() {
        // Do nothing - we're using in-memory data
    }
}