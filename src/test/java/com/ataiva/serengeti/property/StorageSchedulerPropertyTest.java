package com.ataiva.serengeti.property;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageScheduler;
import com.ataiva.serengeti.utils.TestBase;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.AfterProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for the StorageScheduler component.
 * 
 * These tests use jqwik to generate a wide variety of test inputs and verify that
 * certain properties hold true regardless of the input. This approach helps uncover
 * edge cases and unexpected behaviors that might not be caught by traditional unit tests.
 * 
 * Properties tested include:
 * - Persistence operation state consistency
 * - Network state handling
 * - Concurrent operation safety
 * - Error handling robustness
 * - Database name handling
 */
@DisplayName("StorageScheduler Property Tests")
@Tag("property")
@Tag("storage")
public class StorageSchedulerPropertyTest extends TestBase {

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

    @BeforeProperty
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

    @AfterProperty
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
     * Property: The running flag should always be false after performPersistToDisk completes,
     * regardless of the initial network state or running state.
     */
    @Property(tries = 50)
    @DisplayName("Running flag should be false after persistence completes")
    void runningFlagShouldBeFalseAfterPersistence(@ForAll boolean networkOnline, @ForAll boolean initialRunning) {
        // Arrange
        Network.online = networkOnline;
        StorageScheduler.running = initialRunning;
        
        // Act
        storageScheduler.performPersistToDisk();
        
        // Assert
        assertFalse(StorageScheduler.running, 
                "Running flag should be false after persistence completes, " +
                "regardless of initial network state (" + networkOnline + ") or running state (" + initialRunning + ")");
    }

    /**
     * Property: The persistence operation should return true if and only if
     * the network is online, the scheduler is not already running, and no exceptions occur.
     */
    @Property(tries = 50)
    @DisplayName("Persistence should return true only when conditions are met")
    void persistenceShouldReturnTrueOnlyWhenConditionsMet(@ForAll boolean networkOnline, @ForAll boolean initialRunning) {
        // Arrange
        Network.online = networkOnline;
        StorageScheduler.running = initialRunning;
        
        // Act
        boolean result = storageScheduler.performPersistToDisk();
        
        // Assert
        boolean expectedResult = networkOnline && !initialRunning && Storage.databases.isEmpty();
        assertEquals(expectedResult, result,
                "Persistence should return " + expectedResult + " when network online is " + networkOnline +
                " and initial running state is " + initialRunning);
    }

    /**
     * Property: The persistence operation should handle any valid database name without errors.
     */
    @Property(tries = 50)
    @DisplayName("Persistence should handle any valid database name")
    void persistenceShouldHandleAnyValidDatabaseName(@ForAll @AlphaChars @StringLength(min = 1, max = 30) String dbName) {
        // Arrange
        Network.online = true;
        StorageScheduler.running = false;
        
        // Setup mock database object with the generated name
        when(mockDatabaseObject.name).thenReturn(dbName);
        when(mockDatabaseObject.tables).thenReturn(Arrays.asList("table1"));
        when(mockDatabaseObject.returnDBObytes()).thenReturn("test data".getBytes());
        
        // Setup mock table objects
        when(mockTableStorageObject.rows).thenReturn(new HashMap<>());
        when(mockTableReplicaObject.row_replicas).thenReturn(new HashMap<>());
        
        // Add to storage maps
        Storage.databases.put(dbName, mockDatabaseObject);
        Storage.tableStorageObjects.put(dbName + "#table1", mockTableStorageObject);
        Storage.tableReplicaObjects.put(dbName + "#table1", mockTableReplicaObject);
        
        try (MockedStatic<Files> mockedFiles = mockStatic(Files.class);
             MockedStatic<Paths> mockedPaths = mockStatic(Paths.class)) {
            
            Path mockPath = mock(Path.class);
            mockedPaths.when(() -> Paths.get(testDataPath + dbName + Globals.meta_extention)).thenReturn(mockPath);
            
            // Act
            boolean result = storageScheduler.performPersistToDisk();
            
            // Assert
            assertTrue(result, "Persistence should succeed with database name: " + dbName);
            mockedPaths.verify(() -> Paths.get(testDataPath + dbName + Globals.meta_extention), times(1));
            mockedFiles.verify(() -> Files.write(eq(mockPath), any(byte[].class)), times(1));
        }
    }

