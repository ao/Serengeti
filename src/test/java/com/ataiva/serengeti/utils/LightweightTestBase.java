package com.ataiva.serengeti.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Lightweight base class for fast tests.
 * Provides minimal setup for unit tests without initializing the entire system.
 */
public abstract class LightweightTestBase {
    
    protected String testDataPath;
    
    /**
     * Sets up the test environment before each test.
     * Creates a temporary data directory for tests.
     * 
     * @throws Exception If an error occurs during setup
     */
    @BeforeEach
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        
        // Create a temporary data directory for tests
        Path tempDir = Files.createTempDirectory("serengeti_fast_test_");
        testDataPath = tempDir.toString() + "/";
        
        // Initialize only what's needed for the specific test
        initializeComponents();
    }
    
    /**
     * Cleans up the test environment after each test.
     * Deletes the temporary data directory.
     * 
     * @throws Exception If an error occurs during cleanup
     */
    @AfterEach
    public void tearDown() throws Exception {
        // Delete the temporary data directory
        deleteDirectory(new File(testDataPath));
    }
    
    /**
     * Initializes the components needed for the test.
     * To be implemented by subclasses to initialize only what they need.
     * 
     * @throws Exception If an error occurs during initialization
     */
    protected abstract void initializeComponents() throws Exception;
    
    /**
     * Recursively deletes a directory and all its contents.
     * 
     * @param directory The directory to delete
     * @return true if successful, false otherwise
     */
    protected boolean deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
        }
        return directory.delete();
    }
    
    /**
     * Generates a random database name for testing.
     * 
     * @return A random database name
     */
    protected String generateRandomDatabaseName() {
        return "test_db_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    /**
     * Generates a random table name for testing.
     * 
     * @return A random table name
     */
    protected String generateRandomTableName() {
        return "test_table_" + UUID.randomUUID().toString().substring(0, 8);
    }
}