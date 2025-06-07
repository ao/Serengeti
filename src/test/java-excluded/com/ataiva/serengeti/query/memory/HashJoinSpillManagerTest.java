package com.ataiva.serengeti.query.memory;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ataiva.serengeti.performance.PerformanceDataCollector;
import com.ataiva.serengeti.performance.PerformanceMetric;

/**
 * Test class for HashJoinSpillManager
 */
public class HashJoinSpillManagerTest {
    
    private static final String TEST_QUERY_ID = "test-query";
    private static final String TEST_OPERATION_ID = "test-operation";
    
    private HashJoinSpillManager spillManager;
    private PerformanceDataCollector mockCollector;
    private List<Map<Object, List<Object[]>>> partitions;
    private Path testSpillDir;
    
    @Before
    public void setUp() throws IOException {
        // Create a test spill directory
        testSpillDir = Files.createTempDirectory("spill-test");
        SpillManager.setDefaultSpillDirectory(testSpillDir.toString());
        
        // Create mock performance collector
        mockCollector = mock(PerformanceDataCollector.class);
        
        // Create test partitions
        partitions = new ArrayList<>();
        Map<Object, List<Object[]>> partition = new HashMap<>();
        
        // Add some test data
        List<Object[]> rows1 = new ArrayList<>();
        rows1.add(new Object[] { 1, "test1" });
        rows1.add(new Object[] { 2, "test2" });
        partition.put(1, rows1);
        
        List<Object[]> rows2 = new ArrayList<>();
        rows2.add(new Object[] { 3, "test3" });
        rows2.add(new Object[] { 4, "test4" });
        partition.put(2, rows2);
        
        partitions.add(partition);
        
        // Create the spill manager
        spillManager = new HashJoinSpillManager(TEST_QUERY_ID, TEST_OPERATION_ID, partitions, mockCollector);
    }
    
    @After
    public void tearDown() throws IOException {
        // Clean up spill files
        spillManager.cleanup();
        
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
    public void testSpillToDisk() {
        // Spill to disk
        boolean result = spillManager.spillToDisk();
        
        // Verify result
        assertTrue("Spill to disk should succeed", result);
        assertEquals("Spill count should be 1", 1, spillManager.getSpillCount());
        assertTrue("Bytes spilled should be greater than 0", spillManager.getBytesSpilled() > 0);
        assertEquals("Spill files should contain 1 file", 1, spillManager.getSpillFiles().size());
        
        // Verify performance metrics were recorded
        verify(mockCollector, times(1)).recordMetric(any(PerformanceMetric.class));
        
        // Verify partition was cleared
        assertTrue("Partition should be empty", partitions.get(0).isEmpty());
    }
    
    @Test
    public void testReadFromDisk() {
        // Spill to disk first
        spillManager.spillToDisk();
        
        // Read from disk
        boolean result = spillManager.readFromDisk();
        
        // Verify result
        assertTrue("Read from disk should succeed", result);
        assertEquals("Spill files should be empty after reading", 0, spillManager.getSpillFiles().size());
        
        // Verify performance metrics were recorded
        verify(mockCollector, times(2)).recordMetric(any(PerformanceMetric.class));
        
        // Verify partition was restored
        assertFalse("Partition should not be empty", partitions.get(0).isEmpty());
        assertEquals("Partition should have 2 keys", 2, partitions.get(0).size());
        assertTrue("Partition should contain key 1", partitions.get(0).containsKey(1));
        assertTrue("Partition should contain key 2", partitions.get(0).containsKey(2));
    }
    
    @Test
    public void testAllPartitionsSpilled() {
        // Initially no partitions are spilled
        assertFalse("Initially no partitions should be spilled", spillManager.allPartitionsSpilled());
        
        // Spill to disk
        spillManager.spillToDisk();
        
        // All partitions should be spilled
        assertTrue("All partitions should be spilled", spillManager.allPartitionsSpilled());
    }
    
    @Test
    public void testCleanup() {
        // Spill to disk
        spillManager.spillToDisk();
        
        // Verify spill files exist
        assertEquals("Spill files should contain 1 file", 1, spillManager.getSpillFiles().size());
        
        // Clean up
        spillManager.cleanup();
        
        // Verify spill files were deleted
        assertEquals("Spill files should be empty after cleanup", 0, spillManager.getSpillFiles().size());
    }
    
    @Test
    public void testCreateForTesting() {
        // Create a spill manager for testing
        HashJoinSpillManager testManager = HashJoinSpillManager.createForTesting("test", "test");
        
        // Verify it was created correctly
        assertNotNull("Test manager should not be null", testManager);
        assertEquals("Test manager should have 1 partition", 1, testManager.getPartitionCount());
    }
    
    @Test
    public void testMultipleSpills() {
        // Add another partition
        Map<Object, List<Object[]>> partition2 = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] { 5, "test5" });
        partition2.put(3, rows);
        partitions.add(partition2);
        
        // Spill first partition
        boolean result1 = spillManager.spillToDisk();
        assertTrue("First spill should succeed", result1);
        assertEquals("Spill count should be 1", 1, spillManager.getSpillCount());
        
        // Spill second partition
        boolean result2 = spillManager.spillToDisk();
        assertTrue("Second spill should succeed", result2);
        assertEquals("Spill count should be 2", 2, spillManager.getSpillCount());
        assertEquals("Spill files should contain 2 files", 2, spillManager.getSpillFiles().size());
        
        // Read first partition
        boolean readResult1 = spillManager.readFromDisk();
        assertTrue("First read should succeed", readResult1);
        assertEquals("Spill files should contain 1 file after first read", 1, spillManager.getSpillFiles().size());
        
        // Read second partition
        boolean readResult2 = spillManager.readFromDisk();
        assertTrue("Second read should succeed", readResult2);
        assertEquals("Spill files should be empty after second read", 0, spillManager.getSpillFiles().size());
    }
}