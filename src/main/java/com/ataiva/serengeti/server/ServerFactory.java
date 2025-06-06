package com.ataiva.serengeti.server;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.security.SecurityManager;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * A factory class for creating Server instances.
 * This class provides methods to create different types of Server implementations
 * based on the requirements of the application.
 */
public class ServerFactory {
    
    private static final Logger LOGGER = Logger.getLogger(ServerFactory.class.getName());
    
    /**
     * Enum representing the different types of servers that can be created.
     */
    public enum ServerType {
        /**
         * The default Server implementation.
         */
        DEFAULT,
        
        /**
         * A mock Server implementation for testing.
         */
        MOCK,
        
        /**
         * A real Server implementation with enhanced features.
         */
        REAL,
        
        /**
         * A secure Server implementation with authentication and authorization.
         */
        SECURE
    }
    
    /**
     * Creates a Server instance of the specified type.
     * 
     * @param type The type of Server to create
     * @return A Server instance
     */
    public static Server createServer(ServerType type) {
        return createServer(type, Globals.port_default);
    }
    
    /**
     * Creates a Server instance of the specified type with a custom port.
     * 
     * @param type The type of Server to create
     * @param port The port for the server to listen on
     * @return A Server instance
     */
    public static Server createServer(ServerType type, int port) {
        LOGGER.info("Creating server of type: " + type);
        
        switch (type) {
            case MOCK:
                return createMockServer();
            case REAL:
                return createRealServer(port);
            case SECURE:
                return createSecureServer(port);
            case DEFAULT:
            default:
                return createDefaultServer();
        }
    }
    
    /**
     * Creates a default Server instance.
     * 
     * @return A default Server instance
     */
    private static Server createDefaultServer() {
        LOGGER.info("Creating default server");
        return new Server();
    }
    
    /**
     * Creates a mock Server instance for testing.
     * 
     * @return A MockServer instance
     */
    private static Server createMockServer() {
        LOGGER.info("Creating mock server");
        return new MockServerAdapter();
    }
    
    /**
     * Creates a real Server instance with enhanced features.
     * 
     * @param port The port for the server to listen on
     * @return A ServerImpl instance
     */
    private static Server createRealServer(int port) {
        LOGGER.info("Creating real server on port: " + port);
        return new ServerImpl(port, 100, 50, 10, TimeUnit.SECONDS);
    }
    
    /**
     * Creates a real Server instance with custom configuration.
     * 
     * @param port The port for the server to listen on
     * @param backlog The maximum number of queued incoming connections
     * @param threadPoolSize The number of threads in the thread pool
     * @param shutdownTimeout The timeout for graceful shutdown
     * @param shutdownTimeoutUnit The time unit for the shutdown timeout
     * @return A ServerImpl instance
     */
    public static Server createRealServer(int port, int backlog, int threadPoolSize, 
                                         long shutdownTimeout, TimeUnit shutdownTimeoutUnit) {
        LOGGER.info("Creating real server with custom configuration");
        return new ServerImpl(port, backlog, threadPoolSize, shutdownTimeout, shutdownTimeoutUnit);
    }
    
    /**
     * Creates a secure Server instance with authentication, authorization, and HTTPS.
     *
     * @param port The port for the server to listen on
     * @return A SecureServerImpl instance with security configured
     */
    public static Server createSecureServer(int port) {
        LOGGER.info("Creating secure server on port: " + port);
        return new SecureServerImpl(port, 100, 50, 10, TimeUnit.SECONDS);
    }
    
    /**
     * Creates a secure Server instance with custom configuration.
     *
     * @param port The port for the server to listen on
     * @param backlog The maximum number of queued incoming connections
     * @param threadPoolSize The number of threads in the thread pool
     * @param shutdownTimeout The timeout for graceful shutdown
     * @param shutdownTimeoutUnit The time unit for the shutdown timeout
     * @return A SecureServerImpl instance with security configured
     */
    public static Server createSecureServer(int port, int backlog, int threadPoolSize,
                                           long shutdownTimeout, TimeUnit shutdownTimeoutUnit) {
        LOGGER.info("Creating secure server with custom configuration");
        return new SecureServerImpl(port, backlog, threadPoolSize, shutdownTimeout, shutdownTimeoutUnit);
    }
    
    /**
     * An adapter class that adapts MockServer to the Server interface.
     */
    private static class MockServerAdapter extends Server {
        private final MockServer mockServer;
        
        public MockServerAdapter() {
            this.mockServer = new MockServer();
        }
        
        @Override
        public void init() {
            super.init();
        }
        
        @Override
        public void serve() {
            mockServer.start();
        }
        
        /**
         * Stops the mock server.
         */
        public void shutdown() {
            mockServer.stop();
        }
        
        /**
         * Gets the underlying MockServer instance.
         * 
         * @return The MockServer instance
         */
        public MockServer getMockServer() {
            return mockServer;
        }
    }
}