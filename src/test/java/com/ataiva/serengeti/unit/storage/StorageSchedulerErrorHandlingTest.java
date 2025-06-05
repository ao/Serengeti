package com.ataiva.serengeti.unit.storage;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageScheduler;
import com.ataiva.serengeti.utils.TestBase;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the enhanced error handling in the StorageScheduler component.
 * 
 * These tests verify:
 * - Retry mechanism for transient errors
 * - Graceful degradation for persistent errors
 * - Transaction-like behavior for atomicity
 * - Error metrics collection
 * - Health status reporting
 */
@DisplayName("StorageScheduler Error Handling Tests")
@Tag("unit")
@Tag("storage")
@ExtendWith(MockitoExtension.class)
class StorageSchedulerErrorHandlingTest extends TestBase {

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
    private TestLogHandler logHandler;
    
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
        
        // Setup log handler to capture logs
        logHandler = new TestLogHandler();
        Logger logger = Logger.getLogger(StorageScheduler.class.getName());
        logger.addHandler(logHandler);
        logger.setLevel(Level.ALL);
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
        
        // Remove log handler
        Logger logger = Logger.getLogger(StorageScheduler.class.getName());
        logger.removeHandler(logHandler);
        
        if (mockCloseable != null) {
            mockCloseable.close();
        }
        
