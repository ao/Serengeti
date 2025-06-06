package com.ataiva.serengeti.security.authz;

import com.ataiva.serengeti.security.auth.Session;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A filter that enforces authorization for HTTP requests.
 * This filter checks if the authenticated user has the required permissions
 * to access the requested resource.
 */
public class AuthorizationFilter extends Filter {
    
    private static final Logger LOGGER = Logger.getLogger(AuthorizationFilter.class.getName());
    
    private final AuthorizationManager authzManager;
    private final Map<String, Permission> pathPermissions;
    
    /**
     * Creates a new AuthorizationFilter.
     * 
     * @param authzManager The authorization manager to use
     */
    public AuthorizationFilter(AuthorizationManager authzManager) {
        this.authzManager = authzManager;
        this.pathPermissions = new HashMap<>();
        initializeDefaultPathPermissions();
    }
    
    /**
     * Initializes default path permissions.
     */
    private void initializeDefaultPathPermissions() {
        // Public endpoints (no permissions required)
        // These are typically handled by the AuthenticationFilter's excludedPaths
        
        // Admin endpoints
        pathPermissions.put("/admin", new Permission("admin", "dashboard", "access"));
        pathPermissions.put("/admin/shutdown", new Permission("admin", "server", "shutdown"));
        pathPermissions.put("/admin/restart", new Permission("admin", "server", "restart"));
        
        // User management endpoints
        pathPermissions.put("/auth/users", new Permission("admin", "users", "manage"));
        
        // Database endpoints
        pathPermissions.put("/api/databases", new Permission("database", "*", "read"));
        pathPermissions.put("/api/databases/create", new Permission("database", "*", "create"));
        
        // Table endpoints
        pathPermissions.put("/api/tables", new Permission("table", "*", "read"));
        pathPermissions.put("/api/tables/create", new Permission("table", "*", "create"));
        
        // Query endpoints
        pathPermissions.put("/api/query", new Permission("query", "*", "execute"));
        
        LOGGER.info("Default path permissions initialized");
    }
    
    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        // Get the session from the exchange attributes (set by the AuthenticationFilter)
        Session session = (Session) exchange.getAttribute("session");
        if (session == null) {
            // No session means the request wasn't authenticated
            // This should be handled by the AuthenticationFilter, but just in case
            sendForbiddenResponse(exchange, "Authentication required");
            return;
        }
        
        String path = exchange.getRequestURI().getPath();
        
        // Find the most specific permission required for this path
        Permission requiredPermission = findRequiredPermission(path);
        if (requiredPermission == null) {
            // No specific permission required for this path
            chain.doFilter(exchange);
            return;
        }
        
        // Check if the user has the required permission
        boolean hasPermission = authzManager.hasPermission(
            session.getUsername(),
            session.getRole(),
            requiredPermission
        );
        
        if (!hasPermission) {
            LOGGER.warning("Authorization failed: User " + session.getUsername() + 
                          " with role " + session.getRole() + 
                          " does not have permission " + requiredPermission + 
                          " for path: " + path);
            sendForbiddenResponse(exchange, "Insufficient privileges");
            return;
        }
        
        // User has permission, continue with the request
        chain.doFilter(exchange);
    }
    
    /**
     * Finds the permission required for a path.
     * 
     * @param path The path
     * @return The required permission, or null if no specific permission is required
     */
    private Permission findRequiredPermission(String path) {
        // First, check for an exact match
        Permission permission = pathPermissions.get(path);
        if (permission != null) {
            return permission;
        }
        
        // Then, check for prefix matches
        String bestMatch = null;
        for (String registeredPath : pathPermissions.keySet()) {
            if (path.startsWith(registeredPath)) {
                if (bestMatch == null || registeredPath.length() > bestMatch.length()) {
                    bestMatch = registeredPath;
                }
            }
        }
        
        return bestMatch != null ? pathPermissions.get(bestMatch) : null;
    }
    
    /**
     * Registers a permission requirement for a path.
     * 
     * @param path The path
     * @param permission The required permission
     */
    public void registerPathPermission(String path, Permission permission) {
        pathPermissions.put(path, permission);
        LOGGER.info("Registered permission " + permission + " for path " + path);
    }
    
    /**
     * Unregisters a permission requirement for a path.
     * 
     * @param path The path
     * @return true if a permission was unregistered, false otherwise
     */
    public boolean unregisterPathPermission(String path) {
        Permission removed = pathPermissions.remove(path);
        if (removed != null) {
            LOGGER.info("Unregistered permission for path " + path);
            return true;
        }
        return false;
    }
    
    /**
     * Sends a forbidden (403) response.
     * 
     * @param exchange The HTTP exchange
     * @param message The error message
     * @throws IOException If an I/O error occurs
     */
    private void sendForbiddenResponse(HttpExchange exchange, String message) throws IOException {
        exchange.sendResponseHeaders(403, message.length());
        exchange.getResponseBody().write(message.getBytes());
        exchange.getResponseBody().close();
    }
    
    @Override
    public String description() {
        return "Authorization Filter";
    }
}