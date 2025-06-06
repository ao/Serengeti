package com.ataiva.serengeti.storage;

import java.util.logging.Logger;

/**
 * Factory class for creating Storage instances.
 * This class provides methods to create different implementations of the Storage class.
 */
public class StorageFactory {
    private static final Logger LOGGER = Logger.getLogger(StorageFactory.class.getName());
    
    /**
     * Storage implementation types.
     */
    public enum StorageType {
        MOCK,   // Mock implementation for testing
        REAL    // Real implementation for production
    }
    
    /**
     * Create a Storage instance of the specified type.
     * 
     * @param type The type of Storage to create
     * @return A Storage instance
     */
    public static Storage createStorage(StorageType type) {
        switch (type) {
            case MOCK:
                LOGGER.info("Creating mock storage implementation");
                return new MockStorage();
            case REAL:
                LOGGER.info("Creating real storage implementation");
                return new StorageImpl();
            default:
                LOGGER.warning("Unknown storage type, defaulting to real implementation");
                return new StorageImpl();
        }
    }
    
    /**
     * Create a Storage instance with custom configuration.
     * 
     * @param type The type of Storage to create
     * @param cacheEnabled Whether to enable data caching
     * @param maxCacheSize Maximum number of items in the cache
     * @return A Storage instance
     */
    public static Storage createStorage(StorageType type, boolean cacheEnabled, int maxCacheSize) {
        switch (type) {
            case MOCK:
                LOGGER.info("Creating mock storage implementation");
                return new MockStorage();
            case REAL:
                LOGGER.info("Creating real storage implementation with custom configuration");
                return new StorageImpl(cacheEnabled, maxCacheSize);
            default:
                LOGGER.warning("Unknown storage type, defaulting to real implementation");
                return new StorageImpl(cacheEnabled, maxCacheSize);
        }
    }
}