package com.ataiva.serengeti.unit.storage;

import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageFactory;
import com.ataiva.serengeti.storage.StorageImpl;
import com.ataiva.serengeti.storage.StorageResponseObject;
import com.ataiva.serengeti.storage.lsm.LSMStorageEngine;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the StorageImpl class.
 */
@RunWith(MockitoJUnitRunner.class)
public class StorageImplTest {

    private StorageImpl storageImpl;
    
    @Mock
    private LSMStorageEngine mockLSMEngine;
    
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        
        // Create a StorageImpl instance with test configuration
        storageImpl = (StorageImpl) StorageFactory.createStorage(StorageFactory.StorageType.REAL, true, 100);
        
        // Mock the getLSMEngine method to return our mock engine
        StorageImpl spyStorage = spy(storageImpl);
        doReturn(mockLSMEngine).when(spyStorage).getLSMEngine(anyString());
        
        // Use the spy for testing
        storageImpl = spyStorage;
    }
    
    @Test
    public void testInitialization() {
        // Verify that the storage is initialized correctly
        assertNotNull("Storage should not be null", storageImpl);
        assertTrue("Storage should be healthy initially", storageImpl.isHealthy());
        
        // Verify error metrics are empty initially
        Map<String, Integer> errorMetrics = storageImpl.getErrorMetrics();
        assertTrue("Error metrics should be empty initially", errorMetrics.isEmpty());
    }
    
    @Test
    public void testErrorMetricsTracking() {
        // Use reflection to access the private recordError method
        try {
            java.lang.reflect.Method recordErrorMethod = StorageImpl.class.getDeclaredMethod("recordError", String.class);
            recordErrorMethod.setAccessible(true);
            
            // Record some errors
            recordErrorMethod.invoke(storageImpl, "TestError");
            recordErrorMethod.invoke(storageImpl, "TestError");
            recordErrorMethod.invoke(storageImpl, "AnotherError");
            
            // Verify error metrics
            Map<String, Integer> errorMetrics = storageImpl.getErrorMetrics();
            assertEquals("TestError count should be 2", 2, (int) errorMetrics.get("TestError"));
            assertEquals("AnotherError count should be 1", 1, (int) errorMetrics.get("AnotherError"));
            
            // Reset error metrics
            storageImpl.resetErrorMetrics();
            
            // Verify error metrics are empty after reset
            errorMetrics = storageImpl.getErrorMetrics();
            assertTrue("Error metrics should be empty after reset", errorMetrics.isEmpty());
            assertTrue("Storage should be healthy after reset", storageImpl.isHealthy());
        } catch (Exception e) {
            fail("Exception during test: " + e.getMessage());
        }
    }
    
    @Test
    public void testCacheInvalidation() {
        // Use reflection to access the private invalidateCache methods
        try {
            java.lang.reflect.Method invalidateCacheDbMethod = StorageImpl.class.getDeclaredMethod("invalidateCache", String.class);
            invalidateCacheDbMethod.setAccessible(true);
            
            java.lang.reflect.Method invalidateCacheTableMethod = StorageImpl.class.getDeclaredMethod("invalidateCache", String.class, String.class);
            invalidateCacheTableMethod.setAccessible(true);
            
            // Invoke the methods
            invalidateCacheDbMethod.invoke(storageImpl, "testDb");
            invalidateCacheTableMethod.invoke(storageImpl, "testDb", "testTable");
            
            // No assertions needed, just verify that the methods don't throw exceptions
        } catch (Exception e) {
            fail("Exception during test: " + e.getMessage());
        }
    }
    
    @Test
    public void testGetLSMEngine() {
        // Test getting an LSM engine
        LSMStorageEngine engine = storageImpl.getLSMEngine("testDb");
        
        // Verify that the mock engine is returned
        assertNotNull("LSM engine should not be null", engine);
        assertSame("LSM engine should be the mock engine", mockLSMEngine, engine);
    }
    
    @Test
    public void testShutdown() {
        // Test shutdown
        storageImpl.shutdown();
        
        // No assertions needed, just verify that the method doesn't throw exceptions
    }
    
    @Test
    public void testFactoryCreation() {
        // Test creating a storage instance with the factory
        Storage storage = StorageFactory.createStorage(StorageFactory.StorageType.REAL);
        
        // Verify that the storage is created correctly
        assertNotNull("Storage should not be null", storage);
        assertTrue("Storage should be an instance of StorageImpl", storage instanceof StorageImpl);
        
        // Test creating a storage instance with custom configuration
        Storage customStorage = StorageFactory.createStorage(StorageFactory.StorageType.REAL, false, 200);
        
        // Verify that the storage is created correctly
        assertNotNull("Custom storage should not be null", customStorage);
        assertTrue("Custom storage should be an instance of StorageImpl", customStorage instanceof StorageImpl);
    }
}