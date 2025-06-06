package com.ataiva.serengeti.security.auth;

import java.io.Serializable;
import java.time.Instant;

/**
 * Represents a user in the Serengeti system.
 */
public class User implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private String username;
    private String hashedPassword;
    private UserRole role;
    private Instant createdAt;
    private Instant lastLogin;
    
    /**
     * Creates a new User.
     * 
     * @param username The username
     * @param hashedPassword The hashed password
     * @param role The user's role
     */
    public User(String username, String hashedPassword, UserRole role) {
        this.username = username;
        this.hashedPassword = hashedPassword;
        this.role = role;
        this.createdAt = Instant.now();
    }
    
    /**
     * Gets the username.
     * 
     * @return The username
     */
    public String getUsername() {
        return username;
    }
    
    /**
     * Gets the hashed password.
     * 
     * @return The hashed password
     */
    public String getHashedPassword() {
        return hashedPassword;
    }
    
    /**
     * Sets the hashed password.
     * 
     * @param hashedPassword The new hashed password
     */
    public void setHashedPassword(String hashedPassword) {
        this.hashedPassword = hashedPassword;
    }
    
    /**
     * Gets the user's role.
     * 
     * @return The user's role
     */
    public UserRole getRole() {
        return role;
    }
    
    /**
     * Sets the user's role.
     * 
     * @param role The new role
     */
    public void setRole(UserRole role) {
        this.role = role;
    }
    
    /**
     * Gets the creation time.
     * 
     * @return The creation time
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    /**
     * Gets the last login time.
     * 
     * @return The last login time
     */
    public Instant getLastLogin() {
        return lastLogin;
    }
    
    /**
     * Sets the last login time.
     * 
     * @param lastLogin The new last login time
     */
    public void setLastLogin(Instant lastLogin) {
        this.lastLogin = lastLogin;
    }
    
    /**
     * Updates the last login time to now.
     */
    public void updateLastLogin() {
        this.lastLogin = Instant.now();
    }
    
    @Override
    public String toString() {
        return "User{" +
                "username='" + username + '\'' +
                ", role=" + role +
                ", createdAt=" + createdAt +
                ", lastLogin=" + lastLogin +
                '}';
    }
}