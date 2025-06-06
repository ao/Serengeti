package com.ataiva.serengeti.security.authz;

import com.ataiva.serengeti.security.auth.UserRole;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages authorization for the Serengeti system.
 * This class handles role-based access control (RBAC) and permission evaluation.
 */
public class AuthorizationManager {
    
    private static final Logger LOGGER = Logger.getLogger(AuthorizationManager.class.getName());
    
    // Maps roles to their permissions
    private final Map<UserRole, Set<Permission>> rolePermissions = new ConcurrentHashMap<>();
    
    // Maps usernames to their additional permissions (beyond their role)
    private final Map<String, Set<Permission>> userPermissions = new ConcurrentHashMap<>();
    
    /**
     * Creates a new AuthorizationManager with default permissions.
     */
    public AuthorizationManager() {
        initializeDefaultPermissions();
    }
    
    /**
     * Initializes default permissions for each role.
     */
    private void initializeDefaultPermissions() {
        // Admin role has all permissions
        Set<Permission> adminPermissions = new HashSet<>();
        adminPermissions.add(new Permission("*", "*", "*"));
        rolePermissions.put(UserRole.ADMIN, adminPermissions);
        
        // Power user role has read access to everything and write access to most things
        Set<Permission> powerUserPermissions = new HashSet<>();
        powerUserPermissions.add(new Permission("*", "*", "read"));
        powerUserPermissions.add(new Permission("database", "*", "write"));
        powerUserPermissions.add(new Permission("table", "*", "write"));
        powerUserPermissions.add(new Permission("query", "*", "execute"));
        rolePermissions.put(UserRole.POWER_USER, powerUserPermissions);
        
        // User role has read access to everything and write access to their own data
        Set<Permission> userPermissions = new HashSet<>();
        userPermissions.add(new Permission("*", "*", "read"));
        userPermissions.add(new Permission("database", "user_*", "write"));
        userPermissions.add(new Permission("table", "user_*", "write"));
        userPermissions.add(new Permission("query", "*", "execute"));
        rolePermissions.put(UserRole.USER, userPermissions);
        
        // Read-only role has read access to everything
        Set<Permission> readOnlyPermissions = new HashSet<>();
        readOnlyPermissions.add(new Permission("*", "*", "read"));
        rolePermissions.put(UserRole.READ_ONLY, readOnlyPermissions);
        
        LOGGER.info("Default permissions initialized");
    }
    
    /**
     * Checks if a user has a specific permission.
     * 
     * @param username The username
     * @param role The user's role
     * @param permission The permission to check
     * @return true if the user has the permission, false otherwise
     */
    public boolean hasPermission(String username, UserRole role, Permission permission) {
        // Check role permissions
        Set<Permission> permissions = rolePermissions.get(role);
        if (permissions != null) {
            for (Permission p : permissions) {
                if (p.implies(permission)) {
                    return true;
                }
            }
        }
        
        // Check user-specific permissions
        Set<Permission> userSpecificPermissions = userPermissions.get(username);
        if (userSpecificPermissions != null) {
            for (Permission p : userSpecificPermissions) {
                if (p.implies(permission)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Checks if a user has a specific permission.
     * 
     * @param username The username
     * @param role The user's role
     * @param resourceType The resource type
     * @param resourceName The resource name
     * @param action The action
     * @return true if the user has the permission, false otherwise
     */
    public boolean hasPermission(String username, UserRole role, String resourceType, String resourceName, String action) {
        Permission permission = new Permission(resourceType, resourceName, action);
        return hasPermission(username, role, permission);
    }
    
    /**
     * Adds a permission to a role.
     * 
     * @param role The role
     * @param permission The permission to add
     */
    public void addRolePermission(UserRole role, Permission permission) {
        rolePermissions.computeIfAbsent(role, k -> new HashSet<>()).add(permission);
        LOGGER.info("Added permission " + permission + " to role " + role);
    }
    
    /**
     * Removes a permission from a role.
     * 
     * @param role The role
     * @param permission The permission to remove
     * @return true if the permission was removed, false if it wasn't found
     */
    public boolean removeRolePermission(UserRole role, Permission permission) {
        Set<Permission> permissions = rolePermissions.get(role);
        if (permissions != null) {
            boolean removed = permissions.remove(permission);
            if (removed) {
                LOGGER.info("Removed permission " + permission + " from role " + role);
            }
            return removed;
        }
        return false;
    }
    
    /**
     * Adds a permission to a user.
     * 
     * @param username The username
     * @param permission The permission to add
     */
    public void addUserPermission(String username, Permission permission) {
        userPermissions.computeIfAbsent(username, k -> new HashSet<>()).add(permission);
        LOGGER.info("Added permission " + permission + " to user " + username);
    }
    
    /**
     * Removes a permission from a user.
     * 
     * @param username The username
     * @param permission The permission to remove
     * @return true if the permission was removed, false if it wasn't found
     */
    public boolean removeUserPermission(String username, Permission permission) {
        Set<Permission> permissions = userPermissions.get(username);
        if (permissions != null) {
            boolean removed = permissions.remove(permission);
            if (removed) {
                LOGGER.info("Removed permission " + permission + " from user " + username);
            }
            return removed;
        }
        return false;
    }
    
    /**
     * Gets all permissions for a role.
     * 
     * @param role The role
     * @return The permissions for the role, or an empty set if the role has no permissions
     */
    public Set<Permission> getRolePermissions(UserRole role) {
        Set<Permission> permissions = rolePermissions.get(role);
        return permissions != null ? new HashSet<>(permissions) : new HashSet<>();
    }
    
    /**
     * Gets all permissions for a user.
     * 
     * @param username The username
     * @return The permissions for the user, or an empty set if the user has no permissions
     */
    public Set<Permission> getUserPermissions(String username) {
        Set<Permission> permissions = userPermissions.get(username);
        return permissions != null ? new HashSet<>(permissions) : new HashSet<>();
    }
    
    /**
     * Gets all effective permissions for a user (role permissions + user permissions).
     * 
     * @param username The username
     * @param role The user's role
     * @return The effective permissions for the user
     */
    public Set<Permission> getEffectivePermissions(String username, UserRole role) {
        Set<Permission> effectivePermissions = new HashSet<>();
        
        // Add role permissions
        Set<Permission> permissions = rolePermissions.get(role);
        if (permissions != null) {
            effectivePermissions.addAll(permissions);
        }
        
        // Add user-specific permissions
        Set<Permission> userSpecificPermissions = userPermissions.get(username);
        if (userSpecificPermissions != null) {
            effectivePermissions.addAll(userSpecificPermissions);
        }
        
        return effectivePermissions;
    }
    
    /**
     * Clears all permissions for a user.
     * 
     * @param username The username
     */
    public void clearUserPermissions(String username) {
        userPermissions.remove(username);
        LOGGER.info("Cleared all permissions for user " + username);
    }
}