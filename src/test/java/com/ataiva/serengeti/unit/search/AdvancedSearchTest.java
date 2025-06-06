package com.ataiva.serengeti.unit.search;

import com.ataiva.serengeti.search.AdvancedSearch;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageResponseObject;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the AdvancedSearch class.
 */
public class AdvancedSearchTest {

    @Mock
    private Storage mockStorage;
    
    private AdvancedSearch advancedSearch;
    
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        advancedSearch = new AdvancedSearch(mockStorage);
    }
    
    /**
     * Test range query with matching records.
     */
    @Test
    public void testRangeQueryWithMatches() {
        // Set up test data
        String database = "testDb";
        String table = "testTable";
        String field = "age";
        
        // Create test records
        JSONObject record1 = new JSONObject();
        record1.put("id", "1");
        record1.put("name", "Alice");
        record1.put("age", 25);
        
        JSONObject record2 = new JSONObject();
        record2.put("id", "2");
        record2.put("name", "Bob");
        record2.put("age", 30);
        
        JSONObject record3 = new JSONObject();
        record3.put("id", "3");
        record3.put("name", "Charlie");
        record3.put("age", 35);
        
        // Mock the storage response
        when(mockStorage.select(database, table, "*", null, null))
            .thenReturn(Arrays.asList(record1.toString(), record2.toString(), record3.toString()));
        
        // Perform range query
        List<JSONObject> results = advancedSearch.rangeQuery(database, table, field, 25, 30);
        
        // Verify results
        assertEquals("Should return 2 records", 2, results.size());
        assertEquals("First record should have age 25", 25, results.get(0).getInt("age"));
        assertEquals("Second record should have age 30", 30, results.get(1).getInt("age"));
    }
    
    /**
     * Test range query with no matching records.
     */
    @Test
    public void testRangeQueryWithNoMatches() {
        // Set up test data
        String database = "testDb";
        String table = "testTable";
        String field = "age";
        
        // Create test records
        JSONObject record1 = new JSONObject();
        record1.put("id", "1");
        record1.put("name", "Alice");
        record1.put("age", 25);
        
        JSONObject record2 = new JSONObject();
        record2.put("id", "2");
        record2.put("name", "Bob");
        record2.put("age", 30);
        
        // Mock the storage response
        when(mockStorage.select(database, table, "*", null, null))
            .thenReturn(Arrays.asList(record1.toString(), record2.toString()));
        
        // Perform range query
        List<JSONObject> results = advancedSearch.rangeQuery(database, table, field, 40, 50);
        
        // Verify results
        assertTrue("Should return no records", results.isEmpty());
    }
    
    /**
     * Test full-text search with matching records.
     */
    @Test
    public void testFullTextSearchWithMatches() {
        // Set up test data
        String database = "testDb";
        String table = "testTable";
        String field = "description";
        
        // Create test records
        JSONObject record1 = new JSONObject();
        record1.put("id", "1");
        record1.put("description", "The quick brown fox jumps over the lazy dog");
        
        JSONObject record2 = new JSONObject();
        record2.put("id", "2");
        record2.put("description", "A quick brown dog runs in the park");
        
        JSONObject record3 = new JSONObject();
        record3.put("id", "3");
        record3.put("description", "The lazy cat sleeps all day");
        
        // Mock the storage response
        when(mockStorage.select(database, table, "*", null, null))
            .thenReturn(Arrays.asList(record1.toString(), record2.toString(), record3.toString()));
        
        // Perform full-text search
        List<JSONObject> results = advancedSearch.fullTextSearch(database, table, field, "quick brown");
        
        // Verify results
        assertEquals("Should return 2 records", 2, results.size());
        assertTrue("Results should contain records with 'quick' and 'brown'", 
                results.stream().allMatch(r -> r.getString("description").contains("quick") && 
                                               r.getString("description").contains("brown")));
    }
    
    /**
     * Test regex match with matching records.
     */
    @Test
    public void testRegexMatchWithMatches() {
        // Set up test data
        String database = "testDb";
        String table = "testTable";
        String field = "email";
        
        // Create test records
        JSONObject record1 = new JSONObject();
        record1.put("id", "1");
        record1.put("email", "alice@example.com");
        
        JSONObject record2 = new JSONObject();
        record2.put("id", "2");
        record2.put("email", "bob@example.org");
        
        JSONObject record3 = new JSONObject();
        record3.put("id", "3");
        record3.put("email", "charlie@gmail.com");
        
        // Mock the storage response
        when(mockStorage.select(database, table, "*", null, null))
            .thenReturn(Arrays.asList(record1.toString(), record2.toString(), record3.toString()));
        
        // Perform regex match
        List<JSONObject> results = advancedSearch.regexMatch(database, table, field, ".*@example\\.(com|org)");
        
        // Verify results
        assertEquals("Should return 2 records", 2, results.size());
        assertTrue("Results should contain emails with @example.com or @example.org", 
                results.stream().allMatch(r -> r.getString("email").matches(".*@example\\.(com|org)")));
    }
    
    /**
     * Test fuzzy match with matching records.
     */
    @Test
    public void testFuzzyMatchWithMatches() {
        // Set up test data
        String database = "testDb";
        String table = "testTable";
        String field = "name";
        
        // Create test records
        JSONObject record1 = new JSONObject();
        record1.put("id", "1");
        record1.put("name", "Alice");
        
        JSONObject record2 = new JSONObject();
        record2.put("id", "2");
        record2.put("name", "Alicia");
        
        JSONObject record3 = new JSONObject();
        record3.put("id", "3");
        record3.put("name", "Bob");
        
        // Mock the storage response
        when(mockStorage.select(database, table, "*", null, null))
            .thenReturn(Arrays.asList(record1.toString(), record2.toString(), record3.toString()));
        
        // Perform fuzzy match
        List<JSONObject> results = advancedSearch.fuzzyMatch(database, table, field, "Alice", 2);
        
        // Verify results
        assertEquals("Should return 2 records", 2, results.size());
        assertEquals("First record should be exact match", "Alice", results.get(0).getString("name"));
        assertEquals("Second record should be close match", "Alicia", results.get(1).getString("name"));
    }
    
    /**
     * Test handling of storage errors.
     */
    @Test
    public void testHandlingOfStorageErrors() {
        // Set up test data
        String database = "testDb";
        String table = "testTable";
        String field = "name";
        
        // Mock the storage to throw an exception
        when(mockStorage.select(database, table, "*", null, null))
            .thenThrow(new RuntimeException("Storage error"));
        
        // Perform searches
        List<JSONObject> rangeResults = advancedSearch.rangeQuery(database, table, field, 1, 10);
        List<JSONObject> textResults = advancedSearch.fullTextSearch(database, table, field, "test");
        List<JSONObject> regexResults = advancedSearch.regexMatch(database, table, field, "test");
        List<JSONObject> fuzzyResults = advancedSearch.fuzzyMatch(database, table, field, "test", 2);
        
        // Verify results
        assertTrue("Range query should return empty list on error", rangeResults.isEmpty());
        assertTrue("Full-text search should return empty list on error", textResults.isEmpty());
        assertTrue("Regex match should return empty list on error", regexResults.isEmpty());
        assertTrue("Fuzzy match should return empty list on error", fuzzyResults.isEmpty());
    }
}