package ms.ao.serengeti.system;

import ms.ao.serengeti.Serengeti;
import ms.ao.serengeti.helpers.Globals;
import ms.ao.serengeti.query.QueryEngine;
import ms.ao.serengeti.utils.TestBase;
import org.json.JSONObject;
import org.junit.jupiter.api.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * System tests for the entire Serengeti system.
 * These tests start a complete Serengeti instance and test it as a black box.
 */
@DisplayName("Serengeti System Tests")
class SerengetiSystemTest extends TestBase {
    
    private static Serengeti serengetiInstance;
    private static String testDb;
    private static String testTable;
    private static final int PORT = Globals.port_default;
    private static final String BASE_URL = "http://localhost:" + PORT;
    private static CountDownLatch serverStartLatch;
    
    @BeforeAll
    static void startSerengeti() throws Exception {
        // Create unique test database and table names
        testDb = "test_db_" + UUID.randomUUID().toString().substring(0, 8);
        testTable = "test_table_" + UUID.randomUUID().toString().substring(0, 8);
        
        // Start Serengeti in a separate thread
        serverStartLatch = new CountDownLatch(1);
        Thread serengetiThread = new Thread(() -> {
            serengetiInstance = new Serengeti();
            serverStartLatch.countDown();
        });
        serengetiThread.start();
        
        // Wait for Serengeti to start
        assertTrue(serverStartLatch.await(10, TimeUnit.SECONDS), "Serengeti failed to start within 10 seconds");
        
        // Wait a bit more for the server to be ready
        Thread.sleep(2000);
    }
    
    @AfterAll
    static void stopSerengeti() {
        // Clean up any data created during the tests
        if (Serengeti.storage != null) {
            Serengeti.storage.deleteEverything();
        }
    }
    
    @Nested
    @DisplayName("HTTP Endpoint Tests")
    class HttpEndpointTests {
        
        @Test
        @DisplayName("Root endpoint returns node information")
        void testRootEndpointReturnsNodeInformation() throws Exception {
            // Send a GET request to the root endpoint
            URL url = new URL(BASE_URL + "/");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            
            // Check the response code
            assertEquals(200, connection.getResponseCode());
            
            // Read the response
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            
            // Parse the response as JSON
            JSONObject json = new JSONObject(response.toString());
            
            // Verify the response contains the expected fields
            assertTrue(json.has("this"));
            assertTrue(json.has("totalNodes"));
            assertTrue(json.has("availableNodes"));
            
            // Verify the node information
            JSONObject thisNode = json.getJSONObject("this");
            assertTrue(thisNode.has("id"));
            assertTrue(thisNode.has("ip"));
            assertTrue(thisNode.has("version"));
            assertTrue(thisNode.has("uptime"));
        }
        
        @Test
        @DisplayName("Dashboard endpoint returns HTML")
        void testDashboardEndpointReturnsHtml() throws Exception {
            // Send a GET request to the dashboard endpoint
            URL url = new URL(BASE_URL + "/dashboard");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            
            // Check the response code
            assertEquals(200, connection.getResponseCode());
            
            // Read the response
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            
            // Verify the response is HTML
            assertTrue(response.toString().contains("<!DOCTYPE html>"));
            assertTrue(response.toString().contains("<html"));
            assertTrue(response.toString().contains("</html>"));
        }
        
        @Test
        @DisplayName("Interactive endpoint returns HTML")
        void testInteractiveEndpointReturnsHtml() throws Exception {
            // Send a GET request to the interactive endpoint
            URL url = new URL(BASE_URL + "/interactive");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            
            // Check the response code
            assertEquals(200, connection.getResponseCode());
            
            // Read the response
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            
            // Verify the response is HTML
            assertTrue(response.toString().contains("<!DOCTYPE html>"));
            assertTrue(response.toString().contains("<html"));
            assertTrue(response.toString().contains("</html>"));
        }
        
        @Test
        @DisplayName("Meta endpoint returns database metadata")
        void testMetaEndpointReturnsDatabaseMetadata() throws Exception {
            // Send a GET request to the meta endpoint
            URL url = new URL(BASE_URL + "/meta");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            
            // Check the response code
            assertEquals(200, connection.getResponseCode());
            
            // Read the response
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            
            // Parse the response as JSON
            JSONObject json = new JSONObject(response.toString());
            
            // Verify the response contains the expected fields
            assertTrue(json.has("meta"));
        }
    }
    
    @Nested
    @DisplayName("Database Operations Tests")
    class DatabaseOperationsTests {
        
        @Test
        @DisplayName("Create and drop database")
        void testCreateAndDropDatabase() {
            // Create a database
            List<JSONObject> createResult = QueryEngine.query("create database " + testDb);
            assertNotNull(createResult);
            assertEquals(1, createResult.size());
            assertTrue(createResult.get(0).getBoolean("executed"));
            
            // Verify the database exists
            assertTrue(Serengeti.storage.databaseExists(testDb));
            
            // Drop the database
            List<JSONObject> dropResult = QueryEngine.query("drop database " + testDb);
            assertNotNull(dropResult);
            assertEquals(1, dropResult.size());
            assertTrue(dropResult.get(0).getBoolean("executed"));
            
            // Verify the database no longer exists
            assertFalse(Serengeti.storage.databaseExists(testDb));
        }
        
