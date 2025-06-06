package com.ataiva.serengeti.security.auth;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A filter that enforces authentication for HTTP requests.
 * This filter checks for a valid session token in the Authorization header
 * and rejects requests that don't have a valid token.
 */
public class AuthenticationFilter extends Filter {
    
    private static final Logger LOGGER = Logger.getLogger(AuthenticationFilter.class.getName());
    
    private final AuthenticationManager authManager;
    private final List<String> excludedPaths;
    private final List<UserRole> allowedRoles;
    
    /**
     * Creates a new AuthenticationFilter.
     * 
     * @param authManager The authentication manager to use
     * @param excludedPaths Paths that don't require authentication
     * @param allowedRoles Roles that are allowed to access the protected resources
     */
    public AuthenticationFilter(AuthenticationManager authManager, List<String> excludedPaths, UserRole... allowedRoles) {
        this.authManager = authManager;
        this.excludedPaths = excludedPaths != null ? excludedPaths : new ArrayList<>();
        this.allowedRoles = Arrays.asList(allowedRoles);
    }
    
    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        String path = exchange.getRequestURI().getPath();
        
        // Check if the path is excluded from authentication
        for (String excludedPath : excludedPaths) {
            if (path.startsWith(excludedPath)) {
                chain.doFilter(exchange);
                return;
            }
        }
        
        // Get the Authorization header
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            LOGGER.warning("Authentication failed: Missing or invalid Authorization header for path: " + path);
            sendUnauthorizedResponse(exchange, "Authentication required");
            return;
        }
        
        // Extract the token
        String token = authHeader.substring(7);
        
        // Validate the session
        Session session = authManager.validateSession(token);
        if (session == null) {
            LOGGER.warning("Authentication failed: Invalid or expired session token for path: " + path);
            sendUnauthorizedResponse(exchange, "Invalid or expired session");
            return;
        }
        
        // Check if the user's role is allowed
        if (!allowedRoles.isEmpty() && !allowedRoles.contains(session.getRole())) {
            LOGGER.warning("Authorization failed: User " + session.getUsername() + 
                          " with role " + session.getRole() + 
                          " is not allowed to access path: " + path);
            sendForbiddenResponse(exchange, "Insufficient privileges");
            return;
        }
        
        // Add the session to the exchange attributes for use by handlers
        exchange.setAttribute("session", session);
        
        // Continue with the request
        chain.doFilter(exchange);
    }
    
    /**
     * Sends an unauthorized (401) response.
     * 
     * @param exchange The HTTP exchange
     * @param message The error message
     * @throws IOException If an I/O error occurs
     */
    private void sendUnauthorizedResponse(HttpExchange exchange, String message) throws IOException {
        exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"Serengeti\"");
        exchange.sendResponseHeaders(401, message.length());
        exchange.getResponseBody().write(message.getBytes());
        exchange.getResponseBody().close();
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
        return "Authentication Filter";
    }
}