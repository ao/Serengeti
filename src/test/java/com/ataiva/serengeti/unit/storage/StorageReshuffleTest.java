package com.ataiva.serengeti.unit.storage;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageReshuffle;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the StorageReshuffle component.
 * Tests data rebalancing functionality when nodes join or leave the cluster.
 */
@DisplayName("StorageReshuffle Unit Tests")
@Tag("unit")
class StorageReshuffleTest {

    @Mock
    private Network mockNetwork;
    
    @Mock
    private TableReplicaObject mockTableReplicaObject;
    
    @Mock
    private TableStorageObject mockTableStorageObject;

    private StorageReshuffle storageReshuffle;
    private JSONObject testNode;
    private JSONObject testPrimarySecondary;
    private Map<String, TableReplicaObject> originalTableReplicaObjects;
    private Map<String, TableStorageObject> originalTableStorageObjects;
    private AutoCloseable mockCloseable;

    @BeforeEach
    void setUp() throws Exception {
        mockCloseable = MockitoAnnotations.openMocks(this);
        storageReshuffle = new StorageReshuffle();
        
        // Setup test node
        testNode = new JSONObject();
        testNode.put("id", "lost-node-123");
        testNode.put("ip", "192.168.1.100");
        
        // Setup primary/secondary response
        testPrimarySecondary = new JSONObject();
        JSONObject primary = new JSONObject();
        primary.put("id", "primary-node-456");
        primary.put("ip", "192.168.1.101");
        JSONObject secondary = new JSONObject();
        secondary.put("id", "secondary-node-789");
        secondary.put("ip", "192.168.1.102");
        testPrimarySecondary.put("primary", primary);
        testPrimarySecondary.put("secondary", secondary);
        
        // Mock Serengeti.network
        setStaticField(Serengeti.class, "network", mockNetwork);
        
        // Backup original static maps
        originalTableReplicaObjects = Storage.tableReplicaObjects;
        originalTableStorageObjects = Storage.tableStorageObjects;
        
        // Setup mock storage maps
        Storage.tableReplicaObjects = new HashMap<>();
        Storage.tableStorageObjects = new HashMap<>();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Restore original static maps
        Storage.tableReplicaObjects = originalTableReplicaObjects;
        Storage.tableStorageObjects = originalTableStorageObjects;
        
        if (mockCloseable != null) {
            mockCloseable.close();
        }
    }

    @Test
    @DisplayName("Should handle node that comes back online within 10 seconds")
    void testNodeComesBackOnline() throws InterruptedException {
        // Arrange
        when(mockNetwork.nodeIsOnline("192.168.1.100")).thenReturn(true);
        
        // Act
        storageReshuffle.queueLostNode(testNode);
        
        // Wait for the thread to complete (slightly more than 10 seconds)
        Thread.sleep(10500);
        
        // Assert
        verify(mockNetwork, times(1)).nodeIsOnline("192.168.1.100");
        // Should not call any rebalancing methods since node came back online
        verify(mockNetwork, never()).getPrimarySecondary();
        verify(mockNetwork, never()).communicateQueryLogSingleNode(anyString(), anyString(), anyString());
        verify(mockNetwork, never()).communicateQueryLogAllNodes(anyString());
    }

    @Test
    @DisplayName("Should handle primary node failure and rebalance data")
    void testPrimaryNodeFailureRebalancing() throws InterruptedException {
        // Arrange
        setupNodeFailureScenario();
        setupPrimaryNodeFailure();
        
        // Act
        storageReshuffle.queueLostNode(testNode);
        
        // Wait for the thread to complete
        Thread.sleep(10500);
        
        // Assert
        verify(mockNetwork, times(1)).nodeIsOnline("192.168.1.100");
        verify(mockNetwork, times(1)).getPrimarySecondary();
        verify(mockNetwork, times(1)).communicateQueryLogSingleNode(
            eq("primary-node-456"), 
            eq("192.168.1.101"), 
            contains("ReplicateInsertObject")
        );
        verify(mockNetwork, times(1)).communicateQueryLogAllNodes(
            contains("TableReplicaObjectInsertOrReplace")
        );
        verify(mockTableReplicaObject, times(1)).updateNewPrimary("row123", "primary-node-456");
    }

