package com.ataiva.serengeti.security.auth;

import java.io.Serializable;

/**
 * Represents an authenticated user session.
 */
public class Session implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private final String token;
    private final String username;
    private final UserRole role;
    private final long creationTime;
    private final long expiryTime;
    private long lastAccessTime;
    
    /**
     * Creates a new Session.
     * 
     * @param token The session token
     * @param username The username
     * @param role The user's role
     * @param creationTime The creation time in milliseconds
     * @param expiryTime The expiry time in milliseconds
     */
    public Session(String token, String username, UserRole role, long creationTime, long expiryTime) {
        this.token = token;
        this.username = username;
        this.role = role;
        this.creationTime = creationTime;
        this.expiryTime = expiryTime;
        this.lastAccessTime = creationTime;
    }
    
    /**
     * Gets the session token.
     * 
     * @return The session token
     */
    public String getToken() {
        return token;
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
     * Gets the user's role.
     * 
     * @return The user's role
     */
    public UserRole getRole() {
        return role;
    }
    
    /**
     * Gets the creation time.
     * 
     * @return The creation time in milliseconds
     */
    public long getCreationTime() {
        return creationTime;
    }
    
    /**
     * Gets the expiry time.
     * 
     * @return The expiry time in milliseconds
     */
    public long getExpiryTime() {
        return expiryTime;
    }
    
    /**
     * Gets the last access time.
     * 
     * @return The last access time in milliseconds
     */
    public long getLastAccessTime() {
        return lastAccessTime;
    }
    
    /**
     * Sets the last access time.
     * 
     * @param lastAccessTime The new last access time in milliseconds
     */
    public void setLastAccessTime(long lastAccessTime) {
        this.lastAccessTime = lastAccessTime;
    }
    
    /**
     * Checks if the session has expired.
     * 
     * @return true if the session has expired, false otherwise
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > expiryTime;
    }
    
    /**
     * Gets the remaining time until expiry.
     * 
     * @return The remaining time in milliseconds, or 0 if expired
     */
    public long getRemainingTime() {
        long remaining = expiryTime - System.currentTimeMillis();
        return Math.max(0, remaining);
    }
    
    @Override
    public String toString() {
        return "Session{" +
                "username='" + username + '\'' +
                ", role=" + role +
                ", creationTime=" + creationTime +
                ", expiryTime=" + expiryTime +
                ", lastAccessTime=" + lastAccessTime +
                '}';
    }
}