package com.ataiva.serengeti.server;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A mock implementation of a server for testing purposes.
 * This class simulates the behavior of a real server without actually
 * creating network connections or handling real HTTP requests.
 */
public class MockServer {
    
    private boolean isRunning;
    private final Map<String, JSONObject> dataStore;
    private final Map<String, Integer> requestCounts;
    
    /**
     * Creates a new MockServer instance.
     */
    public MockServer() {
        this.isRunning = false;
        this.dataStore = new ConcurrentHashMap<>();
        this.requestCounts = new ConcurrentHashMap<>();
    }
    
    /**
     * Starts the mock server.
     */
    public void start() {
        if (!isRunning) {
            isRunning = true;
            System.out.println("MockServer started");
        } else {
            System.out.println("MockServer is already running");
        }
    }
    
    /**
     * Stops the mock server.
     */
    public void stop() {
        if (isRunning) {
            isRunning = false;
            System.out.println("MockServer stopped");
        } else {
            System.out.println("MockServer is not running");
        }
    }
    
    /**
     * Handles a request to the mock server.
     * 
     * @param method The HTTP method (GET, POST, PUT, DELETE)
     * @param path The request path
     * @param data The request data (for POST and PUT requests)
     * @return A JSONObject containing the response
     */
    public JSONObject handleRequest(String method, String path, JSONObject data) {
        if (!isRunning) {
            JSONObject errorResponse = new JSONObject();
            errorResponse.put("status", "error");
            errorResponse.put("message", "Server is not running");
            return errorResponse;
        }
        
        // Increment request count for this path
        requestCounts.put(path, requestCounts.getOrDefault(path, 0) + 1);
        
        // Create response object
        JSONObject response = new JSONObject();
        
        try {
            switch (method) {
                case "GET":
                    handleGetRequest(path, response);
                    break;
                case "POST":
                    handlePostRequest(path, data, response);
                    break;
                case "PUT":
                    handlePutRequest(path, data, response);
                    break;
                case "DELETE":
                    handleDeleteRequest(path, response);
                    break;
                default:
                    response.put("status", "error");
                    response.put("message", "Unsupported method: " + method);
            }
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Error processing request: " + e.getMessage());
        }
        
        return response;
    }
    
    /**
     * Handles a GET request.
     * 
     * @param path The request path
     * @param response The response object to populate
     */
    private void handleGetRequest(String path, JSONObject response) {
        // Simulate retrieving data
        if (dataStore.containsKey(path)) {
            response.put("status", "success");
            response.put("data", dataStore.get(path));
        } else {
            response.put("status", "success");
            response.put("data", new JSONObject());
        }
    }
    
    /**
     * Handles a POST request.
     * 
     * @param path The request path
     * @param data The request data
     * @param response The response object to populate
     */
    private void handlePostRequest(String path, JSONObject data, JSONObject response) {
        // Simulate storing data
        if (data != null) {
            // Generate an ID if one doesn't exist
            if (!data.has("id")) {
                data.put("id", UUID.randomUUID().toString());
            }
            
            dataStore.put(path + "/" + data.get("id"), data);
            
            response.put("status", "success");
            response.put("id", data.get("id"));
            response.put("message", "Data stored successfully");
        } else {
            response.put("status", "error");
            response.put("message", "No data provided");
        }
    }
    
    /**
     * Handles a PUT request.
     * 
     * @param path The request path
     * @param data The request data
     * @param response The response object to populate
     */
    private void handlePutRequest(String path, JSONObject data, JSONObject response) {
        // Simulate updating data
        if (data != null && data.has("id")) {
            String id = data.getString("id");
            String fullPath = path + "/" + id;
            
            if (dataStore.containsKey(fullPath)) {
                dataStore.put(fullPath, data);
                response.put("status", "success");
                response.put("message", "Data updated successfully");
            } else {
                response.put("status", "error");
                response.put("message", "Data not found");
            }
        } else {
            response.put("status", "error");
            response.put("message", "Invalid data or missing ID");
        }
    }
    
    /**
     * Handles a DELETE request.
     * 
     * @param path The request path
     * @param response The response object to populate
     */
    private void handleDeleteRequest(String path, JSONObject response) {
        // Simulate deleting data
        boolean found = false;
        
        // Check if the path exists directly
        if (dataStore.containsKey(path)) {
            dataStore.remove(path);
            found = true;
        } else {
            // Check if the path is a prefix of any stored paths
            for (String key : dataStore.keySet()) {
                if (key.startsWith(path)) {
                    dataStore.remove(key);
                    found = true;
                }
            }
        }
        
        if (found) {
            response.put("status", "success");
            response.put("message", "Data deleted successfully");
        } else {
            response.put("status", "error");
            response.put("message", "Data not found");
        }
    }
    
    /**
     * Gets the number of requests made to a specific path.
     * 
     * @param path The path to check
     * @return The number of requests made to the path
     */
    public int getRequestCount(String path) {
        return requestCounts.getOrDefault(path, 0);
    }
    
    /**
     * Gets the total number of requests made to the server.
     * 
     * @return The total number of requests
     */
    public int getTotalRequestCount() {
        int total = 0;
        for (int count : requestCounts.values()) {
            total += count;
        }
        return total;
    }
    
    /**
     * Clears all stored data and request counts.
     */
    public void reset() {
        dataStore.clear();
        requestCounts.clear();
    }
    
    /**
     * Checks if the server is running.
     * 
     * @return true if the server is running, false otherwise
     */
    public boolean isRunning() {
        return isRunning;
    }
}