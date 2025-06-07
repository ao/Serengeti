package com.ataiva.serengeti.unit.query;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.index.IndexManager;
import com.ataiva.serengeti.mocks.MockNetwork;
import com.ataiva.serengeti.mocks.MockStorage;
import com.ataiva.serengeti.query.QueryEngine;
import com.ataiva.serengeti.search.FullTextSearch;
import com.ataiva.serengeti.utils.QueryTestUtils;
import com.ataiva.serengeti.utils.TestBase;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the advanced search features in QueryEngine.
 */
@DisplayName("Advanced Search Tests")
class AdvancedSearchTest extends TestBase {
    
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
        
        // Initialize the index manager and full-text search
        Serengeti.indexManager = new IndexManager();
        Serengeti.fullTextSearch = new FullTextSearch();
        
        // Create unique test database and table names for each test
        testDb = generateRandomDatabaseName();
        testTable = generateRandomTableName();
        
        // Create the test database and table
        mockStorage.createDatabase(testDb);
        mockStorage.createTable(testDb, testTable);
        
        // Insert test data
        mockStorage.insert(testDb, testTable, new JSONObject()
            .put("id", "1")
            .put("name", "John Smith")
            .put("age", 30)
            .put("email", "john.smith@example.com")
            .put("description", "Senior software engineer with 10 years of experience"));
        
        mockStorage.insert(testDb, testTable, new JSONObject()
            .put("id", "2")
            .put("name", "Jane Doe")
            .put("age", 25)
            .put("email", "jane.doe@example.com")
            .put("description", "Junior software developer with 2 years of experience"));
        
        mockStorage.insert(testDb, testTable, new JSONObject()
            .put("id", "3")
            .put("name", "Bob Johnson")
            .put("age", 40)
            .put("email", "bob.johnson@example.com")
            .put("description", "Product manager with background in software development"));
        
