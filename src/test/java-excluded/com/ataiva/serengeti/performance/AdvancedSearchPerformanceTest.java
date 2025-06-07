package com.ataiva.serengeti.performance;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.index.IndexManager;
import com.ataiva.serengeti.query.QueryEngine;
import com.ataiva.serengeti.search.FullTextSearch;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.utils.TestBase;
import org.json.JSONObject;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for the advanced search features.
 * These tests measure the performance of range queries, full-text search,
 * regex matching, and fuzzy matching with large datasets.
 */
@DisplayName("Advanced Search Performance Tests")
@Tag("performance")
public class AdvancedSearchPerformanceTest extends TestBase {
    
    private static final String TEST_DB = "perf_test_db";
    private static final String TEST_TABLE = "perf_test_table";
    private static final int NUM_RECORDS = 1000; // Number of test records to create
    
    private static final String[] DESCRIPTIONS = {
        "Senior software engineer with extensive experience in Java and distributed systems",
        "Junior developer with knowledge of web technologies and frontend frameworks",
        "DevOps engineer specializing in CI/CD pipelines and cloud infrastructure",
        "Data scientist with expertise in machine learning and statistical analysis",
        "Product manager with background in agile methodologies and user research",
        "UX designer focused on creating intuitive and accessible user interfaces",
        "QA engineer with experience in automated testing and quality assurance",
        "Database administrator with expertise in SQL and NoSQL databases",
        "Security specialist with knowledge of encryption and network security",
        "Technical writer with experience in API documentation and user guides"
    };
    
    @BeforeAll
    public static void setUpTestData() throws Exception {
        // Initialize components
        Serengeti.storage = new Storage();
        Serengeti.indexManager = new IndexManager();
        Serengeti.fullTextSearch = new FullTextSearch();
        
        // Create test database and table
        Serengeti.storage.createDatabase(TEST_DB);
        Serengeti.storage.createTable(TEST_DB, TEST_TABLE);
        
        // Generate and insert test data
        Random random = new Random(42); // Use fixed seed for reproducibility
        for (int i = 0; i < NUM_RECORDS; i++) {
            String id = String.valueOf(i);
            String name = generateRandomName(random);
            int age = 20 + random.nextInt(40); // Ages between 20 and 59
            String email = generateRandomEmail(name, random);
            String description = DESCRIPTIONS[random.nextInt(DESCRIPTIONS.length)];
            
            JSONObject record = new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("age", age)
                .put("email", email)
                .put("description", description)
                .put("created_at", System.currentTimeMillis() - random.nextInt(10000000))
                .put("status", random.nextBoolean() ? "active" : "inactive")
                .put("score", random.nextInt(100));
            
            Serengeti.storage.insert(TEST_DB, TEST_TABLE, record);
        }
        
        // Create indexes
        QueryEngine.query("create index on " + TEST_DB + "." + TEST_TABLE + "(age)");
        QueryEngine.query("create index on " + TEST_DB + "." + TEST_TABLE + "(score)");
        QueryEngine.query("create fulltext index on " + TEST_DB + "." + TEST_TABLE + "(description)");
    }
    
    @AfterAll
    public static void tearDown() {
        // Clean up test data
        Serengeti.storage.dropDatabase(TEST_DB);
    }
    
    @Nested
    @DisplayName("Range Query Performance Tests")
    class RangeQueryPerformanceTests {
        
        @Test
        @DisplayName("Range query with index")
        void testRangeQueryWithIndex() {
            // Measure performance of range query with index
            long startTime = System.nanoTime();
            
            List<JSONObject> result = QueryEngine.query(
                "select * from " + TEST_DB + "." + TEST_TABLE + " where age>30 and age<40"
            );
            
            long endTime = System.nanoTime();
            long duration = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            
            // Verify results
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            
            // Log performance metrics
            System.out.println("Range query with index execution time: " + duration + " ms");
            System.out.println("Number of results: " + response.getJSONArray("list").length());
            
            // Performance assertion - should be reasonably fast
            assertTrue(duration < 1000, "Range query with index should execute in less than 1000ms");
        }
        
        @Test
        @DisplayName("Range query without index")
        void testRangeQueryWithoutIndex() {
            // Measure performance of range query without index
            long startTime = System.nanoTime();
            
            List<JSONObject> result = QueryEngine.query(
                "select * from " + TEST_DB + "." + TEST_TABLE + " where created_at>1000000"
            );
            
            long endTime = System.nanoTime();
            long duration = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            
            // Verify results
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            
            // Log performance metrics
            System.out.println("Range query without index execution time: " + duration + " ms");
            System.out.println("Number of results: " + response.getJSONArray("list").length());
            
            // Performance comparison - should be slower than indexed query but still reasonable
            assertTrue(duration < 2000, "Range query without index should execute in less than 2000ms");
        }
    }
    
    @Nested
    @DisplayName("Full-Text Search Performance Tests")
    class FullTextSearchPerformanceTests {
        
