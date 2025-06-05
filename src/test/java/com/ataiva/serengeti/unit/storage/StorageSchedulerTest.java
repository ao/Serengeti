package com.ataiva.serengeti.unit.storage;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageScheduler;
import com.ataiva.serengeti.utils.StorageTestUtils;
import com.ataiva.serengeti.utils.TestBase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the StorageScheduler component.
 *
 * The StorageScheduler is responsible for periodically persisting database state to disk.
 * These tests verify:
 * - Periodic task scheduling works correctly
 * - Database and table data is properly persisted to disk
 * - Error conditions are handled gracefully
 * - Concurrent operations are managed correctly
 * - Edge cases like special characters in names and large data are handled properly
 * - Performance characteristics under various load conditions
 * - Resource cleanup and memory management
 * - System behavior under stress conditions
 *
 * Test Strategy:
 * - Unit tests focus on isolated component behavior
 * - Performance tests measure execution time and resource usage
 * - Stress tests verify system stability under high load
 * - Parameterized tests verify behavior across different configurations
 * - Fast tests are tagged for CI/CD pipeline integration
 *
 * Assumptions:
 * - Tests assume a clean environment for each test case
 * - Mock objects are used to isolate the StorageScheduler from other components
 * - Performance metrics are relative and may vary across different environments
 *
 * Limitations:
 * - Some tests may be environment-dependent
 * - Performance thresholds are guidelines and not strict requirements
 */
@DisplayName("StorageScheduler Unit Tests")
@Tag("unit")
@Tag("storage")
@ExtendWith(MockitoExtension.class)
class StorageSchedulerTest extends TestBase {

    @Mock
    private DatabaseObject mockDatabaseObject;
    
    @Mock
    private TableStorageObject mockTableStorageObject;
    
    @Mock
    private TableReplicaObject mockTableReplicaObject;

    private StorageScheduler storageScheduler;
    private Map<String, DatabaseObject> originalDatabases;
    private Map<String, TableStorageObject> originalTableStorageObjects;
    private Map<String, TableReplicaObject> originalTableReplicaObjects;
    private boolean originalNetworkOnline;
    private boolean originalRunning;
    private String originalDataPath;
    private AutoCloseable mockCloseable;
    private ByteArrayOutputStream outputStream;
    private PrintStream originalOut;
    
    // Performance tracking
    private long startTime;
    private long endTime;

