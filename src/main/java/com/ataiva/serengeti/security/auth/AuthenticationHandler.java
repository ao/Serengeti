package com.ataiva.serengeti.security.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Handles authentication-related HTTP requests.
 * This includes login, logout, and user management operations.
 */
public class AuthenticationHandler implements HttpHandler {
    
    private static final Logger LOGGER = Logger.getLogger(AuthenticationHandler.class.getName());
    
    private final AuthenticationManager authManager;
    
    /**
     * Creates a new AuthenticationHandler.
     * 
     * @param authManager The authentication manager to use
     */
    public AuthenticationHandler(AuthenticationManager authManager) {
        this.authManager = authManager;
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        
        try {
            if (path.endsWith("/login") && "POST".equals(method)) {
                handleLogin(exchange);
            } else if (path.endsWith("/logout") && "POST".equals(method)) {
                handleLogout(exchange);
            } else if (path.endsWith("/users") && "GET".equals(method)) {
                handleListUsers(exchange);
            } else if (path.endsWith("/users") && "POST".equals(method)) {
                handleCreateUser(exchange);
            } else if (path.matches(".*/users/[^/]+") && "PUT".equals(method)) {
                handleUpdateUser(exchange);
            } else if (path.matches(".*/users/[^/]+") && "DELETE".equals(method)) {
                handleDeleteUser(exchange);
            } else {
                sendResponse(exchange, 404, "Not Found");
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error handling authentication request", e);
            sendResponse(exchange, 500, "Internal Server Error");
        }
    }
    
    /**
     * Handles login requests.
     * 
     * @param exchange The HTTP exchange
     * @throws IOException If an I/O error occurs
     */
    private void handleLogin(HttpExchange exchange) throws IOException {
        // Parse the request body
        String requestBody = readRequestBody(exchange);
        JSONObject requestJson = new JSONObject(requestBody);
        
        // Extract credentials
        String username = requestJson.optString("username");
        String password = requestJson.optString("password");
        
        if (username.isEmpty() || password.isEmpty()) {
            sendResponse(exchange, 400, "Username and password are required");
            return;
        }
        
        // Authenticate
        String sessionToken = authManager.authenticate(username, password);
        if (sessionToken == null) {
            sendResponse(exchange, 401, "Invalid credentials");
            return;
        }
        
        // Create response
        JSONObject responseJson = new JSONObject();
        responseJson.put("token", sessionToken);
        
        Session session = authManager.validateSession(sessionToken);
        responseJson.put("username", session.getUsername());
        responseJson.put("role", session.getRole().toString());
        responseJson.put("expiresAt", session.getExpiryTime());
        
        sendJsonResponse(exchange, 200, responseJson);
    }
    
    /**
     * Handles logout requests.
     * 
     * @param exchange The HTTP exchange
     * @throws IOException If an I/O error occurs
     */
    private void handleLogout(HttpExchange exchange) throws IOException {
        // Get the session from the exchange attributes (set by the AuthenticationFilter)
        Session session = (Session) exchange.getAttribute("session");
        if (session == null) {
            sendResponse(exchange, 401, "Not authenticated");
            return;
        }
        
        // Invalidate the session
        authManager.invalidateSession(session.getToken());
        
        sendResponse(exchange, 200, "Logged out successfully");
    }
    
    /**
     * Handles requests to list users.
     * 
     * @param exchange The HTTP exchange
     * @throws IOException If an I/O error occurs
     */
    private void handleListUsers(HttpExchange exchange) throws IOException {
        // Get the session from the exchange attributes (set by the AuthenticationFilter)
        Session session = (Session) exchange.getAttribute("session");
        if (session == null || !session.getRole().isAdmin()) {
            sendResponse(exchange, 403, "Insufficient privileges");
            return;
        }
        
        // This would normally query the user store
        // For now, we'll just return a success message
        sendResponse(exchange, 200, "User listing functionality to be implemented");
    }
    
    /**
     * Handles requests to create a user.
     * 
     * @param exchange The HTTP exchange
     * @throws IOException If an I/O error occurs
     */
    private void handleCreateUser(HttpExchange exchange) throws IOException {
        // Get the session from the exchange attributes (set by the AuthenticationFilter)
        Session session = (Session) exchange.getAttribute("session");
        if (session == null || !session.getRole().isAdmin()) {
            sendResponse(exchange, 403, "Insufficient privileges");
            return;
        }
        
        // Parse the request body
        String requestBody = readRequestBody(exchange);
        JSONObject requestJson = new JSONObject(requestBody);
        
        // Extract user details
        String username = requestJson.optString("username");
        String password = requestJson.optString("password");
        String roleStr = requestJson.optString("role");
        
        if (username.isEmpty() || password.isEmpty() || roleStr.isEmpty()) {
            sendResponse(exchange, 400, "Username, password, and role are required");
            return;
        }
        
        // Parse the role
        UserRole role;
        try {
            role = UserRole.valueOf(roleStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, "Invalid role: " + roleStr);
            return;
        }
        
        // Create the user
        boolean created = authManager.createUser(username, password, role);
        if (!created) {
            sendResponse(exchange, 409, "User already exists");
            return;
        }
        
        sendResponse(exchange, 201, "User created successfully");
    }
    
    /**
     * Handles requests to update a user.
     * 
     * @param exchange The HTTP exchange
     * @throws IOException If an I/O error occurs
     */
    private void handleUpdateUser(HttpExchange exchange) throws IOException {
        // Get the session from the exchange attributes (set by the AuthenticationFilter)
        Session session = (Session) exchange.getAttribute("session");
        if (session == null || !session.getRole().isAdmin()) {
            sendResponse(exchange, 403, "Insufficient privileges");
            return;
        }
        
        // Extract the username from the path
        String path = exchange.getRequestURI().getPath();
        String username = path.substring(path.lastIndexOf('/') + 1);
        
        // Parse the request body
        String requestBody = readRequestBody(exchange);
        JSONObject requestJson = new JSONObject(requestBody);
        
        // Check if the user exists
        User user = authManager.getUser(username);
        if (user == null) {
            sendResponse(exchange, 404, "User not found");
            return;
        }
        
        // Update password if provided
        String password = requestJson.optString("password");
        if (!password.isEmpty()) {
            authManager.updatePassword(username, password);
        }
        
        // Update role if provided
        String roleStr = requestJson.optString("role");
        if (!roleStr.isEmpty()) {
            try {
                UserRole role = UserRole.valueOf(roleStr.toUpperCase());
                authManager.updateRole(username, role);
            } catch (IllegalArgumentException e) {
                sendResponse(exchange, 400, "Invalid role: " + roleStr);
                return;
            }
        }
        
        sendResponse(exchange, 200, "User updated successfully");
    }
    
    /**
     * Handles requests to delete a user.
     * 
     * @param exchange The HTTP exchange
     * @throws IOException If an I/O error occurs
     */
    private void handleDeleteUser(HttpExchange exchange) throws IOException {
        // Get the session from the exchange attributes (set by the AuthenticationFilter)
        Session session = (Session) exchange.getAttribute("session");
        if (session == null || !session.getRole().isAdmin()) {
            sendResponse(exchange, 403, "Insufficient privileges");
            return;
        }
        
        // Extract the username from the path
        String path = exchange.getRequestURI().getPath();
        String username = path.substring(path.lastIndexOf('/') + 1);
        
        // Delete the user
        boolean deleted = authManager.deleteUser(username);
        if (!deleted) {
            sendResponse(exchange, 404, "User not found");
            return;
        }
        
        sendResponse(exchange, 200, "User deleted successfully");
    }
    
    /**
     * Reads the request body as a string.
     * 
     * @param exchange The HTTP exchange
     * @return The request body as a string
     * @throws IOException If an I/O error occurs
     */
    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            return br.lines().collect(Collectors.joining(System.lineSeparator()));
        }
    }
    
    /**
     * Sends a text response.
     * 
     * @param exchange The HTTP exchange
     * @param statusCode The HTTP status code
     * @param response The response text
     * @throws IOException If an I/O error occurs
     */
    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, response.length());
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes(StandardCharsets.UTF_8));
        }
    }
    
    /**
     * Sends a JSON response.
     * 
     * @param exchange The HTTP exchange
     * @param statusCode The HTTP status code
     * @param json The JSON object to send
     * @throws IOException If an I/O error occurs
     */
    private void sendJsonResponse(HttpExchange exchange, int statusCode, JSONObject json) throws IOException {
        String response = json.toString();
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, response.length());
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes(StandardCharsets.UTF_8));
        }
    }
}