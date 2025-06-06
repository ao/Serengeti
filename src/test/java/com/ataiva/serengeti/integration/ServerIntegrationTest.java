package com.ataiva.serengeti.integration;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.network.NetworkFactory;
import com.ataiva.serengeti.server.Server;
import com.ataiva.serengeti.server.ServerFactory;
import com.ataiva.serengeti.server.ServerImpl;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageFactory;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Integration tests for the ServerImpl class.
 * These tests verify that the ServerImpl class integrates correctly with other components.
 */
public class ServerIntegrationTest {

    private ServerImpl serverImpl;
    private Network network;
    private Storage storage;
    private int testPort;
    
    @Before
    public void setUp() throws Exception {
        // Use a different port for testing to avoid conflicts
        testPort = 8765;
        
        // Create a real network implementation
        network = NetworkFactory.createNetwork(NetworkFactory.NetworkType.REAL);
        
        // Create a real storage implementation
        storage = StorageFactory.createStorage(StorageFactory.StorageType.REAL);
        
        // Initialize the network and storage
        network.init();
        storage.init();
        
        // Set up the Serengeti static references
        Serengeti.network = network;
        Serengeti.storage = storage;
        Serengeti.currentDate = new java.util.Date();
        Serengeti.startTime = System.currentTimeMillis();
        
        // Create a ServerImpl instance with test configuration
        serverImpl = new ServerImpl(testPort, 10, 5, 5, TimeUnit.SECONDS);
        
        // Initialize the server
        serverImpl.init();
        
        // Set the Serengeti server reference
        Serengeti.server = serverImpl;
    }
    
    @After
    public void tearDown() throws Exception {
        // Shutdown the server
        if (serverImpl != null && serverImpl.isRunning()) {
            serverImpl.shutdown();
        }
        
        // Shutdown the network and storage
        if (network != null) {
            network.shutdown();
        }
        
        if (storage != null) {
            storage.shutdown();
        }
    }
    
    @Test
    public void testServerStartup() {
        // Start the server
        serverImpl.serve();
        
        // Verify that the server is running
        assertTrue("Server should be running after serve() is called", serverImpl.isRunning());
    }
    
    @Test
    public void testServerShutdown() {
        // Start the server
        serverImpl.serve();
        
        // Verify that the server is running
        assertTrue("Server should be running after serve() is called", serverImpl.isRunning());
        
        // Shutdown the server
        serverImpl.shutdown();
        
        // Verify that the server is not running
        assertFalse("Server should not be running after shutdown() is called", serverImpl.isRunning());
    }
    
    @Test
    public void testRootEndpoint() throws Exception {
        // Start the server
        serverImpl.serve();
        
        // Wait for the server to start
        Thread.sleep(1000);
        
        // Send a request to the root endpoint
        String response = sendHttpRequest("http://localhost:" + testPort + "/");
        
        // Verify the response
        assertNotNull("Response should not be null", response);
        assertTrue("Response should contain Serengeti", response.contains("Serengeti"));
        
        // Parse the response as JSON
        JSONObject jsonResponse = new JSONObject(response);
        
        // Verify the response structure
        assertTrue("Response should have '_' field", jsonResponse.has("_"));
        assertTrue("Response should have 'this' field", jsonResponse.has("this"));
        assertTrue("Response should have 'totalNodes' field", jsonResponse.has("totalNodes"));
        assertTrue("Response should have 'availableNodes' field", jsonResponse.has("availableNodes"));
        
        // Verify the response content
        assertEquals("Response should have correct title", 
                "Serengeti - The Autonomous Distributed Database", jsonResponse.getString("_"));
    }
    
    @Test
    public void testHealthEndpoint() throws Exception {
        // Start the server
        serverImpl.serve();
        
        // Wait for the server to start
        Thread.sleep(1000);
        
        // Send a request to the health endpoint
        String response = sendHttpRequest("http://localhost:" + testPort + "/health");
        
        // Verify the response
        assertNotNull("Response should not be null", response);
        
        // Parse the response as JSON
        JSONObject jsonResponse = new JSONObject(response);
        
        // Verify the response structure
        assertTrue("Response should have 'status' field", jsonResponse.has("status"));
        assertTrue("Response should have 'timestamp' field", jsonResponse.has("timestamp"));
        assertTrue("Response should have 'version' field", jsonResponse.has("version"));
        assertTrue("Response should have 'metrics' field", jsonResponse.has("metrics"));
        
        // Verify the response content
        assertEquals("Response should have UP status", "UP", jsonResponse.getString("status"));
        
        // Verify the metrics
        JSONObject metrics = jsonResponse.getJSONObject("metrics");
        assertTrue("Metrics should have 'activeConnections' field", metrics.has("activeConnections"));
        assertTrue("Metrics should have 'threadPoolSize' field", metrics.has("threadPoolSize"));
        assertTrue("Metrics should have 'freeMemory' field", metrics.has("freeMemory"));
        assertTrue("Metrics should have 'totalMemory' field", metrics.has("totalMemory"));
        assertTrue("Metrics should have 'maxMemory' field", metrics.has("maxMemory"));
    }
    