    /**
     * Property: The persistence operation should handle any number of databases.
     */
    @Property(tries = 20)
    @DisplayName("Persistence should handle any number of databases")
    void persistenceShouldHandleAnyNumberOfDatabases(@ForAll @IntRange(min = 0, max = 10) int dbCount) {
        // Arrange
        Network.online = true;
        StorageScheduler.running = false;
        
        // Create the specified number of mock databases
        List<DatabaseObject> mockDatabases = new ArrayList<>();
        List<TableStorageObject> mockTableStorageObjects = new ArrayList<>();
        List<TableReplicaObject> mockTableReplicaObjects = new ArrayList<>();
        
        for (int i = 0; i < dbCount; i++) {
            DatabaseObject db = mock(DatabaseObject.class);
            when(db.name).thenReturn("db" + i);
            when(db.tables).thenReturn(Arrays.asList("table" + i));
            when(db.returnDBObytes()).thenReturn(("data for db" + i).getBytes());
            mockDatabases.add(db);
            
            TableStorageObject tso = mock(TableStorageObject.class);
            when(tso.rows).thenReturn(new HashMap<>());
            mockTableStorageObjects.add(tso);
            
            TableReplicaObject tro = mock(TableReplicaObject.class);
            when(tro.row_replicas).thenReturn(new HashMap<>());
            mockTableReplicaObjects.add(tro);
            
            Storage.databases.put("db" + i, db);
            Storage.tableStorageObjects.put("db" + i + "#table" + i, tso);
            Storage.tableReplicaObjects.put("db" + i + "#table" + i, tro);
        }
        
        try (MockedStatic<Files> mockedFiles = mockStatic(Files.class);
             MockedStatic<Paths> mockedPaths = mockStatic(Paths.class)) {
            
            // Setup path mocking for each database
            for (int i = 0; i < dbCount; i++) {
                Path mockPath = mock(Path.class);
                final String dbName = "db" + i;
                mockedPaths.when(() -> Paths.get(testDataPath + dbName + Globals.meta_extention)).thenReturn(mockPath);
            }
            
            // Act
            boolean result = storageScheduler.performPersistToDisk();
            
            // Assert
            if (dbCount == 0) {
                assertTrue(result, "Persistence should succeed with empty database list");
                String output = outputStream.toString();
                assertTrue(output.contains("No databases found, nothing to persist"), 
                        "Should log empty databases message");
            } else {
                assertTrue(result, "Persistence should succeed with " + dbCount + " databases");
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(dbCount));
                
                // Verify each table storage and replica was saved
                for (int i = 0; i < dbCount; i++) {
                    verify(mockTableStorageObjects.get(i), times(1)).saveToDisk();
                    verify(mockTableReplicaObjects.get(i), times(1)).saveToDisk();
                }
            }
        }
    }

    /**
     * Property: The persistence operation should be resilient to exceptions.
     */
    @Property(tries = 20)
    @DisplayName("Persistence should be resilient to exceptions")
    void persistenceShouldBeResilientToExceptions(@ForAll @IntRange(min = 0, max = 3) int exceptionType) {
        // Arrange
        Network.online = true;
        StorageScheduler.running = false;
        
        // Setup mock database object
        when(mockDatabaseObject.name).thenReturn("testdb");
        when(mockDatabaseObject.tables).thenReturn(Arrays.asList("testtable"));
        when(mockDatabaseObject.returnDBObytes()).thenReturn("test data".getBytes());
        
        // Setup mock table objects
        when(mockTableStorageObject.rows).thenReturn(new HashMap<>());
        when(mockTableReplicaObject.row_replicas).thenReturn(new HashMap<>());
        
        // Add to storage maps
        Storage.databases.put("testdb", mockDatabaseObject);
        Storage.tableStorageObjects.put("testdb#testtable", mockTableStorageObject);
        Storage.tableReplicaObjects.put("testdb#testtable", mockTableReplicaObject);
        
        try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
            // Configure different types of exceptions based on the generated value
            switch (exceptionType) {
                case 0: // No exception
                    break;
                case 1: // Exception in Files.write
                    mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                              .thenThrow(new RuntimeException("File write failed"));
                    break;
                case 2: // Exception in TableStorageObject.saveToDisk
                    doThrow(new RuntimeException("Table storage save failed")).when(mockTableStorageObject).saveToDisk();
                    break;
                case 3: // Exception in TableReplicaObject.saveToDisk
                    doThrow(new RuntimeException("Table replica save failed")).when(mockTableReplicaObject).saveToDisk();
                    break;
            }
            
            // Act
            boolean result = storageScheduler.performPersistToDisk();
            
            // Assert
            if (exceptionType == 0) {
                assertTrue(result, "Persistence should succeed when no exceptions occur");
            } else {
                assertFalse(result, "Persistence should fail when exceptions occur (type " + exceptionType + ")");
            }
            
            // The running flag should always be reset to false
            assertFalse(StorageScheduler.running, "Running flag should be reset to false even after exceptions");
        }
    }

    /**
     * Property: The persistence operation should be thread-safe.
     */
    @Property(tries = 10)
    @DisplayName("Persistence should be thread-safe")
    void persistenceShouldBeThreadSafe(@ForAll @IntRange(min = 2, max = 5) int threadCount) throws InterruptedException {
        // Arrange
        Network.online = true;
        StorageScheduler.running = false;
        
        // Setup mock database object
        when(mockDatabaseObject.name).thenReturn("testdb");
        when(mockDatabaseObject.tables).thenReturn(Arrays.asList("testtable"));
        when(mockDatabaseObject.returnDBObytes()).thenReturn("test data".getBytes());
        
        // Setup mock table objects
        when(mockTableStorageObject.rows).thenReturn(new HashMap<>());
        when(mockTableReplicaObject.row_replicas).thenReturn(new HashMap<>());
        
        // Add to storage maps
        Storage.databases.put("testdb", mockDatabaseObject);
        Storage.tableStorageObjects.put("testdb#testtable", mockTableStorageObject);
        Storage.tableReplicaObjects.put("testdb#testtable", mockTableReplicaObject);
        
        // Track how many threads actually performed persistence
        AtomicInteger persistenceCount = new AtomicInteger(0);
        
        try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
            // Create and start multiple threads that all try to perform persistence
            Thread[] threads = new Thread[threadCount];
            for (int i = 0; i < threadCount; i++) {
                threads[i] = new Thread(() -> {
                    boolean result = storageScheduler.performPersistToDisk();
                    if (result) {
                        persistenceCount.incrementAndGet();
                    }
                });
                threads[i].start();
            }
            
            // Wait for all threads to complete
            for (Thread thread : threads) {
                thread.join();
            }
            
            // Assert
            assertEquals(1, persistenceCount.get(), 
                    "Only one thread should successfully perform persistence when " + threadCount + " threads attempt it");
            assertFalse(StorageScheduler.running, "Running flag should be false after all threads complete");
            
            // Verify Files.write was called exactly once
            mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(1));
        }
    }
}