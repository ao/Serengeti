package com.ataiva.serengeti.storage.lsm;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageScheduler;
import com.ataiva.serengeti.utils.LSMFastTestBase;
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
 * Fast tests for the StorageScheduler component with LSM storage engine integration.
 * These tests focus on core functionality and run quickly to provide rapid feedback during development.
 * 
 * The StorageScheduler is responsible for periodically persisting database state to disk.
 * These LSM-specific fast tests verify:
 * - Basic persistence functionality works correctly with LSM storage engine
 * - Error conditions are handled gracefully
 * - Thread management works properly
 * - LSM-specific storage operations are performed correctly
 * 
 * Test Strategy:
 * - Use minimal setup with mocked dependencies
 * - Focus on core functionality only
 * - Avoid long-running operations
 * - Use small test data sets
 */
@DisplayName("StorageScheduler LSM Fast Tests")
@Tag("fast")
@Tag("lsm")
public class StorageSchedulerFastTest extends LSMFastTestBase {

    @Mock
    private DatabaseObject mockDatabaseObject;
    
    @Mock
    private TableStorageObject mockTableStorageObject;
    
    @Mock
    private TableReplicaObject mockTableReplicaObject;
    
    @Mock
    private LSMStorageEngine mockLSMStorageEngine;

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
        super.setUp(); // Initialize LSMFastTestBase components
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
        Globals.data_path = testDataPath; // Use LSMFastTestBase's temporary directory
        
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
        
        super.tearDown(); // Clean up LSMFastTestBase resources
    }

    /**
     * Tests for the initialization and thread management of StorageScheduler with LSM
     */
    @Nested
    @DisplayName("Thread Management Tests")
    class ThreadManagementTests {
        
        @Test
        @DisplayName("Should initialize and start background thread with LSM storage")
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
        }
    }

    /**
     * Tests for the basic persistence functionality of StorageScheduler with LSM
     */
    @Nested
    @DisplayName("LSM Persistence Tests")
    class LSMPersistenceTests {
        
        @Test
        @DisplayName("Should persist LSM databases to disk when network is online and not running")
        void testPerformPersistToDiskWithLSM() throws Exception {
            // Arrange
            setupLSMPersistenceScenario();
            
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
        @DisplayName("Should handle LSM storage engine exceptions gracefully")
        void testLSMStorageEngineException() throws Exception {
            // Arrange
            setupLSMPersistenceScenario();
            doThrow(new RuntimeException("LSM storage engine error")).when(mockTableStorageObject).saveToDisk();
            
            // Act
            boolean result = storageScheduler.performPersistToDisk();
            
            // Assert
            assertFalse(result, "Should return false on LSM storage engine exception");
            assertFalse(StorageScheduler.running, "Running flag should be reset to false");
        }
    }

    /**
     * Tests for error handling in the StorageScheduler with LSM
     */
    @Nested
    @DisplayName("LSM Error Handling Tests")
    class LSMErrorHandlingTests {
        
        @Test
        @DisplayName("Should handle LSM file write exceptions gracefully")
        void testPerformPersistToDiskLSMFileWriteException() throws Exception {
            // Arrange
            setupLSMPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenThrow(new RuntimeException("LSM file write failed"));
                
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertFalse(result, "Should return false on exception");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
            }
        }
    }

    /**
     * Helper method to set up an LSM persistence scenario
     */
    private void setupLSMPersistenceScenario() {
        Network.online = true;
        StorageScheduler.running = false;
        
        // Setup mock database object
        when(mockDatabaseObject.name).thenReturn("lsmdb");
        when(mockDatabaseObject.tables).thenReturn(Arrays.asList("lsmtable"));
        when(mockDatabaseObject.returnDBObytes()).thenReturn("lsm test data".getBytes());
        
        // Setup mock table objects
        when(mockTableStorageObject.rows).thenReturn(new HashMap<>());
        when(mockTableReplicaObject.row_replicas).thenReturn(new HashMap<>());
        
        // Add to storage maps
        Storage.databases.put("lsmdb", mockDatabaseObject);
        Storage.tableStorageObjects.put("lsmdb#lsmtable", mockTableStorageObject);
        Storage.tableReplicaObjects.put("lsmdb#lsmtable", mockTableReplicaObject);
    }
}