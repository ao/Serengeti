package com.ataiva.serengeti.unit.server;

import com.ataiva.serengeti.server.MockServer;
import com.ataiva.serengeti.server.Server;
import com.ataiva.serengeti.server.ServerFactory;
import com.ataiva.serengeti.server.ServerImpl;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Unit tests for the ServerFactory class.
 */
public class ServerFactoryTest {

    /**
     * Test creating a default server.
     */
    @Test
    public void testCreateDefaultServer() {
        Server server = ServerFactory.createServer(ServerFactory.ServerType.DEFAULT);
        
        assertNotNull("Server should not be null", server);
        assertTrue("Server should be an instance of Server", server instanceof Server);
        assertFalse("Server should not be an instance of ServerImpl", server instanceof ServerImpl);
    }
    
    /**
     * Test creating a mock server.
     */
    @Test
    public void testCreateMockServer() {
        Server server = ServerFactory.createServer(ServerFactory.ServerType.MOCK);
        
        assertNotNull("Server should not be null", server);
        assertTrue("Server should be an instance of Server", server instanceof Server);
        
        // The MockServerAdapter is a private inner class, so we can't directly check its type
        // Instead, we'll check that it's not a ServerImpl
        assertFalse("Server should not be an instance of ServerImpl", server instanceof ServerImpl);
    }
    
    /**
     * Test creating a real server.
     */
    @Test
    public void testCreateRealServer() {
        Server server = ServerFactory.createServer(ServerFactory.ServerType.REAL);
        
        assertNotNull("Server should not be null", server);
        assertTrue("Server should be an instance of ServerImpl", server instanceof ServerImpl);
    }
    
    /**
     * Test creating a real server with a custom port.
     */
    @Test
    public void testCreateRealServerWithPort() {
        int testPort = 8888;
        Server server = ServerFactory.createServer(ServerFactory.ServerType.REAL, testPort);
        
        assertNotNull("Server should not be null", server);
        assertTrue("Server should be an instance of ServerImpl", server instanceof ServerImpl);
        
        // We can't directly check the port since it's a private field
        // In a real test, we might start the server and try to connect to it
    }
    
    /**
     * Test creating a real server with custom configuration.
     */
    @Test
    public void testCreateRealServerWithCustomConfig() {
        int testPort = 8889;
        int backlog = 50;
        int threadPoolSize = 25;
        long shutdownTimeout = 5;
        TimeUnit shutdownTimeoutUnit = TimeUnit.SECONDS;
        
        Server server = ServerFactory.createRealServer(
                testPort, backlog, threadPoolSize, shutdownTimeout, shutdownTimeoutUnit);
        
        assertNotNull("Server should not be null", server);
        assertTrue("Server should be an instance of ServerImpl", server instanceof ServerImpl);
    }
    
    /**
     * Test server initialization.
     */
    @Test
    public void testServerInitialization() {
        Server server = ServerFactory.createServer(ServerFactory.ServerType.DEFAULT);
        
        // This should not throw an exception
        server.init();
    }
    
    /**
     * Test server serve method.
     * 
     * Note: This test doesn't actually start the server since that would bind to a port.
     * In a real test environment, we might use a mock or a test-specific implementation.
     */
    @Test
    public void testServerServe() {
        Server server = ServerFactory.createServer(ServerFactory.ServerType.MOCK);
        
        // Initialize the server
        server.init();
        
        // This should not throw an exception
        server.serve();
    }
    
    /**
     * Test server shutdown.
     */
    @Test
    public void testServerShutdown() {
        ServerImpl server = (ServerImpl) ServerFactory.createServer(ServerFactory.ServerType.REAL);
        
        // Initialize the server
        server.init();
        
        // This should not throw an exception
        server.shutdown();
    }
}