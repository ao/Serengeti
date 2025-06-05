package com.ataiva.serengeti.integration;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageScheduler;
import com.ataiva.serengeti.utils.TestBase;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for the enhanced error handling in the StorageScheduler component.
 * 
 * These tests verify the error handling behavior in a more realistic environment,
 * using the error scenarios defined in the test fixtures.
 */
@DisplayName("StorageScheduler Error Handling Integration Tests")
@Tag("integration")
@Tag("storage")
class StorageSchedulerErrorHandlingIntegrationTest extends TestBase {

    private StorageScheduler storageScheduler;
    private Storage storage;
    private Map<String, DatabaseObject> originalDatabases;
    private Map<String, TableStorageObject> originalTableStorageObjects;
    private Map<String, TableReplicaObject> originalTableReplicaObjects;
    private boolean originalNetworkOnline;
    private boolean originalRunning;
    private String originalDataPath;
    private ByteArrayOutputStream outputStream;
    private PrintStream originalOut;
    private TestLogHandler logHandler;
    private JSONObject errorScenarios;
    
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp(); // Initialize TestBase components
        
        // Load error scenarios from fixture
        String scenariosJson = new String(Files.readAllBytes(
            Paths.get("src/test/resources/fixtures/storage-scheduler/error_scenarios.json")));
        errorScenarios = new JSONObject(scenariosJson);
        
        storageScheduler = new StorageScheduler();
        storage = new Storage();
        
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
        
        // Create test database directory
        Files.createDirectories(Paths.get(testDataPath));
        
