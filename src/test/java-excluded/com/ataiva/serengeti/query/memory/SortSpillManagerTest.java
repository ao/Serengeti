package com.ataiva.serengeti.query.memory;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ataiva.serengeti.performance.PerformanceDataCollector;
import com.ataiva.serengeti.performance.PerformanceMetric;

/**
 * Test class for SortSpillManager
 */
public class SortSpillManagerTest {
    
    private static final String TEST_QUERY_ID = "test-query";
    private static final String TEST_OPERATION_ID = "test-operation";
    private static final int MAX_ROWS_PER_CHUNK = 100;
    
    private SortSpillManager spillManager;
    private PerformanceDataCollector mockCollector;
    private List<List<Object[]>> chunks;
    private Path testSpillDir;
    private Comparator<Object[]> comparator;
    
    @Before
    public void setUp() throws IOException {
        // Create a test spill directory
        testSpillDir = Files.createTempDirectory("spill-test");
        SpillManager.setDefaultSpillDirectory(testSpillDir.toString());
        
        // Create mock performance collector
        mockCollector = mock(PerformanceDataCollector.class);
        
        // Create comparator
        comparator = (a, b) -> {
            Integer val1 = (Integer) a[0];
            Integer val2 = (Integer) b[0];
            return val1.compareTo(val2);
        };
        
        // Create test chunks
        chunks = new ArrayList<>();
        List<Object[]> chunk1 = new ArrayList<>();
        
        // Add some test data in unsorted order
        chunk1.add(new Object[] { 3, "test3" });
        chunk1.add(new Object[] { 1, "test1" });
        chunk1.add(new Object[] { 4, "test4" });
        chunk1.add(new Object[] { 2, "test2" });
        
        chunks.add(chunk1);
        
        // Create the spill manager
        spillManager = new SortSpillManager(
            TEST_QUERY_ID, TEST_OPERATION_ID, chunks, comparator, mockCollector, MAX_ROWS_PER_CHUNK);
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
        
        // Verify chunk was cleared
        assertTrue("Chunk should be empty", chunks.get(0).isEmpty());
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
        
        // Verify chunk was restored and sorted
        assertFalse("Chunk should not be empty", chunks.get(0).isEmpty());
        assertEquals("Chunk should have 4 rows", 4, chunks.get(0).size());
        
        // Verify the chunk is sorted
        for (int i = 1; i < chunks.get(0).size(); i++) {
            Object[] prev = chunks.get(0).get(i - 1);
            Object[] curr = chunks.get(0).get(i);
            assertTrue("Chunk should be sorted", (Integer)prev[0] <= (Integer)curr[0]);
        }
    }
    
    @Test
    public void testAllChunksSpilled() {
        // Initially no chunks are spilled
        assertFalse("Initially no chunks should be spilled", spillManager.allChunksSpilled());
        
        // Spill to disk
        spillManager.spillToDisk();
        
        // All chunks should be spilled
        assertTrue("All chunks should be spilled", spillManager.allChunksSpilled());
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
        SortSpillManager testManager = SortSpillManager.createForTesting("test", "test");
        
        // Verify it was created correctly
        assertNotNull("Test manager should not be null", testManager);
        assertEquals("Test manager should have 1 chunk", 1, testManager.getChunkCount());
    }
    
    @Test
    public void testMultipleSpills() {
        // Add another chunk
        List<Object[]> chunk2 = new ArrayList<>();
        chunk2.add(new Object[] { 5, "test5" });
        chunk2.add(new Object[] { 6, "test6" });
        chunks.add(chunk2);
        
        // Spill first chunk
        boolean result1 = spillManager.spillToDisk();
        assertTrue("First spill should succeed", result1);
        assertEquals("Spill count should be 1", 1, spillManager.getSpillCount());
        
        // Spill second chunk
        boolean result2 = spillManager.spillToDisk();
        assertTrue("Second spill should succeed", result2);
        assertEquals("Spill count should be 2", 2, spillManager.getSpillCount());
        assertEquals("Spill files should contain 2 files", 2, spillManager.getSpillFiles().size());
        
        // Read first chunk
        boolean readResult1 = spillManager.readFromDisk();
        assertTrue("First read should succeed", readResult1);
        assertEquals("Spill files should contain 1 file after first read", 1, spillManager.getSpillFiles().size());
        
        // Read second chunk
        boolean readResult2 = spillManager.readFromDisk();
        assertTrue("Second read should succeed", readResult2);
        assertEquals("Spill files should be empty after second read", 0, spillManager.getSpillFiles().size());
    }
    
    @Test
    public void testMergeChunks() {
        // Create multiple chunks
        chunks.clear();
        
        // First chunk with even numbers
        List<Object[]> chunk1 = new ArrayList<>();
        chunk1.add(new Object[] { 2, "test2" });
        chunk1.add(new Object[] { 4, "test4" });
        chunk1.add(new Object[] { 6, "test6" });
        chunks.add(chunk1);
        
        // Second chunk with odd numbers
        List<Object[]> chunk2 = new ArrayList<>();
        chunk2.add(new Object[] { 1, "test1" });
        chunk2.add(new Object[] { 3, "test3" });
        chunk2.add(new Object[] { 5, "test5" });
        chunks.add(chunk2);
        
        // Spill both chunks
        spillManager.spillToDisk();
        spillManager.spillToDisk();
        
        // Merge chunks
        List<Object[]> merged = spillManager.mergeChunks();
        
        // Verify merged result
        assertNotNull("Merged result should not be null", merged);
        assertEquals("Merged result should have 6 rows", 6, merged.size());
        
        // Verify the merged result is sorted
        for (int i = 1; i < merged.size(); i++) {
            Object[] prev = merged.get(i - 1);
            Object[] curr = merged.get(i);
            assertTrue("Merged result should be sorted", (Integer)prev[0] <= (Integer)curr[0]);
        }
        
        // Verify the first element is 1 and the last is 6
        assertEquals("First element should be 1", 1, merged.get(0)[0]);
        assertEquals("Last element should be 6", 6, merged.get(5)[0]);
    }
}