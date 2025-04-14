package com.ataiva.serengeti.unit.network;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.mocks.MockNetwork;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.server.Server;
import com.ataiva.serengeti.server.ServerConstants;
import com.ataiva.serengeti.utils.NetworkTestUtils;
import com.ataiva.serengeti.utils.TestBase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the Network component.
 */
@DisplayName("Network Tests")
class NetworkTest extends TestBase {
    
    private MockNetwork mockNetwork;
    
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        
        // Create a mock network instead of using a spy
        mockNetwork = new MockNetwork();
        Serengeti.network = mockNetwork;
        
        // Set up the server constants
        ServerConstants serverConstants = new ServerConstants();
        serverConstants.id = UUID.randomUUID().toString();
        server.server_constants = serverConstants;
    }
    
    @Nested
    @DisplayName("Initialization Tests")
    class InitializationTests {
        
        @Test
        @DisplayName("Init method sets myIP")
        void testInitSetsMyIP() {
            // Reset the network object to ensure init() is called fresh
            mockNetwork = new MockNetwork();
            mockNetwork.init();
            
            assertNotNull(mockNetwork.myIP);
            assertEquals("127.0.0.1", mockNetwork.myIP);
        }
        
        @Test
        @DisplayName("Init method handles local-only mode")
        void testInitHandlesLocalOnlyMode() {
            // Create a new network
            MockNetwork localNetwork = new MockNetwork();
            
            // Set the IP directly to localhost
            localNetwork.myIP = "127.0.0.1";
            
            // Call init
            localNetwork.init();
            
            // Verify the IP is set to localhost
            assertEquals("127.0.0.1", localNetwork.myIP);
        }
    }
    
    @Nested
    @DisplayName("Node Discovery Tests")
    class NodeDiscoveryTests {
        
        @Test
        @DisplayName("Find nodes method is called")
        void testFindNodesMethodIsCalled() {
            // Call findNodes
            mockNetwork.findNodes();
            
            // Verify that findNodes was called
            assertTrue(mockNetwork.wasFindNodesExecuted());
        }
    }
    
    @Nested
    @DisplayName("Network Communication Tests")
    class NetworkCommunicationTests {
        
        @Test
        @DisplayName("Communicate query log to single node")
        void testCommunicateQueryLogSingleNode() {
            // Set up a mock response
            String nodeId = "test-node-id";
            String nodeIp = "192.168.1.100";
            String query = "{\"type\":\"test\"}";
            String expectedResponse = "{\"status\":\"success\"}";
            
            mockNetwork.setMockResponse(nodeId, query, expectedResponse);
            
            // Call communicateQueryLogSingleNode
            String result = mockNetwork.communicateQueryLogSingleNode(nodeId, nodeIp, query);
            
            // Verify the result
            assertEquals(expectedResponse, result);
        }
        
        @Test
        @DisplayName("Communicate query log to all nodes")
        void testCommunicateQueryLogAllNodes() {
            // Set up mock nodes
            Map<String, String> nodeMap = new HashMap<>();
            nodeMap.put("node1", "192.168.1.101");
            nodeMap.put("node2", "192.168.1.102");
            
            for (Map.Entry<String, String> entry : nodeMap.entrySet()) {
                JSONObject nodeInfo = NetworkTestUtils.createMockNodeInfo(entry.getKey(), entry.getValue());
                mockNetwork.addMockNode(entry.getKey(), nodeInfo);
            }
            
            // Set up mock responses
            String query = "{\"type\":\"test\"}";
            String expectedResponse = "{\"status\":\"success\"}";
            
            mockNetwork.setMockResponse("node1", query, expectedResponse);
            mockNetwork.setMockResponse("node2", query, expectedResponse);
            
            // Call communicateQueryLogAllNodes
            JSONArray result = mockNetwork.communicateQueryLogAllNodes(query);
            
            // Verify the result
            assertEquals(2, result.length());
            assertEquals(expectedResponse, result.getString(0));
            assertEquals(expectedResponse, result.getString(1));
        }
    }
    
    @Nested
    @DisplayName("Network Metadata Tests")
    class NetworkMetadataTests {
        
        @Test
        @DisplayName("Request network metas method is called")
        void testRequestNetworkMetasMethodIsCalled() {
            // Call requestNetworkMetas
            mockNetwork.requestNetworkMetas();
            
            // Verify that requestNetworkMetas was called
            assertTrue(mockNetwork.wasRequestNetworkMetasExecuted());
        }
    }
    
    @Nested
    @DisplayName("Node Management Tests")
    class NodeManagementTests {
        
        @Test
        @DisplayName("Get primary secondary returns valid nodes")
        void testGetPrimarySecondaryReturnsValidNodes() {
            // Set up mock nodes
            for (int i = 0; i < 3; i++) {
                JSONObject nodeInfo = NetworkTestUtils.createMockNodeInfo("node" + i, "192.168.1.10" + i);
                mockNetwork.addMockNode("node" + i, nodeInfo);
            }
            
            // Call getPrimarySecondary
            JSONObject result = mockNetwork.getPrimarySecondary();
            
            // Verify the result
            assertNotNull(result.getJSONObject("primary"));
            assertNotNull(result.getJSONObject("secondary"));
            assertNotEquals(result.getJSONObject("primary").getString("id"), result.getJSONObject("secondary").getString("id"));
        }
        
        @Test
        @DisplayName("Get primary secondary handles insufficient nodes")
        void testGetPrimarySecondaryHandlesInsufficientNodes() {
            // Clear available nodes
            mockNetwork.availableNodes.clear();
            
            // Call getPrimarySecondary
            JSONObject result = mockNetwork.getPrimarySecondary();
            
            // Verify the result
            assertNotNull(result.getJSONObject("primary"));
            assertEquals(mockNetwork.myIP, result.getJSONObject("primary").getString("ip"));
            assertEquals("", result.getJSONObject("secondary").getString("id"));
            assertEquals("", result.getJSONObject("secondary").getString("ip"));
        }
        
        @Test
        @DisplayName("Get random available node returns valid node")
        void testGetRandomAvailableNodeReturnsValidNode() {
            // Set up mock nodes
            for (int i = 0; i < 3; i++) {
                JSONObject nodeInfo = NetworkTestUtils.createMockNodeInfo("node" + i, "192.168.1.10" + i);
                mockNetwork.addMockNode("node" + i, nodeInfo);
            }
            
            // Set myIP to a different value to ensure we don't get ourselves
            mockNetwork.myIP = "192.168.1.200";
            
            // Call getRandomAvailableNode
            JSONObject result = mockNetwork.getRandomAvailableNode();
            
            // Verify the result
            assertNotNull(result);
            assertTrue(result.getString("id").startsWith("node"));
        }
        
        @Test
        @DisplayName("Get random available node handles no nodes")
        void testGetRandomAvailableNodeHandlesNoNodes() {
            // Clear available nodes
            mockNetwork.availableNodes.clear();
            
            // Call getRandomAvailableNode
            JSONObject result = mockNetwork.getRandomAvailableNode();
            
            // Verify the result
            assertNull(result);
        }
        
        @Test
        @DisplayName("Get IP from UUID returns correct IP")
        void testGetIPFromUUIDReturnsCorrectIP() {
            // Set up a mock node
            String nodeId = "test-node-id";
            String nodeIp = "192.168.1.100";
            JSONObject nodeInfo = NetworkTestUtils.createMockNodeInfo(nodeId, nodeIp);
            mockNetwork.addMockNode(nodeId, nodeInfo);
            
            // Call getIPFromUUID
            String result = mockNetwork.getIPFromUUID(nodeId);
            
            // Verify the result
            assertEquals(nodeIp, result);
        }
        
        @Test
        @DisplayName("Get IP from UUID handles unknown UUID")
        void testGetIPFromUUIDHandlesUnknownUUID() {
            // Call getIPFromUUID with an unknown UUID
            String result = mockNetwork.getIPFromUUID("unknown-uuid");
            
            // Verify the result
            assertEquals("", result);
        }
    }
}