    @Test
    @DisplayName("Should handle secondary node failure and rebalance data")
    void testSecondaryNodeFailureRebalancing() throws InterruptedException {
        // Arrange
        setupNodeFailureScenario();
        setupSecondaryNodeFailure();
        
        // Act
        storageReshuffle.queueLostNode(testNode);
        
        // Wait for the thread to complete
        Thread.sleep(10500);
        
        // Assert
        verify(mockNetwork, times(1)).nodeIsOnline("192.168.1.100");
        verify(mockNetwork, times(1)).getPrimarySecondary();
        verify(mockNetwork, times(1)).communicateQueryLogSingleNode(
            eq("primary-node-456"), 
            eq("192.168.1.101"), 
            contains("ReplicateInsertObject")
        );
        verify(mockNetwork, times(1)).communicateQueryLogAllNodes(
            contains("TableReplicaObjectInsertOrReplace")
        );
        verify(mockTableReplicaObject, times(1)).updateNewSecondary("row123", "secondary-node-789");
    }

    @Test
    @DisplayName("Should handle multiple tables during node failure")
    void testMultipleTablesRebalancing() throws InterruptedException {
        // Arrange
        setupMultipleTablesScenario();
        
        // Act
        storageReshuffle.queueLostNode(testNode);
        
        // Wait for the thread to complete
        Thread.sleep(10500);
        
        // Assert
        verify(mockNetwork, times(1)).nodeIsOnline("192.168.1.100");
        verify(mockNetwork, times(2)).getPrimarySecondary(); // Called for each table
        verify(mockNetwork, times(2)).communicateQueryLogSingleNode(anyString(), anyString(), anyString());
        verify(mockNetwork, times(2)).communicateQueryLogAllNodes(anyString());
    }

    @Test
    @DisplayName("Should handle node that is not primary or secondary")
    void testNodeNotInReplicaSet() throws InterruptedException {
        // Arrange
        setupNodeFailureScenario();
        setupNodeNotInReplicaSet();
        
        // Act
        storageReshuffle.queueLostNode(testNode);
        
        // Wait for the thread to complete
        Thread.sleep(10500);
        
        // Assert
        verify(mockNetwork, times(1)).nodeIsOnline("192.168.1.100");
        verify(mockNetwork, times(1)).getPrimarySecondary();
        // Should not perform any rebalancing since node is not in replica set
        verify(mockNetwork, never()).communicateQueryLogSingleNode(anyString(), anyString(), anyString());
        verify(mockNetwork, never()).communicateQueryLogAllNodes(anyString());
    }

    @Test
    @DisplayName("Should handle null row data gracefully")
    void testNullRowDataHandling() throws InterruptedException {
        // Arrange
        setupNodeFailureScenario();
        setupPrimaryNodeFailure();
        when(mockTableStorageObject.getJsonFromRowId("row123")).thenReturn(null);
        
        // Act
        storageReshuffle.queueLostNode(testNode);
        
        // Wait for the thread to complete
        Thread.sleep(10500);
        
        // Assert
        verify(mockNetwork, times(1)).nodeIsOnline("192.168.1.100");
        verify(mockNetwork, times(1)).getPrimarySecondary();
        // Should not perform replication when row data is null
        verify(mockNetwork, never()).communicateQueryLogSingleNode(anyString(), anyString(), anyString());
        verify(mockNetwork, never()).communicateQueryLogAllNodes(anyString());
    }

