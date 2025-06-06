package com.ataiva.serengeti.performance;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.server.ServerImpl;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Performance tests for the ServerImpl class.
 * These tests evaluate the performance characteristics of the ServerImpl class
 * under various conditions and workloads.
 */
@Category(PerformanceTest.class)
public class ServerPerformanceTest {

    private ServerImpl serverImpl;
    private int testPort;
    private static final int SMALL_REQUEST_COUNT = 100;
    private static final int MEDIUM_REQUEST_COUNT = 500;
    private static final int LARGE_REQUEST_COUNT = 1000;
    private static final int THREAD_COUNT = 10;
    
    @Before
    public void setUp() throws Exception {
        // Use a different port for testing to avoid conflicts
        testPort = 8766;
        
        // Create a ServerImpl instance with test configuration
        serverImpl = new ServerImpl(testPort, 100, 50, 10, TimeUnit.SECONDS);
        
        // Initialize the server
        serverImpl.init();
        
        // Set the Serengeti server reference
        Serengeti.server = serverImpl;
        
        // Start the server
        serverImpl.serve();
        
        // Wait for the server to start
        Thread.sleep(1000);
    }
    
    @After
    public void tearDown() throws Exception {
        // Shutdown the server
        if (serverImpl != null && serverImpl.isRunning()) {
            serverImpl.shutdown();
        }
    }
    
    /**
     * Test the throughput of GET requests.
     */
    @Test
    public void testGetRequestThroughput() throws Exception {
        System.out.println("Testing GET request throughput...");
        
        // Measure time for small request count
        long startTime = System.currentTimeMillis();
        sendGetRequests("/", SMALL_REQUEST_COUNT);
        long smallRequestTime = System.currentTimeMillis() - startTime;
        
        // Measure time for medium request count
        startTime = System.currentTimeMillis();
        sendGetRequests("/", MEDIUM_REQUEST_COUNT);
        long mediumRequestTime = System.currentTimeMillis() - startTime;
        
        // Measure time for large request count
        startTime = System.currentTimeMillis();
        sendGetRequests("/", LARGE_REQUEST_COUNT);
        long largeRequestTime = System.currentTimeMillis() - startTime;
        
        // Print results
        System.out.println("GET request throughput results:");
        System.out.println("Small request count (" + SMALL_REQUEST_COUNT + " requests): " + smallRequestTime + "ms, " 
                + calculateThroughput(SMALL_REQUEST_COUNT, smallRequestTime) + " ops/sec");
        System.out.println("Medium request count (" + MEDIUM_REQUEST_COUNT + " requests): " + mediumRequestTime + "ms, " 
                + calculateThroughput(MEDIUM_REQUEST_COUNT, mediumRequestTime) + " ops/sec");
        System.out.println("Large request count (" + LARGE_REQUEST_COUNT + " requests): " + largeRequestTime + "ms, " 
                + calculateThroughput(LARGE_REQUEST_COUNT, largeRequestTime) + " ops/sec");
        
        // Verify that the throughput is acceptable
        // This is a simple check to ensure that the throughput doesn't degrade too much with larger request counts
        double smallThroughput = calculateThroughput(SMALL_REQUEST_COUNT, smallRequestTime);
        double largeThroughput = calculateThroughput(LARGE_REQUEST_COUNT, largeRequestTime);
        
        // The large request throughput should be at least 50% of the small request throughput
        assertTrue("Large request throughput should be at least 50% of small request throughput", 
                largeThroughput >= smallThroughput * 0.5);
    }
    