    @Test
    public void testMetricsEndpoint() throws Exception {
        // Start the server
        serverImpl.serve();
        
        // Wait for the server to start
        Thread.sleep(1000);
        
        // Send a request to the metrics endpoint
        String response = sendHttpRequest("http://localhost:" + testPort + "/metrics");
        
        // Verify the response
        assertNotNull("Response should not be null", response);
        
        // Parse the response as JSON
        JSONObject jsonResponse = new JSONObject(response);
        
        // Verify the response structure
        assertTrue("Response should have 'system' field", jsonResponse.has("system"));
        assertTrue("Response should have 'jvm' field", jsonResponse.has("jvm"));
        assertTrue("Response should have 'server' field", jsonResponse.has("server"));
        assertTrue("Response should have 'network' field", jsonResponse.has("network"));
        
        // Verify the system metrics
        JSONObject system = jsonResponse.getJSONObject("system");
        assertTrue("System metrics should have 'cpuCores' field", system.has("cpuCores"));
        assertTrue("System metrics should have 'freeMemory' field", system.has("freeMemory"));
        assertTrue("System metrics should have 'totalMemory' field", system.has("totalMemory"));
        assertTrue("System metrics should have 'maxMemory' field", system.has("maxMemory"));
        
        // Verify the JVM metrics
        JSONObject jvm = jsonResponse.getJSONObject("jvm");
        assertTrue("JVM metrics should have 'threadCount' field", jvm.has("threadCount"));
        assertTrue("JVM metrics should have 'uptime' field", jvm.has("uptime"));
        
        // Verify the server metrics
        JSONObject server = jsonResponse.getJSONObject("server");
        assertTrue("Server metrics should have 'activeConnections' field", server.has("activeConnections"));
        assertTrue("Server metrics should have 'requestCount' field", server.has("requestCount"));
        
        // Verify the network metrics
        JSONObject network = jsonResponse.getJSONObject("network");
        assertTrue("Network metrics should have 'discoveryLatency' field", network.has("discoveryLatency"));
        assertTrue("Network metrics should have 'availableNodes' field", network.has("availableNodes"));
    }
    
    @Test
    public void testServerFactoryIntegration() {
        // Create a server using the factory
        Server server = ServerFactory.createServer(ServerFactory.ServerType.REAL, testPort);
        
        // Verify that the server is created correctly
        assertNotNull("Server should not be null", server);
        assertTrue("Server should be an instance of ServerImpl", server instanceof ServerImpl);
        
        // Initialize and start the server
        server.init();
        server.serve();
        
        // Verify that the server is running
        assertTrue("Server should be running after serve() is called", ((ServerImpl) server).isRunning());
        
        // Shutdown the server
        ((ServerImpl) server).shutdown();
    }
    
    @Test
    public void testSerengetiIntegration() {
        // Set the server in Serengeti
        Serengeti.server = serverImpl;
        
        // Initialize and start the server
        serverImpl.init();
        serverImpl.serve();
        
        // Verify that the server is running
        assertTrue("Server should be running after serve() is called", serverImpl.isRunning());
        
        // Verify that the server is set correctly in Serengeti
        assertNotNull("Serengeti.server should not be null", Serengeti.server);
        assertTrue("Serengeti.server should be an instance of ServerImpl", Serengeti.server instanceof ServerImpl);
    }
    
    @Test
    public void testNetworkIntegration() throws Exception {
        // Start the server
        serverImpl.serve();
        
        // Wait for the server to start
        Thread.sleep(1000);
        
        // Send a request to the root endpoint
        String response = sendHttpRequest("http://localhost:" + testPort + "/");
        
        // Parse the response as JSON
        JSONObject jsonResponse = new JSONObject(response);
        
        // Verify that the network information is included in the response
        assertTrue("Response should have 'totalNodes' field", jsonResponse.has("totalNodes"));
        assertTrue("Response should have 'availableNodes' field", jsonResponse.has("availableNodes"));
        assertTrue("Response should have 'discoveryLatency' field", jsonResponse.has("discoveryLatency"));
    }
    
    @Test
    public void testStorageIntegration() throws Exception {
        // Start the server
        serverImpl.serve();
        
        // Wait for the server to start
        Thread.sleep(1000);
        
        // Send a request to the meta endpoint
        String response = sendHttpRequest("http://localhost:" + testPort + "/meta");
        
        // Verify the response
        assertNotNull("Response should not be null", response);
        
        // Parse the response as JSON
        JSONObject jsonResponse = new JSONObject(response);
        
        // Verify that the storage information is included in the response
        assertTrue("Response should have 'meta' field", jsonResponse.has("meta"));
    }
    
    /**
     * Helper method to send an HTTP request and get the response.
     * 
     * @param urlString The URL to send the request to
     * @return The response as a string
     * @throws Exception If an error occurs
     */
    private String sendHttpRequest(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        
        int responseCode = connection.getResponseCode();
        assertEquals("HTTP response code should be 200", 200, responseCode);
        
        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String inputLine;
        StringBuilder response = new StringBuilder();
        
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();
        
        return response.toString();
    }
}