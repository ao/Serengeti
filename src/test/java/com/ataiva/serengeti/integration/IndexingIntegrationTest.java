package com.ataiva.serengeti.integration;

import static org.junit.Assert.*;

import java.util.List;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.index.IndexManager;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.query.QueryEngine;
import com.ataiva.serengeti.server.Server;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageReshuffle;
import com.ataiva.serengeti.storage.StorageScheduler;

/**
 * Integration test for the indexing functionality.
 * Tests the complete flow from creating indexes to using them in queries.
 */
public class IndexingIntegrationTest {
    
    private static final String TEST_DB = "indextest_db";
    private static final String TEST_TABLE = "indextest_table";
    private static final int NUM_ROWS = 1000;
    
    @Before
    public void setUp() {
        // Initialize components
        Serengeti.storage = new Storage();
        Serengeti.network = new Network();
        Serengeti.server = new Server();
        Serengeti.storageReshuffle = new StorageReshuffle();
        Serengeti.storageScheduler = new StorageScheduler();
        Serengeti.indexManager = new IndexManager();
        
        // Create test database and table
        createTestData();
    }
    
    @After
    public void tearDown() {
        // Clean up test data
        if (Serengeti.storage.databaseExists(TEST_DB)) {
            Serengeti.storage.dropDatabase(TEST_DB);
        }
    }
    
    @Test
    public void testIndexCreationAndUsage() {
        // Verify test data was created
        assertTrue(Serengeti.storage.databaseExists(TEST_DB));
        assertTrue(Serengeti.storage.tableExists(TEST_DB, TEST_TABLE));
        
        // Run a query without an index and measure time
        long startTimeNoIndex = System.nanoTime();
        List<JSONObject> resultNoIndex = QueryEngine.query("SELECT * FROM " + TEST_DB + "." + TEST_TABLE + " WHERE user_id='user500'");
        long endTimeNoIndex = System.nanoTime();
        long durationNoIndex = endTimeNoIndex - startTimeNoIndex;
        
        // Verify query results
        assertNotNull(resultNoIndex);
        assertEquals(1, resultNoIndex.size());
        JSONObject resultObj = resultNoIndex.get(0);
        assertTrue(resultObj.has("executed"));
        assertTrue(resultObj.getBoolean("executed"));
        assertTrue(resultObj.has("list"));
        assertEquals(1, resultObj.getJSONArray("list").length());
        
        // Create an index on the user_id column
        List<JSONObject> createIndexResult = QueryEngine.query("CREATE INDEX ON " + TEST_DB + "." + TEST_TABLE + "(user_id)");
        assertNotNull(createIndexResult);
        assertEquals(1, createIndexResult.size());
        assertTrue(createIndexResult.get(0).getBoolean("executed"));
        
        // Verify the index was created
        assertTrue(Serengeti.indexManager.hasIndex(TEST_DB, TEST_TABLE, "user_id"));
        
        // Run the same query with the index and measure time
        long startTimeWithIndex = System.nanoTime();
        List<JSONObject> resultWithIndex = QueryEngine.query("SELECT * FROM " + TEST_DB + "." + TEST_TABLE + " WHERE user_id='user500'");
        long endTimeWithIndex = System.nanoTime();
        long durationWithIndex = endTimeWithIndex - startTimeWithIndex;
        
        // Verify query results
        assertNotNull(resultWithIndex);
        assertEquals(1, resultWithIndex.size());
        JSONObject resultObjWithIndex = resultWithIndex.get(0);
        assertTrue(resultObjWithIndex.has("executed"));
        assertTrue(resultObjWithIndex.getBoolean("executed"));
        assertTrue(resultObjWithIndex.has("list"));
        assertEquals(1, resultObjWithIndex.getJSONArray("list").length());
        
        // Verify that the index was used
        assertTrue(resultObjWithIndex.has("explain"));
        assertTrue(resultObjWithIndex.getString("explain").contains("Used index"));
        
        // The query with index should be faster, but we can't guarantee by how much in a test
        // So we just log the times for informational purposes
        System.out.println("Query without index: " + durationNoIndex / 1000000 + " ms");
        System.out.println("Query with index: " + durationWithIndex / 1000000 + " ms");
        
        // Test dropping the index
        List<JSONObject> dropIndexResult = QueryEngine.query("DROP INDEX ON " + TEST_DB + "." + TEST_TABLE + "(user_id)");
        assertNotNull(dropIndexResult);
        assertEquals(1, dropIndexResult.size());
        assertTrue(dropIndexResult.get(0).getBoolean("executed"));
        
        // Verify the index was dropped
        assertFalse(Serengeti.indexManager.hasIndex(TEST_DB, TEST_TABLE, "user_id"));
    }
    
