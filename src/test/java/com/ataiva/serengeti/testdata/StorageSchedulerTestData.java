package com.ataiva.serengeti.testdata;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;
import com.ataiva.serengeti.storage.Storage;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Test data generation utilities for StorageScheduler tests.
 * This class provides methods for creating various types of test data
 * including databases, tables, and rows with different characteristics.
 */
public class StorageSchedulerTestData {

    /**
     * Creates a test database with the specified name.
     * If the name is null or empty, a random name will be generated.
     *
     * @param name The database name (optional)
     * @return The created DatabaseObject
     */
    public static DatabaseObject createTestDatabase(String name) {
        if (name == null || name.isEmpty()) {
            name = "test_db_" + UUID.randomUUID().toString().substring(0, 8);
        }
        
        DatabaseObject db = new DatabaseObject();
        db.createNew(name, new ArrayList<>());
        return db;
    }
    
    /**
     * Creates a test database with random tables.
     *
     * @param name The database name (optional)
     * @param tableCount The number of tables to create
     * @return The created DatabaseObject
     */
    public static DatabaseObject createTestDatabaseWithTables(String name, int tableCount) {
        DatabaseObject db = createTestDatabase(name);
        
        for (int i = 0; i < tableCount; i++) {
            String tableName = "test_table_" + i;
            db.createTable(tableName);
        }
        
        return db;
    }
    
