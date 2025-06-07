package com.ataiva.serengeti.integration;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.storage.IStorage;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageFactory;
import com.ataiva.serengeti.storage.StorageImpl;
import com.ataiva.serengeti.storage.StorageResponseObject;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Integration tests for the StorageImpl class.
 * These tests verify that the StorageImpl class integrates correctly with other components.
 */
public class StorageIntegrationTest {

    private StorageImpl storageImpl;
    private String testDbName;
    private String testTableName;
    
    @Before
    public void setUp() throws Exception {
        // Create a unique test database and table name
        testDbName = "test_db_" + UUID.randomUUID().toString().substring(0, 8);
        testTableName = "test_table_" + UUID.randomUUID().toString().substring(0, 8);
        
        // Create a StorageImpl instance with test configuration
        storageImpl = (StorageImpl) StorageFactory.createStorage(StorageFactory.StorageType.REAL, true, 100);
        
        // Initialize the storage
        storageImpl.init();
    }
    
    @After
    public void tearDown() throws Exception {
        // Clean up test database if it exists
        if (storageImpl.databaseExists(testDbName)) {
            storageImpl.dropDatabase(testDbName);
        }
        
        // Shutdown the storage
        storageImpl.shutdown();
    }
    
    @Test
    public void testDatabaseOperations() {
        // Test creating a database
        boolean created = storageImpl.createDatabase(testDbName);
        assertTrue("Database should be created successfully", created);
        assertTrue("Database should exist after creation", storageImpl.databaseExists(testDbName));
        
        // Test dropping a database
        boolean dropped = storageImpl.dropDatabase(testDbName);
        assertTrue("Database should be dropped successfully", dropped);
        assertFalse("Database should not exist after dropping", storageImpl.databaseExists(testDbName));
    }
    
    @Test
    public void testTableOperations() {
        // Create a database first
        storageImpl.createDatabase(testDbName);
        
        // Test creating a table
        boolean created = storageImpl.createTable(testDbName, testTableName);
        assertTrue("Table should be created successfully", created);
        assertTrue("Table should exist after creation", storageImpl.tableExists(testDbName, testTableName));
        
        // Test dropping a table
        boolean dropped = storageImpl.dropTable(testDbName, testTableName);
        assertTrue("Table should be dropped successfully", dropped);
        assertFalse("Table should not exist after dropping", storageImpl.tableExists(testDbName, testTableName));
    }
    
    @Test
    public void testDataOperations() {
        // Create a database and table first
        storageImpl.createDatabase(testDbName);
        storageImpl.createTable(testDbName, testTableName);
        
        // Test inserting data
        JSONObject testData = new JSONObject();
        testData.put("name", "Test Name");
        testData.put("value", 42);
        
        StorageResponseObject insertResponse = storageImpl.insert(testDbName, testTableName, testData);
        assertTrue("Insert should be successful", insertResponse.success);
        assertNotNull("Row ID should not be null", insertResponse.rowId);
        
        // Test selecting data
        List<String> results = storageImpl.select(testDbName, testTableName, "*", "name", "Test Name");
        assertFalse("Results should not be empty", results.isEmpty());
        
        JSONObject retrievedData = new JSONObject(results.get(0));
        assertEquals("Retrieved name should match inserted name", "Test Name", retrievedData.getString("name"));
        assertEquals("Retrieved value should match inserted value", 42, retrievedData.getInt("value"));
        
        // Test updating data
        boolean updated = storageImpl.update(testDbName, testTableName, "value", "43", "name", "Test Name");
        assertTrue("Update should be successful", updated);
        
        // Verify update
        results = storageImpl.select(testDbName, testTableName, "*", "name", "Test Name");
        assertFalse("Results should not be empty after update", results.isEmpty());
        
        retrievedData = new JSONObject(results.get(0));
        assertEquals("Retrieved value should be updated", "43", retrievedData.getString("value"));
        
        // Test deleting data
        boolean deleted = storageImpl.delete(testDbName, testTableName, "name", "Test Name");
        assertTrue("Delete should be successful", deleted);
        
        // Verify delete
        results = storageImpl.select(testDbName, testTableName, "*", "name", "Test Name");
        assertTrue("Results should be empty after delete", results.isEmpty());
    }
    
    @Test
    public void testStorageFactoryIntegration() {
        // Test creating a storage instance with the factory
        IStorage storage = StorageFactory.createStorage(StorageFactory.StorageType.REAL);
        
        // Verify that the storage is created correctly
        assertNotNull("Storage should not be null", storage);
        assertTrue("Storage should be an instance of Storage", storage instanceof Storage);
    }
    
    @Test
    public void testSerengetiIntegration() {
        // Create a test instance of Serengeti
        Serengeti testSerengeti = new Serengeti();
        
        // Get a Storage instance for Serengeti
        Storage storage = new Storage();
        
        // Set the storage to our test instance
        Serengeti.storage = storage;
        
        // Verify that the storage is set correctly
        assertNotNull("Storage should not be null", Serengeti.storage);
        assertTrue("Storage should be an instance of Storage", Serengeti.storage instanceof Storage);
    }
    
    @Test
    public void testFileSystemIntegration() throws Exception {
        // Create a database
        storageImpl.createDatabase(testDbName);
        
        // Verify that the database file exists
        Path dbFilePath = Paths.get("data/" + testDbName + ".meta");
        assertTrue("Database file should exist", Files.exists(dbFilePath));
        
        // Create a table
        storageImpl.createTable(testDbName, testTableName);
        
        // Verify that the table directory exists
        Path tableDir = Paths.get("data/" + testDbName + "/" + testTableName);
        assertTrue("Table directory should exist", Files.exists(tableDir));
        
        // Drop the database
        storageImpl.dropDatabase(testDbName);
        
        // Verify that the database file and directory are deleted
        assertFalse("Database file should not exist after dropping", Files.exists(dbFilePath));
        assertFalse("Database directory should not exist after dropping", Files.exists(Paths.get("data/" + testDbName)));
    }
}