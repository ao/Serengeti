package com.ataiva.serengeti.unit.query;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.mocks.MockNetwork;
import com.ataiva.serengeti.mocks.MockStorage;
import com.ataiva.serengeti.query.QueryEngine;
import com.ataiva.serengeti.query.cache.QueryCache;
import com.ataiva.serengeti.query.optimizer.QueryPlan;
import com.ataiva.serengeti.query.optimizer.QueryPlanGenerator;
import com.ataiva.serengeti.query.statistics.StatisticsManager;
import com.ataiva.serengeti.utils.QueryTestUtils;
import com.ataiva.serengeti.utils.TestBase;
import org.json.JSONObject;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Query Optimizer component.
 */
@DisplayName("Query Optimizer Tests")
class QueryOptimizerTest extends TestBase {
    
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
        
        // Insert some test data
        for (int i = 0; i < 10; i++) {
            JSONObject data = new JSONObject();
            data.put("id", "id" + i);
            data.put("name", "name" + i);
            data.put("value", i * 10);
            mockStorage.insert(testDb, testTable, data);
        }
        
        // Initialize the query engine
        QueryEngine.initialize();
    }
    
    @AfterEach
    public void tearDown() throws Exception {
        // Shutdown the query engine
        QueryEngine.shutdown();
        
        super.tearDown();
    }
    
    @Nested
    @DisplayName("Query Optimization Tests")
    class QueryOptimizationTests {
        
        @Test
        @DisplayName("Test optimization commands")
        void testOptimizationCommands() {
            // Test optimization status
            List<JSONObject> result = QueryEngine.query("optimization status");
            assertNotNull(result);
            assertEquals(1, result.size());
            assertTrue(result.get(0).getString("explain").contains("Optimization:"));
            
            // Test enabling optimization
            result = QueryEngine.query("optimization enable");
            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("Query optimization enabled", result.get(0).getString("explain"));
            
            // Test disabling optimization
            result = QueryEngine.query("optimization disable");
            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("Query optimization disabled", result.get(0).getString("explain"));
            
            // Test setting optimization level
            result = QueryEngine.query("optimization level medium");
            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("Optimization level set to MEDIUM", result.get(0).getString("explain"));
        }
        
        @Test
        @DisplayName("Test cache commands")
        void testCacheCommands() {
            // Test cache enable
            List<JSONObject> result = QueryEngine.query("cache enable");
            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("Query caching enabled", result.get(0).getString("explain"));
            
            // Test cache disable
            result = QueryEngine.query("cache disable");
            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("Query caching disabled and cache cleared", result.get(0).getString("explain"));
            
            // Test cache clear
            result = QueryEngine.query("cache enable");
            result = QueryEngine.query("cache clear");
            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("Query cache cleared", result.get(0).getString("explain"));
            
            // Test cache stats
            result = QueryEngine.query("cache stats");
            assertNotNull(result);
            assertEquals(1, result.size());
            assertTrue(result.get(0).getString("explain").contains("Cache size:"));
        }
        
        @Test
        @DisplayName("Test statistics collection")
        void testStatisticsCollection() {
            // Test statistics collection
            List<JSONObject> result = QueryEngine.query("statistics collect");
            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("Statistics collection started", result.get(0).getString("explain"));
            
            // Verify that statistics were collected
            StatisticsManager statsManager = StatisticsManager.getInstance();
            assertNotNull(statsManager.getTableStatistics(testDb, testTable));
            assertNotNull(statsManager.getColumnStatistics(testDb, testTable, "id"));
        }
        
        @Test
        @DisplayName("Test query caching")
        void testQueryCaching() {
            // Enable caching
            QueryEngine.query("cache enable");
            
            // Execute a query
            String query = "select * from " + testDb + "." + testTable + " where id='id1'";
            List<JSONObject> result1 = QueryEngine.query(query);
            assertNotNull(result1);
            assertEquals(1, result1.size());
            
            // Execute the same query again
            List<JSONObject> result2 = QueryEngine.query(query);
            assertNotNull(result2);
            assertEquals(1, result2.size());
            
            // Verify that the second query used the cache
            assertTrue(result2.get(0).getString("explain").contains("Result retrieved from cache"));
            
            // Verify cache stats
            List<JSONObject> cacheStats = QueryEngine.query("cache stats");
            assertTrue(cacheStats.get(0).getString("explain").contains("Hits: 1"));
        }
        
        @Test
        @DisplayName("Test query plan generation")
        void testQueryPlanGeneration() {
            // Enable optimization
            QueryEngine.query("optimization enable");
            
            // Generate a query plan
            QueryPlanGenerator planGenerator = QueryPlanGenerator.getInstance();
            QueryPlan plan = planGenerator.generateSelectPlan(
                testDb, testTable, "*", "id", "id1", "=");
            
            // Verify the plan
            assertNotNull(plan);
            assertEquals(testDb, plan.getDatabase());
            assertEquals(testTable, plan.getTable());
            assertEquals("*", plan.getSelectColumns());
            assertEquals("id", plan.getWhereColumn());
            assertEquals("id1", plan.getWhereValue());
            assertEquals("=", plan.getWhereOperator());
            
            // Verify that the plan has operations
            assertFalse(plan.getOperations().isEmpty());
        }
        
        @Test
        @DisplayName("Test optimized query execution")
        void testOptimizedQueryExecution() {
            // Enable optimization
            QueryEngine.query("optimization enable");
            
            // Execute a query
            String query = "select * from " + testDb + "." + testTable + " where id='id1'";
            List<JSONObject> result = QueryEngine.query(query);
            
            // Verify the result
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            assertNotNull(response.getString("explain"));
            
            // Verify that the query used the optimizer
            assertTrue(response.getString("explain").contains("Query Plan:"));
        }
    }
    
    /**
     * Generate a random database name for testing
     * @return Random database name
     */
    private String generateRandomDatabaseName() {
        return "test_db_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
    
    /**
     * Generate a random table name for testing
     * @return Random table name
     */
    private String generateRandomTableName() {
        return "test_table_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}