        @Test
        @DisplayName("Full-text search with index")
        void testFullTextSearchWithIndex() {
            // Measure performance of full-text search with index
            long startTime = System.nanoTime();
            
            List<JSONObject> result = QueryEngine.query(
                "select * from " + TEST_DB + "." + TEST_TABLE + " where description CONTAINS 'engineer'"
            );
            
            long endTime = System.nanoTime();
            long duration = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            
            // Verify results
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            
            // Log performance metrics
            System.out.println("Full-text search with index execution time: " + duration + " ms");
            System.out.println("Number of results: " + response.getJSONArray("list").length());
            
            // Performance assertion - should be reasonably fast
            assertTrue(duration < 1000, "Full-text search with index should execute in less than 1000ms");
        }
    }
    
    @Nested
    @DisplayName("Regex Matching Performance Tests")
    class RegexMatchingPerformanceTests {
        
        @Test
        @DisplayName("Regex matching performance")
        void testRegexMatchingPerformance() {
            // Measure performance of regex matching
            long startTime = System.nanoTime();
            
            List<JSONObject> result = QueryEngine.query(
                "select * from " + TEST_DB + "." + TEST_TABLE + " where email REGEX '.*@example\\.com'"
            );
            
            long endTime = System.nanoTime();
            long duration = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            
            // Verify results
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            
            // Log performance metrics
            System.out.println("Regex matching execution time: " + duration + " ms");
            System.out.println("Number of results: " + response.getJSONArray("list").length());
            
            // Performance assertion - regex can be slower but should still be reasonable
            assertTrue(duration < 2000, "Regex matching should execute in less than 2000ms");
        }
        
        @Test
        @DisplayName("Complex regex matching performance")
        void testComplexRegexMatchingPerformance() {
            // Measure performance of complex regex matching
            long startTime = System.nanoTime();
            
            List<JSONObject> result = QueryEngine.query(
                "select * from " + TEST_DB + "." + TEST_TABLE + " where name REGEX '^[A-M][a-z]+ [A-Z][a-z]+$'"
            );
            
            long endTime = System.nanoTime();
            long duration = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            
            // Verify results
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            
            // Log performance metrics
            System.out.println("Complex regex matching execution time: " + duration + " ms");
            System.out.println("Number of results: " + response.getJSONArray("list").length());
            
            // Performance assertion - complex regex can be slower
            assertTrue(duration < 3000, "Complex regex matching should execute in less than 3000ms");
        }
    }
    
    @Nested
    @DisplayName("Fuzzy Matching Performance Tests")
    class FuzzyMatchingPerformanceTests {
        
        @Test
        @DisplayName("Fuzzy matching performance")
        void testFuzzyMatchingPerformance() {
            // Measure performance of fuzzy matching
            long startTime = System.nanoTime();
            
            List<JSONObject> result = QueryEngine.query(
                "select * from " + TEST_DB + "." + TEST_TABLE + " where name FUZZY 'John Smith'"
            );
            
            long endTime = System.nanoTime();
            long duration = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            
            // Verify results
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            
            // Log performance metrics
            System.out.println("Fuzzy matching execution time: " + duration + " ms");
            System.out.println("Number of results: " + response.getJSONArray("list").length());
            
            // Performance assertion - fuzzy matching can be computationally expensive
            assertTrue(duration < 3000, "Fuzzy matching should execute in less than 3000ms");
        }
    }
    
    @Nested
    @DisplayName("Combined Query Performance Tests")
    class CombinedQueryPerformanceTests {
        
        @Test
        @DisplayName("Multiple queries in sequence")
        void testMultipleQueriesInSequence() {
            // Measure performance of multiple queries in sequence
            long startTime = System.nanoTime();
            
            // Run multiple different types of queries
            QueryEngine.query("select * from " + TEST_DB + "." + TEST_TABLE + " where age>40");
            QueryEngine.query("select * from " + TEST_DB + "." + TEST_TABLE + " where description CONTAINS 'engineer'");
            QueryEngine.query("select * from " + TEST_DB + "." + TEST_TABLE + " where email REGEX '.*@example\\.com'");
            QueryEngine.query("select * from " + TEST_DB + "." + TEST_TABLE + " where name FUZZY 'John Smith'");
            
            long endTime = System.nanoTime();
            long duration = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            
            // Log performance metrics
            System.out.println("Multiple queries in sequence execution time: " + duration + " ms");
            
            // Performance assertion - combined queries should be reasonably fast
            assertTrue(duration < 5000, "Multiple queries in sequence should execute in less than 5000ms");
        }
    }
    
    // Helper methods for generating test data
    
    private static String generateRandomName(Random random) {
        String[] firstNames = {"John", "Jane", "Michael", "Emily", "David", "Sarah", "Robert", "Lisa", "William", "Mary"};
        String[] lastNames = {"Smith", "Johnson", "Brown", "Davis", "Wilson", "Miller", "Moore", "Taylor", "Anderson", "Thomas"};
        
        return firstNames[random.nextInt(firstNames.length)] + " " + lastNames[random.nextInt(lastNames.length)];
    }
    
    private static String generateRandomEmail(String name, Random random) {
        String[] domains = {"example.com", "test.com", "mail.org", "domain.net"};
        String namePart = name.toLowerCase().replace(" ", ".");
        return namePart + "@" + domains[random.nextInt(domains.length)];
    }
}