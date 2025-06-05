package com.ataiva.serengeti.performance;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.query.QueryEngine;
import com.ataiva.serengeti.utils.TestBase;
import org.json.JSONObject;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Performance tests for the Query Optimization system.
 * These tests measure the performance impact of query optimization.
 */
@DisplayName("Query Optimization Performance Tests")
public class QueryOptimizationPerformanceTest extends TestBase {
    
    private static final int NUM_ROWS = 10000;
    private static final int NUM_QUERIES = 100;
    private static final String TEST_DB = "perf_test_db";
    private static final String TEST_TABLE = "perf_test_table";
    
    @BeforeAll
    public static void setUpTestData() throws Exception {
        // Create test database and table
        if (!Serengeti.storage.databaseExists(TEST_DB)) {
            Serengeti.storage.createDatabase(TEST_DB);
        }
        
        if (!Serengeti.storage.tableExists(TEST_DB, TEST_TABLE)) {
            Serengeti.storage.createTable(TEST_DB, TEST_TABLE);
        }
        
        // Generate test data
        Random random = new Random(42); // Use fixed seed for reproducibility
        for (int i = 0; i < NUM_ROWS; i++) {
            JSONObject data = new JSONObject();
            data.put("id", "id" + i);
            data.put("name", "name" + random.nextInt(1000));
            data.put("category", "category" + random.nextInt(10));
            data.put("value", random.nextInt(10000));
            data.put("description", generateRandomString(random, 50));
            
            Serengeti.storage.insert(TEST_DB, TEST_TABLE, data);
        }
        
        // Create indexes for some columns
        Serengeti.indexManager.createIndex(TEST_DB, TEST_TABLE, "id", null);
        Serengeti.indexManager.createIndex(TEST_DB, TEST_TABLE, "category", null);
        
        // Initialize the query engine
        QueryEngine.initialize();
        
        // Collect statistics
        QueryEngine.query("statistics collect");
    }
    
    @AfterAll
    public static void tearDownTestData() throws Exception {
        // Clean up
        if (Serengeti.storage.tableExists(TEST_DB, TEST_TABLE)) {
            Serengeti.storage.dropTable(TEST_DB, TEST_TABLE);
        }
        
        if (Serengeti.storage.databaseExists(TEST_DB)) {
            Serengeti.storage.dropDatabase(TEST_DB);
        }
        
        // Shutdown the query engine
        QueryEngine.shutdown();
    }
    
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        
        // Clear cache before each test
        QueryEngine.query("cache clear");
    }
    
    @Test
    @DisplayName("Test equality query performance with optimization")
    void testEqualityQueryPerformanceWithOptimization() {
        // Enable optimization
        QueryEngine.query("optimization enable");
        
        // Prepare queries
        List<String> queries = generateEqualityQueries(NUM_QUERIES);
        
        // Measure execution time
        long startTime = System.nanoTime();
        
        for (String query : queries) {
            List<JSONObject> result = QueryEngine.query(query);
            assertNotNull(result);
        }
        
        long endTime = System.nanoTime();
        long optimizedTime = endTime - startTime;
        
        System.out.println("Equality queries with optimization: " + optimizedTime / 1_000_000 + " ms");
    }
    
    @Test
    @DisplayName("Test equality query performance without optimization")
    void testEqualityQueryPerformanceWithoutOptimization() {
        // Disable optimization
        QueryEngine.query("optimization disable");
        
        // Prepare queries
        List<String> queries = generateEqualityQueries(NUM_QUERIES);
        
        // Measure execution time
        long startTime = System.nanoTime();
        
        for (String query : queries) {
            List<JSONObject> result = QueryEngine.query(query);
            assertNotNull(result);
        }
        
        long endTime = System.nanoTime();
        long unoptimizedTime = endTime - startTime;
        
        System.out.println("Equality queries without optimization: " + unoptimizedTime / 1_000_000 + " ms");
    }
    
    @Test
    @DisplayName("Test range query performance with optimization")
    void testRangeQueryPerformanceWithOptimization() {
        // Enable optimization
        QueryEngine.query("optimization enable");
        
        // Prepare queries
        List<String> queries = generateRangeQueries(NUM_QUERIES);
        
        // Measure execution time
        long startTime = System.nanoTime();
        
        for (String query : queries) {
            List<JSONObject> result = QueryEngine.query(query);
            assertNotNull(result);
        }
        
        long endTime = System.nanoTime();
        long optimizedTime = endTime - startTime;
        
        System.out.println("Range queries with optimization: " + optimizedTime / 1_000_000 + " ms");
    }
    
    @Test
    @DisplayName("Test range query performance without optimization")
    void testRangeQueryPerformanceWithoutOptimization() {
        // Disable optimization
        QueryEngine.query("optimization disable");
        
        // Prepare queries
        List<String> queries = generateRangeQueries(NUM_QUERIES);
        
        // Measure execution time
        long startTime = System.nanoTime();
        
        for (String query : queries) {
            List<JSONObject> result = QueryEngine.query(query);
            assertNotNull(result);
        }
        
        long endTime = System.nanoTime();
        long unoptimizedTime = endTime - startTime;
        
        System.out.println("Range queries without optimization: " + unoptimizedTime / 1_000_000 + " ms");
    }
    
    @Test
    @DisplayName("Test query caching performance")
    void testQueryCachingPerformance() {
        // Enable optimization and caching
        QueryEngine.query("optimization enable");
        QueryEngine.query("cache enable");
        
        // Prepare queries (using the same query multiple times)
        List<String> queries = new ArrayList<>();
        for (int i = 0; i < NUM_QUERIES; i++) {
            queries.add("select * from " + TEST_DB + "." + TEST_TABLE + " where id='id" + (i % 10) + "'");
        }
        
        // Measure execution time
        long startTime = System.nanoTime();
        
        for (String query : queries) {
            List<JSONObject> result = QueryEngine.query(query);
            assertNotNull(result);
        }
        
        long endTime = System.nanoTime();
        long cachedTime = endTime - startTime;
        
        System.out.println("Queries with caching: " + cachedTime / 1_000_000 + " ms");
        
        // Get cache stats
        List<JSONObject> cacheStats = QueryEngine.query("cache stats");
        System.out.println("Cache stats: " + cacheStats.get(0).getString("explain"));
    }
    
    /**
     * Generate random equality queries
     * @param count Number of queries to generate
     * @return List of query strings
     */
    private List<String> generateEqualityQueries(int count) {
        List<String> queries = new ArrayList<>();
        Random random = new Random(42); // Use fixed seed for reproducibility
        
        for (int i = 0; i < count; i++) {
            int id = random.nextInt(NUM_ROWS);
            queries.add("select * from " + TEST_DB + "." + TEST_TABLE + " where id='id" + id + "'");
        }
        
        return queries;
    }
    
    /**
     * Generate random range queries
     * @param count Number of queries to generate
     * @return List of query strings
     */
    private List<String> generateRangeQueries(int count) {
        List<String> queries = new ArrayList<>();
        Random random = new Random(42); // Use fixed seed for reproducibility
        
        for (int i = 0; i < count; i++) {
            int value = random.nextInt(10000);
            queries.add("select * from " + TEST_DB + "." + TEST_TABLE + " where value>" + value);
        }
        
        return queries;
    }
    
    /**
     * Generate a random string
     * @param random Random generator
     * @param length Length of the string
     * @return Random string
     */
    private static String generateRandomString(Random random, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = (char) ('a' + random.nextInt(26));
            sb.append(c);
        }
        return sb.toString();
    }
}