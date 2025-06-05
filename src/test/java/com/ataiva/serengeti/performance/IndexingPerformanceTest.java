package com.ataiva.serengeti.performance;

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

import java.util.List;
import java.util.Random;

/**
 * Performance test for the indexing system.
 * Measures the performance impact of using indexes for queries.
 */
public class IndexingPerformanceTest {
    
    private static final String TEST_DB = "perftest_db";
    private static final String TEST_TABLE = "perftest_table";
    private static final int NUM_ROWS = 10000;
    private static final int NUM_QUERIES = 100;
    
    private Random random = new Random();
    
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
    public void testIndexPerformance() {
        System.out.println("=== Indexing Performance Test ===");
        System.out.println("Database: " + TEST_DB);
        System.out.println("Table: " + TEST_TABLE);
        System.out.println("Rows: " + NUM_ROWS);
        System.out.println("Queries: " + NUM_QUERIES);
        System.out.println();
        
        // Test queries on different columns
        testColumnPerformance("user_id");
        testColumnPerformance("email");
        testColumnPerformance("age");
        testColumnPerformance("status");
    }
    
    /**
     * Tests query performance on a specific column with and without an index
     */
    private void testColumnPerformance(String column) {
        System.out.println("Testing column: " + column);
        
        // Run queries without an index
        long startTimeNoIndex = System.currentTimeMillis();
        int matchesNoIndex = runRandomQueries(column, false);
        long endTimeNoIndex = System.currentTimeMillis();
        long durationNoIndex = endTimeNoIndex - startTimeNoIndex;
        
        // Create an index
        List<JSONObject> createIndexResult = QueryEngine.query("CREATE INDEX ON " + TEST_DB + "." + TEST_TABLE + "(" + column + ")");
        boolean indexCreated = createIndexResult.get(0).getBoolean("executed");
        
        if (!indexCreated) {
            System.out.println("Failed to create index on " + column);
            return;
        }
        
        // Run queries with an index
        long startTimeWithIndex = System.currentTimeMillis();
        int matchesWithIndex = runRandomQueries(column, true);
        long endTimeWithIndex = System.currentTimeMillis();
        long durationWithIndex = endTimeWithIndex - startTimeWithIndex;
        
        // Calculate performance improvement
        double improvement = 100.0 * (durationNoIndex - durationWithIndex) / durationNoIndex;
        
        // Print results
        System.out.println("Without index: " + durationNoIndex + " ms, " + matchesNoIndex + " matches");
        System.out.println("With index: " + durationWithIndex + " ms, " + matchesWithIndex + " matches");
        System.out.println("Improvement: " + String.format("%.2f", improvement) + "%");
        System.out.println();
        
        // Drop the index
        QueryEngine.query("DROP INDEX ON " + TEST_DB + "." + TEST_TABLE + "(" + column + ")");
    }
    
    /**
     * Runs random queries on a column
     */
    private int runRandomQueries(String column, boolean expectIndex) {
        int totalMatches = 0;
        
        for (int i = 0; i < NUM_QUERIES; i++) {
            String value;
            
            // Generate a random value based on the column type
            if (column.equals("age")) {
                value = String.valueOf(20 + random.nextInt(60));
            } else if (column.equals("status")) {
                String[] statuses = {"active", "inactive", "pending", "suspended"};
                value = statuses[random.nextInt(statuses.length)];
            } else {
                // For user_id and email, use an existing value
                int userId = random.nextInt(NUM_ROWS);
                if (column.equals("user_id")) {
                    value = "user" + userId;
                } else {
                    value = "user" + userId + "@example.com";
                }
            }
            
            // Run the query
            List<JSONObject> result = QueryEngine.query("SELECT * FROM " + TEST_DB + "." + TEST_TABLE + " WHERE " + column + "='" + value + "'");
            
            // Check if the index was used
            if (expectIndex) {
                JSONObject resultObj = result.get(0);
                if (resultObj.has("explain")) {
                    String explain = resultObj.getString("explain");
                    if (!explain.contains("Used index")) {
                        System.out.println("Warning: Index not used for query on " + column);
                    }
                }
            }
            
            // Count matches
            if (result.get(0).has("list")) {
                totalMatches += result.get(0).getJSONArray("list").length();
            }
        }
        
        return totalMatches;
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
            json.put("age", 20 + (i % 60));
            json.put("email", "user" + i + "@example.com");
            json.put("address", "Address " + i);
            json.put("phone", "555-" + String.format("%04d", i));
            
            // Add a status field with limited cardinality
            String[] statuses = {"active", "inactive", "pending", "suspended"};
            json.put("status", statuses[i % statuses.length]);
            
            Serengeti.storage.insert(TEST_DB, TEST_TABLE, json);
        }
    }
}