    /**
     * Creates a test table storage object.
     *
     * @param dbName The database name
     * @param tableName The table name
     * @return The created TableStorageObject
     */
    public static TableStorageObject createTestTableStorage(String dbName, String tableName) {
        TableStorageObject tableStorage = new TableStorageObject();
        
        try {
            // Use reflection to set private fields
            java.lang.reflect.Field dbField = TableStorageObject.class.getDeclaredField("databaseName");
            dbField.setAccessible(true);
            dbField.set(tableStorage, dbName);
            
            java.lang.reflect.Field tableField = TableStorageObject.class.getDeclaredField("tableName");
            tableField.setAccessible(true);
            tableField.set(tableStorage, tableName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test table storage", e);
        }
        
        return tableStorage;
    }
    
    /**
     * Creates a test table replica object.
     *
     * @param dbName The database name
     * @param tableName The table name
     * @return The created TableReplicaObject
     */
    public static TableReplicaObject createTestTableReplica(String dbName, String tableName) {
        TableReplicaObject tableReplica = new TableReplicaObject();
        
        try {
            // Use reflection to set private fields
            java.lang.reflect.Field dbField = TableReplicaObject.class.getDeclaredField("databaseName");
            dbField.setAccessible(true);
            dbField.set(tableReplica, dbName);
            
            java.lang.reflect.Field tableField = TableReplicaObject.class.getDeclaredField("tableName");
            tableField.setAccessible(true);
            tableField.set(tableReplica, tableName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test table replica", e);
        }
        
        return tableReplica;
    }
    
    /**
     * Populates a table storage object with random rows.
     *
     * @param tableStorage The table storage object to populate
     * @param rowCount The number of rows to create
     * @return The populated TableStorageObject
     */
    public static TableStorageObject populateTableStorage(TableStorageObject tableStorage, int rowCount) {
        Random random = new Random();
        
        for (int i = 0; i < rowCount; i++) {
            String rowId = UUID.randomUUID().toString();
            JSONObject rowData = new JSONObject();
            rowData.put("id", i);
            rowData.put("name", "Test Row " + i);
            rowData.put("value", random.nextInt(1000));
            rowData.put("timestamp", System.currentTimeMillis());
            
            tableStorage.insert(rowId, rowData);
        }
        
        return tableStorage;
    }
    
    /**
     * Populates a table replica object with random rows.
     *
     * @param tableReplica The table replica object to populate
     * @param rowCount The number of rows to create
     * @return The populated TableReplicaObject
     */
    public static TableReplicaObject populateTableReplica(TableReplicaObject tableReplica, int rowCount) {
        Random random = new Random();
        
        for (int i = 0; i < rowCount; i++) {
            String rowId = UUID.randomUUID().toString();
            JSONObject rowData = new JSONObject();
            rowData.put("id", i);
            rowData.put("primary", "node" + random.nextInt(5));
            rowData.put("secondary", "node" + random.nextInt(5));
            rowData.put("timestamp", System.currentTimeMillis());
            
            tableReplica.insertOrReplace(rowId, rowData);
        }
        
        return tableReplica;
    }
    
    /**
     * Creates a complete test environment with databases, tables, and data.
     *
     * @param dbCount The number of databases to create
     * @param tablesPerDb The number of tables per database
     * @param rowsPerTable The number of rows per table
     * @return A map containing the created test objects
     */
    public static Map<String, Object> createTestEnvironment(int dbCount, int tablesPerDb, int rowsPerTable) {
        Map<String, Object> testEnv = new HashMap<>();
        Map<String, DatabaseObject> databases = new HashMap<>();
        Map<String, TableStorageObject> tableStorageObjects = new HashMap<>();
        Map<String, TableReplicaObject> tableReplicaObjects = new HashMap<>();
        
        for (int i = 0; i < dbCount; i++) {
            String dbName = "test_db_" + i;
            DatabaseObject db = createTestDatabaseWithTables(dbName, tablesPerDb);
            databases.put(dbName, db);
            
            for (int j = 0; j < tablesPerDb; j++) {
                String tableName = "test_table_" + j;
                String key = dbName + "#" + tableName;
                
                TableStorageObject tableStorage = createTestTableStorage(dbName, tableName);
                populateTableStorage(tableStorage, rowsPerTable);
                tableStorageObjects.put(key, tableStorage);
                
                TableReplicaObject tableReplica = createTestTableReplica(dbName, tableName);
                populateTableReplica(tableReplica, rowsPerTable);
                tableReplicaObjects.put(key, tableReplica);
            }
        }
        
        testEnv.put("databases", databases);
        testEnv.put("tableStorageObjects", tableStorageObjects);
        testEnv.put("tableReplicaObjects", tableReplicaObjects);
        
        return testEnv;
    }
    
    /**
     * Sets up the Storage static fields with test data.
     *
     * @param dbCount The number of databases to create
     * @param tablesPerDb The number of tables per database
     * @param rowsPerTable The number of rows per table
     * @return A map containing the original Storage state for restoration
     */
    public static Map<String, Object> setupStorageWithTestData(int dbCount, int tablesPerDb, int rowsPerTable) {
        // Save original state
        Map<String, Object> originalState = new HashMap<>();
        originalState.put("databases", Storage.databases);
        originalState.put("tableStorageObjects", Storage.tableStorageObjects);
        originalState.put("tableReplicaObjects", Storage.tableReplicaObjects);
        originalState.put("networkOnline", Network.online);
        
        // Create test environment
        Map<String, Object> testEnv = createTestEnvironment(dbCount, tablesPerDb, rowsPerTable);
        
        // Set up Storage with test data
        Storage.databases = (Map<String, DatabaseObject>) testEnv.get("databases");
        Storage.tableStorageObjects = (Map<String, TableStorageObject>) testEnv.get("tableStorageObjects");
        Storage.tableReplicaObjects = (Map<String, TableReplicaObject>) testEnv.get("tableReplicaObjects");
        Network.online = true;
        
        return originalState;
    }
    
    /**
     * Restores the original Storage state.
     *
     * @param originalState The original state to restore
     */
    public static void restoreStorageState(Map<String, Object> originalState) {
        Storage.databases = (Map<String, DatabaseObject>) originalState.get("databases");
        Storage.tableStorageObjects = (Map<String, TableStorageObject>) originalState.get("tableStorageObjects");
        Storage.tableReplicaObjects = (Map<String, TableReplicaObject>) originalState.get("tableReplicaObjects");
        Network.online = (boolean) originalState.get("networkOnline");
    }
    
    /**
     * Creates a database with special characters in its name.
     *
     * @return The created DatabaseObject
     */
    public static DatabaseObject createSpecialCharacterDatabase() {
        String name = "special#db$name";
        DatabaseObject db = createTestDatabase(name);
        db.createTable("special@table");
        return db;
    }
    
    /**
     * Creates a database with a very large number of tables.
     *
     * @param tableCount The number of tables to create
     * @return The created DatabaseObject
     */
    public static DatabaseObject createLargeDatabase(int tableCount) {
        return createTestDatabaseWithTables("large_db", tableCount);
    }
    
    /**
     * Creates a table with a very large number of rows.
     *
     * @param rowCount The number of rows to create
     * @return The created TableStorageObject
     */
    public static TableStorageObject createLargeTable(int rowCount) {
        TableStorageObject tableStorage = createTestTableStorage("large_db", "large_table");
        return populateTableStorage(tableStorage, rowCount);
    }
    
    /**
     * Creates a temporary directory for test data.
     *
     * @return The path to the temporary directory
     * @throws IOException If an error occurs
     */
    public static String createTempDataDirectory() throws IOException {
        Path tempDir = Files.createTempDirectory("storage_scheduler_test_");
        return tempDir.toString() + File.separator;
    }
    
    /**
     * Ensures the directory structure exists for a database and table.
     *
     * @param dataPath The base data path
     * @param dbName The database name
     * @param tableName The table name
     * @throws IOException If an error occurs
     */
    public static void ensureDirectoryStructure(String dataPath, String dbName, String tableName) throws IOException {
        Path dbPath = Paths.get(dataPath + dbName);
        Path tablePath = Paths.get(dataPath + dbName + File.separator + tableName);
        
        if (!Files.exists(dbPath)) {
            Files.createDirectories(dbPath);
        }
        
        if (!Files.exists(tablePath)) {
            Files.createDirectories(tablePath);
        }
    }
}