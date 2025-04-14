package com.ataiva.serengeti.mocks;

import com.ataiva.serengeti.server.Server;
import com.ataiva.serengeti.server.ServerConstants;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mock implementation of the Server class for testing.
 */
public class MockServer extends Server {
    
    private boolean isServing = false;
    private Map<String, Boolean> methodCalls = new HashMap<>();
    private Map<String, Integer> methodCallCounts = new HashMap<>();
    private Map<String, Object> requestHandlers = new HashMap<>();
    private Map<String, Object> responseData = new HashMap<>();
    
    /**
     * Constructor.
     */
    public MockServer() {
        super();
        resetMethodCalls();
    }
    
    /**
     * Resets the method call tracking.
     */
    public void resetMethodCalls() {
        methodCalls.clear();
        methodCallCounts.clear();
        
        // Initialize method call tracking
        String[] methods = {
            "init", "serve", "saveServerConstants"
        };
        
        for (String method : methods) {
            methodCalls.put(method, false);
            methodCallCounts.put(method, 0);
        }
    }
    
    /**
     * Records a method call.
     * 
     * @param methodName The method name
     */
    private void recordMethodCall(String methodName) {
        methodCalls.put(methodName, true);
        methodCallCounts.put(methodName, methodCallCounts.getOrDefault(methodName, 0) + 1);
    }
    
    /**
     * Checks if a method was called.
     * 
     * @param methodName The method name
     * @return true if the method was called, false otherwise
     */
    public boolean wasMethodCalled(String methodName) {
        return methodCalls.getOrDefault(methodName, false);
    }
    
    /**
     * Gets the number of times a method was called.
     * 
     * @param methodName The method name
     * @return The number of times the method was called
     */
    public int getMethodCallCount(String methodName) {
        return methodCallCounts.getOrDefault(methodName, 0);
    }
    
    /**
     * Overrides the init method.
     */
    @Override
    public void init() {
        recordMethodCall("init");
        
        // If server_constants is already set, preserve its ID
        if (server_constants != null) {
            String existingId = server_constants.id;
            server_constants = new ServerConstants();
            server_constants.id = existingId;
        } else {
            // Create a server constants object with a fixed ID for testing
            server_constants = new ServerConstants();
            server_constants.id = "c1e3eb5d-2bf1-44a1-8bd0-5d4289f3b15a";
        }
    }
    
    /**
     * Overrides the serve method.
     */
    @Override
    public void serve() {
        recordMethodCall("serve");
        isServing = true;
    }
    
    /**
     * Simulates saving server constants.
     * Since the original method is private, we can't override it.
     */
    public void simulateSaveServerConstants() {
        recordMethodCall("saveServerConstants");
        // Do nothing to avoid actual file operations
    }
    
    /**
     * Checks if the server is serving.
     * 
     * @return true if the server is serving, false otherwise
     */
    public boolean isServing() {
        return isServing;
    }
    
    /**
     * Sets the server serving state.
     * 
     * @param serving The serving state
     */
    public void setServing(boolean serving) {
        isServing = serving;
    }
    
    /**
     * Sets a request handler for a specific endpoint.
     * 
     * @param endpoint The endpoint
     * @param handler The handler
     */
    public void setRequestHandler(String endpoint, Object handler) {
        requestHandlers.put(endpoint, handler);
    }
    
    /**
     * Gets the request handler for a specific endpoint.
     * 
     * @param endpoint The endpoint
     * @return The handler
     */
    public Object getRequestHandler(String endpoint) {
        return requestHandlers.get(endpoint);
    }
    
    /**
     * Sets response data for a specific endpoint.
     * 
     * @param endpoint The endpoint
     * @param data The data
     */
    public void setResponseData(String endpoint, Object data) {
        responseData.put(endpoint, data);
    }
    
    /**
     * Gets the response data for a specific endpoint.
     * 
     * @param endpoint The endpoint
     * @return The data
     */
    public Object getResponseData(String endpoint) {
        return responseData.get(endpoint);
    }
    
    /**
     * Gets the server constants.
     * 
     * @return The server constants
     */
    public ServerConstants getServerConstants() {
        return server_constants;
    }
    
    /**
     * Sets the server constants.
     * 
     * @param constants The server constants
     */
    public void setServerConstants(ServerConstants constants) {
        server_constants = constants;
    }
}