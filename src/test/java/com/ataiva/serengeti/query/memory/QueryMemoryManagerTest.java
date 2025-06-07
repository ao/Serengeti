package com.ataiva.serengeti.query.memory;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.ataiva.serengeti.performance.PerformanceProfiler;

/**
 * Test class for QueryMemoryManager
 */
public class QueryMemoryManagerTest {
    
    private QueryMemoryManager memoryManager;
    private SpillManager mockSpillManager;
    private Path testSpillDir;
    private String queryId;
    private String operationId;
    
    @Before
    public void setUp() throws IOException {
        // Create a test spill directory
        testSpillDir = Files.createTempDirectory("memory-manager-test");
        SpillManager.setDefaultSpillDirectory(testSpillDir.toString());
        
        // Create a new instance of QueryMemoryManager
        memoryManager = QueryMemoryManager.getInstance();
        memoryManager.initialize(1024 * 1024 * 1024); // 1GB
        
        // Create a mock SpillManager
        mockSpillManager = mock(SpillManager.class);
        
        // Create a query context
        queryId = memoryManager.createQueryContext();
        operationId = "test-operation";
        
        // Register the mock SpillManager
        memoryManager.registerSpillManager(queryId, operationId, mockSpillManager);
        
        // Mock PerformanceProfiler
        PerformanceProfiler mockProfiler = mock(PerformanceProfiler.class);
        when(mockProfiler.startTimer(anyString(), anyString())).thenReturn("mock-timer");
        
        // Use reflection to set the mock PerformanceProfiler
        try {
            java.lang.reflect.Field instance = PerformanceProfiler.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, mockProfiler);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    @After
    public void tearDown() throws IOException {
        // Release the query context
        memoryManager.releaseQueryContext(queryId);
        
        // Delete the test spill directory
        Files.walk(testSpillDir)
            .sorted((a, b) -> b.compareTo(a)) // Reverse order to delete files before directories
            .forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // Ignore
                }
            });
    }
    
    @Test
    public void testAllocateMemory() {
        // Allocate memory
        boolean result = memoryManager.allocateMemory(queryId, operationId, 1024 * 1024); // 1MB
        
        // Verify result
        assertTrue("Memory allocation should succeed", result);
        assertEquals("Memory usage should be 1MB", 1024 * 1024, memoryManager.getQueryMemoryUsage(queryId));
    }
    
    @Test
    public void testFreeMemory() {
        // Allocate memory
        memoryManager.allocateMemory(queryId, operationId, 1024 * 1024); // 1MB
        
        // Free memory
        memoryManager.freeMemory(queryId, operationId);
        
        // Verify result
        assertEquals("Memory usage should be 0", 0, memoryManager.getQueryMemoryUsage(queryId));
    }
    
    @Test
    public void testSpillToDisk() {
        // Set up the mock SpillManager
        when(mockSpillManager.spillToDisk()).thenReturn(true);
        
        // Allocate memory
        memoryManager.allocateMemory(queryId, operationId, 1024 * 1024); // 1MB
        
        // Spill to disk
        boolean result = memoryManager.spillToDisk(queryId, operationId);
        
        // Verify result
        assertTrue("Spill to disk should succeed", result);
        assertEquals("Memory usage should be 0 after spill", 0, memoryManager.getQueryMemoryUsage(queryId));
        
        // Verify the SpillManager was called
        verify(mockSpillManager, times(1)).spillToDisk();
    }
    
    @Test
    public void testReadFromDisk() {
        // Set up the mock SpillManager
        when(mockSpillManager.spillToDisk()).thenReturn(true);
        when(mockSpillManager.readFromDisk()).thenReturn(true);
        
        // Allocate memory
        memoryManager.allocateMemory(queryId, operationId, 1024 * 1024); // 1MB
        
        // Spill to disk
        memoryManager.spillToDisk(queryId, operationId);
        
        // Read from disk
        boolean result = memoryManager.readFromDisk(queryId, operationId);
        
        // Verify result
        assertTrue("Read from disk should succeed", result);
        assertEquals("Memory usage should be 1MB after read", 1024 * 1024, memoryManager.getQueryMemoryUsage(queryId));
        
        // Verify the SpillManager was called
        verify(mockSpillManager, times(1)).readFromDisk();
    }
    
    @Test
    public void testAllocateMemoryWithSpill() {
        // Set up the mock SpillManager
        when(mockSpillManager.spillToDisk()).thenReturn(true);
        
        // Set a small memory budget
        memoryManager.setTotalMemoryBudget(10 * 1024 * 1024); // 10MB
        
        // Allocate memory up to the limit
        memoryManager.allocateMemory(queryId, operationId, 5 * 1024 * 1024); // 5MB
        
        // Try to allocate more memory than available
        String operationId2 = "test-operation-2";
        memoryManager.registerSpillManager(queryId, operationId2, mockSpillManager);
        
        // This should trigger a spill
        boolean result = memoryManager.allocateMemory(queryId, operationId2, 6 * 1024 * 1024); // 6MB
        
        // Verify result
        assertTrue("Memory allocation with spill should succeed", result);
        
        // Verify the SpillManager was called
        verify(mockSpillManager, times(1)).spillToDisk();
    }
    
    @Test
    public void testMemoryStats() {
        // Get memory stats
        Map<String, Object> stats = memoryManager.getMemoryStats();
        
        // Verify stats
        assertNotNull("Stats should not be null", stats);
        assertEquals("Total memory budget should be 1GB", 1024 * 1024 * 1024L, stats.get("totalMemoryBudget"));
        assertEquals("Query memory fraction should be 0.7", 0.7, stats.get("queryMemoryFraction"));
        assertEquals("Reserved system memory should be 256MB", 256 * 1024 * 1024L, stats.get("reservedSystemMemory"));
    }
    
    @Test
    public void testRegisterSpillManager() {
        // Register a new spill manager
        String newOperationId = "new-operation";
        SpillManager newSpillManager = mock(SpillManager.class);
        memoryManager.registerSpillManager(queryId, newOperationId, newSpillManager);
        
        // Verify it works by spilling to disk
        when(newSpillManager.spillToDisk()).thenReturn(true);
        
        // Allocate memory
        memoryManager.allocateMemory(queryId, newOperationId, 1024 * 1024); // 1MB
        
        // Spill to disk
        boolean result = memoryManager.spillToDisk(queryId, newOperationId);
        
        // Verify result
        assertTrue("Spill to disk should succeed", result);
        
        // Verify the new SpillManager was called
        verify(newSpillManager, times(1)).spillToDisk();
    }
    
    @Test
    public void testReleaseQueryContext() {
        // Allocate memory
        memoryManager.allocateMemory(queryId, operationId, 1024 * 1024); // 1MB
        
        // Release the query context
        memoryManager.releaseQueryContext(queryId);
        
        // Verify memory usage is -1 (query not found)
        assertEquals("Memory usage should be -1 after release", -1, memoryManager.getQueryMemoryUsage(queryId));
        
        // Try to allocate memory again
        boolean result = memoryManager.allocateMemory(queryId, operationId, 1024 * 1024);
        
        // Verify result
        assertFalse("Memory allocation should fail after release", result);
    }
    
    @Test
    public void testBufferPool() {
        // Get the buffer pool
        BufferPool bufferPool = memoryManager.getBufferPool();
        
        // Verify it's not null
        assertNotNull("Buffer pool should not be null", bufferPool);
        
        // Verify it has the correct size
        assertEquals("Buffer pool should have the correct size", 
                    memoryManager.getQueryPoolMemory(), bufferPool.getTotalSize());
    }
}