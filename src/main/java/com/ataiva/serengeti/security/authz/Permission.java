package com.ataiva.serengeti.security.authz;

import java.io.Serializable;
import java.util.Objects;

/**
 * Represents a permission in the Serengeti system.
 * A permission is defined by a resource type, resource name, and action.
 */
public class Permission implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private final String resourceType;
    private final String resourceName;
    private final String action;
    
    /**
     * Creates a new Permission.
     * 
     * @param resourceType The type of resource (e.g., "database", "table")
     * @param resourceName The name of the resource (e.g., "users_db", "customers")
     * @param action The action to perform (e.g., "read", "write", "delete")
     */
    public Permission(String resourceType, String resourceName, String action) {
        this.resourceType = resourceType;
        this.resourceName = resourceName;
        this.action = action;
    }
    
    /**
     * Gets the resource type.
     * 
     * @return The resource type
     */
    public String getResourceType() {
        return resourceType;
    }
    
    /**
     * Gets the resource name.
     * 
     * @return The resource name
     */
    public String getResourceName() {
        return resourceName;
    }
    
    /**
     * Gets the action.
     * 
     * @return The action
     */
    public String getAction() {
        return action;
    }
    
    /**
     * Checks if this permission implies another permission.
     * A permission implies another if it is more general or equal to the other.
     * For example, a permission for "database:*:read" implies "database:users_db:read".
     * 
     * @param other The other permission to check
     * @return true if this permission implies the other, false otherwise
     */
    public boolean implies(Permission other) {
        // Check resource type
        if (!resourceTypeMatches(other.resourceType)) {
            return false;
        }
        
        // Check resource name
        if (!resourceNameMatches(other.resourceName)) {
            return false;
        }
        
        // Check action
        return actionMatches(other.action);
    }
    
    /**
     * Checks if this permission's resource type matches another resource type.
     * 
     * @param otherResourceType The other resource type
     * @return true if the resource types match, false otherwise
     */
    private boolean resourceTypeMatches(String otherResourceType) {
        return "*".equals(resourceType) || resourceType.equals(otherResourceType);
    }
    
    /**
     * Checks if this permission's resource name matches another resource name.
     * 
     * @param otherResourceName The other resource name
     * @return true if the resource names match, false otherwise
     */
    private boolean resourceNameMatches(String otherResourceName) {
        if ("*".equals(resourceName)) {
            return true;
        }
        
        // Support wildcard patterns like "users_*"
        if (resourceName.endsWith("*") && !resourceName.equals("*")) {
            String prefix = resourceName.substring(0, resourceName.length() - 1);
            return otherResourceName.startsWith(prefix);
        }
        
        return resourceName.equals(otherResourceName);
    }
    
    /**
     * Checks if this permission's action matches another action.
     * 
     * @param otherAction The other action
     * @return true if the actions match, false otherwise
     */
    private boolean actionMatches(String otherAction) {
        return "*".equals(action) || action.equals(otherAction);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Permission that = (Permission) o;
        return Objects.equals(resourceType, that.resourceType) &&
               Objects.equals(resourceName, that.resourceName) &&
               Objects.equals(action, that.action);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(resourceType, resourceName, action);
    }
    
    @Override
    public String toString() {
        return resourceType + ":" + resourceName + ":" + action;
    }
    
    /**
     * Parses a permission string in the format "resourceType:resourceName:action".
     * 
     * @param permissionString The permission string
     * @return The parsed Permission
     * @throws IllegalArgumentException If the permission string is invalid
     */
    public static Permission fromString(String permissionString) {
        String[] parts = permissionString.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid permission format: " + permissionString);
        }
        return new Permission(parts[0], parts[1], parts[2]);
    }
}