    @Test
    @DisplayName("Should handle invalid table key format")
    void testInvalidTableKeyFormat() throws InterruptedException {
        // Arrange
        when(mockNetwork.nodeIsOnline("192.168.1.100")).thenReturn(false);
        when(mockNetwork.getPrimarySecondary()).thenReturn(testPrimarySecondary);
        
        // Setup invalid table key (missing # separator)
        Storage.tableReplicaObjects.put("invalidtablekey", mockTableReplicaObject);
        Map<String, String> rowReplicas = new HashMap<>();
        rowReplicas.put("row123", createReplicaJson("lost-node-123", "other-node"));
        when(mockTableReplicaObject.row_replicas).thenReturn(rowReplicas);
        
        // Act
        storageReshuffle.queueLostNode(testNode);
        
        // Wait for the thread to complete
        Thread.sleep(10500);
        
        // Assert
        verify(mockNetwork, times(1)).nodeIsOnline("192.168.1.100");
        // Should not perform any rebalancing for invalid table key
        verify(mockNetwork, never()).communicateQueryLogSingleNode(anyString(), anyString(), anyString());
        verify(mockNetwork, never()).communicateQueryLogAllNodes(anyString());
    }

    @Test
    @DisplayName("Should handle InterruptedException gracefully")
    void testInterruptedExceptionHandling() throws InterruptedException {
        // Arrange
        when(mockNetwork.nodeIsOnline("192.168.1.100")).thenReturn(false);
        
        // Act
        Thread testThread = new Thread(() -> storageReshuffle.queueLostNode(testNode));
        testThread.start();
        
        // Interrupt the thread before it completes
        Thread.sleep(5000);
        testThread.interrupt();
        testThread.join(1000);
        
        // Assert - should not throw exception and should handle interruption gracefully
        assertFalse(testThread.isAlive());
    }

    @Test
    @DisplayName("Should handle null replica update result")
    void testNullReplicaUpdateResult() throws InterruptedException {
        // Arrange
        setupNodeFailureScenario();
        setupPrimaryNodeFailure();
        when(mockTableReplicaObject.updateNewPrimary("row123", "primary-node-456")).thenReturn(null);
        
        // Act
        storageReshuffle.queueLostNode(testNode);
        
        // Wait for the thread to complete
        Thread.sleep(10500);
        
        // Assert
        verify(mockNetwork, times(1)).nodeIsOnline("192.168.1.100");
        verify(mockNetwork, times(1)).getPrimarySecondary();
        verify(mockNetwork, times(1)).communicateQueryLogSingleNode(anyString(), anyString(), anyString());
        // Should not communicate replica update when result is null
        verify(mockNetwork, never()).communicateQueryLogAllNodes(anyString());
    }

    @Test
    @DisplayName("Should handle empty storage maps")
    void testEmptyStorageMaps() throws InterruptedException {
        // Arrange
        when(mockNetwork.nodeIsOnline("192.168.1.100")).thenReturn(false);
        when(mockNetwork.getPrimarySecondary()).thenReturn(testPrimarySecondary);
        
        // Storage maps are already empty from setUp
        
        // Act
        storageReshuffle.queueLostNode(testNode);
        
        // Wait for the thread to complete
        Thread.sleep(10500);
        
        // Assert
        verify(mockNetwork, times(1)).nodeIsOnline("192.168.1.100");
        verify(mockNetwork, times(1)).getPrimarySecondary();
        // Should not perform any operations on empty storage
        verify(mockNetwork, never()).communicateQueryLogSingleNode(anyString(), anyString(), anyString());
        verify(mockNetwork, never()).communicateQueryLogAllNodes(anyString());
    }

    // Helper methods

    private void setupNodeFailureScenario() {
        when(mockNetwork.nodeIsOnline("192.168.1.100")).thenReturn(false);
        when(mockNetwork.getPrimarySecondary()).thenReturn(testPrimarySecondary);
        
        Storage.tableReplicaObjects.put("testdb#testtable", mockTableReplicaObject);
        Storage.tableStorageObjects.put("testdb#testtable", mockTableStorageObject);
        
        Map<String, String> rowReplicas = new HashMap<>();
        rowReplicas.put("row123", createReplicaJson("lost-node-123", "other-node"));
        when(mockTableReplicaObject.row_replicas).thenReturn(rowReplicas);
        
        JSONObject rowData = new JSONObject();
        rowData.put("data", "test data");
        when(mockTableStorageObject.getJsonFromRowId("row123")).thenReturn(rowData);
    }

