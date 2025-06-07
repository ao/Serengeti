package com.ataiva.serengeti.storage;

import java.util.logging.Logger;

/**
 * Factory class for creating Storage instances.
 * This class provides methods to create different implementations of the IStorage interface.
 */
public class StorageFactory {
    private static final Logger LOGGER = Logger.getLogger(StorageFactory.class.getName());
    
    /**
     * Storage implementation types.
     */
    public enum StorageType {
        MOCK,   // Mock implementation for testing
        REAL,   // Real implementation for production
        LSM     // Log-Structured Merge Tree implementation
    }
    
    /**
     * Create an IStorage instance of the specified type.
     *
     * @param type The type of Storage to create
     * @return An IStorage instance
     */
    public static IStorage createStorage(StorageType type) {
        switch (type) {
            case MOCK:
                LOGGER.info("Creating mock storage implementation");
                return new Storage(); // Using Storage as a temporary mock implementation
            case REAL:
                LOGGER.info("Creating real storage implementation");
                return new Storage();
            case LSM:
                LOGGER.info("Creating LSM storage implementation");
                return new StorageImpl();
            default:
                LOGGER.warning("Unknown storage type, defaulting to real implementation");
                return new Storage();
        }
    }
    
    /**
     * Create an IStorage instance with custom configuration.
     *
     * @param type The type of Storage to create
     * @param cacheEnabled Whether to enable data caching
     * @param maxCacheSize Maximum number of items in the cache
     * @return An IStorage instance
     */
    public static IStorage createStorage(StorageType type, boolean cacheEnabled, int maxCacheSize) {
        switch (type) {
            case MOCK:
                LOGGER.info("Creating mock storage implementation");
                return new Storage(); // Using Storage as a temporary mock implementation
            case REAL:
                LOGGER.info("Creating real storage implementation");
                return new Storage();
            case LSM:
                LOGGER.info("Creating LSM storage implementation with custom configuration");
                return new StorageImpl(cacheEnabled, maxCacheSize, 10); // Default compaction threshold of 10
            default:
                LOGGER.warning("Unknown storage type, defaulting to real implementation");
                return new Storage();
        }
    }
}