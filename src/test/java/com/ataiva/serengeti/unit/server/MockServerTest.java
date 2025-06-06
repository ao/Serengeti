package com.ataiva.serengeti.unit.server;

import com.ataiva.serengeti.server.MockServer;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for the MockServer class.
 */
public class MockServerTest {

    private MockServer mockServer;
    
    @Before
    public void setUp() {
        mockServer = new MockServer();
    }
    
    @After
    public void tearDown() {
        if (mockServer.isRunning()) {
            mockServer.stop();
        }
    }
    
    /**
     * Test starting the server.
     */
    @Test
    public void testStart() {
        // Server should not be running initially
        assertFalse("Server should not be running initially", mockServer.isRunning());
        
        // Start the server
        mockServer.start();
        
        // Server should be running after start
        assertTrue("Server should be running after start", mockServer.isRunning());
    }
    
    /**
     * Test stopping the server.
     */
    @Test
    public void testStop() {
        // Start the server
        mockServer.start();
        
        // Server should be running after start
        assertTrue("Server should be running after start", mockServer.isRunning());
        
        // Stop the server
        mockServer.stop();
        
        // Server should not be running after stop
        assertFalse("Server should not be running after stop", mockServer.isRunning());
    }
    
    /**
     * Test handling a GET request.
     */
    @Test
    public void testHandleGetRequest() {
        // Start the server
        mockServer.start();
        
        // Send a GET request
        JSONObject response = mockServer.handleRequest("GET", "/test", null);
        
        // Verify the response
        assertNotNull("Response should not be null", response);
        assertEquals("Response status should be success", "success", response.getString("status"));
        assertTrue("Response should contain data", response.has("data"));
    }
    
    /**
     * Test handling a POST request.
     */
    @Test
    public void testHandlePostRequest() {
        // Start the server
        mockServer.start();
        
        // Create test data
        JSONObject testData = new JSONObject();
        testData.put("name", "Test Name");
        testData.put("value", 42);
        
        // Send a POST request
        JSONObject response = mockServer.handleRequest("POST", "/test", testData);
        
        // Verify the response
        assertNotNull("Response should not be null", response);
        assertEquals("Response status should be success", "success", response.getString("status"));
        assertTrue("Response should contain id", response.has("id"));
        assertEquals("Response message should be correct", "Data stored successfully", response.getString("message"));
    }
    
    /**
     * Test handling a PUT request.
     */
    @Test
    public void testHandlePutRequest() {
        // Start the server
        mockServer.start();
        
        // Create test data
        JSONObject testData = new JSONObject();
        testData.put("id", "test-id");
        testData.put("name", "Test Name");
        testData.put("value", 42);
        
        // Send a POST request to create the data
        mockServer.handleRequest("POST", "/test", testData);
        
        // Update the data
        testData.put("value", 43);
        
        // Send a PUT request
        JSONObject response = mockServer.handleRequest("PUT", "/test", testData);
        
        // Verify the response
        assertNotNull("Response should not be null", response);
        assertEquals("Response status should be success", "success", response.getString("status"));
        assertEquals("Response message should be correct", "Data updated successfully", response.getString("message"));
    }
    
    /**
     * Test handling a DELETE request.
     */
    @Test
    public void testHandleDeleteRequest() {
        // Start the server
        mockServer.start();
        
        // Create test data
        JSONObject testData = new JSONObject();
        testData.put("id", "test-id");
        testData.put("name", "Test Name");
        testData.put("value", 42);
        
        // Send a POST request to create the data
        mockServer.handleRequest("POST", "/test", testData);
        
        // Send a DELETE request
        JSONObject response = mockServer.handleRequest("DELETE", "/test/test-id", null);
        
        // Verify the response
        assertNotNull("Response should not be null", response);
        assertEquals("Response status should be success", "success", response.getString("status"));
        assertEquals("Response message should be correct", "Data deleted successfully", response.getString("message"));
    }
    
    /**
     * Test handling an unsupported method.
     */
    @Test
    public void testHandleUnsupportedMethod() {
        // Start the server
        mockServer.start();
        
        // Send a request with an unsupported method
        JSONObject response = mockServer.handleRequest("PATCH", "/test", null);
        
        // Verify the response
        assertNotNull("Response should not be null", response);
        assertEquals("Response status should be error", "error", response.getString("status"));
        assertTrue("Response should contain error message", response.getString("message").contains("Unsupported method"));
    }
    
    /**
     * Test handling a request when the server is not running.
     */
    @Test
    public void testHandleRequestServerNotRunning() {
        // Do not start the server
        
        // Send a GET request
        JSONObject response = mockServer.handleRequest("GET", "/test", null);
        
        // Verify the response
        assertNotNull("Response should not be null", response);
        assertEquals("Response status should be error", "error", response.getString("status"));
        assertEquals("Response message should be correct", "Server is not running", response.getString("message"));
    }
    
    /**
     * Test request counting.
     */
    @Test
    public void testRequestCounting() {
        // Start the server
        mockServer.start();
        
        // Send multiple requests to the same path
        mockServer.handleRequest("GET", "/test", null);
        mockServer.handleRequest("GET", "/test", null);
        mockServer.handleRequest("GET", "/test", null);
        
        // Send a request to a different path
        mockServer.handleRequest("GET", "/other", null);
        
        // Check the request counts
        assertEquals("Request count for /test should be 3", 3, mockServer.getRequestCount("/test"));
        assertEquals("Request count for /other should be 1", 1, mockServer.getRequestCount("/other"));
        assertEquals("Total request count should be 4", 4, mockServer.getTotalRequestCount());
    }
    
    /**
     * Test resetting the server.
     */
    @Test
    public void testReset() {
        // Start the server
        mockServer.start();
        
        // Send some requests
        mockServer.handleRequest("GET", "/test", null);
        mockServer.handleRequest("POST", "/test", new JSONObject().put("id", "test-id"));
        
        // Reset the server
        mockServer.reset();
        
        // Check that the request counts are reset
        assertEquals("Request count for /test should be 0 after reset", 0, mockServer.getRequestCount("/test"));
        assertEquals("Total request count should be 0 after reset", 0, mockServer.getTotalRequestCount());
        
        // Check that the data is reset
        JSONObject response = mockServer.handleRequest("GET", "/test/test-id", null);
        assertTrue("Data should be empty after reset", response.getJSONObject("data").isEmpty());
    }
}