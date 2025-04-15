package com.ataiva.serengeti.utils;

import com.ataiva.serengeti.storage.lsm.LSMStorageEngine;
import com.ataiva.serengeti.storage.lsm.MemTable;
import com.ataiva.serengeti.storage.lsm.SSTable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Lightweight base class for fast LSM storage engine tests.
 * Provides minimal setup for LSM component tests without initializing the entire system.
 */
public class LSMFastTestBase extends LightweightTestBase {
    
    protected Path tempDir;
    protected LSMStorageEngine storageEngine;
    
    /**
     * Initializes only the LSM components for testing.
     * 
     * @throws Exception If an error occurs during initialization
     */
    @Override
    protected void initializeComponents() throws Exception {
        // Create a temporary directory for LSM files
        tempDir = Files.createTempDirectory("lsm_fast_test_");
        
        // Initialize the LSM storage engine with the temporary directory
        storageEngine = new LSMStorageEngine(tempDir, 1024 * 1024, 2); // 1MB memtable size, max 2 immutable memtables
    }
    
    /**
     * Creates a new MemTable for testing.
     * 
     * @param maxSizeInBytes Maximum size in bytes before flushing to disk
     * @return A new MemTable instance
     */
    protected MemTable createMemTable(long maxSizeInBytes) {
        return new MemTable(maxSizeInBytes);
    }
    
    /**
     * Creates a test key as a byte array.
     * 
     * @param keyName The key name
     * @return The key as a byte array
     */
    protected byte[] createTestKey(String keyName) {
        return keyName.getBytes();
    }
    
    /**
     * Creates a test value as a byte array.
     * 
     * @param value The value
     * @return The value as a byte array
     */
    protected byte[] createTestValue(String value) {
        return value.getBytes();
    }
}