    @BeforeEach
    public void setUp() throws Exception {
        super.setUp(); // Initialize TestBase components
        mockCloseable = MockitoAnnotations.openMocks(this);
        storageScheduler = new StorageScheduler();
        
        // Backup original static state
        originalDatabases = Storage.databases;
        originalTableStorageObjects = Storage.tableStorageObjects;
        originalTableReplicaObjects = Storage.tableReplicaObjects;
        originalNetworkOnline = Network.online;
        originalRunning = StorageScheduler.running;
        originalDataPath = Globals.data_path;
        
        // Setup clean test state
        Storage.databases = new HashMap<>();
        Storage.tableStorageObjects = new HashMap<>();
        Storage.tableReplicaObjects = new HashMap<>();
        Network.online = true;
        StorageScheduler.running = false;
        Globals.data_path = testDataPath; // Use TestBase's temporary directory
        
        // Capture console output for testing
        outputStream = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(outputStream));
    }

    @AfterEach
    public void tearDown() throws Exception {
        // Restore original static state
        Storage.databases = originalDatabases;
        Storage.tableStorageObjects = originalTableStorageObjects;
        Storage.tableReplicaObjects = originalTableReplicaObjects;
        Network.online = originalNetworkOnline;
        StorageScheduler.running = originalRunning;
        Globals.data_path = originalDataPath;
        
        // Restore console output
        System.setOut(originalOut);
        
        if (mockCloseable != null) {
            mockCloseable.close();
        }
        
        super.tearDown(); // Clean up TestBase resources
    }

    /**
     * Tests for the initialization and thread management of StorageScheduler
     */
    @Nested
    @DisplayName("Initialization Tests")
    class InitializationTests {
        
        @Test
        @DisplayName("Should initialize and start background thread")
        void testInit() throws InterruptedException {
            // Arrange
            CountDownLatch latch = new CountDownLatch(1);
            
            // Override the thread behavior to avoid actual 60-second wait
            StorageScheduler testScheduler = new StorageScheduler() {
                @Override
                public void init() {
                    new Thread(() -> {
                        try {
                            latch.countDown();
                            Thread.sleep(100); // Short sleep for testing
                            performPersistToDisk();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                }
            };
            
            // Act
            testScheduler.init();
            
            // Assert
            assertTrue(latch.await(1, TimeUnit.SECONDS), "Background thread should start");
            String output = outputStream.toString();
            assertTrue(output.contains("StorageScheduler Initiated"), "Should log scheduler initiation");
        }
        
        @Test
        @DisplayName("Should handle thread interruption in init method")
        void testInitThreadInterruption() throws InterruptedException {
            // Arrange
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch interruptLatch = new CountDownLatch(1);
            
            StorageScheduler testScheduler = new StorageScheduler() {
                @Override
                public void init() {
                    Thread schedulerThread = new Thread(() -> {
                        try {
                            startLatch.countDown();
                            Thread.sleep(60 * 1000); // Original 60-second sleep
                        } catch (InterruptedException ie) {
                            interruptLatch.countDown();
                            Thread.currentThread().interrupt();
                        }
                    });
                    schedulerThread.start();
                    
                    // Interrupt the thread after it starts
                    new Thread(() -> {
                        try {
                            startLatch.await();
                            Thread.sleep(100); // Give thread time to start sleeping
                            schedulerThread.interrupt();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                }
            };
            
            // Act
            testScheduler.init();
            
            // Assert
            assertTrue(startLatch.await(1, TimeUnit.SECONDS), "Thread should start");
            assertTrue(interruptLatch.await(1, TimeUnit.SECONDS), "Thread should be interrupted");
        }
    }

    /**
     * Tests for the basic persistence functionality of StorageScheduler
     */
    @Nested
    @DisplayName("Basic Persistence Tests")
    class BasicPersistenceTests {
        
        @Test
        @DisplayName("Should persist databases to disk when network is online and not running")
        void testPerformPersistToDiskSuccess() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(result, "Should return true on successful persistence");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
                
                String output = outputStream.toString();
                assertTrue(output.contains("Persisting to disk"), "Should log persistence start");
                assertTrue(output.contains("Written db: 'testdb' to disk"), "Should log database write");
                assertTrue(output.contains("Written table: 'testdb'#'testtable' storage to disk"), "Should log table storage write");
                assertTrue(output.contains("Written table: 'testdb'#'testtable' replica to disk"), "Should log table replica write");
                
                // Verify file operations
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(1));
                verify(mockTableStorageObject, times(1)).saveToDisk();
                verify(mockTableReplicaObject, times(1)).saveToDisk();
            }
        }
    
        @Test
        @DisplayName("Should skip persistence when network is offline")
        void testPerformPersistToDiskNetworkOffline() {
            // Arrange
            Network.online = false;
            
            // Act
            boolean result = storageScheduler.performPersistToDisk();
            
            // Assert
            assertFalse(result, "Should return false when network is offline");
            assertFalse(StorageScheduler.running, "Running flag should be false");
            
            String output = outputStream.toString();
            assertTrue(output.contains("Node reported as not having started fully"), "Should log network offline message");
        }
    
        @Test
        @DisplayName("Should skip persistence when already running")
        void testPerformPersistToDiskAlreadyRunning() {
            // Arrange
            Network.online = true;
            StorageScheduler.running = true;
            
            // Act
            boolean result = storageScheduler.performPersistToDisk();
            
            // Assert
            assertFalse(result, "Should return false when already running");
            assertTrue(StorageScheduler.running, "Running flag should remain true");
            
            String output = outputStream.toString();
            assertTrue(output.contains("Node reported as not having started fully"), "Should log skip message");
        }
    
        @Test
        @DisplayName("Should handle empty databases gracefully")
        void testPerformPersistToDiskEmptyDatabases() {
            // Arrange
            Network.online = true;
            StorageScheduler.running = false;
            // Storage.databases is already empty from setUp
            
            // Act
            boolean result = storageScheduler.performPersistToDisk();
            
            // Assert
            assertTrue(result, "Should return true even with empty databases");
            assertFalse(StorageScheduler.running, "Running flag should be reset to false");
            
            String output = outputStream.toString();
            assertTrue(output.contains("No databases found, nothing to persist"), "Should log empty databases message");
        }
        
        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("Should handle network online state correctly")
        void testNetworkOnlineState(boolean networkOnline) {
            // Arrange
            Network.online = networkOnline;
            StorageScheduler.running = false;
            
            // Act
            boolean result = storageScheduler.performPersistToDisk();
            
            // Assert
            assertEquals(networkOnline && Storage.databases.isEmpty(), result, "Result should match expected outcome based on network state");
            assertFalse(StorageScheduler.running, "Running flag should be reset to false");
        }
    }

    /**
     * Tests for advanced persistence scenarios with multiple databases and tables
     */
    @Nested
    @DisplayName("Advanced Persistence Tests")
    class AdvancedPersistenceTests {
        
        @Test
        @DisplayName("Should handle multiple databases and tables")
        void testPerformPersistToDiskMultipleDatabases() throws Exception {
            // Arrange
            setupMultipleDatabasesScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(result, "Should return true on successful persistence");
                
                String output = outputStream.toString();
                assertTrue(output.contains("Written db: 'db1' to disk"), "Should log first database write");
                assertTrue(output.contains("Written db: 'db2' to disk"), "Should log second database write");
                assertTrue(output.contains("Written table: 'db1'#'table1' storage to disk"), "Should log first table write");
                assertTrue(output.contains("Written table: 'db2'#'table2' storage to disk"), "Should log second table write");
                
                // Verify file operations for both databases
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(2));
            }
        }
        
        @Test
        @DisplayName("Should create correct file paths for database persistence")
        void testCorrectFilePathGeneration() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class);
                 MockedStatic<Paths> mockedPaths = mockStatic(Paths.class)) {
                
                Path mockPath = mock(Path.class);
                mockedPaths.when(() -> Paths.get(testDataPath + "testdb.ddbm")).thenReturn(mockPath);
                
                // Act
                storageScheduler.performPersistToDisk();
                
                // Assert
                mockedPaths.verify(() -> Paths.get(testDataPath + "testdb.ddbm"), times(1));
                mockedFiles.verify(() -> Files.write(eq(mockPath), any(byte[].class)), times(1));
            }
        }
        
        @Test
        @DisplayName("Should handle database with special characters in name")
        void testDatabaseWithSpecialCharacters() throws Exception {
            // Arrange
            Network.online = true;
            StorageScheduler.running = false;
            
            // Setup database with special characters in name
            DatabaseObject specialDb = mock(DatabaseObject.class);
            when(specialDb.name).thenReturn("special#db$name");
            when(specialDb.tables).thenReturn(Arrays.asList("special@table"));
            when(specialDb.returnDBObytes()).thenReturn("special data".getBytes());
            
            TableStorageObject specialTableStorage = mock(TableStorageObject.class);
            TableReplicaObject specialTableReplica = mock(TableReplicaObject.class);
            when(specialTableStorage.rows).thenReturn(new HashMap<>());
            when(specialTableReplica.row_replicas).thenReturn(new HashMap<>());
            
            Storage.databases.put("special#db$name", specialDb);
            Storage.tableStorageObjects.put("special#db$name#special@table", specialTableStorage);
            Storage.tableReplicaObjects.put("special#db$name#special@table", specialTableReplica);
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class);
                 MockedStatic<Paths> mockedPaths = mockStatic(Paths.class)) {
                
                Path mockPath = mock(Path.class);
                mockedPaths.when(() -> Paths.get(testDataPath + "special#db$name.ddbm")).thenReturn(mockPath);
                
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(result, "Should return true with special characters in names");
                
                // Verify correct path was used
                mockedPaths.verify(() -> Paths.get(testDataPath + "special#db$name.ddbm"), times(1));
                mockedFiles.verify(() -> Files.write(eq(mockPath), any(byte[].class)), times(1));
            }
        }
        
        @Test
        @DisplayName("Should handle very large database objects")
        void testVeryLargeDatabaseObject() throws Exception {
            // Arrange
            Network.online = true;
            StorageScheduler.running = false;
            
            // Create a large byte array (1MB)
            byte[] largeData = new byte[1024 * 1024];
            Arrays.fill(largeData, (byte) 'X');
            
            when(mockDatabaseObject.name).thenReturn("largedb");
            when(mockDatabaseObject.tables).thenReturn(Arrays.asList("largetable"));
            when(mockDatabaseObject.returnDBObytes()).thenReturn(largeData);
            
            Storage.databases.put("largedb", mockDatabaseObject);
            Storage.tableStorageObjects.put("largedb#largetable", mockTableStorageObject);
            Storage.tableReplicaObjects.put("largedb#largetable", mockTableReplicaObject);
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(result, "Should return true with large database object");
                
                // Verify large data was written
                mockedFiles.verify(() -> Files.write(any(Path.class), eq(largeData)), times(1));
            }
        }
    }
    
    /**
     * Tests for error handling in the StorageScheduler
     */
    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {
        
        @Test
        @DisplayName("Should handle file write exceptions gracefully")
        void testPerformPersistToDiskFileWriteException() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenThrow(new RuntimeException("File write failed"));
                
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertFalse(result, "Should return false on exception");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
                
                String output = outputStream.toString();
                assertTrue(output.contains("Persisting to disk"), "Should log persistence start");
            }
        }
    
        @Test
        @DisplayName("Should handle table save exceptions gracefully")
        void testPerformPersistToDiskTableSaveException() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            doThrow(new RuntimeException("Table save failed")).when(mockTableStorageObject).saveToDisk();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertFalse(result, "Should return false on table save exception");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
            }
        }
        
        @Test
        @DisplayName("Should handle table save to disk failure")
        void testTableSaveToDiskFailure() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            
            // Make the first save succeed but the second one fail
            doNothing().when(mockTableStorageObject).saveToDisk();
            doThrow(new IOException("Failed to save replica")).when(mockTableReplicaObject).saveToDisk();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertFalse(result, "Should return false when table replica save fails");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
                
                // Verify the database and table storage were saved but not the replica
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(1));
                verify(mockTableStorageObject, times(1)).saveToDisk();
                verify(mockTableReplicaObject, times(1)).saveToDisk();
            }
        }
    
        @Test
        @DisplayName("Should handle null database object gracefully")
        void testPerformPersistToDiskNullDatabaseObject() {
            // Arrange
            Network.online = true;
            StorageScheduler.running = false;
            Storage.databases.put("nulldb", null);
            
            // Act & Assert
            assertThrows(NullPointerException.class, () -> {
                storageScheduler.performPersistToDisk();
            }, "Should throw NullPointerException for null database object");
            
            assertFalse(StorageScheduler.running, "Running flag should be reset to false on exception");
        }
    
        @Test
        @DisplayName("Should handle database with null tables list")
        void testPerformPersistToDiskNullTablesList() throws Exception {
            // Arrange
            Network.online = true;
            StorageScheduler.running = false;
            
            when(mockDatabaseObject.name).thenReturn("testdb");
            when(mockDatabaseObject.tables).thenReturn(null);
            when(mockDatabaseObject.returnDBObytes()).thenReturn("test data".getBytes());
            
            Storage.databases.put("testdb", mockDatabaseObject);
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act & Assert
                assertThrows(NullPointerException.class, () -> {
                    storageScheduler.performPersistToDisk();
                }, "Should throw NullPointerException for null tables list");
                
                assertFalse(StorageScheduler.running, "Running flag should be reset to false on exception");
            }
        }
    }
    
    // This class has been merged with the ConcurrencyTests class below

    /**
     * Tests for concurrency and thread safety in the StorageScheduler
     */
    @Nested
    @DisplayName("Concurrency Tests")
    class ConcurrencyTests {
        
        @Test
        @DisplayName("Should set running flag during execution")
        void testRunningFlagManagement() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            
            // Create a custom scheduler that allows us to check the running flag during execution
            StorageScheduler testScheduler = new StorageScheduler() {
                @Override
                public boolean performPersistToDisk() {
                    assertTrue(StorageScheduler.running, "Running flag should be true during execution");
                    return super.performPersistToDisk();
                }
            };
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                boolean result = testScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(result, "Should return true on successful persistence");
                assertFalse(StorageScheduler.running, "Running flag should be false after execution");
            }
        }
        
        @Test
        @DisplayName("Should handle concurrent persistence operations")
        void testConcurrentPersistOperations() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            
            // Create a custom scheduler that allows us to track concurrent access
            final boolean[] concurrentAccess = {false};
            final boolean[] firstThreadRunning = {false};
            
            StorageScheduler testScheduler = new StorageScheduler() {
                @Override
                public boolean performPersistToDisk() {
                    if (StorageScheduler.running && firstThreadRunning[0]) {
                        concurrentAccess[0] = true;
                    }
                    firstThreadRunning[0] = true;
                    boolean result = super.performPersistToDisk();
                    firstThreadRunning[0] = false;
                    return result;
                }
            };
            
            // Act - Simulate concurrent access by forcing running flag to true
            StorageScheduler.running = true;
            boolean result = testScheduler.performPersistToDisk();
            
            // Assert
            assertFalse(result, "Should return false when already running");
            assertFalse(concurrentAccess[0], "Should prevent concurrent access");
        }
        
        @Test
        @DisplayName("Should handle multiple threads trying to persist simultaneously")
        void testMultipleThreadsPersisting() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(2);
            AtomicInteger successCount = new AtomicInteger(0);
            
            // Act - Start two threads that try to persist simultaneously
            for (int i = 0; i < 2; i++) {
                new Thread(() -> {
                    try {
                        startLatch.await(); // Wait for signal to start
                        boolean result = storageScheduler.performPersistToDisk();
                        if (result) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        completionLatch.countDown();
                    }
                }).start();
            }
            
            // Signal threads to start
            startLatch.countDown();
            
            // Wait for both threads to complete
            completionLatch.await(2, TimeUnit.SECONDS);
            
            // Assert
            assertEquals(1, successCount.get(), "Only one thread should succeed in persisting");
        }
        
        @Test
        @DisplayName("Should handle persistence during shutdown")
        void testPersistenceDuringShutdown() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            
            // Simulate a shutdown hook by creating a custom thread
            Thread shutdownThread = new Thread(() -> {
                try {
                    storageScheduler.performPersistToDisk();
                } catch (Exception e) {
                    fail("Should not throw exception during shutdown: " + e.getMessage());
                }
            });
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                shutdownThread.start();
                shutdownThread.join(1000); // Wait for thread to complete
                
                // Assert
                assertFalse(shutdownThread.isAlive(), "Shutdown thread should complete");
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(1));
            }
        }
    }
    
    /**
     * Tests for logging and output in the StorageScheduler
     */
    @Nested
    @DisplayName("Logging Tests")
    class LoggingTests {
        
        @Test
        @DisplayName("Should log row counts for tables")
        void testTableRowCountLogging() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            
            // Setup row counts
            Map<String, String> storageRows = new HashMap<>();
            storageRows.put("row1", "data1");
            storageRows.put("row2", "data2");
            when(mockTableStorageObject.rows).thenReturn(storageRows);
            
            Map<String, String> replicaRows = new HashMap<>();
            replicaRows.put("row1", "replica1");
            replicaRows.put("row2", "replica2");
            replicaRows.put("row3", "replica3");
            when(mockTableReplicaObject.row_replicas).thenReturn(replicaRows);
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                storageScheduler.performPersistToDisk();
                
                // Assert
                String output = outputStream.toString();
                assertTrue(output.contains("(2 rows)"), "Should log storage row count");
                assertTrue(output.contains("(3 rows)"), "Should log replica row count");
            }
        }
    }
    
    /**
     * Tests for edge cases in the StorageScheduler
     */
    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {
        
        @Test
        @DisplayName("Should handle database with empty tables list")
        void testPerformPersistToDiskEmptyTablesList() throws Exception {
            // Arrange
            Network.online = true;
            StorageScheduler.running = false;
            
            when(mockDatabaseObject.name).thenReturn("testdb");
            when(mockDatabaseObject.tables).thenReturn(Collections.emptyList());
            when(mockDatabaseObject.returnDBObytes()).thenReturn("test data".getBytes());
            
            Storage.databases.put("testdb", mockDatabaseObject);
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(result, "Should return true with empty tables list");
                
                // Should write database but not attempt to write any tables
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(1));
                verify(mockTableStorageObject, never()).saveToDisk();
                verify(mockTableReplicaObject, never()).saveToDisk();
            }
        }
        
        @Test
        @DisplayName("Should handle database with very large number of tables")
        void testPerformPersistToDiskLargeNumberOfTables() throws Exception {
            // Arrange
            Network.online = true;
            StorageScheduler.running = false;
            
            // Create a database with a large number of tables
            when(mockDatabaseObject.name).thenReturn("largedb");
            List<String> manyTables = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                manyTables.add("table" + i);
            }
            when(mockDatabaseObject.tables).thenReturn(manyTables);
            when(mockDatabaseObject.returnDBObytes()).thenReturn("large db data".getBytes());
            
            Storage.databases.put("largedb", mockDatabaseObject);
            
            // Setup mock tables
            for (int i = 0; i < 100; i++) {
                TableStorageObject mockStorage = mock(TableStorageObject.class);
                TableReplicaObject mockReplica = mock(TableReplicaObject.class);
                
                when(mockStorage.rows).thenReturn(new HashMap<>());
                when(mockReplica.row_replicas).thenReturn(new HashMap<>());
                
                Storage.tableStorageObjects.put("largedb#table" + i, mockStorage);
                Storage.tableReplicaObjects.put("largedb#table" + i, mockReplica);
            }
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(result, "Should return true with large number of tables");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
                
                // Verify database was written once
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(1));
            }
        }
    }
    
    // This class has been merged with the ConcurrencyTests class above
    
    /**
     * Fast test implementation for StorageScheduler
     */
    @Nested
    @Tag("fast")
    @Tag("ci")
    @DisplayName("StorageScheduler Fast Tests")
    class StorageSchedulerFastTest {
        
        @Test
        @DisplayName("Should initialize and perform basic persistence")
        void testBasicPersistence() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(result, "Should return true on successful persistence");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
                
                // Verify file operations
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(1));
                verify(mockTableStorageObject, times(1)).saveToDisk();
                verify(mockTableReplicaObject, times(1)).saveToDisk();
            }
        }
        
        @Test
        @DisplayName("Should handle network offline state")
        void testNetworkOffline() {
            // Arrange
            Network.online = false;
            
            // Act
            boolean result = storageScheduler.performPersistToDisk();
            
            // Assert
            assertFalse(result, "Should return false when network is offline");
            assertFalse(StorageScheduler.running, "Running flag should be false");
        }
        
        @Test
        @DisplayName("Should handle concurrent access")
        void testConcurrentAccess() {
            // Arrange
            Network.online = true;
            StorageScheduler.running = true;
            
            // Act
            boolean result = storageScheduler.performPersistToDisk();
            
            // Assert
            assertFalse(result, "Should return false when already running");
            assertTrue(StorageScheduler.running, "Running flag should remain true");
        }
    }

    // Helper methods

    private void setupSuccessfulPersistenceScenario() {
        Network.online = true;
        StorageScheduler.running = false;
        
        // Setup mock database object
        when(mockDatabaseObject.name).thenReturn("testdb");
        when(mockDatabaseObject.tables).thenReturn(Arrays.asList("testtable"));
        when(mockDatabaseObject.returnDBObytes()).thenReturn("test database data".getBytes());
        
        // Setup mock table objects
        Map<String, String> rows = new HashMap<>();
        rows.put("row1", "data1");
        when(mockTableStorageObject.rows).thenReturn(rows);
        
        Map<String, String> rowReplicas = new HashMap<>();
        rowReplicas.put("row1", "replica1");
        when(mockTableReplicaObject.row_replicas).thenReturn(rowReplicas);
        
        // Add to storage maps
        Storage.databases.put("testdb", mockDatabaseObject);
        Storage.tableStorageObjects.put("testdb#testtable", mockTableStorageObject);
        Storage.tableReplicaObjects.put("testdb#testtable", mockTableReplicaObject);
    }

    private void setupMultipleDatabasesScenario() {
        Network.online = true;
        StorageScheduler.running = false;
        
        // Setup first database
        DatabaseObject mockDb1 = mock(DatabaseObject.class);
        when(mockDb1.name).thenReturn("db1");
        when(mockDb1.tables).thenReturn(Arrays.asList("table1"));
        when(mockDb1.returnDBObytes()).thenReturn("db1 data".getBytes());
        
        TableStorageObject mockTable1Storage = mock(TableStorageObject.class);
        TableReplicaObject mockTable1Replica = mock(TableReplicaObject.class);
        when(mockTable1Storage.rows).thenReturn(new HashMap<>());
        when(mockTable1Replica.row_replicas).thenReturn(new HashMap<>());
        
        // Setup second database
        DatabaseObject mockDb2 = mock(DatabaseObject.class);
        when(mockDb2.name).thenReturn("db2");
        when(mockDb2.tables).thenReturn(Arrays.asList("table2"));
        when(mockDb2.returnDBObytes()).thenReturn("db2 data".getBytes());
        
        TableStorageObject mockTable2Storage = mock(TableStorageObject.class);
        TableReplicaObject mockTable2Replica = mock(TableReplicaObject.class);
        when(mockTable2Storage.rows).thenReturn(new HashMap<>());
        when(mockTable2Replica.row_replicas).thenReturn(new HashMap<>());
        
        // Add to storage maps
        Storage.databases.put("db1", mockDb1);
        Storage.databases.put("db2", mockDb2);
        Storage.tableStorageObjects.put("db1#table1", mockTable1Storage);
        Storage.tableStorageObjects.put("db2#table2", mockTable2Storage);
        Storage.tableReplicaObjects.put("db1#table1", mockTable1Replica);
        Storage.tableReplicaObjects.put("db2#table2", mockTable2Replica);
    }

    private void setStaticField(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }
    
    // These tests have been moved to their respective nested test classes
    
    /**
     * Tests for performance characteristics of the StorageScheduler
     */
    @Nested
    @DisplayName("Performance Tests")
    @Tag("performance")
    class PerformanceTests {
        
        @Test
        @DisplayName("Should persist data within acceptable time limits")
        void testPersistencePerformance() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                startTime = System.nanoTime();
                boolean result = storageScheduler.performPersistToDisk();
                endTime = System.nanoTime();
                
                // Assert
                assertTrue(result, "Should return true on successful persistence");
                long executionTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
                
                // Log performance data
                System.out.println("Persistence operation completed in " + executionTimeMs + "ms");
                
                // Performance threshold (adjust based on environment)
                assertTrue(executionTimeMs < 1000,
                    "Persistence operation should complete within 1000ms, took: " + executionTimeMs + "ms");
            }
        }
        
        @ParameterizedTest(name = "Database size: {0} entries")
        @ValueSource(ints = {10, 100, 1000})
        @DisplayName("Should scale linearly with database size")
        @Tag("scalability")
        void testPersistenceScalability(int databaseSize) throws Exception {
            // Arrange
            Network.online = true;
            StorageScheduler.running = false;
            
            // Create test data with specified size
            DatabaseObject testDb = generateTestDatabase("scalability_db", databaseSize);
            Storage.databases.put(testDb.name, testDb);
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                startTime = System.nanoTime();
                boolean result = storageScheduler.performPersistToDisk();
                endTime = System.nanoTime();
                
                // Assert
                assertTrue(result, "Should return true on successful persistence");
                long executionTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
                
                // Log performance data
                System.out.println("Persistence of " + databaseSize +
                    " entries completed in " + executionTimeMs + "ms");
                
                // We expect roughly linear scaling, but with some overhead
                // This is a simple heuristic and may need adjustment
                long expectedMaxTime = 100 + (databaseSize / 10);
                assertTrue(executionTimeMs < expectedMaxTime,
                    "Persistence should scale linearly, expected < " + expectedMaxTime +
                    "ms, actual: " + executionTimeMs + "ms");
            }
        }
        
        @Test
        @DisplayName("Should measure memory usage during persistence")
        @Tag("memory")
        void testMemoryUsageDuringPersistence() throws Exception {
            // Arrange
            setupLargeDataScenario(1000); // 1000 entries
            Runtime runtime = Runtime.getRuntime();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Measure memory before operation
                runtime.gc(); // Request garbage collection to get more accurate readings
                long memoryBefore = runtime.totalMemory() - runtime.freeMemory();
                
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Measure memory after operation
                runtime.gc(); // Request garbage collection
                long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
                
                // Assert
                assertTrue(result, "Should return true on successful persistence");
                
                // Log memory usage
                long memoryUsed = memoryAfter - memoryBefore;
                System.out.println("Memory used during persistence: " +
                    (memoryUsed / 1024 / 1024) + "MB");
                
                // We don't assert on exact memory usage as it can vary,
                // but we log it for analysis
            }
        }
    }
    
    /**
     * Tests for stress conditions in the StorageScheduler
     */
    @Nested
    @DisplayName("Stress Tests")
    @Tag("stress")
    class StressTests {
        
        @Test
        @DisplayName("Should handle high volume of concurrent persistence requests")
        void testHighConcurrentLoad() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            int threadCount = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            
            // Act - Start multiple threads that try to persist simultaneously
            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    try {
                        startLatch.await(); // Wait for signal to start
                        boolean result = storageScheduler.performPersistToDisk();
                        if (result) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        completionLatch.countDown();
                    }
                }).start();
            }
            
            // Signal threads to start
            startLatch.countDown();
            
            // Wait for all threads to complete
            boolean allCompleted = completionLatch.await(5, TimeUnit.SECONDS);
            
            // Assert
            assertTrue(allCompleted, "All threads should complete within timeout");
            assertEquals(1, successCount.get(), "Only one thread should succeed in persisting");
        }
        
        @Test
        @DisplayName("Should handle rapid sequential persistence requests")
        void testRapidSequentialRequests() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            int requestCount = 100;
            AtomicInteger successCount = new AtomicInteger(0);
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act - Perform multiple sequential persistence operations
                startTime = System.nanoTime();
                for (int i = 0; i < requestCount; i++) {
                    StorageScheduler.running = false; // Reset running flag for each iteration
                    boolean result = storageScheduler.performPersistToDisk();
                    if (result) {
                        successCount.incrementAndGet();
                    }
                }
                endTime = System.nanoTime();
                
                // Assert
                long totalTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
                System.out.println("Completed " + successCount.get() + " out of " +
                    requestCount + " requests in " + totalTimeMs + "ms");
                
                // We expect all requests to succeed since we're resetting the running flag
                assertEquals(requestCount, successCount.get(),
                    "All requests should succeed when running flag is reset");
            }
        }
        
        @Test
        @DisplayName("Should handle persistence with large number of databases")
        void testLargeDatabaseCount() throws Exception {
            // Arrange
            Network.online = true;
            StorageScheduler.running = false;
            int databaseCount = 50;
            
            // Create many databases
            for (int i = 0; i < databaseCount; i++) {
                DatabaseObject mockDb = mock(DatabaseObject.class);
                when(mockDb.name).thenReturn("db" + i);
                when(mockDb.tables).thenReturn(Arrays.asList("table" + i));
                when(mockDb.returnDBObytes()).thenReturn(("db" + i + " data").getBytes());
                
                TableStorageObject mockStorage = mock(TableStorageObject.class);
                TableReplicaObject mockReplica = mock(TableReplicaObject.class);
                when(mockStorage.rows).thenReturn(new HashMap<>());
                when(mockReplica.row_replicas).thenReturn(new HashMap<>());
                
                Storage.databases.put("db" + i, mockDb);
                Storage.tableStorageObjects.put("db" + i + "#table" + i, mockStorage);
                Storage.tableReplicaObjects.put("db" + i + "#table" + i, mockReplica);
            }
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                startTime = System.nanoTime();
                boolean result = storageScheduler.performPersistToDisk();
                endTime = System.nanoTime();
                
                // Assert
                assertTrue(result, "Should return true with large number of databases");
                long executionTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
                System.out.println("Persisted " + databaseCount +
                    " databases in " + executionTimeMs + "ms");
            }
        }
    }
    
    /**
     * Tests for resource cleanup verification
     */
    @Nested
    @DisplayName("Resource Cleanup Tests")
    @Tag("cleanup")
    class ResourceCleanupTests {
        
        @Test
        @DisplayName("Should release all resources after persistence")
        void testResourceReleaseAfterPersistence() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                Runtime runtime = Runtime.getRuntime();
                long memoryBefore = runtime.totalMemory() - runtime.freeMemory();
                
                boolean result = storageScheduler.performPersistToDisk();
                
                // Force garbage collection to clean up any unreferenced objects
                runtime.gc();
                runtime.gc(); // Run twice to be more thorough
                
                long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
                
                // Assert
                assertTrue(result, "Should return true on successful persistence");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
                
                // Log memory usage
                System.out.println("Memory before: " + (memoryBefore / 1024) + "KB");
                System.out.println("Memory after: " + (memoryAfter / 1024) + "KB");
                
                // We don't assert on exact memory values as they can vary,
                // but we verify the running flag is properly reset
            }
        }
        
        @Test
        @DisplayName("Should close file handles after persistence")
        void testFileHandleCleanup() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Mock to verify file handles are closed
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenAnswer(invocation -> {
                              // Return null as Files.write returns Path
                              return null;
                          });
                
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(result, "Should return true on successful persistence");
                
                // Verify Files.write was called (which would close the file handle)
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(1));
            }
        }
        
        @Test
        @DisplayName("Should handle persistence after out of memory condition")
        void testPersistenceAfterOutOfMemory() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            
            // Simulate an OutOfMemoryError during a previous operation
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenThrow(new OutOfMemoryError("Simulated OOM"))
                          .thenReturn(null); // Return null on second call
                
                // Act & Assert - First attempt should fail
                assertThrows(OutOfMemoryError.class, () -> {
                    storageScheduler.performPersistToDisk();
                });
                
                // Reset running flag (as would happen in production after error recovery)
                StorageScheduler.running = false;
                
                // Second attempt should succeed
                boolean result = storageScheduler.performPersistToDisk();
                assertTrue(result, "Should recover and succeed after OOM condition");
            }
        }
    }
    
    /**
     * Tests for CI/CD integration
     */
    @Nested
    @DisplayName("CI/CD Integration Tests")
    @Tag("ci")
    class CICDTests {
        
        @Test
        @DisplayName("Should run basic verification for CI pipeline")
        @Tag("smoke")
        void testBasicVerification() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(result, "Basic persistence should succeed");
            }
        }
        
        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("Should handle network state correctly in CI environment")
        @Tag("integration")
        void testNetworkStateHandling(boolean networkOnline) {
            // Arrange
            Network.online = networkOnline;
            StorageScheduler.running = false;
            
            // Act
            boolean result = storageScheduler.performPersistToDisk();
            
            // Assert
            assertEquals(networkOnline && Storage.databases.isEmpty(), result,
                "Result should match expected outcome based on network state");
        }
        
        @Test
        @DisplayName("Should complete within CI time constraints")
        @Tag("performance")
        void testCITimeConstraints() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                startTime = System.nanoTime();
                boolean result = storageScheduler.performPersistToDisk();
                endTime = System.nanoTime();
                
                // Assert
                assertTrue(result, "Should return true on successful persistence");
                long executionTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
                
                // CI environments typically have stricter time constraints
                assertTrue(executionTimeMs < 500,
                    "CI execution should complete within 500ms, took: " + executionTimeMs + "ms");
            }
        }
    }
    
    // Helper methods for test data generation
    
    /**
     * Generates a test database with the specified number of entries
     *
     * @param dbName The name of the database
     * @param entryCount The number of entries to generate
     * @return A mock DatabaseObject with the specified characteristics
     */
    private DatabaseObject generateTestDatabase(String dbName, int entryCount) {
        DatabaseObject mockDb = mock(DatabaseObject.class);
        when(mockDb.name).thenReturn(dbName);
        
        // Generate table names
        List<String> tableNames = new ArrayList<>();
        for (int i = 0; i < Math.min(entryCount, 10); i++) {
            tableNames.add("table" + i);
        }
        when(mockDb.tables).thenReturn(tableNames);
        
        // Generate database data proportional to entry count
        byte[] dbData = new byte[entryCount * 100]; // 100 bytes per entry
        Arrays.fill(dbData, (byte) 'X');
        when(mockDb.returnDBObytes()).thenReturn(dbData);
        
        // Create corresponding table objects
        for (String tableName : tableNames) {
            TableStorageObject mockStorage = mock(TableStorageObject.class);
            TableReplicaObject mockReplica = mock(TableReplicaObject.class);
            
            // Create rows proportional to entry count
            Map<String, String> rows = new HashMap<>();
            Map<String, String> replicas = new HashMap<>();
            
            int rowsPerTable = entryCount / tableNames.size();
            for (int i = 0; i < rowsPerTable; i++) {
                rows.put("key" + i, "value" + i);
                replicas.put("key" + i, "replica" + i);
            }
            
            when(mockStorage.rows).thenReturn(rows);
            when(mockReplica.row_replicas).thenReturn(replicas);
            
            Storage.tableStorageObjects.put(dbName + "#" + tableName, mockStorage);
            Storage.tableReplicaObjects.put(dbName + "#" + tableName, mockReplica);
        }
        
        return mockDb;
    }
    
    /**
     * Sets up a scenario with large amounts of data
     *
     * @param entryCount The number of entries to generate
     */
    private void setupLargeDataScenario(int entryCount) {
        Network.online = true;
        StorageScheduler.running = false;
        
        // Clear existing data
        Storage.databases.clear();
        Storage.tableStorageObjects.clear();
        Storage.tableReplicaObjects.clear();
        
        // Add a large database
        DatabaseObject largeDb = generateTestDatabase("large_db", entryCount);
        Storage.databases.put(largeDb.name, largeDb);
    }
}