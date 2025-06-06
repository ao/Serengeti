package com.ataiva.serengeti.security.auth;

/**
 * Represents the roles that users can have in the Serengeti system.
 */
public enum UserRole {
    /**
     * Administrator role with full access to all features.
     */
    ADMIN,
    
    /**
     * Power user role with access to most features except administrative functions.
     */
    POWER_USER,
    
    /**
     * Standard user role with access to basic features.
     */
    USER,
    
    /**
     * Read-only user role with access to view data but not modify it.
     */
    READ_ONLY;
    
    /**
     * Checks if this role has administrative privileges.
     * 
     * @return true if this role has administrative privileges, false otherwise
     */
    public boolean isAdmin() {
        return this == ADMIN;
    }
    
    /**
     * Checks if this role has write privileges.
     * 
     * @return true if this role has write privileges, false otherwise
     */
    public boolean canWrite() {
        return this == ADMIN || this == POWER_USER || this == USER;
    }
    
    /**
     * Checks if this role has read privileges.
     * 
     * @return true if this role has read privileges, false otherwise
     */
    public boolean canRead() {
        return true; // All roles can read
    }
    
    /**
     * Checks if this role has power user privileges.
     * 
     * @return true if this role has power user privileges, false otherwise
     */
    public boolean isPowerUser() {
        return this == ADMIN || this == POWER_USER;
    }
}