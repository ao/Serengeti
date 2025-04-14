package com.ataiva.serengeti.utils;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.mocks.InMemoryStorage;
import com.ataiva.serengeti.storage.Storage;

import java.lang.reflect.Field;

/**
 * Lightweight base class for fast Storage tests.
 * Initializes only the Storage component without the full system.
 */
public class StorageFastTestBase extends LightweightTestBase {
    
    protected Storage storage;
    
    /**
     * Initializes only the Storage component for testing.
     * 
     * @throws Exception If an error occurs during initialization
     */
    @Override
    protected void initializeComponents() throws Exception {
        // Initialize in-memory storage instead of real storage
        storage = new InMemoryStorage();
        
        // Configure storage to use the test data path
        Field field = Globals.class.getDeclaredField("data_path");
        field.setAccessible(true);
        field.set(null, testDataPath);
    }
}