        @Test
        @DisplayName("Create and drop table")
        void testCreateAndDropTable() {
            // Create a database
            QueryEngine.query("create database " + testDb);
            
            // Create a table
            List<JSONObject> createResult = QueryEngine.query("create table " + testDb + "." + testTable);
            assertNotNull(createResult);
            assertEquals(1, createResult.size());
            assertTrue(createResult.get(0).getBoolean("executed"));
            
            // Verify the table exists
            assertTrue(Serengeti.storage.tableExists(testDb, testTable));
            
            // Drop the table
            List<JSONObject> dropResult = QueryEngine.query("drop table " + testDb + "." + testTable);
            assertNotNull(dropResult);
            assertEquals(1, dropResult.size());
            assertTrue(dropResult.get(0).getBoolean("executed"));
            
            // Verify the table no longer exists
            assertFalse(Serengeti.storage.tableExists(testDb, testTable));
            
            // Clean up
            QueryEngine.query("drop database " + testDb);
        }
    }
    
    @Nested
    @DisplayName("Data Operations Tests")
    class DataOperationsTests {
        
        @BeforeEach
        void setUp() {
            // Create a database and table for each test
            QueryEngine.query("create database " + testDb);
            QueryEngine.query("create table " + testDb + "." + testTable);
        }
        
        @AfterEach
        void tearDown() {
            // Clean up after each test
            QueryEngine.query("drop database " + testDb);
        }
        
        @Test
        @DisplayName("Insert, select, update, and delete data")
        void testInsertSelectUpdateAndDeleteData() {
            // Insert data
            String insertQuery = "insert into " + testDb + "." + testTable + " (name, value) values('Test', '42')";
            List<JSONObject> insertResult = QueryEngine.query(insertQuery);
            assertNotNull(insertResult);
            assertEquals(1, insertResult.size());
            assertTrue(insertResult.get(0).getBoolean("executed"));
            
            // Select data
            String selectQuery = "select * from " + testDb + "." + testTable + " where name='Test'";
            List<JSONObject> selectResult = QueryEngine.query(selectQuery);
            assertNotNull(selectResult);
            assertEquals(1, selectResult.size());
            
            JSONObject response = selectResult.get(0);
            assertTrue(response.getBoolean("executed"));
            assertNotNull(response.getJSONArray("list"));
            assertEquals(1, response.getJSONArray("list").length());
            
            JSONObject record = new JSONObject(response.getJSONArray("list").getString(0));
            assertEquals("Test", record.getString("name"));
            assertEquals("42", record.getString("value"));
            
            // Update data
            String updateQuery = "update " + testDb + "." + testTable + " set value='43' where name='Test'";
            List<JSONObject> updateResult = QueryEngine.query(updateQuery);
            assertNotNull(updateResult);
            assertEquals(1, updateResult.size());
            assertTrue(updateResult.get(0).getBoolean("executed"));
            
            // Verify update
            selectResult = QueryEngine.query(selectQuery);
            response = selectResult.get(0);
            record = new JSONObject(response.getJSONArray("list").getString(0));
            assertEquals("43", record.getString("value"));
            
            // Delete data
            String deleteQuery = "delete " + testDb + "." + testTable + " where name='Test'";
            List<JSONObject> deleteResult = QueryEngine.query(deleteQuery);
            assertNotNull(deleteResult);
            assertEquals(1, deleteResult.size());
            assertTrue(deleteResult.get(0).getBoolean("executed"));
            
            // Verify delete
            selectResult = QueryEngine.query(selectQuery);
            response = selectResult.get(0);
            assertEquals(0, response.getJSONArray("list").length());
        }
    }
    
    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {
        
        @Test
        @DisplayName("Invalid query returns error")
        void testInvalidQueryReturnsError() {
            // Execute an invalid query
            List<JSONObject> result = QueryEngine.query("invalid query");
            
            // Verify the result
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.has("error"));
        }
        
        @Test
        @DisplayName("Drop non-existing database returns error")
        void testDropNonExistingDatabaseReturnsError() {
            // Execute a query to drop a non-existing database
            String nonExistingDb = "non_existing_db_" + UUID.randomUUID().toString().substring(0, 8);
            List<JSONObject> result = QueryEngine.query("drop database " + nonExistingDb);
            
            // Verify the result
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.has("error"));
        }
        
        @Test
        @DisplayName("Drop non-existing table returns error")
        void testDropNonExistingTableReturnsError() {
            // Create a database
            QueryEngine.query("create database " + testDb);
            
            // Execute a query to drop a non-existing table
            String nonExistingTable = "non_existing_table_" + UUID.randomUUID().toString().substring(0, 8);
            List<JSONObject> result = QueryEngine.query("drop table " + testDb + "." + nonExistingTable);
            
            // Verify the result
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.has("error"));
            
            // Clean up
            QueryEngine.query("drop database " + testDb);
        }
    }
}