        super.tearDown(); // Clean up TestBase resources
    }

    /**
     * Helper method to set up a basic persistence scenario
     */
    private void setupBasicPersistenceScenario() {
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

    /**
     * Tests for retry mechanism for transient errors
     */
    @Nested
    @DisplayName("Retry Mechanism Tests")
    class RetryMechanismTests {
        
        @Test
        @DisplayName("Should retry transient I/O errors")
        void testRetryTransientIOErrors() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class);
                 MockedStatic<Thread> mockedThread = mockStatic(Thread.class)) {
                
                // First call fails with transient error, second call succeeds
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenThrow(new IOException("Connection reset"))
                          .thenReturn(mock(Path.class));
                
                // Don't actually sleep in tests
                mockedThread.when(() -> Thread.sleep(anyLong())).thenAnswer(i -> null);
                
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(result, "Should return true after successful retry");
                
                // Verify retry was attempted
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(2));
                mockedThread.verify(() -> Thread.sleep(anyLong()), times(1));
                
                // Verify error metrics
                Map<String, Object> metrics = storageScheduler.getErrorMetrics();
                assertEquals(1, metrics.get("totalErrors"));
                assertEquals(1, metrics.get("transientErrors"));
                assertEquals(0, metrics.get("persistentErrors"));
            }
        }
        
        @Test
        @DisplayName("Should stop retrying after max attempts")
        void testMaxRetryAttempts() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class);
                 MockedStatic<Thread> mockedThread = mockStatic(Thread.class)) {
                
                // All calls fail with transient error
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenThrow(new IOException("Connection reset"));
                
                // Don't actually sleep in tests
                mockedThread.when(() -> Thread.sleep(anyLong())).thenAnswer(i -> null);
                
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertFalse(result, "Should return false after max retry attempts");
                
                // Verify retry was attempted 3 times (MAX_RETRY_ATTEMPTS)
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(3));
                mockedThread.verify(() -> Thread.sleep(anyLong()), times(2)); // Sleep between retries
                
                // Verify error metrics
                Map<String, Object> metrics = storageScheduler.getErrorMetrics();
                assertEquals(3, metrics.get("totalErrors"));
                assertEquals(3, metrics.get("transientErrors"));
                assertEquals(0, metrics.get("persistentErrors"));
            }
        }
        
        @Test
        @DisplayName("Should not retry persistent errors")
        void testNoRetryForPersistentErrors() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class);
                 MockedStatic<Thread> mockedThread = mockStatic(Thread.class)) {
                
                // Call fails with persistent error
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenThrow(new AccessDeniedException("Permission denied"));
                
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertFalse(result, "Should return false for persistent error");
                
                // Verify no retry was attempted
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(1));
                mockedThread.verify(() -> Thread.sleep(anyLong()), never());
                
                // Verify error metrics
                Map<String, Object> metrics = storageScheduler.getErrorMetrics();
                assertEquals(1, metrics.get("totalErrors"));
                assertEquals(0, metrics.get("transientErrors"));
                assertEquals(1, metrics.get("persistentErrors"));
            }
        }
    }

    /**
     * Tests for transaction-like behavior
     */
    @Nested
    @DisplayName("Transaction Behavior Tests")
    class TransactionBehaviorTests {
        
        @Test
        @DisplayName("Should attempt all operations when non-critical operations fail")
        void testContinueOnNonCriticalFailure() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Database metadata write succeeds
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenReturn(mock(Path.class));
                
                // Table storage save fails
                doThrow(new IOException("Table storage save failed")).when(mockTableStorageObject).saveToDisk();
                
                // Table replica save succeeds
                doNothing().when(mockTableReplicaObject).saveToDisk();
                
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertFalse(result, "Should return false when any operation fails");
                
                // Verify all operations were attempted
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(1));
                verify(mockTableStorageObject, times(1)).saveToDisk();
                verify(mockTableReplicaObject, times(1)).saveToDisk();
                
                // Verify error metrics
                Map<String, Object> metrics = storageScheduler.getErrorMetrics();
                assertEquals(1, metrics.get("totalErrors"));
            }
        }
        
        @Test
        @DisplayName("Should stop operations when critical database metadata operation fails")
        void testStopOnCriticalFailure() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            // Add a second database to test operation ordering
            DatabaseObject mockDb2 = mock(DatabaseObject.class);
            when(mockDb2.name).thenReturn("testdb2");
            when(mockDb2.tables).thenReturn(Arrays.asList("testtable2"));
            when(mockDb2.returnDBObytes()).thenReturn("test database 2 data".getBytes());
            Storage.databases.put("testdb2", mockDb2);
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // First database metadata write fails
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenThrow(new IOException("Critical database write failed"));
                
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertFalse(result, "Should return false on critical failure");
                
                // Verify only the first operation was attempted
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(1));
                verify(mockTableStorageObject, never()).saveToDisk();
                verify(mockTableReplicaObject, never()).saveToDisk();
                
                // Verify error metrics
                Map<String, Object> metrics = storageScheduler.getErrorMetrics();
                assertEquals(1, metrics.get("totalErrors"));
            }
        }
    }

    /**
     * Tests for error metrics and health status
     */
    @Nested
    @DisplayName("Error Metrics and Health Status Tests")
    class ErrorMetricsTests {
        
        @Test
        @DisplayName("Should track error metrics correctly")
        void testErrorMetricsTracking() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class);
                 MockedStatic<Thread> mockedThread = mockStatic(Thread.class)) {
                
                // Don't actually sleep in tests
                mockedThread.when(() -> Thread.sleep(anyLong())).thenAnswer(i -> null);
                
                // First call - transient error
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenThrow(new IOException("Connection reset"));
                
                storageScheduler.performPersistToDisk();
                
                // Second call - persistent error
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenThrow(new NoSuchFileException("File not found"));
                
                storageScheduler.performPersistToDisk();
                
                // Act
                Map<String, Object> metrics = storageScheduler.getErrorMetrics();
                
                // Assert
                assertEquals(4, metrics.get("totalErrors")); // 3 transient + 1 persistent
                assertEquals(3, metrics.get("transientErrors"));
                assertEquals(1, metrics.get("persistentErrors"));
                
                @SuppressWarnings("unchecked")
                Map<String, Integer> errorCounts = (Map<String, Integer>) metrics.get("errorTypeCount");
                assertTrue(errorCounts.containsKey("IOException"));
                assertTrue(errorCounts.containsKey("NoSuchFileException"));
                
                // Reset and verify
                storageScheduler.resetErrorMetrics();
                metrics = storageScheduler.getErrorMetrics();
                assertEquals(0, metrics.get("totalErrors"));
            }
        }
        
        @Test
        @DisplayName("Should update health status on critical errors")
        void testHealthStatusUpdate() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            // Initially healthy
            assertTrue(storageScheduler.isHealthy(), "Should start in healthy state");
            
            // Simulate OutOfMemoryError
            when(mockDatabaseObject.returnDBObytes()).thenThrow(new OutOfMemoryError("Simulated OOM"));
            
            // Act & Assert
            assertThrows(OutOfMemoryError.class, () -> storageScheduler.performPersistToDisk());
            assertFalse(storageScheduler.isHealthy(), "Should be unhealthy after critical error");
        }
    }

    /**
     * Tests for logging improvements
     */
    @Nested
    @DisplayName("Logging Improvement Tests")
    class LoggingImprovementTests {
        
        @Test
        @DisplayName("Should log detailed error information")
        void testDetailedErrorLogging() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Simulate specific error
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenThrow(new IOException("Disk full"));
                
                // Act
                storageScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(logHandler.hasLogContaining("Persisting to disk at"), "Should log operation start");
                assertTrue(logHandler.hasLogContaining("Error during DATABASE_METADATA"), "Should log error context");
                assertTrue(logHandler.hasLogContaining("Disk full"), "Should log error message");
            }
        }
    }
    
    /**
     * Custom log handler to capture log messages for testing
     */
    private static class TestLogHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();
        
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }
        
        @Override
        public void flush() {
            // No-op
        }
        
        @Override
        public void close() throws SecurityException {
            records.clear();
        }
        
        public boolean hasLogContaining(String text) {
            for (LogRecord record : records) {
                if (record.getMessage().contains(text)) {
                    return true;
                }
            }
            return false;
        }
    }
}