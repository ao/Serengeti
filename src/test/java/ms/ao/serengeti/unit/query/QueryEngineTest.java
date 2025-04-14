package ms.ao.serengeti.unit.query;

import ms.ao.serengeti.Serengeti;
import ms.ao.serengeti.mocks.MockNetwork;
import ms.ao.serengeti.mocks.MockStorage;
import ms.ao.serengeti.query.QueryEngine;
import ms.ao.serengeti.utils.QueryTestUtils;
import ms.ao.serengeti.utils.TestBase;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the QueryEngine component.
 */
@DisplayName("QueryEngine Tests")
class QueryEngineTest extends TestBase {
    
    private MockStorage mockStorage;
    private MockNetwork mockNetwork;
    private String testDb;
    private String testTable;
    
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        
        // Replace the storage and network with mocks
        mockStorage = new MockStorage();
        Serengeti.storage = mockStorage;
        
        mockNetwork = new MockNetwork();
        Serengeti.network = mockNetwork;
        
        // Create unique test database and table names for each test
        testDb = generateRandomDatabaseName();
        testTable = generateRandomTableName();
        
        // Create the test database and table
        mockStorage.createDatabase(testDb);
        mockStorage.createTable(testDb, testTable);
    }
    
    @Nested
    @DisplayName("Query Parsing Tests")
    class QueryParsingTests {
        
        @Test
        @DisplayName("Parse empty query")
        void testParseEmptyQuery() {
            List<JSONObject> result = QueryEngine.query("");
            
            assertNull(result);
        }
        
        @Test
        @DisplayName("Parse single query without semicolon")
        void testParseSingleQueryWithoutSemicolon() {
            List<JSONObject> result = QueryEngine.query("show databases");
            
            assertNotNull(result);
            assertEquals(1, result.size());
        }
        
        @Test
        @DisplayName("Parse multiple queries")
        void testParseMultipleQueries() {
            List<JSONObject> result = QueryEngine.query("show databases; show " + testDb + " tables");
            
            assertNotNull(result);
            assertEquals(2, result.size());
        }
        
        @Test
        @DisplayName("Parse query with newlines")
        void testParseQueryWithNewlines() {
            List<JSONObject> result = QueryEngine.query("show\ndatabases");
            
            assertNotNull(result);
            assertEquals(1, result.size());
        }
    }
    
    @Nested
    @DisplayName("Database Operations Tests")
    class DatabaseOperationsTests {
        
        @Test
        @DisplayName("Show databases")
        void testShowDatabases() {
            List<JSONObject> result = QueryEngine.query("show databases");
            
            assertNotNull(result);
            assertEquals(1, result.size());
            assertTrue(mockStorage.wasMethodCalled("getDatabases"));
        }
        
        @Test
        @DisplayName("Create database")
        void testCreateDatabase() {
            String newDb = "new_test_db";
            List<JSONObject> result = QueryEngine.query("create database " + newDb);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            assertTrue(mockStorage.wasMethodCalled("createDatabase"));
            assertTrue(mockStorage.databaseExists(newDb));
        }
        
        @Test
        @DisplayName("Create existing database")
        void testCreateExistingDatabase() {
            List<JSONObject> result = QueryEngine.query("create database " + testDb);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.has("error"));
        }
        
        @Test
        @DisplayName("Drop database")
        void testDropDatabase() {
            List<JSONObject> result = QueryEngine.query("drop database " + testDb);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            assertTrue(mockStorage.wasMethodCalled("dropDatabase"));
            assertFalse(mockStorage.databaseExists(testDb));
        }
        
        @Test
        @DisplayName("Drop non-existing database")
        void testDropNonExistingDatabase() {
            String nonExistingDb = "non_existing_db";
            List<JSONObject> result = QueryEngine.query("drop database " + nonExistingDb);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.has("error"));
        }
    }
    
    @Nested
    @DisplayName("Table Operations Tests")
    class TableOperationsTests {
        
        @Test
        @DisplayName("Show tables")
        void testShowTables() {
            List<JSONObject> result = QueryEngine.query("show " + testDb + " tables");
            
            assertNotNull(result);
            assertEquals(1, result.size());
            assertTrue(mockStorage.wasMethodCalled("getTables"));
        }
        
        @Test
        @DisplayName("Create table")
        void testCreateTable() {
            String newTable = "new_test_table";
            List<JSONObject> result = QueryEngine.query("create table " + testDb + "." + newTable);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            assertTrue(mockStorage.wasMethodCalled("createTable"));
            assertTrue(mockStorage.tableExists(testDb, newTable));
        }
        
        @Test
        @DisplayName("Create existing table")
        void testCreateExistingTable() {
            List<JSONObject> result = QueryEngine.query("create table " + testDb + "." + testTable);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.has("error"));
        }
        
        @Test
        @DisplayName("Create table with invalid syntax")
        void testCreateTableWithInvalidSyntax() {
            List<JSONObject> result = QueryEngine.query("create table invalid_syntax");
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.has("error"));
        }
        
        @Test
        @DisplayName("Drop table")
        void testDropTable() {
            List<JSONObject> result = QueryEngine.query("drop table " + testDb + "." + testTable);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            assertTrue(mockStorage.wasMethodCalled("dropTable"));
            assertFalse(mockStorage.tableExists(testDb, testTable));
        }
        
        @Test
        @DisplayName("Drop non-existing table")
        void testDropNonExistingTable() {
            String nonExistingTable = "non_existing_table";
            List<JSONObject> result = QueryEngine.query("drop table " + testDb + "." + nonExistingTable);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.has("error"));
        }
    }
    
    @Nested
    @DisplayName("Data Operations Tests")
    class DataOperationsTests {
        
        @Test
        @DisplayName("Insert data")
        void testInsertData() {
            String query = "insert into " + testDb + "." + testTable + " (name, value) values('Test', '42')";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            assertTrue(mockStorage.wasMethodCalled("insert"));
        }
        
        @Test
        @DisplayName("Insert data with invalid syntax")
        void testInsertDataWithInvalidSyntax() {
            String query = "insert into " + testDb + "." + testTable + " (name) values('Test', '42')";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.has("error"));
        }
        
        @Test
        @DisplayName("Select data")
        void testSelectData() {
            // Insert test data
            mockStorage.insert(testDb, testTable, new JSONObject().put("name", "Test").put("value", 42));
            
            String query = "select * from " + testDb + "." + testTable + " where name='Test'";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            assertTrue(mockStorage.wasMethodCalled("select"));
        }
        
        @Test
        @DisplayName("Select all data")
        void testSelectAllData() {
            // Insert test data
            mockStorage.insert(testDb, testTable, new JSONObject().put("name", "Test").put("value", 42));
            
            String query = "select * from " + testDb + "." + testTable;
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            assertTrue(mockStorage.wasMethodCalled("select"));
        }
        
        @Test
        @DisplayName("Update data")
        void testUpdateData() {
            // Insert test data
            mockStorage.insert(testDb, testTable, new JSONObject().put("name", "Test").put("value", 42));
            
            String query = "update " + testDb + "." + testTable + " set value='43' where name='Test'";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            assertTrue(mockStorage.wasMethodCalled("update"));
        }
        
        @Test
        @DisplayName("Update data with invalid syntax")
        void testUpdateDataWithInvalidSyntax() {
            // Use a malformed query that will be caught by the QueryEngine's error handling
            String query = "update invalid_syntax";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.has("error"));
        }
        
        @Test
        @DisplayName("Delete data")
        void testDeleteData() {
            // Insert test data
            mockStorage.insert(testDb, testTable, new JSONObject().put("name", "Test").put("value", 42));
            
            String query = "delete " + testDb + "." + testTable + " where name='Test'";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            assertTrue(mockStorage.wasMethodCalled("delete"));
        }
        
        @Test
        @DisplayName("Delete data with invalid syntax")
        void testDeleteDataWithInvalidSyntax() {
            // Use a malformed query that will be caught by the QueryEngine's error handling
            String query = "delete invalid_syntax";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.has("error"));
        }
    }
    
    @Nested
    @DisplayName("Special Commands Tests")
    class SpecialCommandsTests {
        
        @Test
        @DisplayName("Delete everything")
        void testDeleteEverything() {
            List<JSONObject> result = QueryEngine.query("delete everything");
            
            assertNotNull(result);
            assertEquals(1, result.size());
            assertTrue(mockStorage.wasMethodCalled("deleteEverything"));
        }
    }
    
    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {
        
        @ParameterizedTest
        @ValueSource(strings = {
            "invalid query",
            "select from",
            "create",
            "drop",
            "insert into",
            "update table",
            "delete from"
        })
        @DisplayName("Invalid queries")
        void testInvalidQueries(String query) {
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.has("error"));
        }
    }
}