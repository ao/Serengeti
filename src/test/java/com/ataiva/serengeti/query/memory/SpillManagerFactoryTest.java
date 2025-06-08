package com.ataiva.serengeti.query.memory;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ataiva.serengeti.performance.PerformanceProfiler;

/**
 * Test class for SpillManagerFactory
 */
public class SpillManagerFactoryTest {
    
    private static final String TEST_QUERY_ID = "test-query";
    private static final String TEST_OPERATION_ID = "test-operation";
    
    private SpillManagerFactory factory;
    private PerformanceProfiler mockCollector;
    private Path testSpillDir;
    
    @Before
    public void setUp() throws IOException {
        // Create a test spill directory
        testSpillDir = Files.createTempDirectory("spill-factory-test");
        SpillManager.setDefaultSpillDirectory(testSpillDir.toString());
        
        // Create mock performance profiler
        mockCollector = mock(PerformanceProfiler.class);
        
        // Create the factory
        factory = new SpillManagerFactory(mockCollector);
    }
    
    @After
    public void tearDown() throws IOException {
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
    public void testCreateHashJoinSpillManager() {
        // Create a HashJoinSpillManager
        HashJoinSpillManager manager = factory.createHashJoinSpillManager(TEST_QUERY_ID, TEST_OPERATION_ID);
        
        // Verify it was created correctly
        assertNotNull("Manager should not be null", manager);
        assertEquals("Manager should have 1 partition", 1, manager.getPartitionCount());
        assertEquals("Manager should have query ID set", TEST_QUERY_ID, manager.queryId);
        assertEquals("Manager should have operation ID set", TEST_OPERATION_ID, manager.operationId);
    }
    
    @Test
    public void testCreateHashJoinSpillManagerWithPartitions() {
        // Create test partitions
        List<Map<Object, List<Object[]>>> partitions = new ArrayList<>();
        Map<Object, List<Object[]>> partition = new HashMap<>();
        
        // Add some test data
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] { 1, "test1" });
        partition.put(1, rows);
        
        partitions.add(partition);
        
        // Create a HashJoinSpillManager with partitions
        HashJoinSpillManager manager = factory.createHashJoinSpillManager(
            TEST_QUERY_ID, TEST_OPERATION_ID, partitions);
        
        // Verify it was created correctly
        assertNotNull("Manager should not be null", manager);
        assertEquals("Manager should have 1 partition", 1, manager.getPartitionCount());
        assertEquals("Manager should have query ID set", TEST_QUERY_ID, manager.queryId);
        assertEquals("Manager should have operation ID set", TEST_OPERATION_ID, manager.operationId);
    }
    
    @Test
    public void testCreateSortSpillManager() {
        // Create a comparator
        Comparator<Object[]> comparator = (a, b) -> {
            Integer val1 = (Integer) a[0];
            Integer val2 = (Integer) b[0];
            return val1.compareTo(val2);
        };
        
        // Create a SortSpillManager
        SortSpillManager manager = factory.createSortSpillManager(
            TEST_QUERY_ID, TEST_OPERATION_ID, comparator);
        
        // Verify it was created correctly
        assertNotNull("Manager should not be null", manager);
        assertEquals("Manager should have 1 chunk", 1, manager.getChunkCount());
        assertEquals("Manager should have query ID set", TEST_QUERY_ID, manager.queryId);
        assertEquals("Manager should have operation ID set", TEST_OPERATION_ID, manager.operationId);
    }
    
    @Test
    public void testCreateSortSpillManagerWithChunks() {
        // Create test chunks
        List<List<Object[]>> chunks = new ArrayList<>();
        List<Object[]> chunk = new ArrayList<>();
        
        // Add some test data
        chunk.add(new Object[] { 1, "test1" });
        chunks.add(chunk);
        
        // Create a comparator
        Comparator<Object[]> comparator = (a, b) -> {
            Integer val1 = (Integer) a[0];
            Integer val2 = (Integer) b[0];
            return val1.compareTo(val2);
        };
        
        // Create a SortSpillManager with chunks
        SortSpillManager manager = factory.createSortSpillManager(
            TEST_QUERY_ID, TEST_OPERATION_ID, chunks, comparator, 100);
        
        // Verify it was created correctly
        assertNotNull("Manager should not be null", manager);
        assertEquals("Manager should have 1 chunk", 1, manager.getChunkCount());
        assertEquals("Manager should have query ID set", TEST_QUERY_ID, manager.queryId);
        assertEquals("Manager should have operation ID set", TEST_OPERATION_ID, manager.operationId);
    }
    
    @Test
    public void testFactoryWithCustomSpillDirectory() throws IOException {
        // Create a custom spill directory
        Path customSpillDir = Files.createTempDirectory("custom-spill-dir");
        
        // Create a factory with custom spill directory
        SpillManagerFactory customFactory = new SpillManagerFactory(mockCollector, customSpillDir);
        
        // Create a HashJoinSpillManager
        HashJoinSpillManager manager = customFactory.createHashJoinSpillManager(TEST_QUERY_ID, TEST_OPERATION_ID);
        
        // Verify it was created correctly
        assertNotNull("Manager should not be null", manager);
        
        // Clean up
        Files.walk(customSpillDir)
            .sorted((a, b) -> b.compareTo(a))
            .forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // Ignore
                }
            });
    }
    
    @Test
    public void testGenerateOperationId() {
        // Generate an operation ID
        String operationId1 = SpillManagerFactory.generateOperationId();
        
        // Verify it's not null or empty
        assertNotNull("Operation ID should not be null", operationId1);
        assertFalse("Operation ID should not be empty", operationId1.isEmpty());
        
        // Generate another operation ID
        String operationId2 = SpillManagerFactory.generateOperationId();
        
        // Verify they're different
        assertNotEquals("Operation IDs should be different", operationId1, operationId2);
    }
    
    @Test
    public void testGetDefaultSpillDirectory() {
        // Get the default spill directory
        Path defaultSpillDir = SpillManagerFactory.getDefaultSpillDirectory();
        
        // Verify it's not null
        assertNotNull("Default spill directory should not be null", defaultSpillDir);
    }
    
    @Test
    public void testSetDefaultSpillDirectory() {
        // Set a custom default spill directory
        String customDir = "/tmp/custom-spill-dir";
        SpillManagerFactory.setDefaultSpillDirectory(customDir);
        
        // Verify it was set correctly
        assertEquals("Default spill directory should be set correctly", 
                    customDir, SpillManager.getDefaultSpillDirectory());
        
        // Reset to the test spill directory
        SpillManager.setDefaultSpillDirectory(testSpillDir.toString());
    }
}