        mockStorage.insert(testDb, testTable, new JSONObject()
            .put("id", "4")
            .put("name", "Alice Brown")
            .put("age", 35)
            .put("email", "alice.brown@example.com")
            .put("description", "UX designer with focus on user research"));
    }
    
    @Nested
    @DisplayName("Range Query Tests")
    class RangeQueryTests {
        
        @Test
        @DisplayName("Greater than operator")
        void testGreaterThanOperator() {
            // Create an index on the age column
            String createIndexQuery = "create index on " + testDb + "." + testTable + "(age)";
            List<JSONObject> createResult = QueryEngine.query(createIndexQuery);
            assertTrue(createResult.get(0).getBoolean("executed"));
            
            // Query for ages > 30
            String query = "select * from " + testDb + "." + testTable + " where age>30";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            
            List<String> rows = QueryTestUtils.getRowsFromResponse(response);
            assertEquals(2, rows.size());
            
            // Verify that all returned rows have age > 30
            for (String rowStr : rows) {
                JSONObject row = new JSONObject(rowStr);
                int age = row.getInt("age");
                assertTrue(age > 30);
            }
        }
        
        @Test
        @DisplayName("Less than operator")
        void testLessThanOperator() {
            // Create an index on the age column
            String createIndexQuery = "create index on " + testDb + "." + testTable + "(age)";
            List<JSONObject> createResult = QueryEngine.query(createIndexQuery);
            assertTrue(createResult.get(0).getBoolean("executed"));
            
            // Query for ages < 30
            String query = "select * from " + testDb + "." + testTable + " where age<30";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            
            List<String> rows = QueryTestUtils.getRowsFromResponse(response);
            assertEquals(1, rows.size());
            
            // Verify that all returned rows have age < 30
            for (String rowStr : rows) {
                JSONObject row = new JSONObject(rowStr);
                int age = row.getInt("age");
                assertTrue(age < 30);
            }
        }
        
        @Test
        @DisplayName("Greater than or equal operator")
        void testGreaterThanOrEqualOperator() {
            // Create an index on the age column
            String createIndexQuery = "create index on " + testDb + "." + testTable + "(age)";
            List<JSONObject> createResult = QueryEngine.query(createIndexQuery);
            assertTrue(createResult.get(0).getBoolean("executed"));
            
            // Query for ages >= 35
            String query = "select * from " + testDb + "." + testTable + " where age>=35";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            
            List<String> rows = QueryTestUtils.getRowsFromResponse(response);
            assertEquals(2, rows.size());
            
            // Verify that all returned rows have age >= 35
            for (String rowStr : rows) {
                JSONObject row = new JSONObject(rowStr);
                int age = row.getInt("age");
                assertTrue(age >= 35);
            }
        }
        
        @Test
        @DisplayName("Less than or equal operator")
        void testLessThanOrEqualOperator() {
            // Create an index on the age column
            String createIndexQuery = "create index on " + testDb + "." + testTable + "(age)";
            List<JSONObject> createResult = QueryEngine.query(createIndexQuery);
            assertTrue(createResult.get(0).getBoolean("executed"));
            
            // Query for ages <= 30
            String query = "select * from " + testDb + "." + testTable + " where age<=30";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            
            List<String> rows = QueryTestUtils.getRowsFromResponse(response);
            assertEquals(2, rows.size());
            
            // Verify that all returned rows have age <= 30
            for (String rowStr : rows) {
                JSONObject row = new JSONObject(rowStr);
                int age = row.getInt("age");
                assertTrue(age <= 30);
            }
        }
    }
    
    @Nested
    @DisplayName("Full-Text Search Tests")
    class FullTextSearchTests {
        
        @Test
        @DisplayName("Create full-text index")
        void testCreateFullTextIndex() {
            String query = "create fulltext index on " + testDb + "." + testTable + "(description)";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
        }
        
        @Test
        @DisplayName("Full-text search with CONTAINS operator")
        void testFullTextSearch() {
            // Create a full-text index on the description column
            String createIndexQuery = "create fulltext index on " + testDb + "." + testTable + "(description)";
            List<JSONObject> createResult = QueryEngine.query(createIndexQuery);
            assertTrue(createResult.get(0).getBoolean("executed"));
            
            // Search for descriptions containing "software"
            String query = "select * from " + testDb + "." + testTable + " where description CONTAINS 'software'";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            
            List<String> rows = QueryTestUtils.getRowsFromResponse(response);
            assertEquals(3, rows.size());
            
            // Verify that all returned rows have "software" in the description
            for (String rowStr : rows) {
                JSONObject row = new JSONObject(rowStr);
                String description = row.getString("description");
                assertTrue(description.toLowerCase().contains("software"));
            }
        }
        
        @Test
        @DisplayName("Show full-text indexes")
        void testShowFullTextIndexes() {
            // Create a full-text index on the description column
            String createIndexQuery = "create fulltext index on " + testDb + "." + testTable + "(description)";
            List<JSONObject> createResult = QueryEngine.query(createIndexQuery);
            assertTrue(createResult.get(0).getBoolean("executed"));
            
            // Show all full-text indexes
            String query = "show fulltext indexes";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            
            List<String> indexes = QueryTestUtils.getRowsFromResponse(response);
            assertTrue(indexes.size() > 0);
        }
        
        @Test
        @DisplayName("Drop full-text index")
        void testDropFullTextIndex() {
            // Create a full-text index on the description column
            String createIndexQuery = "create fulltext index on " + testDb + "." + testTable + "(description)";
            List<JSONObject> createResult = QueryEngine.query(createIndexQuery);
            assertTrue(createResult.get(0).getBoolean("executed"));
            
            // Drop the full-text index
            String dropQuery = "drop fulltext index on " + testDb + "." + testTable + "(description)";
            List<JSONObject> dropResult = QueryEngine.query(dropQuery);
            
            assertNotNull(dropResult);
            assertEquals(1, dropResult.size());
            JSONObject response = dropResult.get(0);
            assertTrue(response.getBoolean("executed"));
        }
    }
    
    @Nested
    @DisplayName("Regex Matching Tests")
    class RegexMatchingTests {
        
        @Test
        @DisplayName("Regex matching with REGEX operator")
        void testRegexMatching() {
            // Search for email addresses matching a pattern
            String query = "select * from " + testDb + "." + testTable + " where email REGEX '.*@example\\.com'";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            
            List<String> rows = QueryTestUtils.getRowsFromResponse(response);
            assertEquals(4, rows.size());
            
            // Verify that all returned rows have email addresses matching the pattern
            for (String rowStr : rows) {
                JSONObject row = new JSONObject(rowStr);
                String email = row.getString("email");
                assertTrue(email.matches(".*@example\\.com"));
            }
        }
        
        @Test
        @DisplayName("Regex matching with complex pattern")
        void testComplexRegexMatching() {
            // Search for names with a specific pattern
            String query = "select * from " + testDb + "." + testTable + " where name REGEX '^[A-J]\\w+ [A-Z]\\w+$'";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            
            List<String> rows = QueryTestUtils.getRowsFromResponse(response);
            assertEquals(3, rows.size());
            
            // Verify that all returned rows have names matching the pattern
            for (String rowStr : rows) {
                JSONObject row = new JSONObject(rowStr);
                String name = row.getString("name");
                assertTrue(name.matches("^[A-J]\\w+ [A-Z]\\w+$"));
            }
        }
        
        @Test
        @DisplayName("Invalid regex pattern")
        void testInvalidRegexPattern() {
            // Search with an invalid regex pattern
            String query = "select * from " + testDb + "." + testTable + " where name REGEX '['";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertFalse(response.getBoolean("executed"));
            assertTrue(response.has("error"));
        }
    }
    
    @Nested
    @DisplayName("Fuzzy Matching Tests")
    class FuzzyMatchingTests {
        
        @Test
        @DisplayName("Fuzzy matching with FUZZY operator")
        void testFuzzyMatching() {
            // Search for names similar to "Jon Smith"
            String query = "select * from " + testDb + "." + testTable + " where name FUZZY 'Jon Smith'";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            
            List<String> rows = QueryTestUtils.getRowsFromResponse(response);
            assertTrue(rows.size() > 0);
            
            // Verify that the first result is the closest match
            JSONObject firstRow = new JSONObject(rows.get(0));
            assertEquals("John Smith", firstRow.getString("name"));
            assertTrue(firstRow.has("__fuzzy_distance"));
        }
        
        @Test
        @DisplayName("Fuzzy matching with approximate description")
        void testFuzzyMatchingDescription() {
            // Search for descriptions similar to "software engineer experience"
            String query = "select * from " + testDb + "." + testTable + " where description FUZZY 'software engineer experience'";
            List<JSONObject> result = QueryEngine.query(query);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            JSONObject response = result.get(0);
            assertTrue(response.getBoolean("executed"));
            
            List<String> rows = QueryTestUtils.getRowsFromResponse(response);
            assertTrue(rows.size() > 0);
        }
    }
    
    private String generateRandomDatabaseName() {
        return "test_db_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    private String generateRandomTableName() {
        return "test_table_" + UUID.randomUUID().toString().substring(0, 8);
    }
}