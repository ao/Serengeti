package ms.ao.serengeti.storage.lsm;

import ms.ao.serengeti.utils.LSMFastTestBase;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.util.NavigableMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fast tests for the MemTable class.
 * These tests focus on core functionality and run quickly.
 */
@DisplayName("MemTable Fast Tests")
@Tag("fast")
public class MemTableFastTest extends LSMFastTestBase {
    
    private MemTable memTable;
    private static final long MAX_SIZE = 1024; // 1KB for fast tests
    
    @BeforeEach
    public void setUpMemTable() {
        memTable = createMemTable(MAX_SIZE);
    }
    
    @Test
    @DisplayName("Put and get operations work correctly")
    void testPutAndGet() {
        // Create test data
        byte[] key1 = createTestKey("key1");
        byte[] value1 = createTestValue("value1");
        byte[] key2 = createTestKey("key2");
        byte[] value2 = createTestValue("value2");
        
        // Put data
        boolean shouldFlush1 = memTable.put(key1, value1);
        boolean shouldFlush2 = memTable.put(key2, value2);
        
        // Verify no flush needed yet
        assertFalse(shouldFlush1);
        assertFalse(shouldFlush2);
        
        // Get data
        byte[] retrievedValue1 = memTable.get(key1);
        byte[] retrievedValue2 = memTable.get(key2);
        
        // Verify retrieved data
        assertArrayEquals(value1, retrievedValue1);
        assertArrayEquals(value2, retrievedValue2);
    }
    
    @Test
    @DisplayName("Size tracking works correctly")
    void testSizeTracking() {
        // Create test data
        byte[] key = createTestKey("key");
        byte[] value = createTestValue("value");
        
        // Calculate expected size
        long expectedSize = key.length + value.length;
        
        // Put data
        memTable.put(key, value);
        
        // Verify size
        assertEquals(expectedSize, memTable.getSizeInBytes());
        assertEquals(1, memTable.size());
    }
    
    @Test
    @DisplayName("Updating existing key updates size correctly")
    void testUpdateExistingKey() {
        // Create test data
        byte[] key = createTestKey("key");
        byte[] value1 = createTestValue("value1");
        byte[] value2 = createTestValue("value2-longer");
        
        // Put initial data
        memTable.put(key, value1);
        long initialSize = memTable.getSizeInBytes();
        
        // Update with longer value
        memTable.put(key, value2);
        
        // Verify size increased by the difference
        long expectedSize = initialSize + (value2.length - value1.length);
        assertEquals(expectedSize, memTable.getSizeInBytes());
        assertEquals(1, memTable.size());
        
        // Verify the value was updated
        assertArrayEquals(value2, memTable.get(key));
    }
    
    @Test
    @DisplayName("Delete operation works correctly")
    void testDelete() {
        // Create test data
        byte[] key = createTestKey("key");
        byte[] value = createTestValue("value");
        
        // Put data
        memTable.put(key, value);
        
        // Verify it exists
        assertTrue(memTable.containsKey(key));
        
        // Delete it
        memTable.delete(key);
        
        // Verify it's marked as deleted (null value)
        assertNull(memTable.get(key));
        
        // But the key still exists in the map
        assertTrue(memTable.containsKey(key));
    }
    
    @Test
    @DisplayName("Flush threshold works correctly")
    void testFlushThreshold() {
        // Create a small MemTable
        MemTable smallMemTable = createMemTable(20);
        
        // Create test data that exceeds the threshold
        byte[] key = createTestKey("key");
        byte[] value = createTestValue("value-that-exceeds-threshold");
        
        // Put data and check if flush is needed
        boolean shouldFlush = smallMemTable.put(key, value);
        
        // Verify flush is needed
        assertTrue(shouldFlush);
    }
    
    @Test
    @DisplayName("Snapshot creates a copy of the data")
    void testSnapshot() {
        // Create test data
        byte[] key1 = createTestKey("key1");
        byte[] value1 = createTestValue("value1");
        byte[] key2 = createTestKey("key2");
        byte[] value2 = createTestValue("value2");
        
        // Put data
        memTable.put(key1, value1);
        memTable.put(key2, value2);
        
        // Get snapshot
        NavigableMap<byte[], byte[]> snapshot = memTable.getSnapshot();
        
        // Verify snapshot contains the data
        assertEquals(2, snapshot.size());
        
        // Modify the original MemTable
        memTable.put(key1, createTestValue("modified"));
        
        // Verify snapshot is not affected
        assertArrayEquals(value1, snapshot.get(key1));
    }
}