        // Capture console output for testing
        outputStream = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(outputStream));
        
        // Setup log handler to capture logs
        logHandler = new TestLogHandler();
        Logger logger = Logger.getLogger(StorageScheduler.class.getName());
        logger.addHandler(logHandler);
        logger.setLevel(Level.ALL);
        
        // Reset error metrics
        storageScheduler.resetErrorMetrics();
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
        
        super.tearDown(); // Clean up TestBase resources
    }

    /**
     * Creates test data for a database with tables
     */
    private void createTestDatabase(String dbName, List<String> tableNames) throws IOException {
        // Create database object
        DatabaseObject dbo = new DatabaseObject();
        dbo.createNew(dbName, tableNames);
        Storage.databases.put(dbName, dbo);
        
        // Create database directory
        Files.createDirectories(Paths.get(testDataPath + dbName));
        
        // Create tables
        for (String tableName : tableNames) {
            // Create table directory
            Files.createDirectories(Paths.get(testDataPath + dbName + "/" + tableName));
            
            // Create table storage object
            TableStorageObject tso = new TableStorageObject(dbName, tableName);
            tso.insert(new JSONObject().put("key1", "value1"));
            tso.insert(new JSONObject().put("key2", "value2"));
            Storage.tableStorageObjects.put(dbName + "#" + tableName, tso);
            
            // Create table replica object
            TableReplicaObject tro = new TableReplicaObject(dbName, tableName);
            tro.insertOrReplace("row1", new JSONObject().put("primary", "node1").put("secondary", "node2"));
            Storage.tableReplicaObjects.put(dbName + "#" + tableName, tro);
        }
    }

    /**
     * Test that verifies basic persistence works correctly
     */
    @Test
    @DisplayName("Should persist database successfully in normal conditions")
    void testSuccessfulPersistence() throws Exception {
        // Arrange
        String dbName = "testdb";
        List<String> tableNames = Arrays.asList("table1", "table2");
        createTestDatabase(dbName, tableNames);
        
        // Act
        boolean result = storageScheduler.performPersistToDisk();
        
        // Assert
        assertTrue(result, "Persistence should succeed under normal conditions");
        
        // Verify database metadata file was created
        Path dbMetaFile = Paths.get(testDataPath + dbName + Globals.meta_extention);
        assertTrue(Files.exists(dbMetaFile), "Database metadata file should exist");
        
        // Verify table files were created
        for (String tableName : tableNames) {
            Path tableStorageFile = Paths.get(testDataPath + dbName + "/" + tableName + "/" + Globals.storage_filename);
            Path tableReplicaFile = Paths.get(testDataPath + dbName + "/" + tableName + "/" + Globals.replica_filename);
            
            assertTrue(Files.exists(tableStorageFile), "Table storage file should exist for " + tableName);
            assertTrue(Files.exists(tableReplicaFile), "Table replica file should exist for " + tableName);
        }
        
        // Verify error metrics
        Map<String, Object> metrics = storageScheduler.getErrorMetrics();
        assertEquals(0, metrics.get("totalErrors"), "Should have no errors");
    }

    /**
     * Test that verifies the system can handle disk write failures
     */
    @Test
    @DisplayName("Should handle disk write failures gracefully")
    void testDiskWriteFailure() throws Exception {
        // Arrange
        String dbName = "testdb";
        List<String> tableNames = Arrays.asList("table1");
        createTestDatabase(dbName, tableNames);
        
        // Make the directory read-only to simulate permission error
        File tableDir = new File(testDataPath + dbName + "/table1");
        tableDir.setWritable(false);
        
        // Act
        boolean result = storageScheduler.performPersistToDisk();
        
        // Assert
        assertFalse(result, "Persistence should fail with permission error");
        
        // Verify error metrics
        Map<String, Object> metrics = storageScheduler.getErrorMetrics();
        assertTrue((int)metrics.get("totalErrors") > 0, "Should record errors");
        
        // Restore permissions for cleanup
        tableDir.setWritable(true);
    }

    /**
     * Parameterized test that verifies handling of different error scenarios
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("errorScenarioProvider")
    @DisplayName("Should handle different error scenarios appropriately")
    void testErrorScenarios(String scenarioName, String errorType, boolean isTransient, 
                           int expectedRetryCount, String expectedOutcome) throws Exception {
        // Arrange
        String dbName = "errordb";
        List<String> tableNames = Arrays.asList("errortable");
        createTestDatabase(dbName, tableNames);
        
        // Create a spy on the StorageScheduler to track method calls
        StorageScheduler spyScheduler = spy(storageScheduler);
        
        // Configure the spy based on the scenario
        if (isTransient) {
            // For transient errors, we want to simulate retries
            doReturn(true).when(spyScheduler).isHealthy();
            
            // The first few calls will throw exceptions, then succeed
            if ("success_after_retry".equals(expectedOutcome)) {
                doThrow(Class.forName(errorType).asSubclass(Throwable.class))
                    .doThrow(Class.forName(errorType).asSubclass(Throwable.class))
                    .doReturn(true)
                    .when(spyScheduler).performPersistToDisk();
            } else {
                // Always fail for failure_after_retry
                doThrow(Class.forName(errorType).asSubclass(Throwable.class))
                    .when(spyScheduler).performPersistToDisk();
            }
        } else {
            // For persistent errors, we don't expect retries
            if ("critical_failure".equals(expectedOutcome)) {
                doThrow(Class.forName(errorType).asSubclass(Throwable.class))
                    .when(spyScheduler).performPersistToDisk();
                doReturn(false).when(spyScheduler).isHealthy();
            } else {
                doThrow(Class.forName(errorType).asSubclass(Throwable.class))
                    .when(spyScheduler).performPersistToDisk();
            }
        }
        
        // Act & Assert
        if ("critical_failure".equals(expectedOutcome)) {
            assertThrows(Throwable.class, () -> {
                spyScheduler.performPersistToDisk();
            });
            assertFalse(spyScheduler.isHealthy(), "Health status should be unhealthy after critical failure");
        } else {
            try {
                spyScheduler.performPersistToDisk();
            } catch (Exception e) {
                // Expected for some scenarios
                assertEquals(errorType, e.getClass().getName(), "Should throw expected exception type");
            }
        }
        
        // Verify error metrics were recorded
        Map<String, Object> metrics = spyScheduler.getErrorMetrics();
        assertTrue((int)metrics.get("totalErrors") > 0, "Should record errors");
    }

    /**
     * Test that verifies concurrent persistence operations are handled correctly
     */
    @Test
    @DisplayName("Should handle concurrent persistence operations correctly")
    void testConcurrentPersistence() throws Exception {
        // Arrange
        String dbName = "concurrentdb";
        List<String> tableNames = Arrays.asList("table1", "table2");
        createTestDatabase(dbName, tableNames);
        
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(threadCount);
        AtomicBoolean[] results = new AtomicBoolean[threadCount];
        for (int i = 0; i < threadCount; i++) {
            results[i] = new AtomicBoolean(false);
        }
        
        // Create multiple threads to attempt persistence simultaneously
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for signal to start
                    results[index].set(storageScheduler.performPersistToDisk());
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    completionLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to complete
        completionLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Assert
        int successCount = 0;
        for (AtomicBoolean result : results) {
            if (result.get()) {
                successCount++;
            }
        }
        
        // Only one thread should succeed due to the running flag
        assertEquals(1, successCount, "Only one thread should succeed in persisting");
        
        // Verify files were created
        Path dbMetaFile = Paths.get(testDataPath + dbName + Globals.meta_extention);
        assertTrue(Files.exists(dbMetaFile), "Database metadata file should exist");
    }

    /**
     * Provides error scenarios from the test fixture
     */
    static Stream<Arguments> errorScenarioProvider() {
        try {
            String scenariosJson = new String(Files.readAllBytes(
                Paths.get("src/test/resources/fixtures/storage-scheduler/error_scenarios.json")));
            JSONObject scenarios = new JSONObject(scenariosJson);
            JSONArray scenarioArray = scenarios.getJSONArray("scenarios");
            
            List<Arguments> arguments = new ArrayList<>();
            for (int i = 0; i < scenarioArray.length(); i++) {
                JSONObject scenario = scenarioArray.getJSONObject(i);
                arguments.add(Arguments.of(
                    scenario.getString("name"),
                    scenario.getString("errorType"),
                    scenario.getBoolean("isTransient"),
                    scenario.getInt("retryCount"),
                    scenario.getString("expectedOutcome")
                ));
            }
            
            return arguments.stream();
        } catch (Exception e) {
            e.printStackTrace();
            return Stream.empty();
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