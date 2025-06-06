package com.ataiva.serengeti.integration;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.network.NetworkFactory;
import com.ataiva.serengeti.network.NetworkImpl;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Integration tests for the NetworkImpl class.
 * These tests verify that the NetworkImpl class integrates correctly with other components.
 */
public class NetworkIntegrationTest {

    private NetworkImpl networkImpl;
    private ExecutorService testExecutor;
    private ServerSocket mockServer;
    private int testPort;
    
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
                            // Simple HTTP response
                            String response = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: 57\r\n" +
                                    "\r\n" +
                                    "{\"this\":{\"id\":\"test-node\",\"ip\":\"127.0.0.1\",\"port\":" + testPort + "}}";
                            
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
    
    @Test
    public void testNetworkInitialization() {
        // Initialize the network
        networkImpl.init();
        
        // Verify that the network is initialized
        assertTrue("Network should be initialized", networkImpl.initialized);
    }
    
    @Test
    public void testNetworkFactoryIntegration() {
        // Create a network using the factory
        Network network = NetworkFactory.createNetwork(NetworkFactory.NetworkType.REAL);
        
        // Initialize the network
        network.init();
        
        // Verify that the network is initialized
        assertNotNull("Network should not be null", network);
        assertTrue("Network should be an instance of NetworkImpl", network instanceof NetworkImpl);
    }
    
    @Test
    public void testSerengetiIntegration() {
        // Create a test instance of Serengeti
        Serengeti testSerengeti = new Serengeti();
        
        // Set the network to our test network
        Serengeti.network = networkImpl;
        
        // Initialize the network
        Serengeti.network.init();
        
        // Verify that the network is initialized
        assertNotNull("Network should not be null", Serengeti.network);
        assertTrue("Network should be an instance of NetworkImpl", Serengeti.network instanceof NetworkImpl);
    }
    
    @Test
    public void testCommunicationWithMockServer() {
        // Initialize the network
        networkImpl.init();
        
        // Test communication with the mock server
        boolean isOnline = networkImpl.nodeIsOnline("127.0.0.1");
        
        // Verify that the mock server is online
        assertTrue("Mock server should be online", isOnline);
    }
}