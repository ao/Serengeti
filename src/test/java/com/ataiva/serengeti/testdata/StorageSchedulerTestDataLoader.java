package com.ataiva.serengeti.testdata;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;
import com.ataiva.serengeti.storage.Storage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Utility class for loading test data from fixtures for StorageScheduler tests.
 */
public class StorageSchedulerTestDataLoader {

    private static final String FIXTURES_BASE_PATH = "src/test/resources/fixtures/storage-scheduler/";

    /**
     * Loads a JSON fixture file.
     *
     * @param fixtureName The name of the fixture file (without .json extension)
     * @return The JSON content as a string
     * @throws IOException If the file cannot be read
     */
    public static String loadFixture(String fixtureName) throws IOException {
        Path fixturePath = Paths.get(FIXTURES_BASE_PATH + fixtureName + ".json");
        return new String(Files.readAllBytes(fixturePath));
    }

    /**
     * Loads a database from a fixture file.
     *
     * @param fixtureName The name of the fixture file (without .json extension)
     * @return A map containing the loaded database objects
     * @throws IOException If the file cannot be read
     */
    public static Map<String, Object> loadDatabaseFromFixture(String fixtureName) throws IOException {
        String fixtureContent = loadFixture(fixtureName);
        JSONObject json = new JSONObject(fixtureContent);
        
        Map<String, Object> result = new HashMap<>();
        Map<String, DatabaseObject> databases = new HashMap<>();
        Map<String, TableStorageObject> tableStorageObjects = new HashMap<>();
        Map<String, TableReplicaObject> tableReplicaObjects = new HashMap<>();
        
        // Load database
        if (json.has("database")) {
            JSONObject dbJson = json.getJSONObject("database");
            String dbName = dbJson.getString("name");
            
            DatabaseObject db = new DatabaseObject();
            List<String> tables = new ArrayList<>();
            
            if (dbJson.has("tables")) {
                JSONArray tablesJson = dbJson.getJSONArray("tables");
                for (int i = 0; i < tablesJson.length(); i++) {
                    tables.add(tablesJson.getString(i));
                }
            }
            
            db.createNew(dbName, tables);
            databases.put(dbName, db);
            
            // Load tables
            if (json.has("tables")) {
                JSONObject tablesJson = json.getJSONObject("tables");
                for (String tableName : tables) {
                    if (tablesJson.has(tableName)) {
                        JSONObject tableJson = tablesJson.getJSONObject(tableName);
                        
                        // Create table storage object
                        TableStorageObject tableStorage = StorageSchedulerTestData.createTestTableStorage(dbName, tableName);
                        
                        // Add rows
                        if (tableJson.has("rows")) {
                            JSONArray rowsJson = tableJson.getJSONArray("rows");
                            for (int i = 0; i < rowsJson.length(); i++) {
                                JSONObject rowJson = rowsJson.getJSONObject(i);
                                String rowId = rowJson.has("id") ? rowJson.getString("id") : UUID.randomUUID().toString();
                                tableStorage.insert(rowId, rowJson);
                            }
                        }
                        
                        tableStorageObjects.put(dbName + "#" + tableName, tableStorage);
                    }
                }
            }
            
            // Load replicas
            if (json.has("replicas")) {
                JSONObject replicasJson = json.getJSONObject("replicas");
                for (String tableName : tables) {
                    if (replicasJson.has(tableName)) {
                        JSONObject tableReplicaJson = replicasJson.getJSONObject(tableName);
                        
                        // Create table replica object
                        TableReplicaObject tableReplica = StorageSchedulerTestData.createTestTableReplica(dbName, tableName);
                        
                        // Add replicas
                        for (String rowId : tableReplicaJson.keySet()) {
                            JSONObject replicaJson = tableReplicaJson.getJSONObject(rowId);
                            tableReplica.insertOrReplace(rowId, replicaJson);
                        }
                        
                        tableReplicaObjects.put(dbName + "#" + tableName, tableReplica);
                    }
                }
            }
        }
        
        result.put("databases", databases);
        result.put("tableStorageObjects", tableStorageObjects);
        result.put("tableReplicaObjects", tableReplicaObjects);
        
        return result;
    }
    
