package com.ataiva.serengeti.unit.storage;

import com.ataiva.serengeti.storage.StorageResponseObject;
import com.ataiva.serengeti.utils.StorageFastTestBase;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fast tests for the Storage component.
 * These tests focus on core functionality and run quickly.
 */
@DisplayName("Storage Fast Tests")
@Tag("fast")
class StorageFastTest extends StorageFastTestBase {
    
    private String testDb;
    private String testTable;
    
    @BeforeEach
    void setUpTest() {
        testDb = generateRandomDatabaseName();
        testTable = generateRandomTableName();
    }
    
    @Test
    @DisplayName("Create database with valid name")
    void testCreateDatabaseWithValidName() {
        boolean result = storage.createDatabase(testDb);
        
        assertTrue(result);
        assertTrue(storage.databaseExists(testDb));
        assertTrue(storage.getDatabases().contains(testDb));
    }
    
    @Test
    @DisplayName("Create database with empty name")
    void testCreateDatabaseWithEmptyName() {
        String emptyDbName = "";
        
        boolean result = storage.createDatabase(emptyDbName);
        
        assertFalse(result);
        assertFalse(storage.databaseExists(emptyDbName));
        assertFalse(storage.getDatabases().contains(emptyDbName));
    }
    
    @Test
    @DisplayName("Create database with null name")
    void testCreateDatabaseWithNullName() {
        // The InMemoryStorage implementation might handle null differently
        boolean result = storage.createDatabase(null);
        assertFalse(result);
    }
    
    @ParameterizedTest
    @ValueSource(strings = {"test_db", "db123", "my-database", "db_with_underscores"})
    @DisplayName("Create database with various valid names")
    void testCreateDatabaseWithVariousValidNames(String dbName) {
        boolean result = storage.createDatabase(dbName);
        
        assertTrue(result);
        assertTrue(storage.databaseExists(dbName));
        assertTrue(storage.getDatabases().contains(dbName));
    }
    
    @Test
    @DisplayName("Create database that already exists")
    void testCreateDatabaseThatAlreadyExists() {
        storage.createDatabase(testDb);
        
        boolean result = storage.createDatabase(testDb);
        
        assertFalse(result);
    }
    
    @Test
    @DisplayName("Drop existing database")
    void testDropExistingDatabase() {
        storage.createDatabase(testDb);
        
        boolean result = storage.dropDatabase(testDb);
        
        assertTrue(result);
        assertFalse(storage.databaseExists(testDb));
        assertFalse(storage.getDatabases().contains(testDb));
    }
    
    @Test
    @DisplayName("Create table with valid name")
    void testCreateTableWithValidName() {
        storage.createDatabase(testDb);
        
        boolean result = storage.createTable(testDb, testTable);
        
        assertTrue(result);
        assertTrue(storage.tableExists(testDb, testTable));
        assertTrue(storage.getTables(testDb).contains(testTable));
    }
    
    @Test
    @DisplayName("Insert data into table")
    void testInsertDataIntoTable() {
        storage.createDatabase(testDb);
        storage.createTable(testDb, testTable);
        
        JSONObject data = new JSONObject();
        data.put("name", "Test Record");
        data.put("value", 42);
        
        StorageResponseObject response = storage.insert(testDb, testTable, data);
        
        assertTrue(response.success);
        assertNotNull(response.rowId);
        assertNotNull(response.primary);
        assertNotNull(response.secondary);
    }
    
    @Test
    @DisplayName("Select data by column value")
    void testSelectDataByColumnValue() {
        storage.createDatabase(testDb);
        storage.createTable(testDb, testTable);
        
        // Insert test data
        JSONObject data = new JSONObject();
        data.put("name", "Test Record");
        data.put("value", 42);
        storage.insert(testDb, testTable, data);
        
        // Select data
        List<String> results = storage.select(testDb, testTable, "*", "name", "Test Record");
        
        assertNotNull(results);
        assertEquals(1, results.size());
        JSONObject result = new JSONObject(results.get(0));
        assertEquals("Test Record", result.getString("name"));
        assertEquals(42, result.getInt("value"));
    }
    
    @Test
    @DisplayName("Update data by column value")
    void testUpdateDataByColumnValue() {
        storage.createDatabase(testDb);
        storage.createTable(testDb, testTable);
        
        // Insert test data
        JSONObject data = new JSONObject();
        data.put("name", "Test Record");
        data.put("value", 42);
        storage.insert(testDb, testTable, data);
        
        // Update data
        boolean result = storage.update(testDb, testTable, "value", "43", "name", "Test Record");
        
        assertTrue(result);
        
        // Verify update
        List<String> results = storage.select(testDb, testTable, "*", "name", "Test Record");
        JSONObject updatedData = new JSONObject(results.get(0));
        assertEquals("43", updatedData.getString("value"));
    }
    
    @Test
    @DisplayName("Delete data by column value")
    void testDeleteDataByColumnValue() {
        storage.createDatabase(testDb);
        storage.createTable(testDb, testTable);
        
        // Insert test data
        JSONObject data = new JSONObject();
        data.put("name", "Test Record");
        data.put("value", 42);
        storage.insert(testDb, testTable, data);
        
        // Delete data
        boolean result = storage.delete(testDb, testTable, "name", "Test Record");
        
        assertTrue(result);
        
        // Verify delete
        List<String> results = storage.select(testDb, testTable, "*", "name", "Test Record");
        assertEquals(0, results.size());
    }
}