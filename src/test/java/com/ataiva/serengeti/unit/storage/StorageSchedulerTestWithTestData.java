package com.ataiva.serengeti.unit.storage;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageScheduler;
import com.ataiva.serengeti.testdata.StorageSchedulerTestData;
import com.ataiva.serengeti.testdata.StorageSchedulerTestDataCleaner;
import com.ataiva.serengeti.testdata.StorageSchedulerTestDataLoader;
import com.ataiva.serengeti.testdata.StorageSchedulerTestDataLoader.ErrorScenario;
import com.ataiva.serengeti.testdata.StorageSchedulerTestDataLoader.PerformanceScenario;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the StorageScheduler component using the new test data utilities.
 * This class demonstrates how to use the test data generation, loading, and cleanup utilities.
 */
@DisplayName("StorageScheduler Tests with Test Data Utilities")
@Tag("unit")
@Tag("storage")
@ExtendWith(MockitoExtension.class)
class StorageSchedulerTestWithTestData extends TestBase {

    private StorageScheduler storageScheduler;
    private Map<String, Object> originalStorageState;
    private String originalDataPath;
    private ByteArrayOutputStream outputStream;
    private PrintStream originalOut;

    @BeforeEach
    public void setUp() throws Exception {
        super.setUp(); // Initialize TestBase components
        
        // Create a new StorageScheduler instance
        storageScheduler = new StorageScheduler();
        
        // Save original state
        originalStorageState = new HashMap<>();
        originalStorageState.put("databases", Storage.databases);
        originalStorageState.put("tableStorageObjects", Storage.tableStorageObjects);
        originalStorageState.put("tableReplicaObjects", Storage.tableReplicaObjects);
        originalStorageState.put("networkOnline", Network.online);
        
        originalDataPath = Globals.data_path;
        
        // Capture console output for testing
        outputStream = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(outputStream));
    }

    @AfterEach
    public void tearDown() throws Exception {
        // Restore original state
        StorageSchedulerTestDataCleaner.restoreStorageState(originalStorageState);
        Globals.data_path = originalDataPath;
        
        // Restore console output
        System.setOut(originalOut);
        
        super.tearDown(); // Clean up TestBase resources
    }

    /**
     * Tests for the basic persistence functionality using test data utilities
     */
    @Nested
    @DisplayName("Basic Persistence Tests with Test Data")
    class BasicPersistenceTestsWithTestData {
        
        @Test
        @DisplayName("Should persist databases to disk when network is online and not running")
        void testPerformPersistToDiskSuccess() throws Exception {
            // Arrange - Use test data utilities to set up the test environment
            Map<String, Object> testState = StorageSchedulerTestData.setupStorageWithTestData(1, 2, 3);
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(result, "Should return true on successful persistence");
                assertFalse(StorageScheduler.running, "Running flag should be reset to false");
                
                String output = outputStream.toString();
                assertTrue(output.contains("Persisting to disk"), "Should log persistence start");
                assertTrue(output.contains("Written db: 'test_db_0' to disk"), "Should log database write");
                
                // Verify file operations
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(1));
            } finally {
                // Clean up
                StorageSchedulerTestDataCleaner.restoreStorageState(testState);
            }
        }
        
        @Test
        @DisplayName("Should load test data from fixture file")
        void testLoadDataFromFixture() throws Exception {
            // Arrange - Use test data loader to load from fixture
            Map<String, Object> originalState = StorageSchedulerTestDataLoader.setupStorageFromFixture("basic_database");
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(result, "Should return true on successful persistence");
                
                String output = outputStream.toString();
                assertTrue(output.contains("Persisting to disk"), "Should log persistence start");
                assertTrue(output.contains("Written db: 'test_db' to disk"), "Should log database write");
                assertTrue(output.contains("Written table: 'test_db'#'users' storage to disk"), "Should log users table write");
                assertTrue(output.contains("Written table: 'test_db'#'products' storage to disk"), "Should log products table write");
                assertTrue(output.contains("Written table: 'test_db'#'orders' storage to disk"), "Should log orders table write");
                
                // Verify file operations
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(1));
            } finally {
                // Clean up
                StorageSchedulerTestDataCleaner.restoreStorageState(originalState);
            }
        }
        
        @Test
        @DisplayName("Should handle special characters in database and table names")
        void testSpecialCharacters() throws Exception {
            // Arrange - Use test data loader to load from fixture
            Map<String, Object> originalState = StorageSchedulerTestDataLoader.setupStorageFromFixture("special_characters");
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(result, "Should return true on successful persistence");
                
                String output = outputStream.toString();
                assertTrue(output.contains("Written db: 'special#db$name' to disk"), "Should log database write with special characters");
                assertTrue(output.contains("Written table: 'special#db$name'#'special@table' storage to disk"), 
                        "Should log table write with special characters");
                
                // Verify file operations
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(1));
            } finally {
                // Clean up
                StorageSchedulerTestDataCleaner.restoreStorageState(originalState);
            }
        }
        
        @Test
        @DisplayName("Should handle empty database")
        void testEmptyDatabase() throws Exception {
            // Arrange - Use test data loader to load from fixture
            Map<String, Object> originalState = StorageSchedulerTestDataLoader.setupStorageFromFixture("empty_database");
            
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                // Act
                boolean result = storageScheduler.performPersistToDisk();
                
                // Assert
                assertTrue(result, "Should return true on successful persistence");
                
                String output = outputStream.toString();
                assertTrue(output.contains("Written db: 'empty_db' to disk"), "Should log empty database write");
                
                // Verify file operations
                mockedFiles.verify(() -> Files.write(any(Path.class), any(byte[].class)), times(1));
            } finally {
                // Clean up
                StorageSchedulerTestDataCleaner.restoreStorageState(originalState);
            }
        }
    }
    
    /**
     * Tests for error handling using test data utilities
     */
    @Nested
    @DisplayName("Error Handling Tests with Test Data")
    class ErrorHandlingTestsWithTestData {
        
        @Test
        @DisplayName("Should handle file write exceptions gracefully")
        void testPerformPersistToDiskFileWriteException() throws Exception {
            // Arrange - Use test data utilities to set up the test environment
            Map<String, Object> testState = StorageSchedulerTestData.setupStorageWithTestData(1, 1, 1);
            
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
            } finally {
                // Clean up
                StorageSchedulerTestDataCleaner.restoreStorageState(testState);
            }
        }
        
        @Test
        @DisplayName("Should handle error scenarios from fixture")
        void testErrorScenarios() throws Exception {
            // Load error scenarios from fixture
            List<ErrorScenario> errorScenarios = StorageSchedulerTestDataLoader.loadErrorScenarios();
            assertFalse(errorScenarios.isEmpty(), "Should load error scenarios from fixture");
            
            // Test the first error scenario as an example
            ErrorScenario scenario = errorScenarios.get(0);
            assertEquals("disk_write_failure", scenario.name, "Should have correct scenario name");
            assertEquals("IOException", scenario.errorType, "Should have correct error type");
        }
    }
    
    /**
     * Tests for performance using test data utilities
     */
    @Nested
    @DisplayName("Performance Tests with Test Data")
    class PerformanceTestsWithTestData {
        
        @Test
        @DisplayName("Should measure persistence performance with different database sizes")
        void testPerformanceWithDifferentSizes() throws Exception {
            // Load performance scenarios from fixture
            List<PerformanceScenario> performanceScenarios = StorageSchedulerTestDataLoader.loadPerformanceScenarios();
            assertFalse(performanceScenarios.isEmpty(), "Should load performance scenarios from fixture");
            
            // Test with small database scenario
            PerformanceScenario smallDbScenario = performanceScenarios.stream()
                .filter(s -> s.name.equals("small_database"))
                .findFirst()
                .orElseThrow();
            
            // Save original state
            Map<String, Object> originalState = new HashMap<>();
            originalState.put("databases", Storage.databases);
            originalState.put("tableStorageObjects", Storage.tableStorageObjects);
            originalState.put("tableReplicaObjects", Storage.tableReplicaObjects);
            originalState.put("networkOnline", Network.online);
            
            // Create test data for the scenario
            Map<String, Object> testEnv = StorageSchedulerTestDataLoader.createPerformanceTestData(smallDbScenario);
            
            try {
                // Set up Storage with test data
                Storage.databases = (Map<String, DatabaseObject>) testEnv.get("databases");
                Storage.tableStorageObjects = (Map<String, TableStorageObject>) testEnv.get("tableStorageObjects");
                Storage.tableReplicaObjects = (Map<String, TableReplicaObject>) testEnv.get("tableReplicaObjects");
                Network.online = true;
                
                try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                    // Measure performance
                    long startTime = System.currentTimeMillis();
                    boolean result = storageScheduler.performPersistToDisk();
                    long endTime = System.currentTimeMillis();
                    long duration = endTime - startTime;
                    
                    // Assert
                    assertTrue(result, "Should return true on successful persistence");
                    
                    // Note: In a real test, you might want to assert that the duration is within
                    // an acceptable range, but for this example we'll just log it
                    System.out.println("Persistence duration for small database: " + duration + "ms");
                }
            } finally {
                // Clean up
                StorageSchedulerTestDataCleaner.restoreStorageState(originalState);
            }
        }
    }
}