    /**
     * Sets up the Storage static fields with data from a fixture.
     *
     * @param fixtureName The name of the fixture file (without .json extension)
     * @return A map containing the original Storage state for restoration
     * @throws IOException If the file cannot be read
     */
    public static Map<String, Object> setupStorageFromFixture(String fixtureName) throws IOException {
        // Save original state
        Map<String, Object> originalState = new HashMap<>();
        originalState.put("databases", Storage.databases);
        originalState.put("tableStorageObjects", Storage.tableStorageObjects);
        originalState.put("tableReplicaObjects", Storage.tableReplicaObjects);
        originalState.put("networkOnline", Network.online);
        
        // Load data from fixture
        Map<String, Object> fixtureData = loadDatabaseFromFixture(fixtureName);
        
        // Set up Storage with fixture data
        Storage.databases = (Map<String, DatabaseObject>) fixtureData.get("databases");
        Storage.tableStorageObjects = (Map<String, TableStorageObject>) fixtureData.get("tableStorageObjects");
        Storage.tableReplicaObjects = (Map<String, TableReplicaObject>) fixtureData.get("tableReplicaObjects");
        Network.online = true;
        
        return originalState;
    }
    
    /**
     * Loads error scenarios from the error_scenarios.json fixture.
     *
     * @return A list of error scenario objects
     * @throws IOException If the file cannot be read
     */
    public static List<ErrorScenario> loadErrorScenarios() throws IOException {
        String fixtureContent = loadFixture("error_scenarios");
        JSONObject json = new JSONObject(fixtureContent);
        List<ErrorScenario> scenarios = new ArrayList<>();
        
        if (json.has("scenarios")) {
            JSONArray scenariosJson = json.getJSONArray("scenarios");
            for (int i = 0; i < scenariosJson.length(); i++) {
                JSONObject scenarioJson = scenariosJson.getJSONObject(i);
                
                ErrorScenario scenario = new ErrorScenario();
                scenario.name = scenarioJson.getString("name");
                scenario.description = scenarioJson.getString("description");
                scenario.errorType = scenarioJson.getString("error_type");
                scenario.errorMessage = scenarioJson.getString("error_message");
                scenario.errorTarget = scenarioJson.getString("error_target");
                
                if (scenarioJson.has("database")) {
                    JSONObject dbJson = scenarioJson.getJSONObject("database");
                    scenario.databaseName = dbJson.getString("name");
                    
                    if (dbJson.has("tables")) {
                        JSONArray tablesJson = dbJson.getJSONArray("tables");
                        for (int j = 0; j < tablesJson.length(); j++) {
                            scenario.tables.add(tablesJson.getString(j));
                        }
                    }
                }
                
                scenarios.add(scenario);
            }
        }
        
        return scenarios;
    }
    
