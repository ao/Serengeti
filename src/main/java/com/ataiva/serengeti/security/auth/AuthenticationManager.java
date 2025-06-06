package com.ataiva.serengeti.security.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Manages authentication for the Serengeti system.
 * This class handles user authentication, session management, and related security functions.
 */
public class AuthenticationManager {
    
    private static final Logger LOGGER = Logger.getLogger(AuthenticationManager.class.getName());
    
    // In-memory user store - in a production system, this would be persisted to storage
    private final Map<String, User> users = new ConcurrentHashMap<>();
    
    // Session store - maps session tokens to user information
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    
    // Configuration
    private final int sessionExpiryMinutes;
    private final int bcryptWorkFactor;
    private final SecureRandom secureRandom;
    
    /**
     * Creates a new AuthenticationManager with default settings.
     */
    public AuthenticationManager() {
        this(30, 12); // 30 minute sessions, bcrypt work factor 12
    }
    
    /**
     * Creates a new AuthenticationManager with custom settings.
     * 
     * @param sessionExpiryMinutes The number of minutes after which sessions expire
     * @param bcryptWorkFactor The work factor for BCrypt password hashing
     */
    public AuthenticationManager(int sessionExpiryMinutes, int bcryptWorkFactor) {
        this.sessionExpiryMinutes = sessionExpiryMinutes;
        this.bcryptWorkFactor = bcryptWorkFactor;
        this.secureRandom = new SecureRandom();
        
        // Initialize with a default admin user
        createUser("admin", "admin", UserRole.ADMIN);
        
        LOGGER.info("AuthenticationManager initialized with session expiry: " + 
                    sessionExpiryMinutes + " minutes, bcrypt work factor: " + bcryptWorkFactor);
    }
    
    /**
     * Creates a new user.
     * 
     * @param username The username
     * @param password The password (will be hashed)
     * @param role The user's role
     * @return true if the user was created, false if the username already exists
     */
    public boolean createUser(String username, String password, UserRole role) {
        if (users.containsKey(username)) {
            LOGGER.warning("Attempted to create user with existing username: " + username);
            return false;
        }
        
        String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt(bcryptWorkFactor));
        User user = new User(username, hashedPassword, role);
        users.put(username, user);
        
        LOGGER.info("Created user: " + username + " with role: " + role);
        return true;
    }
    
    /**
     * Authenticates a user with username and password.
     * 
     * @param username The username
     * @param password The password
     * @return A session token if authentication is successful, null otherwise
     */
    public String authenticate(String username, String password) {
        User user = users.get(username);
        if (user == null) {
            LOGGER.warning("Authentication failed: User not found: " + username);
            return null;
        }
        
        if (!BCrypt.checkpw(password, user.getHashedPassword())) {
            LOGGER.warning("Authentication failed: Invalid password for user: " + username);
            return null;
        }
        
        // Generate a session token
        String sessionToken = generateSessionToken();
        
        // Create a new session
        Session session = new Session(
            sessionToken,
            username,
            user.getRole(),
            System.currentTimeMillis(),
            System.currentTimeMillis() + (sessionExpiryMinutes * 60 * 1000)
        );
        
        // Store the session
        sessions.put(sessionToken, session);
        
        LOGGER.info("User authenticated: " + username);
        return sessionToken;
    }
    
    /**
     * Validates a session token.
     * 
     * @param sessionToken The session token to validate
     * @return The session if valid, null otherwise
     */
    public Session validateSession(String sessionToken) {
        Session session = sessions.get(sessionToken);
        if (session == null) {
            LOGGER.fine("Session validation failed: Session not found");
            return null;
        }
        
        // Check if the session has expired
        if (System.currentTimeMillis() > session.getExpiryTime()) {
            LOGGER.info("Session expired for user: " + session.getUsername());
            sessions.remove(sessionToken);
            return null;
        }
        
        // Update the last access time
        session.setLastAccessTime(System.currentTimeMillis());
        
        return session;
    }
    
    /**
     * Invalidates a session.
     * 
     * @param sessionToken The session token to invalidate
     */
    public void invalidateSession(String sessionToken) {
        Session session = sessions.remove(sessionToken);
        if (session != null) {
            LOGGER.info("Session invalidated for user: " + session.getUsername());
        }
    }
    
    /**
     * Generates a secure random session token.
     * 
     * @return A secure random session token
     */
    private String generateSessionToken() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
    
    /**
     * Gets a user by username.
     * 
     * @param username The username
     * @return The user, or null if not found
     */
    public User getUser(String username) {
        return users.get(username);
    }
    
    /**
     * Updates a user's password.
     * 
     * @param username The username
     * @param newPassword The new password
     * @return true if the password was updated, false if the user doesn't exist
     */
    public boolean updatePassword(String username, String newPassword) {
        User user = users.get(username);
        if (user == null) {
            return false;
        }
        
        String hashedPassword = BCrypt.hashpw(newPassword, BCrypt.gensalt(bcryptWorkFactor));
        user.setHashedPassword(hashedPassword);
        
        LOGGER.info("Password updated for user: " + username);
        return true;
    }
    
    /**
     * Updates a user's role.
     * 
     * @param username The username
     * @param newRole The new role
     * @return true if the role was updated, false if the user doesn't exist
     */
    public boolean updateRole(String username, UserRole newRole) {
        User user = users.get(username);
        if (user == null) {
            return false;
        }
        
        user.setRole(newRole);
        
        LOGGER.info("Role updated for user: " + username + " to: " + newRole);
        return true;
    }
    
    /**
     * Deletes a user.
     * 
     * @param username The username
     * @return true if the user was deleted, false if the user doesn't exist
     */
    public boolean deleteUser(String username) {
        User user = users.remove(username);
        if (user == null) {
            return false;
        }
        
        // Invalidate any active sessions for this user
        sessions.entrySet().removeIf(entry -> entry.getValue().getUsername().equals(username));
        
        LOGGER.info("User deleted: " + username);
        return true;
    }
    
    /**
     * Cleans up expired sessions.
     */
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        int count = 0;
        
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            if (now > entry.getValue().getExpiryTime()) {
                sessions.remove(entry.getKey());
                count++;
            }
        }
        
        if (count > 0) {
            LOGGER.info("Cleaned up " + count + " expired sessions");
        }
    }
}