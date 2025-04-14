package com.ataiva.serengeti.mocks;

import com.ataiva.serengeti.network.Network;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Mock implementation of the Network class for testing.
 */
public class MockNetwork extends Network {
    
    private Map<String, JSONObject> mockNodes = new HashMap<>();
    private Map<String, String> mockResponses = new HashMap<>();
    private boolean findNodesExecuted = false;
    private boolean requestNetworkMetasExecuted = false;
    
    /**
     * Constructor.
     */
    public MockNetwork() {
        super();
        // Override initialization to avoid actual network operations
        myIP = "127.0.0.1";
    }
    
    /**
     * Overrides the init method to avoid actual network operations.
     */
    @Override
    public void init() {
        // Do nothing to avoid actual network operations
        myIP = "127.0.0.1";
    }
    
    /**
     * Overrides the findNodes method to avoid actual network operations.
     */
    @Override
    public void findNodes() {
        findNodesExecuted = true;
        // Do nothing to avoid actual network operations
    }
    
    /**
     * Overrides the requestNetworkMetas method to avoid actual network operations.
     */
    @Override
    public void requestNetworkMetas() {
        requestNetworkMetasExecuted = true;
        // Do nothing to avoid actual network operations
    }
    
    /**
     * Adds a mock node to the network.
     * 
     * @param id The node ID
     * @param nodeInfo The node information
     */
    public void addMockNode(String id, JSONObject nodeInfo) {
        mockNodes.put(id, nodeInfo);
        availableNodes.put(id, nodeInfo);
    }
    
    /**
     * Removes a mock node from the network.
     * 
     * @param id The node ID
     */
    public void removeMockNode(String id) {
        mockNodes.remove(id);
        availableNodes.remove(id);
    }
    
    /**
     * Sets a mock response for a specific node and query.
     * 
     * @param nodeId The node ID
     * @param query The query
     * @param response The response
     */
    public void setMockResponse(String nodeId, String query, String response) {
        mockResponses.put(nodeId + ":" + query, response);
    }
    
    /**
     * Overrides the communicateQueryLogSingleNode method to return mock responses.
     * 
     * @param id The node ID
     * @param ip The node IP
     * @param jsonString The query JSON string
     * @return The mock response or a default response
     */
    @Override
    public String communicateQueryLogSingleNode(String id, String ip, String jsonString) {
        String key = id + ":" + jsonString;
        if (mockResponses.containsKey(key)) {
            return mockResponses.get(key);
        }
        
        // Default mock response
        return "{\"status\": \"success\"}";
    }
    
    /**
     * Overrides the communicateQueryLogAllNodes method to return mock responses.
     * 
     * @param jsonString The query JSON string
     * @return A JSONArray of mock responses
     */
    @Override
    public JSONArray communicateQueryLogAllNodes(String jsonString) {
        JSONArray responses = new JSONArray();
        
        for (String nodeId : availableNodes.keySet()) {
            String response = communicateQueryLogSingleNode(nodeId, availableNodes.get(nodeId).getString("ip"), jsonString);
            responses.put(response);
        }
        
        return responses;
    }
    
    /**
     * Checks if the findNodes method was executed.
     * 
     * @return true if executed, false otherwise
     */
    public boolean wasFindNodesExecuted() {
        return findNodesExecuted;
    }
    
    /**
     * Checks if the requestNetworkMetas method was executed.
     * 
     * @return true if executed, false otherwise
     */
    public boolean wasRequestNetworkMetasExecuted() {
        return requestNetworkMetasExecuted;
    }
    
    /**
     * Resets the execution flags.
     */
    public void resetExecutionFlags() {
        findNodesExecuted = false;
        requestNetworkMetasExecuted = false;
    }
    
    /**
     * Gets the mock nodes.
     * 
     * @return The mock nodes
     */
    public Map<String, JSONObject> getMockNodes() {
        return mockNodes;
    }
    
    /**
     * Gets the mock responses.
     * 
     * @return The mock responses
     */
    public Map<String, String> getMockResponses() {
        return mockResponses;
    }
}