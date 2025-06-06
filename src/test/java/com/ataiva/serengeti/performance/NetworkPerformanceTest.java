package com.ataiva.serengeti.performance;

import com.ataiva.serengeti.network.NetworkImpl;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Performance tests for the NetworkImpl class.
 * These tests evaluate the performance characteristics of the NetworkImpl class.
 */
public class NetworkPerformanceTest {

    private static final Logger LOGGER = Logger.getLogger(NetworkPerformanceTest.class.getName());
    
    private NetworkImpl networkImpl;
    private ExecutorService testExecutor;
    private ServerSocket mockServer;
    private int testPort;
    private int numThreads = 10;
    private int numRequests = 100;
    private int payloadSize = 1024; // 1KB
    
    @Before
    public void setUp() throws Exception {
        // Find an available port for testing
        try (ServerSocket socket = new ServerSocket(0)) {
            testPort = socket.getLocalPort();
        }
        
        // Create a NetworkImpl instance with test configuration
        networkImpl = new NetworkImpl(
            testPort,    // communicationPort
            testPort + 1, // discoveryPort
            1000,        // heartbeatIntervalMs
            5000,        // nodeTimeoutMs
            1000,        // discoveryTimeoutMs
            2            // maxRetransmissions
        );
        
        // Create a test executor
        testExecutor = Executors.newCachedThreadPool();
        
        // Start a mock server
        startMockServer();
        
        // Initialize the network
        networkImpl.init();
        
        // Set up test node
        JSONObject nodeInfo = new JSONObject();
        nodeInfo.put("id", "test-node");
        nodeInfo.put("ip", "127.0.0.1");
        nodeInfo.put("last_checked", System.currentTimeMillis());
        networkImpl.availableNodes.put("test-node", nodeInfo);
    }
    
    @After
    public void tearDown() throws Exception {
        // Shutdown the network
        if (networkImpl != null) {
            networkImpl.shutdown();
        }
        
        // Close the mock server
        if (mockServer != null && !mockServer.isClosed()) {
            mockServer.close();
        }
        
        // Shutdown the test executor
        if (testExecutor != null) {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    
    /**
     * Start a mock server that responds to network requests.
     */
    private void startMockServer() throws IOException {
        mockServer = new ServerSocket(testPort);
        
        testExecutor.submit(() -> {
            try {
                while (!mockServer.isClosed()) {
                    Socket clientSocket = mockServer.accept();
                    
                    // Handle the client connection in a separate thread
                    testExecutor.submit(() -> {
                        try {
                            // Read the request (we don't care about the content for performance testing)
                            byte[] buffer = new byte[8192];
                            clientSocket.getInputStream().read(buffer);
                            
                            // Generate a response payload of the specified size
                            StringBuilder payload = new StringBuilder();
                            for (int i = 0; i < payloadSize; i++) {
                                payload.append('a');
                            }
                            
                            // Simple HTTP response
                            String response = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: " + (payload.length() + 2) + "\r\n" +
                                    "\r\n" +
                                    "{" + payload.toString() + "}";
                            
                            clientSocket.getOutputStream().write(response.getBytes());
                            clientSocket.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
                }
            } catch (IOException e) {
                if (!mockServer.isClosed()) {
                    e.printStackTrace();
                }
            }
        });
    }
    
    /**
     * Test the performance of single node communication.
     */
    @Test
    public void testSingleNodeCommunicationPerformance() {
        LOGGER.info("Starting single node communication performance test");
        
        // Create a test payload
        String testPayload = "{\"type\":\"TestMessage\",\"data\":\"" + generateRandomString(payloadSize) + "\"}";
        
        // Warm-up
        for (int i = 0; i < 10; i++) {
            networkImpl.communicateQueryLogSingleNode("test-node", "127.0.0.1", testPayload);
        }
        
        // Measure performance
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < numRequests; i++) {
            networkImpl.communicateQueryLogSingleNode("test-node", "127.0.0.1", testPayload);
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double requestsPerSecond = (numRequests * 1000.0) / duration;
        
        LOGGER.info(String.format("Single node communication performance: %.2f requests/second", requestsPerSecond));
        LOGGER.info(String.format("Average request time: %.2f ms", duration / (double) numRequests));
    }
    
    /**
     * Test the performance of concurrent communication.
     */
    @Test
    public void testConcurrentCommunicationPerformance() throws Exception {
        LOGGER.info("Starting concurrent communication performance test");
        
        // Create a test payload
        String testPayload = "{\"type\":\"TestMessage\",\"data\":\"" + generateRandomString(payloadSize) + "\"}";
        
        // Create a thread pool for concurrent requests
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        // Create a list of tasks
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < numRequests; i++) {
            tasks.add(() -> networkImpl.communicateQueryLogSingleNode("test-node", "127.0.0.1", testPayload));
        }
        
        // Warm-up
        for (int i = 0; i < 10; i++) {
            networkImpl.communicateQueryLogSingleNode("test-node", "127.0.0.1", testPayload);
        }
        
        // Measure performance
        long startTime = System.currentTimeMillis();
        
        List<Future<String>> futures = executor.invokeAll(tasks);
        
        // Wait for all tasks to complete
        for (Future<String> future : futures) {
            future.get();
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double requestsPerSecond = (numRequests * 1000.0) / duration;
        
        LOGGER.info(String.format("Concurrent communication performance (%d threads): %.2f requests/second", numThreads, requestsPerSecond));
        LOGGER.info(String.format("Average request time: %.2f ms", duration / (double) numRequests));
        
        // Shutdown the executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    /**
     * Test the performance of node discovery.
     */
    @Test
    public void testNodeDiscoveryPerformance() {
        LOGGER.info("Starting node discovery performance test");
        
        // Measure performance
        long startTime = System.currentTimeMillis();
        
        // Call findNodes multiple times
        for (int i = 0; i < 5; i++) {
            networkImpl.findNodes();
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        LOGGER.info(String.format("Node discovery performance: %.2f ms per scan", duration / 5.0));
    }
    
    /**
     * Generate a random string of the specified length.
     */
    private String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        return sb.toString();
    }
}