    private void setupPrimaryNodeFailure() {
        JSONObject updatedReplica = new JSONObject();
        updatedReplica.put("primary", "primary-node-456");
        updatedReplica.put("secondary", "other-node");
        when(mockTableReplicaObject.updateNewPrimary("row123", "primary-node-456")).thenReturn(updatedReplica);
    }

    private void setupSecondaryNodeFailure() {
        Map<String, String> rowReplicas = new HashMap<>();
        rowReplicas.put("row123", createReplicaJson("other-node", "lost-node-123"));
        when(mockTableReplicaObject.row_replicas).thenReturn(rowReplicas);
        
        JSONObject updatedReplica = new JSONObject();
        updatedReplica.put("primary", "other-node");
        updatedReplica.put("secondary", "secondary-node-789");
        when(mockTableReplicaObject.updateNewSecondary("row123", "secondary-node-789")).thenReturn(updatedReplica);
    }

    private void setupNodeNotInReplicaSet() {
        Map<String, String> rowReplicas = new HashMap<>();
        rowReplicas.put("row123", createReplicaJson("other-primary", "other-secondary"));
        when(mockTableReplicaObject.row_replicas).thenReturn(rowReplicas);
    }

    private void setupMultipleTablesScenario() {
        when(mockNetwork.nodeIsOnline("192.168.1.100")).thenReturn(false);
        when(mockNetwork.getPrimarySecondary()).thenReturn(testPrimarySecondary);
        
        // Setup first table
        TableReplicaObject mockTableReplicaObject1 = mock(TableReplicaObject.class);
        TableStorageObject mockTableStorageObject1 = mock(TableStorageObject.class);
        Storage.tableReplicaObjects.put("db1#table1", mockTableReplicaObject1);
        Storage.tableStorageObjects.put("db1#table1", mockTableStorageObject1);
        
        Map<String, String> rowReplicas1 = new HashMap<>();
        rowReplicas1.put("row1", createReplicaJson("lost-node-123", "other-node"));
        when(mockTableReplicaObject1.row_replicas).thenReturn(rowReplicas1);
        
        JSONObject rowData1 = new JSONObject();
        rowData1.put("data", "test data 1");
        when(mockTableStorageObject1.getJsonFromRowId("row1")).thenReturn(rowData1);
        
        JSONObject updatedReplica1 = new JSONObject();
        updatedReplica1.put("primary", "primary-node-456");
        updatedReplica1.put("secondary", "other-node");
        when(mockTableReplicaObject1.updateNewPrimary("row1", "primary-node-456")).thenReturn(updatedReplica1);
        
        // Setup second table
        TableReplicaObject mockTableReplicaObject2 = mock(TableReplicaObject.class);
        TableStorageObject mockTableStorageObject2 = mock(TableStorageObject.class);
        Storage.tableReplicaObjects.put("db2#table2", mockTableReplicaObject2);
        Storage.tableStorageObjects.put("db2#table2", mockTableStorageObject2);
        
        Map<String, String> rowReplicas2 = new HashMap<>();
        rowReplicas2.put("row2", createReplicaJson("lost-node-123", "other-node2"));
        when(mockTableReplicaObject2.row_replicas).thenReturn(rowReplicas2);
        
        JSONObject rowData2 = new JSONObject();
        rowData2.put("data", "test data 2");
        when(mockTableStorageObject2.getJsonFromRowId("row2")).thenReturn(rowData2);
        
        JSONObject updatedReplica2 = new JSONObject();
        updatedReplica2.put("primary", "primary-node-456");
        updatedReplica2.put("secondary", "other-node2");
        when(mockTableReplicaObject2.updateNewPrimary("row2", "primary-node-456")).thenReturn(updatedReplica2);
    }

    private String createReplicaJson(String primary, String secondary) {
        JSONObject replica = new JSONObject();
        replica.put("primary", primary);
        replica.put("secondary", secondary);
        return replica.toString();
    }

    private void setStaticField(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }
}