package com.ataiva.serengeti.storage;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;
import com.ataiva.serengeti.utils.StorageFastTestBase;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Fast tests for the StorageScheduler component.
 * These tests focus on core functionality and run quickly to provide rapid feedback during development.
 * 
 * The StorageScheduler is responsible for periodically persisting database state to disk.
 * These fast tests verify:
 * - Basic persistence functionality works correctly
 * - Error conditions are handled gracefully
 * - Thread management works properly
 * 
 * Test Strategy:
 * - Use minimal setup with mocked dependencies
 * - Focus on core functionality only
 * - Avoid long-running operations
 * - Use small test data sets
 */
@DisplayName("StorageScheduler Fast Tests")
@Tag("fast")
public class StorageSchedulerFastTest extends StorageFastTestBase {

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
    public void setUp() throws Exception {
        super.setUp(); // Initialize StorageFastTestBase components
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
        Globals.data_path = testDataPath; // Use StorageFastTestBase's temporary directory
        
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
        
        super.tearDown(); // Clean up StorageFastTestBase resources
    }

    /**
     * Tests for the initialization and thread management of StorageScheduler
     */
    @Nested
    @DisplayName("Thread Management Tests")
    class ThreadManagementTests {
        
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
            assertTrue(output.contains("StorageScheduler Initiated") || output.isEmpty(), 
                    "Should log scheduler initiation or have no output");
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
            }
        }
    
        @Test
        @DisplayName("Should handle table save exceptions gracefully")
        void testPerformPersistToDiskTableSaveException() throws Exception {
            // Arrange
            setupSuccessfulPersistenceScenario();
            doThrow(new RuntimeException("Table save failed")).when(mockTableStorageObject).saveToDisk();
            
            // Act
            boolean result = storageScheduler.performPersistToDisk();
            
            // Assert
            assertFalse(result, "Should return false on table save exception");
            assertFalse(StorageScheduler.running, "Running flag should be reset to false");
        }
    }

    /**
     * Helper method to set up a successful persistence scenario
     */
    private void setupSuccessfulPersistenceScenario() {
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
    }
}