    /**
     * Test the throughput of POST requests.
     */
    @Test
    public void testPostRequestThroughput() throws Exception {
        System.out.println("Testing POST request throughput...");
        
        // Measure time for small request count
        long startTime = System.currentTimeMillis();
        sendPostRequests("/post", SMALL_REQUEST_COUNT);
        long smallRequestTime = System.currentTimeMillis() - startTime;
        
        // Measure time for medium request count
        startTime = System.currentTimeMillis();
        sendPostRequests("/post", MEDIUM_REQUEST_COUNT);
        long mediumRequestTime = System.currentTimeMillis() - startTime;
        
        // Measure time for large request count
        startTime = System.currentTimeMillis();
        sendPostRequests("/post", LARGE_REQUEST_COUNT);
        long largeRequestTime = System.currentTimeMillis() - startTime;
        
        // Print results
        System.out.println("POST request throughput results:");
        System.out.println("Small request count (" + SMALL_REQUEST_COUNT + " requests): " + smallRequestTime + "ms, " 
                + calculateThroughput(SMALL_REQUEST_COUNT, smallRequestTime) + " ops/sec");
        System.out.println("Medium request count (" + MEDIUM_REQUEST_COUNT + " requests): " + mediumRequestTime + "ms, " 
                + calculateThroughput(MEDIUM_REQUEST_COUNT, mediumRequestTime) + " ops/sec");
        System.out.println("Large request count (" + LARGE_REQUEST_COUNT + " requests): " + largeRequestTime + "ms, " 
                + calculateThroughput(LARGE_REQUEST_COUNT, largeRequestTime) + " ops/sec");
        
        // Verify that the throughput is acceptable
        double smallThroughput = calculateThroughput(SMALL_REQUEST_COUNT, smallRequestTime);
        double largeThroughput = calculateThroughput(LARGE_REQUEST_COUNT, largeRequestTime);
        
        // The large request throughput should be at least 50% of the small request throughput
        assertTrue("Large request throughput should be at least 50% of small request throughput", 
                largeThroughput >= smallThroughput * 0.5);
    }
    
    /**
     * Test the latency of various server operations.
     */
    @Test
    public void testOperationLatency() throws Exception {
        System.out.println("Testing operation latency...");
        
        // Measure latency for a GET request to the root endpoint
        long startTime = System.currentTimeMillis();
        sendGetRequest("/");
        long rootGetLatency = System.currentTimeMillis() - startTime;
        
        // Measure latency for a GET request to the health endpoint
        startTime = System.currentTimeMillis();
        sendGetRequest("/health");
        long healthGetLatency = System.currentTimeMillis() - startTime;
        
        // Measure latency for a GET request to the metrics endpoint
        startTime = System.currentTimeMillis();
        sendGetRequest("/metrics");
        long metricsGetLatency = System.currentTimeMillis() - startTime;
        
        // Measure latency for a POST request
        startTime = System.currentTimeMillis();
        sendPostRequest("/post", new JSONObject().put("test", "value"));
        long postLatency = System.currentTimeMillis() - startTime;
        
        // Print results
        System.out.println("Operation latency results:");
        System.out.println("GET / request: " + rootGetLatency + "ms");
        System.out.println("GET /health request: " + healthGetLatency + "ms");
        System.out.println("GET /metrics request: " + metricsGetLatency + "ms");
        System.out.println("POST /post request: " + postLatency + "ms");
        
        // Verify that the latencies are within acceptable ranges
        // These are arbitrary thresholds and should be adjusted based on actual performance requirements
        assertTrue("GET / request latency should be less than 500ms", rootGetLatency < 500);
        assertTrue("GET /health request latency should be less than 500ms", healthGetLatency < 500);
        assertTrue("GET /metrics request latency should be less than 500ms", metricsGetLatency < 500);
        assertTrue("POST /post request latency should be less than 500ms", postLatency < 500);
    }
    
