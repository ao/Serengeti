package ms.ao.serengeti.mocks;

import ms.ao.serengeti.storage.Storage;
import ms.ao.serengeti.storage.StorageResponseObject;
import org.json.JSONObject;

import java.util.*;

/**
 * Mock implementation of the Storage class for testing.
 */
public class MockStorage extends Storage {
    
    private Map<String, Map<String, Map<String, JSONObject>>> mockData = new HashMap<>();
    private Map<String, Boolean> methodCalls = new HashMap<>();
    private Map<String, Integer> methodCallCounts = new HashMap<>();
    
    /**
     * Constructor.
     */
    public MockStorage() {
        super();
        // Override initialization to avoid actual file operations
        resetMethodCalls();
    }
    
    /**
     * Resets the method call tracking.
     */
    public void resetMethodCalls() {
        methodCalls.clear();
        methodCallCounts.clear();
        
        // Initialize method call tracking
        String[] methods = {
            "createDatabase", "dropDatabase", "createTable", "dropTable",
            "insert", "select", "update", "delete", "deleteEverything"
        };
        
        for (String method : methods) {
            methodCalls.put(method, false);
            methodCallCounts.put(method, 0);
        }
    }
    
    /**
     * Records a method call.
     * 
     * @param methodName The method name
     */
    private void recordMethodCall(String methodName) {
        methodCalls.put(methodName, true);
        methodCallCounts.put(methodName, methodCallCounts.getOrDefault(methodName, 0) + 1);
    }
    
    /**
     * Checks if a method was called.
     * 
     * @param methodName The method name
     * @return true if the method was called, false otherwise
     */
    public boolean wasMethodCalled(String methodName) {
        return methodCalls.getOrDefault(methodName, false);
    }
    
    /**
     * Gets the number of times a method was called.
     * 
     * @param methodName The method name
     * @return The number of times the method was called
     */
    public int getMethodCallCount(String methodName) {
        return methodCallCounts.getOrDefault(methodName, 0);
    }
    
    /**
     * Overrides the createDatabase method.
     * 
     * @param db The database name
     * @return true if successful, false otherwise
     */
    @Override
    public boolean createDatabase(String db) {
        return createDatabase(db, false);
    }
    
    /**
     * Overrides the createDatabase method.
     * 
     * @param db The database name
     * @param isReplicationAction Whether this is a replication action
     * @return true if successful, false otherwise
     */
    @Override
    public boolean createDatabase(String db, boolean isReplicationAction) {
        recordMethodCall("createDatabase");
        
        // Throw NullPointerException for null database name to match test expectations
        if (db == null) {
            throw new NullPointerException("Database name cannot be null");
        }
        
        if (db.isEmpty()) {
            return false;
        }
        
        if (mockData.containsKey(db)) {
            return false;
        }
        
        mockData.put(db, new HashMap<>());
        return true;
    }
    
    /**
     * Overrides the dropDatabase method.
     * 
     * @param db The database name
     * @return true if successful, false otherwise
     */
    @Override
    public boolean dropDatabase(String db) {
        return dropDatabase(db, false);
    }
    
    /**
     * Overrides the dropDatabase method.
     * 
     * @param db The database name
     * @param isReplicationAction Whether this is a replication action
     * @return true if successful, false otherwise
     */
    @Override
    public boolean dropDatabase(String db, boolean isReplicationAction) {
        recordMethodCall("dropDatabase");
        
        if (db == null || db.isEmpty()) {
            return false;
        }
        
        if (!mockData.containsKey(db)) {
            return false;
        }
        
        mockData.remove(db);
        return true;
    }
    
    /**
     * Overrides the createTable method.
     * 
     * @param db The database name
     * @param table The table name
     * @return true if successful, false otherwise
     */
    @Override
    public boolean createTable(String db, String table) {
        return createTable(db, table, false);
    }
    
    /**
     * Overrides the createTable method.
     * 
     * @param db The database name
     * @param table The table name
     * @param isReplicationAction Whether this is a replication action
     * @return true if successful, false otherwise
     */
    @Override
    public boolean createTable(String db, String table, boolean isReplicationAction) {
        recordMethodCall("createTable");
        
        if (db == null || db.isEmpty() || table == null || table.isEmpty()) {
            return false;
        }
        
        if (!mockData.containsKey(db)) {
            return false;
        }
        
        if (mockData.get(db).containsKey(table)) {
            return false;
        }
        
        mockData.get(db).put(table, new HashMap<>());
        return true;
    }
    