    /**
     * Loads performance test scenarios from the performance_test.json fixture.
     *
     * @return A list of performance test scenario objects
     * @throws IOException If the file cannot be read
     */
    public static List<PerformanceScenario> loadPerformanceScenarios() throws IOException {
        String fixtureContent = loadFixture("performance_test");
        JSONObject json = new JSONObject(fixtureContent);
        List<PerformanceScenario> scenarios = new ArrayList<>();
        
        if (json.has("scenarios")) {
            JSONArray scenariosJson = json.getJSONArray("scenarios");
            for (int i = 0; i < scenariosJson.length(); i++) {
                JSONObject scenarioJson = scenariosJson.getJSONObject(i);
                
                PerformanceScenario scenario = new PerformanceScenario();
                scenario.name = scenarioJson.getString("name");
                scenario.description = scenarioJson.getString("description");
                scenario.expectedTimeMs = scenarioJson.getInt("expected_time_ms");
                
                if (scenarioJson.has("database")) {
                    JSONObject dbJson = scenarioJson.getJSONObject("database");
                    scenario.databaseName = dbJson.getString("name");
                    
                    if (dbJson.has("tables")) {
                        JSONArray tablesJson = dbJson.getJSONArray("tables");
                        for (int j = 0; j < tablesJson.length(); j++) {
                            scenario.tables.add(tablesJson.getString(j));
                        }
                    }
                }
                
                if (scenarioJson.has("row_counts")) {
                    JSONObject rowCountsJson = scenarioJson.getJSONObject("row_counts");
                    for (String tableName : rowCountsJson.keySet()) {
                        scenario.rowCounts.put(tableName, rowCountsJson.getInt(tableName));
                    }
                }
                
                scenarios.add(scenario);
            }
        }
        
        return scenarios;
    }
    
    /**
     * Creates in-memory test data structures based on a performance scenario.
     *
     * @param scenario The performance scenario
     * @return A map containing the created test objects
     */
    public static Map<String, Object> createPerformanceTestData(PerformanceScenario scenario) {
        Map<String, Object> testEnv = new HashMap<>();
        Map<String, DatabaseObject> databases = new HashMap<>();
        Map<String, TableStorageObject> tableStorageObjects = new HashMap<>();
        Map<String, TableReplicaObject> tableReplicaObjects = new HashMap<>();
        
        // Create database
        DatabaseObject db = new DatabaseObject();
        db.createNew(scenario.databaseName, scenario.tables);
        databases.put(scenario.databaseName, db);
        
        // Create tables
        for (String tableName : scenario.tables) {
            String key = scenario.databaseName + "#" + tableName;
            int rowCount = scenario.rowCounts.getOrDefault(tableName, 10);
            
            // Create and populate table storage
            TableStorageObject tableStorage = StorageSchedulerTestData.createTestTableStorage(scenario.databaseName, tableName);
            StorageSchedulerTestData.populateTableStorage(tableStorage, rowCount);
            tableStorageObjects.put(key, tableStorage);
            
            // Create and populate table replica
            TableReplicaObject tableReplica = StorageSchedulerTestData.createTestTableReplica(scenario.databaseName, tableName);
            StorageSchedulerTestData.populateTableReplica(tableReplica, rowCount);
            tableReplicaObjects.put(key, tableReplica);
        }
        
        testEnv.put("databases", databases);
        testEnv.put("tableStorageObjects", tableStorageObjects);
        testEnv.put("tableReplicaObjects", tableReplicaObjects);
        
        return testEnv;
    }
    
    /**
     * Compares actual and expected database objects.
     *
     * @param actual The actual database object
     * @param expected The expected database object
     * @return true if the objects match, false otherwise
     */
    public static boolean compareDatabases(DatabaseObject actual, DatabaseObject expected) {
        if (actual == null && expected == null) {
            return true;
        }
        
        if (actual == null || expected == null) {
            return false;
        }
        
        if (!actual.name.equals(expected.name)) {
            return false;
        }
        
        if (actual.tables.size() != expected.tables.size()) {
            return false;
        }
        
        for (String table : expected.tables) {
            if (!actual.tables.contains(table)) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Error scenario class for testing error handling.
     */
    public static class ErrorScenario {
        public String name;
        public String description;
        public String databaseName;
        public List<String> tables = new ArrayList<>();
        public String errorType;
        public String errorMessage;
        public String errorTarget;
    }
    
    /**
     * Performance scenario class for performance testing.
     */
    public static class PerformanceScenario {
        public String name;
        public String description;
        public String databaseName;
        public List<String> tables = new ArrayList<>();
        public Map<String, Integer> rowCounts = new HashMap<>();
        public int expectedTimeMs;
    }
}