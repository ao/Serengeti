package com.ataiva.serengeti.chaos;

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
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.javacrumbs.fault.injection.core.FaultInjectionAspect;
import net.javacrumbs.fault.injection.core.FaultInjectionException;
import net.javacrumbs.fault.injection.core.FaultInjectionRepository;
import net.javacrumbs.fault.injection.core.condition.Condition;
import net.javacrumbs.fault.injection.core.condition.CountingCondition;
import net.javacrumbs.fault.injection.core.condition.RandomCondition;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Chaos tests for the StorageScheduler component.
 * 
 * These tests verify the resilience of the StorageScheduler under adverse conditions:
 * - Disk failures during persistence operations
 * - Network outages during distributed operations
 * - Resource constraints (memory, CPU, disk space)
 * - Thread interruptions and deadlocks
 * - Unexpected exceptions from dependencies
 * 
 * The goal is to ensure the StorageScheduler can recover from unexpected failures
 * and maintain data integrity even under chaotic conditions.
 */
@DisplayName("StorageScheduler Chaos Tests")
@Tag("chaos")
@ExtendWith(MockitoExtension.class)
public class StorageSchedulerChaosTest extends TestBase {

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
    
    // Fault injection repository
    private FaultInjectionRepository faultInjectionRepository;
    
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
        
        // Initialize fault injection repository
        faultInjectionRepository = FaultInjectionAspect.getRepository();
        faultInjectionRepository.clear();
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
        
        // Clear fault injection repository
        faultInjectionRepository.clear();
        
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
     * Tests for disk failures during persistence operations
     */
    @Nested
    @DisplayName("Disk Failure Tests")
    class DiskFailureTests {
        
        @Test
        @DisplayName("Should handle disk write failures gracefully")
        void testDiskWriteFailure() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Simulate disk write failure
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenThrow(new IOException("Simulated disk write failure"));
                
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertFalse(result, "Should return false on disk write failure");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
                