    /**
     * Overrides the dropTable method.
     * 
     * @param db The database name
     * @param table The table name
     * @return true if successful, false otherwise
     */
    @Override
    public boolean dropTable(String db, String table) {
        return dropTable(db, table, false);
    }
    
    /**
     * Overrides the dropTable method.
     * 
     * @param db The database name
     * @param table The table name
     * @param isReplicationAction Whether this is a replication action
     * @return true if successful, false otherwise
     */
    @Override
    public boolean dropTable(String db, String table, boolean isReplicationAction) {
        recordMethodCall("dropTable");
        
        if (db == null || db.isEmpty() || table == null || table.isEmpty()) {
            return false;
        }
        
        if (!mockData.containsKey(db) || !mockData.get(db).containsKey(table)) {
            return false;
        }
        
        mockData.get(db).remove(table);
        return true;
    }
    
    /**
     * Overrides the insert method.
     * 
     * @param db The database name
     * @param table The table name
     * @param json The data to insert
     * @return A StorageResponseObject
     */
    @Override
    public StorageResponseObject insert(String db, String table, JSONObject json) {
        return insert(db, table, json, false);
    }
    
    /**
     * Overrides the insert method.
     * 
     * @param db The database name
     * @param table The table name
     * @param json The data to insert
     * @param isReplicationAction Whether this is a replication action
     * @return A StorageResponseObject
     */
    @Override
    public StorageResponseObject insert(String db, String table, JSONObject json, boolean isReplicationAction) {
        recordMethodCall("insert");
        
        // Throw NullPointerException for null data to match test expectations
        if (json == null) {
            throw new NullPointerException("JSON data cannot be null");
        }
        
        StorageResponseObject sro = new StorageResponseObject();
        
        if (db == null || db.isEmpty() || table == null || table.isEmpty()) {
            return sro;
        }
        
        // Create database and table if they don't exist
        if (!mockData.containsKey(db)) {
            boolean created = createDatabase(db);
            if (!created) {
                // If we couldn't create the database, return a failed response
                sro.success = false;
                return sro;
            }
        }
        
        if (!mockData.get(db).containsKey(table)) {
            boolean created = createTable(db, table);
            if (!created) {
                // If we couldn't create the table, return a failed response
                sro.success = false;
                return sro;
            }
        }
        
        String rowId = UUID.randomUUID().toString();
        
        // Add UUID to the data if it doesn't have one
        JSONObject dataWithUuid = new JSONObject(json.toString());
        if (!dataWithUuid.has("__uuid")) {
            dataWithUuid.put("__uuid", rowId);
        } else {
            rowId = dataWithUuid.getString("__uuid");
        }
        
        mockData.get(db).get(table).put(rowId, dataWithUuid);
        
        sro.rowId = rowId;
        sro.success = true;
        sro.primary = "mock_primary_node";
        sro.secondary = "mock_secondary_node";
        
        return sro;
    }
    
    /**
     * Overrides the select method.
     * 
     * @param db The database name
     * @param table The table name
     * @param selectWhat The columns to select
     * @param col The column to filter on
     * @param val The value to filter on
     * @return A list of matching records
     */
    @Override
    public List<String> select(String db, String table, String selectWhat, String col, String val) {
        recordMethodCall("select");
        
        if (db == null || db.isEmpty() || table == null || table.isEmpty()) {
            return null;
        }
        
        if (!mockData.containsKey(db) || !mockData.get(db).containsKey(table)) {
            return null;
        }
        
        List<String> results = new ArrayList<>();
        
        // Ensure we always return a non-null list
        if (mockData.get(db).get(table).isEmpty()) {
            return results;
        }
        
        for (JSONObject record : mockData.get(db).get(table).values()) {
            // If no filter is specified, include all records
            if (col == null || col.isEmpty()) {
                results.add(record.toString());
                continue;
            }
            
            // If the record has the column and the value matches, include it
            if (record.has(col) && record.get(col).toString().equals(val)) {
                results.add(record.toString());
            }
        }
        
        return results;
    }
    
    /**
     * Overrides the update method.
     * 
     * @param db The database name
     * @param table The table name
     * @param update_key The column to update
     * @param update_val The new value
     * @param where_col The column to filter on
     * @param where_val The value to filter on
     * @return true if successful, false otherwise
     */
    @Override
    public boolean update(String db, String table, String update_key, String update_val, String where_col, String where_val) {
        return update(db, table, update_key, update_val, where_col, where_val, false);
    }
    
