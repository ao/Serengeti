package com.ataiva.serengeti.integration;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageScheduler;
import com.ataiva.serengeti.utils.TestBase;
import org.json.JSONObject;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the StorageScheduler component.
 * 
 * These tests verify that StorageScheduler correctly interacts with other components
 * in the system, including Storage, Network, DatabaseObject, TableStorageObject,
 * TableReplicaObject, and the file system.
 */
@DisplayName("StorageScheduler Integration Tests")
@Tag("integration")
@Tag("storage")
class StorageSchedulerIntegrationTest extends TestBase {
    
    private StorageScheduler storageScheduler;
    private String testDb;
    private String testTable;
    private String originalDataPath;
    private boolean originalNetworkOnline;
    
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp(); // Initialize TestBase components
        
        // Save original network state
        originalNetworkOnline = Network.online;
        
        // Set network to online for tests
        Network.online = true;
        
        // Create a StorageScheduler instance
        storageScheduler = new StorageScheduler();
        
        // Create unique test database and table names for each test
        testDb = generateRandomDatabaseName();
        testTable = generateRandomTableName();
        
        // Create the test database and table
        storage.createDatabase(testDb);
        storage.createTable(testDb, testTable);
    }
    
    @AfterEach
    public void tearDown() throws Exception {
        // Restore original network state
        Network.online = originalNetworkOnline;
        
        // Reset the running flag
        StorageScheduler.running = false;
        
        super.tearDown(); // Clean up TestBase components
    }
    
    /**
     * Test that StorageScheduler correctly interacts with Storage component
     * to persist database objects to disk.
     */
    @Test
    @DisplayName("Should persist database objects to disk through Storage component")
    void testStorageIntegration() throws Exception {
        // Insert some data into the test table
        JSONObject testData = new JSONObject();
        testData.put("key1", "value1");
        testData.put("key2", "value2");
        
        String rowId = storage.tableStorageObjects.get(testDb + "#" + testTable).insert(testData);
        
        // Perform persistence operation
        boolean result = storageScheduler.performPersistToDisk();
        
        // Verify persistence was successful
        assertTrue(result, "Persistence operation should succeed");
        
        // Verify the database metadata file exists
        Path dbMetaFile = Paths.get(testDataPath + testDb + Globals.meta_extention);
        assertTrue(Files.exists(dbMetaFile), "Database metadata file should exist");
        
        // Verify the table storage file exists
        Path tableStorageFile = Paths.get(testDataPath + testDb + "/" + testTable + "/" + Globals.storage_filename);
        assertTrue(Files.exists(tableStorageFile), "Table storage file should exist");
        
        // Verify the table replica file exists
        Path tableReplicaFile = Paths.get(testDataPath + testDb + "/" + testTable + "/" + Globals.replica_filename);
        assertTrue(Files.exists(tableReplicaFile), "Table replica file should exist");
        
        // Create a new Storage instance to load from disk
        Storage newStorage = new Storage();
        
        // Verify the data was correctly persisted
        TableStorageObject loadedTSO = newStorage.tableStorageObjects.get(testDb + "#" + testTable);
        assertNotNull(loadedTSO, "Loaded TableStorageObject should not be null");
        
        JSONObject loadedData = loadedTSO.getJsonFromRowId(rowId);
        assertNotNull(loadedData, "Loaded data should not be null");
        assertEquals("value1", loadedData.getString("key1"), "Loaded data should contain correct values");
        assertEquals("value2", loadedData.getString("key2"), "Loaded data should contain correct values");
    }
    
    /**
     * Test that StorageScheduler respects the Network.online flag.
     */
    @Test
    @DisplayName("Should respect Network.online flag")
    void testNetworkIntegration() {
        // Set network to offline
        Network.online = false;
        
        // Attempt persistence operation
        boolean result = storageScheduler.performPersistToDisk();
        
        // Verify persistence was skipped
        assertFalse(result, "Persistence operation should be skipped when network is offline");
        
        // Set network back to online
        Network.online = true;
        
        // Attempt persistence operation again
        result = storageScheduler.performPersistToDisk();
        
        // Verify persistence was successful
        assertTrue(result, "Persistence operation should succeed when network is online");
    }
    
    /**
     * Test integration with DatabaseObject, TableStorageObject, and TableReplicaObject.
     */
    @Test
    @DisplayName("Should correctly persist DatabaseObject, TableStorageObject, and TableReplicaObject")
    void testSchemaObjectsIntegration() throws Exception {
        // Insert some data into the test table
        JSONObject testData = new JSONObject();
        testData.put("name", "test_record");
        testData.put("value", 42);
        
        String rowId = storage.tableStorageObjects.get(testDb + "#" + testTable).insert(testData);
        
        // Add a replica entry
        JSONObject replicaData = new JSONObject();
        replicaData.put("primary", "node1");
        replicaData.put("secondary", "node2");
        
        storage.tableReplicaObjects.get(testDb + "#" + testTable).insertOrReplace(rowId, replicaData);
        
        // Perform persistence operation
        boolean result = storageScheduler.performPersistToDisk();
        
        // Verify persistence was successful
        assertTrue(result, "Persistence operation should succeed");
        
        // Create new instances to load from disk
        DatabaseObject loadedDBO = new DatabaseObject().loadExisting(Paths.get(testDataPath + testDb + Globals.meta_extention));
        TableStorageObject loadedTSO = new TableStorageObject(testDb, testTable);
        TableReplicaObject loadedTRO = new TableReplicaObject(testDb, testTable);
        
        // Verify database object was correctly persisted
        assertEquals(testDb, loadedDBO.name, "Database name should be correctly persisted");
        assertTrue(loadedDBO.tables.contains(testTable), "Database tables list should contain the test table");
        
        // Verify table storage object was correctly persisted
        JSONObject loadedData = loadedTSO.getJsonFromRowId(rowId);
        assertNotNull(loadedData, "Loaded data should not be null");
        assertEquals("test_record", loadedData.getString("name"), "Loaded data should contain correct values");
        assertEquals(42, loadedData.getInt("value"), "Loaded data should contain correct values");
        
        // Verify table replica object was correctly persisted
        JSONObject loadedReplicaData = loadedTRO.getRowReplica(rowId);
        assertNotNull(loadedReplicaData, "Loaded replica data should not be null");
        assertEquals("node1", loadedReplicaData.getString("primary"), "Loaded replica data should contain correct values");
        assertEquals("node2", loadedReplicaData.getString("secondary"), "Loaded replica data should contain correct values");
    }
    
    /**
     * Test file system integration with different path configurations.
     */
    @Test
    @DisplayName("Should work with different Globals.data_path configurations")
    void testFileSystemIntegration() throws Exception {
        // Save original data path
        String originalPath = Globals.data_path;
        
        try {
            // Create a new data path
            Path newDataPath = Files.createTempDirectory("serengeti_alt_test_");
            String newPath = newDataPath.toString() + "/";
            
            // Set the new data path
            setGlobalsDataPath(newPath);
            
            // Create a new database and table
            String altDb = generateRandomDatabaseName();
            String altTable = generateRandomTableName();
            
            // Create the database and table in the new location
            storage = new Storage(); // Reinitialize storage with new path
            storage.createDatabase(altDb);
            storage.createTable(altDb, altTable);
            
            // Insert some data
            JSONObject testData = new JSONObject();
            testData.put("alt_key", "alt_value");
            
            storage.tableStorageObjects.get(altDb + "#" + altTable).insert(testData);
            
            // Perform persistence operation
            boolean result = storageScheduler.performPersistToDisk();
            
            // Verify persistence was successful
            assertTrue(result, "Persistence operation should succeed with alternative data path");
            
            // Verify the files were created in the new location
            Path dbMetaFile = Paths.get(newPath + altDb + Globals.meta_extention);
            assertTrue(Files.exists(dbMetaFile), "Database metadata file should exist in alternative location");
            
            Path tableDir = Paths.get(newPath + altDb + "/" + altTable);
            assertTrue(Files.exists(tableDir), "Table directory should exist in alternative location");
            
        } finally {
            // Restore original data path
            setGlobalsDataPath(originalPath);
        }
    }
    
    /**
     * Test that StorageScheduler handles concurrent operations correctly.
     */
    @Test
    @DisplayName("Should handle concurrent operations correctly")
    void testConcurrentOperations() throws Exception {
        // Set up initial data
        JSONObject testData = new JSONObject();
        testData.put("concurrent_test", "initial_value");
        
        String rowId = storage.tableStorageObjects.get(testDb + "#" + testTable).insert(testData);
        
        // Start a persistence operation
        StorageScheduler.running = true;
        
        // Attempt another persistence operation while the first one is running
        boolean result = storageScheduler.performPersistToDisk();
        
        // Verify the second operation was skipped
        assertFalse(result, "Concurrent persistence operation should be skipped");
        
        // Complete the first operation
        StorageScheduler.running = false;
        
        // Now the operation should succeed
        result = storageScheduler.performPersistToDisk();
        assertTrue(result, "Persistence operation should succeed after previous operation completes");
    }
    
    /**
     * Test that StorageScheduler correctly handles error conditions.
     */
    @Test
    @DisplayName("Should handle error conditions gracefully")
    void testErrorHandling() throws Exception {
        // Create a database with a problematic name (containing invalid file system characters)
        String problematicDb = "test_db_with?invalid*chars";
        
        try {
            // Attempt to create the database
            storage.createDatabase(problematicDb);
            storage.createTable(problematicDb, testTable);
            
            // Insert some data
            JSONObject testData = new JSONObject();
            testData.put("error_test", "error_value");
            
            storage.tableStorageObjects.get(problematicDb + "#" + testTable).insert(testData);
            
            // Perform persistence operation
            storageScheduler.performPersistToDisk();
            
            // The test should fail if no exception is thrown
            fail("Should throw an exception when persisting database with invalid characters");
        } catch (Exception e) {
            // Verify the running flag was reset
            assertFalse(StorageScheduler.running, "Running flag should be reset after error");
        }
    }
}