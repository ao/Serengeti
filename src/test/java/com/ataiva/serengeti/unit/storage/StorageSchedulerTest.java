package com.ataiva.serengeti.unit.storage;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageScheduler;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the StorageScheduler component.
 * Tests periodic task scheduling, disk persistence, and error handling.
 */
@DisplayName("StorageScheduler Unit Tests")
@Tag("unit")
class StorageSchedulerTest {

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

    @BeforeEach
    void setUp() throws Exception {
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
        Globals.data_path = "./test-data/";
        
        // Capture console output for testing
        outputStream = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(outputStream));
    }

    @AfterEach
    void tearDown() throws Exception {
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
    }

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

    @Test
    @DisplayName("Should create correct file paths for database persistence")
    void testCorrectFilePathGeneration() throws Exception {
        // Arrange
        setupSuccessfulPersistenceScenario();
        
        try (MockedStatic<Files> mockedFiles = mockStatic(Files.class);
             MockedStatic<Paths> mockedPaths = mockStatic(Paths.class)) {
            
            Path mockPath = mock(Path.class);
            mockedPaths.when(() -> Paths.get("./test-data/testdb.ddbm")).thenReturn(mockPath);
            
            // Act
            storageScheduler.performPersistToDisk();
            
            // Assert
            mockedPaths.verify(() -> Paths.get("./test-data/testdb.ddbm"), times(1));
            mockedFiles.verify(() -> Files.write(eq(mockPath), any(byte[].class)), times(1));
        }
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
}