    /**
     * Overrides the update method.
     * 
     * @param db The database name
     * @param table The table name
     * @param update_key The column to update
     * @param update_val The new value
     * @param where_col The column to filter on
     * @param where_val The value to filter on
     * @param isReplicationAction Whether this is a replication action
     * @return true if successful, false otherwise
     */
    @Override
    public boolean update(String db, String table, String update_key, String update_val, String where_col, String where_val, boolean isReplicationAction) {
        recordMethodCall("update");
        
        if (db == null || db.isEmpty() || table == null || table.isEmpty() ||
            update_key == null || update_key.isEmpty() ||
            where_col == null || where_col.isEmpty()) {
            return false;
        }
        
        // Create database and table if they don't exist to match test expectations
        if (!mockData.containsKey(db)) {
            createDatabase(db);
        }
        
        if (!mockData.get(db).containsKey(table)) {
            createTable(db, table);
            // Since we just created the table, there's nothing to update
            return false;
        }
        
        boolean updated = false;
        
        for (JSONObject record : mockData.get(db).get(table).values()) {
            if (record.has(where_col) && record.get(where_col).toString().equals(where_val)) {
                record.put(update_key, update_val);
                updated = true;
            }
        }
        
        return updated;
    }
    
    /**
     * Overrides the delete method.
     * 
     * @param db The database name
     * @param table The table name
     * @param where_col The column to filter on
     * @param where_val The value to filter on
     * @return true if successful, false otherwise
     */
    @Override
    public boolean delete(String db, String table, String where_col, String where_val) {
        return delete(db, table, where_col, where_val, false);
    }
    
    /**
     * Overrides the delete method.
     * 
     * @param db The database name
     * @param table The table name
     * @param where_col The column to filter on
     * @param where_val The value to filter on
     * @param isReplicationAction Whether this is a replication action
     * @return true if successful, false otherwise
     */
    @Override
    public boolean delete(String db, String table, String where_col, String where_val, boolean isReplicationAction) {
        recordMethodCall("delete");
        
        if (db == null || db.isEmpty() || table == null || table.isEmpty() ||
            where_col == null || where_col.isEmpty()) {
            return false;
        }
        
        // Create database and table if they don't exist to match test expectations
        if (!mockData.containsKey(db)) {
            createDatabase(db);
        }
        
        if (!mockData.get(db).containsKey(table)) {
            createTable(db, table);
            // Since we just created the table, there's nothing to delete
            return false;
        }
        
        List<String> keysToRemove = new ArrayList<>();
        
        for (Map.Entry<String, JSONObject> entry : mockData.get(db).get(table).entrySet()) {
            JSONObject record = entry.getValue();
            if (record.has(where_col) && record.get(where_col).toString().equals(where_val)) {
                keysToRemove.add(entry.getKey());
            }
        }
        
        for (String key : keysToRemove) {
            mockData.get(db).get(table).remove(key);
        }
        
        return !keysToRemove.isEmpty();
    }
    
    /**
     * Overrides the deleteEverything method.
     */
    @Override
    public void deleteEverything() {
        recordMethodCall("deleteEverything");
        mockData.clear();
    }
    
    /**
     * Overrides the getDatabases method.
     * 
     * @return A list of database names
     */
    @Override
    public List<String> getDatabases() {
        return new ArrayList<>(mockData.keySet());
    }
    
    /**
     * Overrides the getTables method.
     * 
     * @param db The database name
     * @return A list of table names
     */
    @Override
    public List<String> getTables(String db) {
        if (db == null || db.isEmpty() || !mockData.containsKey(db)) {
            return new ArrayList<>();
        }
        
        return new ArrayList<>(mockData.get(db).keySet());
    }
    
    /**
     * Overrides the databaseExists method.
     * 
     * @param db The database name
     * @return true if the database exists, false otherwise
     */
    @Override
    public boolean databaseExists(String db) {
        return db != null && !db.isEmpty() && mockData.containsKey(db);
    }
    
    /**
     * Overrides the tableExists method.
     * 
     * @param db The database name
     * @param table The table name
     * @return true if the table exists, false otherwise
     */
    @Override
    public boolean tableExists(String db, String table) {
        return db != null && !db.isEmpty() && table != null && !table.isEmpty() && 
               mockData.containsKey(db) && mockData.get(db).containsKey(table);
    }
    
    /**
     * Gets the mock data.
     * 
     * @return The mock data
     */
    public Map<String, Map<String, Map<String, JSONObject>>> getMockData() {
        return mockData;
    }
}