    /**
     * Test the concurrency handling of the server implementation.
     */
    @Test
    public void testConcurrency() throws Exception {
        System.out.println("Testing concurrency...");
        
        // Create a thread pool
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        
        // Create a countdown latch to synchronize the start of all threads
        CountDownLatch startLatch = new CountDownLatch(1);
        
        // Create a countdown latch to wait for all threads to complete
        CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
        
        // Create an atomic counter to track successful operations
        AtomicInteger successCount = new AtomicInteger(0);
        
        // Create a list to store any exceptions that occur
        List<Exception> exceptions = new ArrayList<>();
        
        // Start the threads
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // Wait for the start signal
                    startLatch.await();
                    
                    // Perform operations
                    for (int j = 0; j < 10; j++) {
                        // Send a GET request
                        String getResponse = sendGetRequest("/");
                        if (getResponse != null && !getResponse.isEmpty()) {
                            successCount.incrementAndGet();
                        }
                        
                        // Send a POST request
                        JSONObject postData = new JSONObject();
                        postData.put("thread_id", threadId);
                        postData.put("request_id", j);
                        postData.put("value", threadId * 1000 + j);
                        
                        String postResponse = sendPostRequest("/post", postData);
                        if (postResponse != null && !postResponse.isEmpty()) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    // Signal that this thread is complete
                    completionLatch.countDown();
                }
            });
        }
        
        // Start all threads
        long startTime = System.currentTimeMillis();
        startLatch.countDown();
        
        // Wait for all threads to complete
        boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
        long totalTime = System.currentTimeMillis() - startTime;
        
        // Shutdown the executor
        executor.shutdown();
        
        // Print results
        System.out.println("Concurrency test results:");
        System.out.println("Total time: " + totalTime + "ms");
        System.out.println("Successful operations: " + successCount.get() + " out of " + (THREAD_COUNT * 20));
        System.out.println("Exceptions: " + exceptions.size());
        
        // Print any exceptions that occurred
        for (Exception e : exceptions) {
            System.err.println("Exception in concurrency test: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Verify that all threads completed
        assertTrue("All threads should complete within the timeout", completed);
        
        // Verify that most operations were successful
        // Allow for some failures due to concurrency conflicts
        assertTrue("At least 90% of operations should succeed", 
                successCount.get() >= THREAD_COUNT * 20 * 0.9);
        
        // Verify that no exceptions occurred
        assertTrue("No exceptions should occur during concurrency test", exceptions.isEmpty());
    }
    
    /**
     * Test the memory usage of the server implementation.
     */
    @Test
    public void testMemoryUsage() throws Exception {
        System.out.println("Testing memory usage...");
        
        // Get initial memory usage
        Runtime runtime = Runtime.getRuntime();
        runtime.gc(); // Run garbage collection to get a more accurate reading
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // Perform a large number of operations
        sendGetRequests("/", LARGE_REQUEST_COUNT);
        
        // Get final memory usage
        runtime.gc(); // Run garbage collection again
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // Calculate memory usage per operation
        long memoryDifference = finalMemory - initialMemory;
        double memoryPerOperation = (double) memoryDifference / LARGE_REQUEST_COUNT;
        
        // Print results
        System.out.println("Memory usage results:");
        System.out.println("Initial memory usage: " + initialMemory + " bytes");
        System.out.println("Final memory usage: " + finalMemory + " bytes");
        System.out.println("Memory difference: " + memoryDifference + " bytes");
        System.out.println("Memory per operation: " + memoryPerOperation + " bytes");
        
        // Verify that the memory usage is acceptable
        // This is an arbitrary threshold and should be adjusted based on actual performance requirements
        assertTrue("Memory usage per operation should be less than 10KB", memoryPerOperation < 10 * 1024);
    }
    
    /**
     * Helper method to send multiple GET requests.
     * 
     * @param path The path to send the requests to
     * @param count The number of requests to send
     * @throws Exception If an error occurs
     */
    private void sendGetRequests(String path, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            sendGetRequest(path);
        }
    }
    
    /**
     * Helper method to send a GET request.
     * 
     * @param path The path to send the request to
     * @return The response as a string
     * @throws Exception If an error occurs
     */
    private String sendGetRequest(String path) throws Exception {
        URL url = new URL("http://localhost:" + testPort + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        
        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            return null;
        }
        
        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String inputLine;
        StringBuilder response = new StringBuilder();
        
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();
        
        return response.toString();
    }
    
    /**
     * Helper method to send multiple POST requests.
     * 
     * @param path The path to send the requests to
     * @param count The number of requests to send
     * @throws Exception If an error occurs
     */
    private void sendPostRequests(String path, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            JSONObject data = new JSONObject();
            data.put("id", i);
            data.put("value", "Test value " + i);
            
            sendPostRequest(path, data);
        }
    }
    
    /**
     * Helper method to send a POST request.
     * 
     * @param path The path to send the request to
     * @param data The data to send
     * @return The response as a string
     * @throws Exception If an error occurs
     */
    private String sendPostRequest(String path, JSONObject data) throws Exception {
        URL url = new URL("http://localhost:" + testPort + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = data.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
        
        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            return null;
        }
        
        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String inputLine;
        StringBuilder response = new StringBuilder();
        
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();
        
        return response.toString();
    }
    
    /**
     * Helper method to calculate throughput in operations per second.
     * 
     * @param operations The number of operations performed
     * @param timeMs The time taken in milliseconds
     * @return The throughput in operations per second
     */
    private double calculateThroughput(int operations, long timeMs) {
        return operations / (timeMs / 1000.0);
    }
}