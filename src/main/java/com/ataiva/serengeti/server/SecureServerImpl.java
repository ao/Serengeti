package com.ataiva.serengeti.server;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.security.SecurityManager;
import com.sun.net.httpserver.HttpsServer;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A secure implementation of the Server class that uses HTTPS.
 * This class extends ServerImpl and adds TLS/SSL support.
 */
public class SecureServerImpl extends ServerImpl {
    
    private static final Logger LOGGER = Logger.getLogger(SecureServerImpl.class.getName());
    
    private final SecurityManager securityManager;
    
    /**
     * Creates a new SecureServerImpl with default settings.
     */
    public SecureServerImpl() {
        this(Globals.port_default, 100, 50, 10, TimeUnit.SECONDS);
    }
    
    /**
     * Creates a new SecureServerImpl with custom settings.
     * 
     * @param port The port to listen on
     * @param backlog The maximum number of queued incoming connections
     * @param threadPoolSize The number of threads in the thread pool
     * @param shutdownTimeout The timeout for graceful shutdown
     * @param shutdownTimeoutUnit The time unit for the shutdown timeout
     */
    public SecureServerImpl(int port, int backlog, int threadPoolSize, 
                           long shutdownTimeout, TimeUnit shutdownTimeoutUnit) {
        super(port, backlog, threadPoolSize, shutdownTimeout, shutdownTimeoutUnit);
        
        // Create security manager with TLS enabled
        this.securityManager = new SecurityManager(true);
        
        LOGGER.info("SecureServerImpl initialized with TLS enabled");
    }
    
    /**
     * Starts the server and begins accepting connections over HTTPS.
     */
    @Override
    public void serve() {
        if (isRunning()) {
            LOGGER.warning("Server is already running");
            return;
        }
        
        try {
            // Create an HTTPS server instead of an HTTP server
            httpServer = HttpsServer.create(new InetSocketAddress(port), backlog);
            
            // Set up request handlers
            setupRequestHandlers();
            
            // Initialize security with TLS
            securityManager.configureHttpServer(httpServer);
            
            // Set the executor
            httpServer.setExecutor(threadPool);
            
            // Start the server
            httpServer.start();
            setRunning(true);
            
            LOGGER.info("SecureServerImpl started on port " + port + " with HTTPS");
            
            // Log server information
            try {
                System.out.printf("\nHTTPS server started at https://%s:%d/%n",
                        Globals.getHost4Address(), port);
                System.out.printf("Dashboard available at https://%s:%d/dashboard%n",
                        Globals.getHost4Address(), port);
                System.out.printf("\nNode is 'online' and ready to contribute (took %dms to startup)%n",
                        System.currentTimeMillis() - Serengeti.startTime);
            } catch (SocketException se) {
                LOGGER.log(Level.WARNING, "Could not get host address", se);
                System.out.println("Could not start HTTPS server, IP lookup failed");
            }
        } catch (BindException be) {
            LOGGER.log(Level.WARNING, "Port " + port + " is already in use", be);
            System.out.println("Warning: Port " + port + " is already in use. This node will operate in passive mode.");
            // Don't exit, just continue without starting the HTTP server
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error starting secure server", e);
            System.out.println("Error starting secure server: " + e.getMessage());
        }
    }
    
    /**
     * Gets the security manager.
     * 
     * @return The security manager
     */
    public SecurityManager getSecurityManager() {
        return securityManager;
    }
    
    /**
     * Checks if the server is using HTTPS.
     * 
     * @return true (always, since this is a secure server)
     */
    public boolean isSecure() {
        return true;
    }
}