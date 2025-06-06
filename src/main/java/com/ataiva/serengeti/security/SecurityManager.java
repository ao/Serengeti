package com.ataiva.serengeti.security;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.security.auth.AuthenticationFilter;
import com.ataiva.serengeti.security.auth.AuthenticationHandler;
import com.ataiva.serengeti.security.auth.AuthenticationManager;
import com.ataiva.serengeti.security.auth.UserRole;
import com.ataiva.serengeti.security.authz.AuthorizationFilter;
import com.ataiva.serengeti.security.authz.AuthorizationManager;
import com.ataiva.serengeti.security.tls.KeyStoreGenerator;
import com.ataiva.serengeti.security.tls.TLSConfig;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages security for the Serengeti system.
 * This class coordinates authentication, authorization, and other security components.
 */
public class SecurityManager {
    
    private static final Logger LOGGER = Logger.getLogger(SecurityManager.class.getName());
    
    private final AuthenticationManager authManager;
    private final AuthorizationManager authzManager;
    private final ScheduledExecutorService scheduler;
    private TLSConfig tlsConfig;
    private boolean tlsEnabled = false;
    
    /**
     * Creates a new SecurityManager with default settings.
     */
    public SecurityManager() {
        this(false);
    }
    
    /**
     * Creates a new SecurityManager.
     *
     * @param enableTLS Whether to enable TLS
     */
    public SecurityManager(boolean enableTLS) {
        this.authManager = new AuthenticationManager();
        this.authzManager = new AuthorizationManager();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.tlsEnabled = enableTLS;
        
        // Initialize TLS if enabled
        if (enableTLS) {
            initializeTLS();
        }
        
        // Schedule periodic tasks
        schedulePeriodicTasks();
        
        LOGGER.info("SecurityManager initialized with TLS " + (tlsEnabled ? "enabled" : "disabled"));
    }
    
    /**
     * Initializes TLS configuration.
     */
    private void initializeTLS() {
        try {
            // Define keystore path
            String keystorePath = Globals.data_path + File.separator + "serengeti.keystore";
            String keystorePassword = "serengeti";
            String keyPassword = "serengeti";
            
            // Ensure keystore exists
            boolean keystoreReady = KeyStoreGenerator.ensureKeyStoreExists(
                keystorePath, keystorePassword, keyPassword, "localhost", 365);
            
            if (!keystoreReady) {
                LOGGER.severe("Failed to initialize keystore for TLS");
                tlsEnabled = false;
                return;
            }
            
            // Create TLS config
            tlsConfig = new TLSConfig(
                keystorePath,
                keystorePassword,
                keyPassword,
                new String[]{"TLSv1.2", "TLSv1.3"},
                null // Use default cipher suites
            );
            
            LOGGER.info("TLS initialized with keystore: " + keystorePath);
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error initializing TLS", e);
            tlsEnabled = false;
        }
    }
    
    /**
     * Schedules periodic security tasks.
     */
    private void schedulePeriodicTasks() {
        // Clean up expired sessions every 5 minutes
        scheduler.scheduleAtFixedRate(
            authManager::cleanupExpiredSessions,
            5, 5, TimeUnit.MINUTES
        );
        
        LOGGER.info("Scheduled periodic security tasks");
    }
    
    /**
     * Configures security for an HTTP server.
     * 
     * @param server The HTTP server to configure
     */
    public void configureHttpServer(HttpServer server) {
        // Configure TLS if enabled and server is an HttpsServer
        if (tlsEnabled && server instanceof HttpsServer && tlsConfig != null) {
            try {
                tlsConfig.configureServer((HttpsServer) server);
                LOGGER.info("TLS configured for HTTPS server");
            } catch (TLSConfig.TLSConfigException e) {
                LOGGER.log(Level.SEVERE, "Error configuring TLS for HTTPS server", e);
            }
        }
        // Define paths that don't require authentication
        List<String> publicPaths = Arrays.asList(
            "/auth/login",
            "/health",
            "/",
            "/dashboard",
            "/interactive"
        );
        
        // Create authentication filter
        AuthenticationFilter authFilter = new AuthenticationFilter(
            authManager,
            publicPaths,
            UserRole.values() // Allow all roles by default
        );
        
        // Create authorization filter
        AuthorizationFilter authzFilter = new AuthorizationFilter(authzManager);
        
        // Create authentication handler
        AuthenticationHandler authHandler = new AuthenticationHandler(authManager);
        
        // Add authentication context
        server.createContext("/auth", authHandler).getFilters().add(authFilter);
        
        // Add filters to existing contexts
        server.getContexts().forEach(context -> {
            // Skip the auth context, which already has the filter
            if (!context.getPath().equals("/auth")) {
                context.getFilters().add(authFilter);
                context.getFilters().add(authzFilter);
            }
        });
        
        LOGGER.info("Security configured for HTTP server");
    }
    
    /**
     * Gets the authentication manager.
     * 
     * @return The authentication manager
     */
    public AuthenticationManager getAuthenticationManager() {
        return authManager;
    }
    
    /**
     * Gets the authorization manager.
     * 
     * @return The authorization manager
     */
    public AuthorizationManager getAuthorizationManager() {
        return authzManager;
    }
    
    /**
     * Checks if TLS is enabled.
     *
     * @return true if TLS is enabled, false otherwise
     */
    public boolean isTlsEnabled() {
        return tlsEnabled;
    }
    
    /**
     * Gets the TLS configuration.
     *
     * @return The TLS configuration, or null if TLS is not enabled
     */
    public TLSConfig getTlsConfig() {
        return tlsConfig;
    }
    
    /**
     * Shuts down the security manager.
     */
    public void shutdown() {
        // Shutdown the scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("SecurityManager shutdown complete");
    }
}