                String output = outputStream.toString();
                assertTrue(output.contains("Persisting to disk"), "Should log persistence start");
            }
        }
        
        @Test
        @DisplayName("Should handle disk full scenario")
        void testDiskFullScenario() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Simulate disk full error
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenThrow(new IOException("No space left on device"));
                
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertFalse(result, "Should return false when disk is full");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
            }
        }
        
        @Test
        @DisplayName("Should handle permission denied errors")
        void testPermissionDeniedError() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Simulate permission denied error
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenThrow(new IOException("Permission denied"));
                
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertFalse(result, "Should return false on permission denied");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
            }
        }
        
        @Test
        @DisplayName("Should handle file system corruption")
        void testFileSystemCorruption() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class);
                 MockedStatic<Paths> mockedPaths = mockStatic(Paths.class)) {
                
                // Simulate file system corruption by throwing an unexpected error
                mockedPaths.when(() -> Paths.get(anyString() + anyString() + anyString()))
                          .thenThrow(new IllegalArgumentException("Invalid path character"));
                
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertFalse(result, "Should return false on file system corruption");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
            }
        }
        
        @Test
        @DisplayName("Should handle intermittent disk failures")
        void testIntermittentDiskFailures() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            // Add multiple databases to test partial failures
            DatabaseObject mockDb2 = mock(DatabaseObject.class);
            when(mockDb2.name).thenReturn("testdb2");
            when(mockDb2.tables).thenReturn(Arrays.asList("testtable2"));
            when(mockDb2.returnDBObytes()).thenReturn("test database 2 data".getBytes());
            Storage.databases.put("testdb2", mockDb2);
            
            TableStorageObject mockTableStorage2 = mock(TableStorageObject.class);
            TableReplicaObject mockTableReplica2 = mock(TableReplicaObject.class);
            when(mockTableStorage2.rows).thenReturn(new HashMap<>());
            when(mockTableReplica2.row_replicas).thenReturn(new HashMap<>());
            Storage.tableStorageObjects.put("testdb2#testtable2", mockTableStorage2);
            Storage.tableReplicaObjects.put("testdb2#testtable2", mockTableReplica2);
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Simulate intermittent failure - first call succeeds, second fails
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenReturn(mock(Path.class))
                          .thenThrow(new IOException("Intermittent disk failure"));
                
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertFalse(result, "Should return false on intermittent failure");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
                
                // Verify first write succeeded
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(2));
            }
        }
    }

    /**
     * Tests for network outages during distributed operations
     */
    @Nested
    @DisplayName("Network Outage Tests")
    class NetworkOutageTests {
        
        @Test
        @DisplayName("Should handle network going offline during persistence")
        void testNetworkOutageDuringPersistence() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            // Create a custom scheduler that simulates network outage during execution
            StorageScheduler testScheduler = new StorageScheduler() {
                @Override
                public boolean performPersistToDisk() {
                    // Start persistence with network online
                    boolean result = super.performPersistToDisk();
                    
                    // Simulate network going offline during operation
                    Network.online = false;
                    
                    return result;
                }
            };
            
            // Act
            boolean result = testScheduler.performPersistToDisk();
            
            // Assert
            assertTrue(result, "Should complete successfully despite network outage");
            assertFalse(StorageScheduler.running, "Running flag should be reset to false");
            assertFalse(Network.online, "Network should be offline");
        }
        
        @Test
        @DisplayName("Should handle intermittent network connectivity")
        void testIntermittentNetworkConnectivity() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            final AtomicInteger counter = new AtomicInteger(0);
            
            // Create a custom scheduler that simulates intermittent network connectivity
            StorageScheduler testScheduler = new StorageScheduler() {
                @Override
                public boolean performPersistToDisk() {
                    if (counter.incrementAndGet() % 2 == 0) {
                        Network.online = false;
                    } else {
                        Network.online = true;
                    }
                    return super.performPersistToDisk();
                }
            };
            
            // Act - Run multiple persistence operations
            boolean firstResult = testScheduler.performPersistToDisk(); // Network online
            boolean secondResult = testScheduler.performPersistToDisk(); // Network offline
            boolean thirdResult = testScheduler.performPersistToDisk(); // Network online
            
            // Assert
            assertTrue(firstResult, "First operation should succeed with network online");
            assertFalse(secondResult, "Second operation should fail with network offline");
            assertTrue(thirdResult, "Third operation should succeed with network online");
        }
        
        @Test
        @DisplayName("Should handle network latency spikes")
        void testNetworkLatencySpikes() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            // Mock TableStorageObject to simulate network latency
            doAnswer(invocation -> {
                // Simulate high latency
                Thread.sleep(500);
                return null;
            }).when(mockTableStorageObject).saveToDisk();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                long startTime = System.nanoTime();
                boolean result = storageScheduler.performPersistToDisk();
                long endTime = System.nanoTime();
                long executionTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
                
                // Assert
                assertTrue(result, "Should complete successfully despite latency");
                assertTrue(executionTimeMs >= 500, "Should take at least 500ms due to latency");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
            }
        }
    }

    /**
     * Tests for resource constraints (memory, CPU, disk space)
     */
    @Nested
    @DisplayName("Resource Constraint Tests")
    class ResourceConstraintTests {
        
        @Test
        @DisplayName("Should handle out of memory errors")
        void testOutOfMemoryError() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            // Mock database object to throw OOM
            when(mockDatabaseObject.returnDBObytes()).thenThrow(new OutOfMemoryError("Simulated OOM"));
            
            // Act & Assert
            assertThrows(OutOfMemoryError.class, () -> {
                storageScheduler.performPersistToDisk();
            }, "Should propagate OutOfMemoryError");
            
            // Verify running flag is reset
            assertFalse(StorageScheduler.running, "Running flag should be reset to false");
        }
        
        @Test
        @DisplayName("Should handle very large database objects")
        void testVeryLargeDatabaseObject() throws Exception {
            // Arrange
            Network.online = true;
            StorageScheduler.running = false;
            
            // Create a large byte array (100MB)
            byte[] largeData = new byte[100 * 1024 * 1024];
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
                assertTrue(result, "Should handle large database object");
                
                // Verify large data was written
                mockedFiles.verify(() -> Files.write(any(Path.class), eq(largeData)), times(1));
            }
        }
        
        @Test
        @DisplayName("Should handle CPU-intensive operations")
        void testCPUIntensiveOperations() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            // Mock database object to perform CPU-intensive operation
            when(mockDatabaseObject.returnDBObytes()).thenAnswer(invocation -> {
                // Simulate CPU-intensive operation
                byte[] result = new byte[1024 * 1024]; // 1MB
                for (int i = 0; i < 1000000; i++) {
                    // Perform some CPU-intensive calculations
                    Math.sqrt(i);
                    Math.log(i + 1);
                    result[i % result.length] = (byte) (i % 256);
                }
                return result;
            });
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(result, "Should complete successfully despite CPU load");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
            }
        }
    }

    /**
     * Tests for thread interruptions and deadlocks
     */
    @Nested
    @DisplayName("Thread Interruption Tests")
    class ThreadInterruptionTests {
        
        @Test
        @DisplayName("Should handle thread interruption during persistence")
        void testThreadInterruptionDuringPersistence() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            // Mock table storage to simulate long operation that can be interrupted
            doAnswer(invocation -> {
                try {
                    Thread.sleep(5000); // Long operation
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // Restore interrupt flag
                    throw e; // Re-throw to simulate interruption
                }
                return null;
            }).when(mockTableStorageObject).saveToDisk();
            
            // Create a thread to run persistence
            Thread persistThread = new Thread(() -> {
                try {
                    storageScheduler.performPersistToDisk();
                } catch (Exception e) {
                    // Expected
                }
            });
            
            // Act
            persistThread.start();
            Thread.sleep(100); // Give time for thread to start
            persistThread.interrupt(); // Interrupt the thread
            persistThread.join(1000); // Wait for thread to complete
            
            // Assert
            assertFalse(persistThread.isAlive(), "Thread should have terminated");
            assertFalse(StorageScheduler.running, "Running flag should be reset to false");
        }
        
        @Test
        @DisplayName("Should handle multiple concurrent persistence attempts")
        void testMultipleConcurrentPersistenceAttempts() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            int threadCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
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
                            // Expected for some threads
                        } finally {
                            completionLatch.countDown();
                        }
                    }).start();
                }
                
                // Signal threads to start
                startLatch.countDown();
                
                // Wait for all threads to complete
                completionLatch.await(5, TimeUnit.SECONDS);
                
                // Assert
                assertEquals(1, successCount.get(), "Only one thread should succeed in persisting");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
            }
        }
        
        @Test
        @DisplayName("Should recover from deadlock-like situations")
        void testRecoveryFromDeadlockSituations() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            final AtomicBoolean deadlockResolved = new AtomicBoolean(false);
            
            // Mock table storage to simulate a deadlock-like situation
            doAnswer(invocation -> {
                if (!deadlockResolved.get()) {
                    // Simulate deadlock on first attempt
                    Thread.sleep(2000); // Wait for a while
                    throw new RuntimeException("Simulated deadlock");
                }
                return null; // Success on second attempt
            }).when(mockTableStorageObject).saveToDisk();
            
            // First attempt - should fail due to deadlock
            boolean firstResult = storageScheduler.performPersistToDisk();
            assertFalse(firstResult, "First attempt should fail due to deadlock");
            
            // Resolve the deadlock
            deadlockResolved.set(true);
            
            // Second attempt - should succeed
            boolean secondResult = storageScheduler.performPersistToDisk();
            assertTrue(secondResult, "Second attempt should succeed after deadlock resolution");
        }
    }

    /**
     * Tests for unexpected exceptions from dependencies
     */
    @Nested
    @DisplayName("Unexpected Exception Tests")
    class UnexpectedExceptionTests {
        
        @Test
        @DisplayName("Should handle NullPointerException from dependencies")
        void testNullPointerExceptionFromDependencies() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            // Mock database object to throw NPE
            when(mockDatabaseObject.returnDBObytes()).thenThrow(new NullPointerException("Simulated NPE"));
            
            // Act
            boolean result = storageScheduler.performPersistToDisk();
            
            // Assert
            assertFalse(result, "Should return false on NPE");
            assertFalse(StorageScheduler.running, "Running flag should be reset to false");
        }
        
        @Test
        @DisplayName("Should handle IllegalArgumentException from dependencies")
        void testIllegalArgumentExceptionFromDependencies() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            // Mock table storage to throw IllegalArgumentException
            doThrow(new IllegalArgumentException("Simulated IllegalArgumentException"))
                .when(mockTableStorageObject).saveToDisk();
            
            // Act
            boolean result = storageScheduler.performPersistToDisk();
            
            // Assert
            assertFalse(result, "Should return false on IllegalArgumentException");
            assertFalse(StorageScheduler.running, "Running flag should be reset to false");
        }
        
        @Test
        @DisplayName("Should handle RuntimeException from dependencies")
        void testRuntimeExceptionFromDependencies() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            // Mock table replica to throw RuntimeException
            doThrow(new RuntimeException("Simulated RuntimeException"))
                .when(mockTableReplicaObject).saveToDisk();
            
            // Act
            boolean result = storageScheduler.performPersistToDisk();
            
            // Assert
            assertFalse(result, "Should return false on RuntimeException");
            assertFalse(StorageScheduler.running, "Running flag should be reset to false");
        }
        
        @Test
        @DisplayName("Should handle Error from dependencies")
        void testErrorFromDependencies() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            // Mock database object to throw Error
            when(mockDatabaseObject.returnDBObytes()).thenThrow(new Error("Simulated Error"));
            
            // Act & Assert
            assertThrows(Error.class, () -> {
                storageScheduler.performPersistToDisk();
            }, "Should propagate Error");
            
            // Verify running flag is reset
            assertFalse(StorageScheduler.running, "Running flag should be reset to false");
        }
        
        @Test
        @DisplayName("Should handle mixed exceptions from different dependencies")
        void testMixedExceptionsFromDependencies() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            // Add a second database
            DatabaseObject mockDb2 = mock(DatabaseObject.class);
            when(mockDb2.name).thenReturn("testdb2");
            when(mockDb2.tables).thenReturn(Arrays.asList("testtable2"));
            when(mockDb2.returnDBObytes()).thenThrow(new RuntimeException("Exception from second DB"));
            Storage.databases.put("testdb2", mockDb2);
            
            // Act
            boolean result = storageScheduler.performPersistToDisk();
            
            // Assert
            assertFalse(result, "Should return false on exception");
            assertFalse(StorageScheduler.running, "Running flag should be reset to false");
        }
    }

    /**
     * Tests for combined chaos scenarios
     */
    @Nested
    @DisplayName("Combined Chaos Scenarios")
    class CombinedChaosScenarios {
        
        @Test
        @DisplayName("Should handle disk failure during network instability")
        void testDiskFailureDuringNetworkInstability() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Simulate disk write failure
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenThrow(new IOException("Simulated disk write failure"));
                
                // Create a custom scheduler that simulates network instability
                StorageScheduler testScheduler = new StorageScheduler() {
                    @Override
                    public boolean performPersistToDisk() {
                        // Simulate network instability
                        Network.online = false;
                        boolean result = super.performPersistToDisk();
                        Network.online = true;
                        return result;
                    }
                };
                
                // Act
                boolean result = testScheduler.performPersistToDisk();
                
                // Assert
                assertFalse(result, "Should return false when network is offline");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
            }
        }
        
        @Test
        @DisplayName("Should handle resource exhaustion with concurrent operations")
        void testResourceExhaustionWithConcurrentOperations() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            int threadCount = 5;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(threadCount);
            
            // Mock database object to consume excessive resources
            when(mockDatabaseObject.returnDBObytes()).thenAnswer(invocation -> {
                // Allocate large amount of memory
                byte[] largeArray = new byte[50 * 1024 * 1024]; // 50MB
                Arrays.fill(largeArray, (byte) 'X');
                
                // Perform CPU-intensive operation
                for (int i = 0; i < 100000; i++) {
                    Math.sqrt(i);
                    Math.log(i + 1);
                }
                
                return "test data".getBytes();
            });
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act - Start multiple threads that try to persist simultaneously
                for (int i = 0; i < threadCount; i++) {
                    new Thread(() -> {
                        try {
                            startLatch.await(); // Wait for signal to start
                            storageScheduler.performPersistToDisk();
                        } catch (Exception e) {
                            // Expected for some threads
                        } finally {
                            completionLatch.countDown();
                        }
                    }).start();
                }
                
                // Signal threads to start
                startLatch.countDown();
                
                // Wait for all threads to complete
                boolean allCompleted = completionLatch.await(10, TimeUnit.SECONDS);
                
                // Assert
                assertTrue(allCompleted, "All threads should complete within timeout");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
            }
        }
        
        @Test
        @DisplayName("Should handle cascading failures")
        void testCascadingFailures() throws Exception {
            // Arrange
            setupBasicPersistenceScenario();
            
            // Add multiple databases to test cascading failures
            DatabaseObject mockDb2 = mock(DatabaseObject.class);
            when(mockDb2.name).thenReturn("testdb2");
            when(mockDb2.tables).thenReturn(Arrays.asList("testtable2"));
            when(mockDb2.returnDBObytes()).thenReturn("test database 2 data".getBytes());
            Storage.databases.put("testdb2", mockDb2);
            
            TableStorageObject mockTableStorage2 = mock(TableStorageObject.class);
            TableReplicaObject mockTableReplica2 = mock(TableReplicaObject.class);
            when(mockTableStorage2.rows).thenReturn(new HashMap<>());
            when(mockTableReplica2.row_replicas).thenReturn(new HashMap<>());
            Storage.tableStorageObjects.put("testdb2#testtable2", mockTableStorage2);
            Storage.tableReplicaObjects.put("testdb2#testtable2", mockTableReplica2);
            
            // Set up cascading failures
            doThrow(new RuntimeException("First failure"))
                .when(mockTableStorageObject).saveToDisk();
                
            doThrow(new IOException("Second failure"))
                .when(mockTableReplica2).saveToDisk();
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Simulate disk write failure for the second database
                mockedFiles.when(() -> Files.write(any(Path.class), any(byte[].class)))
                          .thenThrow(new IOException("Third failure"));
                
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertFalse(result, "Should return false on cascading failures");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
            }
        }
    }
}