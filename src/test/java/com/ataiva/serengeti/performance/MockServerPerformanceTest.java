package com.ataiva.serengeti.performance;

import com.ataiva.serengeti.server.MockServer;
import com.ataiva.serengeti.server.Server;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Performance tests for the MockServer class.
 * These tests evaluate the performance characteristics of the MockServer class
 * under various conditions and workloads.
 */
@Category(PerformanceTest.class)
public class MockServerPerformanceTest {

    private MockServer mockServer;
    private static final int SMALL_REQUEST_COUNT = 100;
    private static final int MEDIUM_REQUEST_COUNT = 1000;
    private static final int LARGE_REQUEST_COUNT = 10000;
    private static final int THREAD_COUNT = 10;
    
    @Before
    public void setUp() throws Exception {
        // Create a MockServer instance
        mockServer = new MockServer();
        
        // Initialize the server
        mockServer.start();
    }
    
    @After
    public void tearDown() throws Exception {
        // Shutdown the server
        mockServer.stop();
    }
    
    /**
     * Test the throughput of request handling.
     */
    @Test
    public void testRequestThroughput() {
        System.out.println("Testing request throughput...");
        
        // Measure time for small request count
        long startTime = System.currentTimeMillis();
        sendTestRequests(SMALL_REQUEST_COUNT);
        long smallRequestTime = System.currentTimeMillis() - startTime;
        
        // Measure time for medium request count
        startTime = System.currentTimeMillis();
        sendTestRequests(MEDIUM_REQUEST_COUNT);
        long mediumRequestTime = System.currentTimeMillis() - startTime;
        
        // Measure time for large request count
        startTime = System.currentTimeMillis();
        sendTestRequests(LARGE_REQUEST_COUNT);
        long largeRequestTime = System.currentTimeMillis() - startTime;
        
        // Print results
        System.out.println("Request throughput results:");
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
     * Test the latency of various server operations.
     */
    @Test
    public void testOperationLatency() {
        System.out.println("Testing operation latency...");
        
        // Measure latency for a single GET request
        long startTime = System.currentTimeMillis();
        JSONObject getResponse = mockServer.handleRequest("GET", "/test", null);
        long getLatency = System.currentTimeMillis() - startTime;
        
        // Measure latency for a single POST request
        JSONObject postData = new JSONObject();
        postData.put("key", "value");
        startTime = System.currentTimeMillis();
        JSONObject postResponse = mockServer.handleRequest("POST", "/test", postData);
        long postLatency = System.currentTimeMillis() - startTime;
        
        // Measure latency for a single PUT request
        JSONObject putData = new JSONObject();
        putData.put("key", "updated_value");
        startTime = System.currentTimeMillis();
        JSONObject putResponse = mockServer.handleRequest("PUT", "/test", putData);
        long putLatency = System.currentTimeMillis() - startTime;
        
        // Measure latency for a single DELETE request
        startTime = System.currentTimeMillis();
        JSONObject deleteResponse = mockServer.handleRequest("DELETE", "/test", null);
        long deleteLatency = System.currentTimeMillis() - startTime;
        
        // Print results
        System.out.println("Operation latency results:");
        System.out.println("GET request: " + getLatency + "ms");
        System.out.println("POST request: " + postLatency + "ms");
        System.out.println("PUT request: " + putLatency + "ms");
        System.out.println("DELETE request: " + deleteLatency + "ms");
        
        // Verify that the latencies are within acceptable ranges
        // These are arbitrary thresholds and should be adjusted based on actual performance requirements
        assertTrue("GET request latency should be less than 50ms", getLatency < 50);
        assertTrue("POST request latency should be less than 50ms", postLatency < 50);
        assertTrue("PUT request latency should be less than 50ms", putLatency < 50);
        assertTrue("DELETE request latency should be less than 50ms", deleteLatency < 50);
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
                    for (int j = 0; j < 100; j++) {
                        // Create a unique request
                        JSONObject requestData = new JSONObject();
                        requestData.put("thread_id", threadId);
                        requestData.put("request_id", j);
                        requestData.put("value", threadId * 1000 + j);
                        
                        // Send the request
                        JSONObject response = mockServer.handleRequest("POST", "/test", requestData);
                        
                        if (response != null && response.has("status") && response.getString("status").equals("success")) {
                            successCount.incrementAndGet();
                        }
                        
                        // Send a GET request
                        response = mockServer.handleRequest("GET", "/test", null);
                        
                        // Send a PUT request
                        JSONObject updateData = new JSONObject();
                        updateData.put("thread_id", threadId);
                        updateData.put("request_id", j);
                        updateData.put("value", threadId * 2000 + j);
                        response = mockServer.handleRequest("PUT", "/test", updateData);
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
        System.out.println("Successful operations: " + successCount.get() + " out of " + (THREAD_COUNT * 100));
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
                successCount.get() >= THREAD_COUNT * 100 * 0.9);
        
        // Verify that no exceptions occurred
        assertTrue("No exceptions should occur during concurrency test", exceptions.isEmpty());
    }
    
    /**
     * Test the memory usage of the server implementation.
     */
    @Test
    public void testMemoryUsage() {
        System.out.println("Testing memory usage...");
        
        // Get initial memory usage
        Runtime runtime = Runtime.getRuntime();
        runtime.gc(); // Run garbage collection to get a more accurate reading
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // Perform a large number of operations
        for (int i = 0; i < LARGE_REQUEST_COUNT; i++) {
            JSONObject requestData = new JSONObject();
            requestData.put("id", i);
            requestData.put("value", "Test value " + i);
            mockServer.handleRequest("POST", "/test", requestData);
        }
        
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
        assertTrue("Memory usage per operation should be less than 1KB", memoryPerOperation < 1024);
    }
    
    /**
     * Helper method to send test requests.
     * 
     * @param count The number of requests to send
     */
    private void sendTestRequests(int count) {
        for (int i = 0; i < count; i++) {
            JSONObject requestData = new JSONObject();
            requestData.put("id", i);
            requestData.put("value", "Test value " + i);
            
            mockServer.handleRequest("POST", "/test", requestData);
        }
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