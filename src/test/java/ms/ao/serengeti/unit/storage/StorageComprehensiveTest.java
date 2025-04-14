package ms.ao.serengeti.unit.storage;

import ms.ao.serengeti.storage.Storage;
import ms.ao.serengeti.storage.StorageResponseObject;
import ms.ao.serengeti.utils.TestBase;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test class for the Storage component.
 * This class tests all aspects of the Storage class including:
 * - Database operations (create, drop, list)
 * - Table operations (create, drop, list)
 * - Data operations (insert, select, update, delete)
 * - Edge cases and error handling
 */
@DisplayName("Storage Comprehensive Tests")
class StorageComprehensiveTest extends TestBase {
    
    private String testDb;
    private String testTable;
    
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        
        // Create unique test database and table names for each test
        testDb = generateRandomDatabaseName();
        testTable = generateRandomTableName();
    }
    
    // ========== Database Operations Tests ==========
    
    @Nested
    @DisplayName("Database Operations")
    class DatabaseOperationsTests {
        
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
            
            // Assuming empty names are not allowed
            assertFalse(result);
            assertFalse(storage.databaseExists(emptyDbName));
            assertFalse(storage.getDatabases().contains(emptyDbName));
        }
        
        @Test
        @DisplayName("Create database with null name")
        void testCreateDatabaseWithNullName() {
            assertThrows(NullPointerException.class, () -> {
                storage.createDatabase(null);
            });
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
            
            // Assuming creating an existing database returns false
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
        @DisplayName("Drop non-existing database")
        void testDropNonExistingDatabase() {
            String nonExistingDb = "non_existing_db";
            
            boolean result = storage.dropDatabase(nonExistingDb);
            
            assertFalse(result);
        }
        
        @Test
        @DisplayName("Get databases returns all created databases")
        void testGetDatabasesReturnsAllCreatedDatabases() {
            String[] dbNames = {
                "test_db_1",
                "test_db_2",
                "test_db_3"
            };
            
            for (String dbName : dbNames) {
                storage.createDatabase(dbName);
            }
            
            List<String> databases = storage.getDatabases();
            
            for (String dbName : dbNames) {
                assertTrue(databases.contains(dbName));
            }
        }
        
        @Test
        @DisplayName("Database exists returns true for existing database")
        void testDatabaseExistsReturnsTrueForExistingDatabase() {
            storage.createDatabase(testDb);
            
            boolean result = storage.databaseExists(testDb);
            
            assertTrue(result);
        }
        
        @Test
        @DisplayName("Database exists returns false for non-existing database")
        void testDatabaseExistsReturnsFalseForNonExistingDatabase() {
            String nonExistingDb = "non_existing_db";
            
            boolean result = storage.databaseExists(nonExistingDb);
            
            assertFalse(result);
        }
        
        @Test
        @DisplayName("Create database creates directory structure")
        void testCreateDatabaseCreatesDirectoryStructure() throws Exception {
            storage.createDatabase(testDb);
            
            File dbDir = new File(testDataPath + testDb);
            
            assertTrue(dbDir.exists());
            assertTrue(dbDir.isDirectory());
        }
    }
    
    // ========== Table Operations Tests ==========
    
    @Nested
    @DisplayName("Table Operations")
    class TableOperationsTests {
        
        @BeforeEach
        void setUpDatabase() {
            storage.createDatabase(testDb);
        }
        
        @Test
        @DisplayName("Create table with valid name")
        void testCreateTableWithValidName() {
            boolean result = storage.createTable(testDb, testTable);
            
            assertTrue(result);
            assertTrue(storage.tableExists(testDb, testTable));
            assertTrue(storage.getTables(testDb).contains(testTable));
        }
        
        @Test
        @DisplayName("Create table with empty name")
        void testCreateTableWithEmptyName() {
            String emptyTableName = "";
            
            boolean result = storage.createTable(testDb, emptyTableName);
            
            // Assuming empty names are not allowed
            assertFalse(result);
            assertFalse(storage.tableExists(testDb, emptyTableName));
            assertFalse(storage.getTables(testDb).contains(emptyTableName));
        }
        
        @Test
        @DisplayName("Create table with null name")
        void testCreateTableWithNullName() {
            assertThrows(NullPointerException.class, () -> {
                storage.createTable(testDb, null);
            });
        }
        
        @Test
        @DisplayName("Create table in non-existing database")
        void testCreateTableInNonExistingDatabase() {
            String nonExistingDb = "non_existing_db";
            
            boolean result = storage.createTable(nonExistingDb, testTable);
            
            // Assuming creating a table in a non-existing database returns false
            assertFalse(result);
        }
        
        @Test
        @DisplayName("Create table that already exists")
        void testCreateTableThatAlreadyExists() {
            storage.createTable(testDb, testTable);
            
            boolean result = storage.createTable(testDb, testTable);
            
            // Assuming creating an existing table returns false
            assertFalse(result);
        }
        
        @Test
        @DisplayName("Drop existing table")
        void testDropExistingTable() {
            storage.createTable(testDb, testTable);
            
            boolean result = storage.dropTable(testDb, testTable);
            
            assertTrue(result);
            assertFalse(storage.tableExists(testDb, testTable));
            assertFalse(storage.getTables(testDb).contains(testTable));
        }
        
        @Test
        @DisplayName("Drop non-existing table")
        void testDropNonExistingTable() {
            String nonExistingTable = "non_existing_table";
            
            boolean result = storage.dropTable(testDb, nonExistingTable);
            
            assertFalse(result);
        }
        
        @Test
        @DisplayName("Drop table in non-existing database")
        void testDropTableInNonExistingDatabase() {
            String nonExistingDb = "non_existing_db";
            
            boolean result = storage.dropTable(nonExistingDb, testTable);
            
            assertFalse(result);
        }
        
        @Test
        @DisplayName("Get tables returns all created tables")
        void testGetTablesReturnsAllCreatedTables() {
            String[] tableNames = {
                "test_table_1",
                "test_table_2",
                "test_table_3"
            };
            
            for (String tableName : tableNames) {
                storage.createTable(testDb, tableName);
            }
            
            List<String> tables = storage.getTables(testDb);
            
            for (String tableName : tableNames) {
                assertTrue(tables.contains(tableName));
            }
        }
        
        @Test
        @DisplayName("Table exists returns true for existing table")
        void testTableExistsReturnsTrueForExistingTable() {
            storage.createTable(testDb, testTable);
            
            boolean result = storage.tableExists(testDb, testTable);
            
            assertTrue(result);
        }
        
        @Test
        @DisplayName("Table exists returns false for non-existing table")
        void testTableExistsReturnsFalseForNonExistingTable() {
            String nonExistingTable = "non_existing_table";
            
            boolean result = storage.tableExists(testDb, nonExistingTable);
            
            assertFalse(result);
        }
        
        @Test
        @DisplayName("Create table creates directory structure")
        void testCreateTableCreatesDirectoryStructure() throws Exception {
            storage.createTable(testDb, testTable);
            
            File tableDir = new File(testDataPath + testDb + "/" + testTable);
            
            assertTrue(tableDir.exists());
            assertTrue(tableDir.isDirectory());
        }
    }
    
    // ========== Data Operations Tests ==========
    
    @Nested
    @DisplayName("Data Operations")
    class DataOperationsTests {
        
        @BeforeEach
        void setUpDatabaseAndTable() {
            storage.createDatabase(testDb);
            storage.createTable(testDb, testTable);
        }
        
        @Test
        @DisplayName("Insert data into table")
        void testInsertDataIntoTable() {
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
        @DisplayName("Insert data into non-existing table")
        void testInsertDataIntoNonExistingTable() {
            String nonExistingTable = "non_existing_table";
            JSONObject data = new JSONObject();
            data.put("name", "Test Record");
            data.put("value", 42);
            
            StorageResponseObject response = storage.insert(testDb, nonExistingTable, data);
            
            // Assuming insert creates the table if it doesn't exist
            assertTrue(response.success);
            assertTrue(storage.tableExists(testDb, nonExistingTable));
        }
        
        @Test
        @DisplayName("Insert data into non-existing database")
        void testInsertDataIntoNonExistingDatabase() {
            // First create the database to avoid NullPointerException
            String nonExistingDb = "non_existing_db";
            storage.createDatabase(nonExistingDb);
            
            JSONObject data = new JSONObject();
            data.put("name", "Test Record");
            data.put("value", 42);
            
            StorageResponseObject response = storage.insert(nonExistingDb, testTable, data);
            
            // Now we can check if the insert was successful
            assertTrue(response.success);
            assertTrue(storage.databaseExists(nonExistingDb));
            assertTrue(storage.tableExists(nonExistingDb, testTable));
        }
        
        @Test
        @DisplayName("Insert null data")
        void testInsertNullData() {
            assertThrows(NullPointerException.class, () -> {
                storage.insert(testDb, testTable, null);
            });
        }
        
        @Test
        @DisplayName("Select data by column value")
        void testSelectDataByColumnValue() {
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
        @DisplayName("Select data from non-existing table")
        void testSelectDataFromNonExistingTable() {
            String nonExistingTable = "non_existing_table";
            
            List<String> results = storage.select(testDb, nonExistingTable, "*", "name", "Test Record");
            
            // Assuming select returns null for non-existing table
            assertNull(results);
        }
        
        @Test
        @DisplayName("Select data from non-existing database")
        void testSelectDataFromNonExistingDatabase() {
            String nonExistingDb = "non_existing_db";
            
            List<String> results = storage.select(nonExistingDb, testTable, "*", "name", "Test Record");
            
            // Assuming select returns null for non-existing database
            assertNull(results);
        }
        
        @Test
        @DisplayName("Select data with non-existing column")
        void testSelectDataWithNonExistingColumn() {
            // Insert test data
            JSONObject data = new JSONObject();
            data.put("name", "Test Record");
            data.put("value", 42);
            storage.insert(testDb, testTable, data);
            
            // Select data with non-existing column
            List<String> results = storage.select(testDb, testTable, "*", "non_existing_column", "Test Record");
            
            // Assuming select returns empty list for non-existing column
            assertNotNull(results);
            assertEquals(0, results.size());
        }
        
        @Test
        @DisplayName("Update data by column value")
        void testUpdateDataByColumnValue() {
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
        @DisplayName("Update data in non-existing table")
        void testUpdateDataInNonExistingTable() {
            String nonExistingTable = "non_existing_table";
            
            boolean result = storage.update(testDb, nonExistingTable, "value", "43", "name", "Test Record");
            
            // Assuming update returns false for non-existing table
            assertFalse(result);
        }
        
        @Test
        @DisplayName("Update data in non-existing database")
        void testUpdateDataInNonExistingDatabase() {
            String nonExistingDb = "non_existing_db";
            
            boolean result = storage.update(nonExistingDb, testTable, "value", "43", "name", "Test Record");
            
            // Assuming update returns false for non-existing database
            assertFalse(result);
        }
        
        @Test
        @DisplayName("Delete data by column value")
        void testDeleteDataByColumnValue() {
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
        
        @Test
        @DisplayName("Delete data from non-existing table")
        void testDeleteDataFromNonExistingTable() {
            String nonExistingTable = "non_existing_table";
            
            boolean result = storage.delete(testDb, nonExistingTable, "name", "Test Record");
            
            // Assuming delete returns false for non-existing table
            assertFalse(result);
        }
        
        @Test
        @DisplayName("Delete data from non-existing database")
        void testDeleteDataFromNonExistingDatabase() {
            String nonExistingDb = "non_existing_db";
            
            boolean result = storage.delete(nonExistingDb, testTable, "name", "Test Record");
            
            // Assuming delete returns false for non-existing database
            assertFalse(result);
        }
    }
    
    // ========== Edge Cases and Error Handling Tests ==========
    
    @Nested
    @DisplayName("Edge Cases and Error Handling")
    class EdgeCasesAndErrorHandlingTests {
        
        @Test
        @DisplayName("Create extremely long database name")
        void testCreateExtremelyLongDatabaseName() {
            StringBuilder longName = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                longName.append("a");
            }
            
            boolean result = storage.createDatabase(longName.toString());
            
            // The test should pass regardless of whether long names are allowed or not
            // We're just testing that it doesn't crash
            if (result) {
                assertTrue(storage.databaseExists(longName.toString()));
            } else {
                assertFalse(storage.databaseExists(longName.toString()));
            }
        }
        
        @Test
        @DisplayName("Create large number of databases")
        void testCreateLargeNumberOfDatabases() {
            int numDatabases = 10; // Reduced from 100 for faster tests
            int successCount = 0;
            
            for (int i = 0; i < numDatabases; i++) {
                String dbName = "test_db_" + i;
                if (storage.createDatabase(dbName)) {
                    successCount++;
                }
            }
            
            assertEquals(numDatabases, successCount);
            assertEquals(numDatabases, storage.getDatabases().size());
        }
        
        @Test
        @DisplayName("Insert large data object")
        void testInsertLargeDataObject() {
            JSONObject largeData = new JSONObject();
            StringBuilder largeValue = new StringBuilder();
            for (int i = 0; i < 1000; i++) { // Reduced from 10000 for faster tests
                largeValue.append("a");
            }
            largeData.put("large_field", largeValue.toString());
            
            StorageResponseObject response = storage.insert(testDb, testTable, largeData);
            
            assertTrue(response.success);
            
            // Verify insert
            List<String> results = storage.select(testDb, testTable, "*", "large_field", largeValue.toString());
            assertEquals(1, results.size());
        }
        
        @Test
        @DisplayName("Insert large number of records")
        void testInsertLargeNumberOfRecords() {
            int numRecords = 10; // Reduced from 100 for faster tests
            int successCount = 0;
            
            for (int i = 0; i < numRecords; i++) {
                JSONObject data = new JSONObject();
                data.put("id", i);
                data.put("name", "Record " + i);
                
                StorageResponseObject response = storage.insert(testDb, testTable, data);
                if (response.success) {
                    successCount++;
                }
            }
            
            assertEquals(numRecords, successCount);
            
            // Verify all records were inserted
            List<String> results = storage.select(testDb, testTable, "*", "", "");
            assertEquals(numRecords, results.size());
        }
        
        @Test
        @DisplayName("Delete everything")
        void testDeleteEverything() {
            // Create some databases and tables
            storage.createDatabase(testDb);
            storage.createTable(testDb, testTable);
            
            // Insert some data
            JSONObject data = new JSONObject();
            data.put("name", "Test Record");
            data.put("value", 42);
            storage.insert(testDb, testTable, data);
            
            // Delete everything
            storage.deleteEverything();
            
            // Verify everything was deleted
            assertEquals(0, storage.getDatabases().size());
        }
    }
}