    @Test
    public void testShowIndexes() {
        // Create multiple indexes
        QueryEngine.query("CREATE INDEX ON " + TEST_DB + "." + TEST_TABLE + "(user_id)");
        QueryEngine.query("CREATE INDEX ON " + TEST_DB + "." + TEST_TABLE + "(name)");
        QueryEngine.query("CREATE INDEX ON " + TEST_DB + "." + TEST_TABLE + "(age)");
        
        // Test SHOW INDEXES
        List<JSONObject> showIndexesResult = QueryEngine.query("SHOW INDEXES");
        assertNotNull(showIndexesResult);
        assertEquals(1, showIndexesResult.size());
        JSONObject resultObj = showIndexesResult.get(0);
        assertTrue(resultObj.has("executed"));
        assertTrue(resultObj.getBoolean("executed"));
        assertTrue(resultObj.has("list"));
        assertEquals(3, resultObj.getJSONArray("list").length());
        
        // Test SHOW INDEXES ON table
        List<JSONObject> showTableIndexesResult = QueryEngine.query("SHOW INDEXES ON " + TEST_DB + "." + TEST_TABLE);
        assertNotNull(showTableIndexesResult);
        assertEquals(1, showTableIndexesResult.size());
        JSONObject tableResultObj = showTableIndexesResult.get(0);
        assertTrue(tableResultObj.has("executed"));
        assertTrue(tableResultObj.getBoolean("executed"));
        assertTrue(tableResultObj.has("list"));
        assertEquals(3, tableResultObj.getJSONArray("list").length());
    }
    
    @Test
    public void testAutomaticIndexing() {
        // Configure automatic indexing with a low threshold for testing
        Serengeti.indexManager.setAutoIndexingEnabled(true);
        Serengeti.indexManager.setAutoIndexThreshold(5);
        
        // Run the same query multiple times to trigger automatic indexing
        for (int i = 0; i < 6; i++) {
            QueryEngine.query("SELECT * FROM " + TEST_DB + "." + TEST_TABLE + " WHERE email='user500@example.com'");
        }
        
        // Verify that an index was automatically created
        assertTrue(Serengeti.indexManager.hasIndex(TEST_DB, TEST_TABLE, "email"));
        
        // Run the query again and verify that the index is used
        List<JSONObject> result = QueryEngine.query("SELECT * FROM " + TEST_DB + "." + TEST_TABLE + " WHERE email='user500@example.com'");
        assertNotNull(result);
        assertEquals(1, result.size());
        JSONObject resultObj = result.get(0);
        assertTrue(resultObj.has("explain"));
        assertTrue(resultObj.getString("explain").contains("Used index"));
    }
    
    /**
     * Creates test database, table, and data
     */
    private void createTestData() {
        // Create database
        Serengeti.storage.createDatabase(TEST_DB);
        
        // Create table
        Serengeti.storage.createTable(TEST_DB, TEST_TABLE);
        
        // Insert test data
        for (int i = 0; i < NUM_ROWS; i++) {
            JSONObject json = new JSONObject();
            json.put("user_id", "user" + i);
            json.put("name", "User " + i);
            json.put("age", 20 + (i % 50));
            json.put("email", "user" + i + "@example.com");
            json.put("address", "Address " + i);
            json.put("phone", "555-" + String.format("%04d", i));
            
            Serengeti.storage.insert(TEST_DB, TEST_TABLE, json);
        }
    }
}