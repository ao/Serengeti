package com.ataiva.serengeti.unit.query;

import com.ataiva.serengeti.query.executor.QueryPlanExecutor;
import com.ataiva.serengeti.query.optimizer.QueryOperation;
import com.ataiva.serengeti.query.optimizer.QueryOperationType;
import com.ataiva.serengeti.query.optimizer.QueryPlan;
import com.ataiva.serengeti.query.optimizer.QueryPlanType;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the QueryPlanExecutor class.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({QueryPlanExecutor.class})
public class QueryPlanExecutorTest {

    private QueryPlanExecutor queryPlanExecutor;
    
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        queryPlanExecutor = QueryPlanExecutor.getInstance();
    }
    
    /**
     * Test the applySort method with ascending sort.
     */
    @Test
    public void testApplySortAscending() throws Exception {
        // Create test data
        List<String> testData = createTestData();
        
        // Create a sort operation
        QueryOperation sortOperation = new QueryOperation(QueryOperationType.SORT);
        sortOperation.setFilterColumn("age");
        sortOperation.setFilterOperator("ASC");
        
        // Use reflection to access the private applySort method
        java.lang.reflect.Method applySort = QueryPlanExecutor.class.getDeclaredMethod(
                "applySort", List.class, QueryOperation.class);
        applySort.setAccessible(true);
        
        // Apply the sort operation
        @SuppressWarnings("unchecked")
        List<String> sortedResults = (List<String>) applySort.invoke(queryPlanExecutor, testData, sortOperation);
        
        // Verify the results
        assertEquals("Should return 3 records", 3, sortedResults.size());
        
        // Check that the records are sorted by age in ascending order
        JSONObject first = new JSONObject(sortedResults.get(0));
        JSONObject second = new JSONObject(sortedResults.get(1));
        JSONObject third = new JSONObject(sortedResults.get(2));
        
        assertEquals("First record should have age 25", 25, first.getInt("age"));
        assertEquals("Second record should have age 30", 30, second.getInt("age"));
        assertEquals("Third record should have age 35", 35, third.getInt("age"));
    }
    
    /**
     * Test the applySort method with descending sort.
     */
    @Test
    public void testApplySortDescending() throws Exception {
        // Create test data
        List<String> testData = createTestData();
        
        // Create a sort operation
        QueryOperation sortOperation = new QueryOperation(QueryOperationType.SORT);
        sortOperation.setFilterColumn("age");
        sortOperation.setFilterOperator("DESC");
        
        // Use reflection to access the private applySort method
        java.lang.reflect.Method applySort = QueryPlanExecutor.class.getDeclaredMethod(
                "applySort", List.class, QueryOperation.class);
        applySort.setAccessible(true);
        
        // Apply the sort operation
        @SuppressWarnings("unchecked")
        List<String> sortedResults = (List<String>) applySort.invoke(queryPlanExecutor, testData, sortOperation);
        
        // Verify the results
        assertEquals("Should return 3 records", 3, sortedResults.size());
        
        // Check that the records are sorted by age in descending order
        JSONObject first = new JSONObject(sortedResults.get(0));
        JSONObject second = new JSONObject(sortedResults.get(1));
        JSONObject third = new JSONObject(sortedResults.get(2));
        
        assertEquals("First record should have age 35", 35, first.getInt("age"));
        assertEquals("Second record should have age 30", 30, second.getInt("age"));
        assertEquals("Third record should have age 25", 25, third.getInt("age"));
    }
    
    /**
     * Test the applySort method with string values.
     */
    @Test
    public void testApplySortWithStrings() throws Exception {
        // Create test data with string values
        List<String> testData = new ArrayList<>();
        
        JSONObject record1 = new JSONObject();
        record1.put("id", "1");
        record1.put("name", "Charlie");
        testData.add(record1.toString());
        
        JSONObject record2 = new JSONObject();
        record2.put("id", "2");
        record2.put("name", "Alice");
        testData.add(record2.toString());
        
        JSONObject record3 = new JSONObject();
        record3.put("id", "3");
        record3.put("name", "Bob");
        testData.add(record3.toString());
        
        // Create a sort operation
        QueryOperation sortOperation = new QueryOperation(QueryOperationType.SORT);
        sortOperation.setFilterColumn("name");
        sortOperation.setFilterOperator("ASC");
        
        // Use reflection to access the private applySort method
        java.lang.reflect.Method applySort = QueryPlanExecutor.class.getDeclaredMethod(
                "applySort", List.class, QueryOperation.class);
        applySort.setAccessible(true);
        
        // Apply the sort operation
        @SuppressWarnings("unchecked")
        List<String> sortedResults = (List<String>) applySort.invoke(queryPlanExecutor, testData, sortOperation);
        
        // Verify the results
        assertEquals("Should return 3 records", 3, sortedResults.size());
        
        // Check that the records are sorted by name in ascending order
        JSONObject first = new JSONObject(sortedResults.get(0));
        JSONObject second = new JSONObject(sortedResults.get(1));
        JSONObject third = new JSONObject(sortedResults.get(2));
        
        assertEquals("First record should have name Alice", "Alice", first.getString("name"));
        assertEquals("Second record should have name Bob", "Bob", second.getString("name"));
        assertEquals("Third record should have name Charlie", "Charlie", third.getString("name"));
    }
    
    /**
     * Test the applyLimit method.
     */
    @Test
    public void testApplyLimit() throws Exception {
        // Create test data
        List<String> testData = createTestData();
        
        // Create a limit operation
        QueryOperation limitOperation = new QueryOperation(QueryOperationType.LIMIT);
        limitOperation.setFilterValue("2");
        
        // Use reflection to access the private applyLimit method
        java.lang.reflect.Method applyLimit = QueryPlanExecutor.class.getDeclaredMethod(
                "applyLimit", List.class, QueryOperation.class);
        applyLimit.setAccessible(true);
        
        // Apply the limit operation
        @SuppressWarnings("unchecked")
        List<String> limitedResults = (List<String>) applyLimit.invoke(queryPlanExecutor, testData, limitOperation);
        
        // Verify the results
        assertEquals("Should return 2 records", 2, limitedResults.size());
    }
    
    /**
     * Test the applyLimit method with offset.
     */
    @Test
    public void testApplyLimitWithOffset() throws Exception {
        // Create test data
        List<String> testData = createTestData();
        
        // Create a limit operation with offset
        QueryOperation limitOperation = new QueryOperation(QueryOperationType.LIMIT);
        limitOperation.setFilterValue("2");
        limitOperation.setFilterColumn("1");
        
        // Use reflection to access the private applyLimit method
        java.lang.reflect.Method applyLimit = QueryPlanExecutor.class.getDeclaredMethod(
                "applyLimit", List.class, QueryOperation.class);
        applyLimit.setAccessible(true);
        
        // Apply the limit operation
        @SuppressWarnings("unchecked")
        List<String> limitedResults = (List<String>) applyLimit.invoke(queryPlanExecutor, testData, limitOperation);
        
        // Verify the results
        assertEquals("Should return 2 records", 2, limitedResults.size());
        
        // Check that the records are offset correctly
        JSONObject first = new JSONObject(limitedResults.get(0));
        assertEquals("First record should have id 2", "2", first.getString("id"));
    }
    
    /**
     * Test the applyLimit method with invalid limit value.
     */
    @Test
    public void testApplyLimitWithInvalidValue() throws Exception {
        // Create test data
        List<String> testData = createTestData();
        
        // Create a limit operation with invalid value
        QueryOperation limitOperation = new QueryOperation(QueryOperationType.LIMIT);
        limitOperation.setFilterValue("invalid");
        
        // Use reflection to access the private applyLimit method
        java.lang.reflect.Method applyLimit = QueryPlanExecutor.class.getDeclaredMethod(
                "applyLimit", List.class, QueryOperation.class);
        applyLimit.setAccessible(true);
        
        // Apply the limit operation
        @SuppressWarnings("unchecked")
        List<String> limitedResults = (List<String>) applyLimit.invoke(queryPlanExecutor, testData, limitOperation);
        
        // Verify the results - should return all records since the limit is invalid
        assertEquals("Should return all records", testData.size(), limitedResults.size());
    }
    
    /**
     * Test the applyLimit method with invalid offset value.
     */
    @Test
    public void testApplyLimitWithInvalidOffset() throws Exception {
        // Create test data
        List<String> testData = createTestData();
        
        // Create a limit operation with invalid offset
        QueryOperation limitOperation = new QueryOperation(QueryOperationType.LIMIT);
        limitOperation.setFilterValue("2");
        limitOperation.setFilterColumn("invalid");
        
        // Use reflection to access the private applyLimit method
        java.lang.reflect.Method applyLimit = QueryPlanExecutor.class.getDeclaredMethod(
                "applyLimit", List.class, QueryOperation.class);
        applyLimit.setAccessible(true);
        
        // Apply the limit operation
        @SuppressWarnings("unchecked")
        List<String> limitedResults = (List<String>) applyLimit.invoke(queryPlanExecutor, testData, limitOperation);
        
        // Verify the results - should return 2 records with offset 0
        assertEquals("Should return 2 records", 2, limitedResults.size());
        
        // Check that the records are not offset
        JSONObject first = new JSONObject(limitedResults.get(0));
        assertEquals("First record should have id 1", "1", first.getString("id"));
    }
    
    /**
     * Helper method to create test data.
     * 
     * @return List of test data
     */
    private List<String> createTestData() {
        List<String> testData = new ArrayList<>();
        
        JSONObject record1 = new JSONObject();
        record1.put("id", "1");
        record1.put("name", "Alice");
        record1.put("age", 25);
        testData.add(record1.toString());
        
        JSONObject record2 = new JSONObject();
        record2.put("id", "2");
        record2.put("name", "Bob");
        record2.put("age", 30);
        testData.add(record2.toString());
        
        JSONObject record3 = new JSONObject();
        record3.put("id", "3");
        record3.put("name", "Charlie");
        record3.put("age", 35);
        testData.add(record3.toString());
        
        return testData;
    }
}