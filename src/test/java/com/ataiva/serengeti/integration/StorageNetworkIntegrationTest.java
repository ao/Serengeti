package ms.ao.serengeti.integration;

import ms.ao.serengeti.Serengeti;
import ms.ao.serengeti.mocks.MockNetwork;
import ms.ao.serengeti.network.Network;
import ms.ao.serengeti.storage.Storage;
import ms.ao.serengeti.storage.StorageResponseObject;
import ms.ao.serengeti.utils.NetworkTestUtils;
import ms.ao.serengeti.utils.TestBase;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Integration tests for the Storage and Network components.
 */
@DisplayName("Storage-Network Integration Tests")
class StorageNetworkIntegrationTest extends TestBase {
    
    private String testDb;
    private String testTable;
    private MockNetwork mockNetwork;
    
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        
        // Create a spy on the network to intercept certain method calls
        mockNetwork = spy(new MockNetwork());
        Serengeti.network = mockNetwork;
        
        // Create unique test database and table names for each test
        testDb = generateRandomDatabaseName();
        testTable = generateRandomTableName();
        
        // Create the test database and table
        storage.createDatabase(testDb);
        storage.createTable(testDb, testTable);
    }
    
    @Nested
    @DisplayName("Data Replication Tests")
    class DataReplicationTests {
        
        @Test
        @DisplayName("Insert replicates data to primary and secondary nodes")
        void testInsertReplicatesDataToPrimaryAndSecondaryNodes() {
            // Set up mock nodes
            String primaryId = "primary-node";
            String primaryIp = "192.168.1.101";
            String secondaryId = "secondary-node";
            String secondaryIp = "192.168.1.102";
            
            JSONObject primaryNode = NetworkTestUtils.createMockNodeInfo(primaryId, primaryIp);
            JSONObject secondaryNode = NetworkTestUtils.createMockNodeInfo(secondaryId, secondaryIp);
            
            // Set up the mock network to return these nodes
            doReturn(new JSONObject() {{
                put("primary", primaryNode);
                put("secondary", secondaryNode);
            }}).when(mockNetwork).getPrimarySecondary();
            
            // Set up the mock network to return a success response for communicateQueryLogSingleNode
            doReturn("{\"status\":\"success\"}").when(mockNetwork).communicateQueryLogSingleNode(anyString(), anyString(), anyString());
            
            // Insert test data
            JSONObject testData = new JSONObject();
            testData.put("name", "Test Record");
            testData.put("value", 42);
            
            StorageResponseObject response = storage.insert(testDb, testTable, testData);
            
            // Verify the response
            assertTrue(response.success);
            assertNotNull(response.rowId);
            assertEquals(primaryId, response.primary);
            assertEquals(secondaryId, response.secondary);
            
            // Verify that communicateQueryLogSingleNode was called for both nodes
            verify(mockNetwork).communicateQueryLogSingleNode(eq(primaryId), eq(primaryIp), anyString());
            verify(mockNetwork).communicateQueryLogSingleNode(eq(secondaryId), eq(secondaryIp), anyString());
            
            // Verify that communicateQueryLogAllNodes was called to update all nodes about the replication
            verify(mockNetwork).communicateQueryLogAllNodes(anyString());
        }
        
        @Test
        @DisplayName("Update replicates changes to primary and secondary nodes")
        void testUpdateReplicatesChangesToPrimaryAndSecondaryNodes() {
            // Set up mock nodes
            String primaryId = "primary-node";
            String primaryIp = "192.168.1.101";
            String secondaryId = "secondary-node";
            String secondaryIp = "192.168.1.102";
            
            JSONObject primaryNode = NetworkTestUtils.createMockNodeInfo(primaryId, primaryIp);
            JSONObject secondaryNode = NetworkTestUtils.createMockNodeInfo(secondaryId, secondaryIp);
            
            // Insert test data with known replication nodes
            JSONObject testData = new JSONObject();
            testData.put("name", "Test Record");
            testData.put("value", 42);
            
            // Mock the TableReplicaObject to return our mock nodes
            doReturn(new JSONObject() {{
                put("primary", primaryId);
                put("secondary", secondaryId);
            }}).when(mockNetwork).communicateQueryLogSingleNode(anyString(), anyString(), contains("SendTableReplicaToNode"));
            
            // Mock the getIPFromUUID method to return our mock IPs
            doReturn(primaryIp).when(mockNetwork).getIPFromUUID(primaryId);
            doReturn(secondaryIp).when(mockNetwork).getIPFromUUID(secondaryId);
            
            // Insert the test data
            StorageResponseObject insertResponse = storage.insert(testDb, testTable, testData);
            assertTrue(insertResponse.success);
            
            // Reset the mock to clear the call count
            Mockito.reset(mockNetwork);
            
            // Set up the mock network to return a success response for communicateQueryLogSingleNode
            doReturn("{\"status\":\"success\"}").when(mockNetwork).communicateQueryLogSingleNode(anyString(), anyString(), anyString());
            
            // Mock the select method to return our test data
            List<String> selectResult = List.of(new JSONObject() {{
                put("name", "Test Record");
                put("value", 42);
                put("__uuid", insertResponse.rowId);
            }}.toString());
            
            Storage storageSpy = spy(storage);
            doReturn(selectResult).when(storageSpy).select(eq(testDb), eq(testTable), anyString(), anyString(), anyString());
            Serengeti.storage = storageSpy;
            
            // Mock the TableReplicaObject.getRowReplica method
            doReturn(new JSONObject() {{
                put("primary", primaryId);
                put("secondary", secondaryId);
            }}).when(storageSpy).select(eq(testDb), eq(testTable), anyString(), anyString(), anyString());
            
            // Update the test data
            boolean updateResult = storage.update(testDb, testTable, "value", "43", "name", "Test Record");
            
            // Verify the result
            assertTrue(updateResult);
            
            // Verify that communicateQueryLogSingleNode was called for both nodes
            verify(mockNetwork).communicateQueryLogSingleNode(eq(primaryId), eq(primaryIp), anyString());
            verify(mockNetwork).communicateQueryLogSingleNode(eq(secondaryId), eq(secondaryIp), anyString());
        }
        
        @Test
        @DisplayName("Delete replicates deletion to primary and secondary nodes")
        void testDeleteReplicatesDeletionToPrimaryAndSecondaryNodes() {
            // Set up mock nodes
            String primaryId = "primary-node";
            String primaryIp = "192.168.1.101";
            String secondaryId = "secondary-node";
            String secondaryIp = "192.168.1.102";
            
            JSONObject primaryNode = NetworkTestUtils.createMockNodeInfo(primaryId, primaryIp);
            JSONObject secondaryNode = NetworkTestUtils.createMockNodeInfo(secondaryId, secondaryIp);
            
            // Insert test data with known replication nodes
            JSONObject testData = new JSONObject();
            testData.put("name", "Test Record");
            testData.put("value", 42);
            
            // Mock the TableReplicaObject to return our mock nodes
            doReturn(new JSONObject() {{
                put("primary", primaryId);
                put("secondary", secondaryId);
            }}).when(mockNetwork).communicateQueryLogSingleNode(anyString(), anyString(), contains("SendTableReplicaToNode"));
            
            // Mock the getIPFromUUID method to return our mock IPs
            doReturn(primaryIp).when(mockNetwork).getIPFromUUID(primaryId);
            doReturn(secondaryIp).when(mockNetwork).getIPFromUUID(secondaryId);
            
            // Insert the test data
            StorageResponseObject insertResponse = storage.insert(testDb, testTable, testData);
            assertTrue(insertResponse.success);
            
            // Reset the mock to clear the call count
            Mockito.reset(mockNetwork);
            
            // Set up the mock network to return a success response for communicateQueryLogSingleNode
            doReturn("{\"status\":\"success\"}").when(mockNetwork).communicateQueryLogSingleNode(anyString(), anyString(), anyString());
            
            // Mock the select method to return our test data
            List<String> selectResult = List.of(new JSONObject() {{
                put("name", "Test Record");
                put("value", 42);
                put("__uuid", insertResponse.rowId);
            }}.toString());
            
            Storage storageSpy = spy(storage);
            doReturn(selectResult).when(storageSpy).select(eq(testDb), eq(testTable), anyString(), anyString(), anyString());
            Serengeti.storage = storageSpy;
            
            // Mock the TableReplicaObject.getRowReplica method
            doReturn(new JSONObject() {{
                put("primary", primaryId);
                put("secondary", secondaryId);
            }}).when(storageSpy).select(eq(testDb), eq(testTable), anyString(), anyString(), anyString());
            
            // Delete the test data
            boolean deleteResult = storage.delete(testDb, testTable, "name", "Test Record");
            
            // Verify the result
            assertTrue(deleteResult);
            
            // Verify that communicateQueryLogSingleNode was called for both nodes
            verify(mockNetwork).communicateQueryLogSingleNode(eq(primaryId), eq(primaryIp), anyString());
            verify(mockNetwork).communicateQueryLogSingleNode(eq(secondaryId), eq(secondaryIp), anyString());
            
            // Verify that communicateQueryLogAllNodes was called to update all nodes about the deletion
            verify(mockNetwork).communicateQueryLogAllNodes(anyString());
        }
    }
    
    @Nested
    @DisplayName("Network Metadata Tests")
    class NetworkMetadataTests {
        
        @Test
        @DisplayName("Request network metas synchronizes database metadata")
        void testRequestNetworkMetasSynchronizesDatabaseMetadata() {
            // Set up mock nodes
            String nodeId = "test-node";
            String nodeIp = "192.168.1.100";
            
            JSONObject nodeInfo = NetworkTestUtils.createMockNodeInfo(nodeId, nodeIp);
            mockNetwork.availableNodes.put(nodeId, nodeInfo);
            
            // Set up the mock network to return metadata
            JSONObject metaResponse = new JSONObject();
            JSONObject meta = new JSONObject();
            meta.put("test_db", List.of("test_table"));
            metaResponse.put("meta", meta);
            
            doReturn(metaResponse.toString()).when(mockNetwork).communicateQueryLogSingleNode(eq(nodeId), eq(nodeIp), contains("meta"));
            
            // Call requestNetworkMetas
            mockNetwork.requestNetworkMetas();
            
            // Since requestNetworkMetas starts a thread, we need to wait a bit for it to execute
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            
            // Verify that the online flag is set to true
            assertTrue(Network.online);
        }
    }
    
    @Nested
    @DisplayName("Node Failure Tests")
    class NodeFailureTests {
        
        @Test
        @DisplayName("Lost node triggers data reshuffling")
        void testLostNodeTriggersDataReshuffling() {
            // Set up mock nodes
            String nodeId = "test-node";
            String nodeIp = "192.168.1.100";
            
            JSONObject nodeInfo = NetworkTestUtils.createMockNodeInfo(nodeId, nodeIp);
            mockNetwork.availableNodes.put(nodeId, nodeInfo);
            
            // Set the last_checked to a time in the past
            nodeInfo.put("last_checked", System.currentTimeMillis() - 10000);
            
            // Call getNetworkIPsPorts
            mockNetwork.getNetworkIPsPorts();
            
            // Verify that the node was removed from availableNodes
            assertFalse(mockNetwork.availableNodes.containsKey(nodeId));
        }
    }
}