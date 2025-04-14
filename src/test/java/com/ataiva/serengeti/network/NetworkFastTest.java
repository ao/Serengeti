package com.ataiva.serengeti.network;

import com.ataiva.serengeti.utils.NetworkFastTestBase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fast tests for the Network component.
 * These tests focus on core functionality and run quickly.
 */
@DisplayName("Network Fast Tests")
@Tag("fast")
public class NetworkFastTest extends NetworkFastTestBase {
    
    private String testNodeId;
    private String testNodeIp;
    
    @BeforeEach
    public void setUpNetworkTest() {
        testNodeId = generateRandomNodeId();
        testNodeIp = generateRandomIp();
    }
    
    @Test
    @DisplayName("Network initialization sets IP address")
    void testNetworkInitialization() {
        // Initialize the network
        network.init();
        
        // Verify IP is set
        assertNotNull(network.myIP);
        assertEquals("127.0.0.1", network.myIP);
    }
    
    @Test
    @DisplayName("Add and retrieve nodes")
    void testAddAndRetrieveNodes() {
        // Add mock nodes
        addMockNode(testNodeId, testNodeIp);
        
        // Verify node was added
        assertTrue(network.availableNodes.containsKey(testNodeId));
        assertEquals(testNodeIp, network.availableNodes.get(testNodeId).getString("ip"));
    }
    
    @Test
    @DisplayName("Get IP from UUID returns correct IP")
    void testGetIPFromUUID() {
        // Add mock node
        addMockNode(testNodeId, testNodeIp);
        
        // Get IP from UUID
        String ip = network.getIPFromUUID(testNodeId);
        
        // Verify correct IP is returned
        assertEquals(testNodeIp, ip);
    }
    
    @Test
    @DisplayName("Get IP from unknown UUID returns empty string")
    void testGetIPFromUnknownUUID() {
        // Get IP from unknown UUID
        String ip = network.getIPFromUUID("unknown-uuid");
        
        // Verify empty string is returned
        assertEquals("", ip);
    }
    
    @Test
    @DisplayName("Communicate query log to single node")
    void testCommunicateQueryLogSingleNode() {
        // Set up mock response
        String query = "{\"type\":\"test\"}";
        String expectedResponse = "{\"status\":\"success\"}";
        
        // Add mock node
        addMockNode(testNodeId, testNodeIp);
        
        // Set mock response
        setMockResponse(testNodeId, query, expectedResponse);
        
        // Communicate query log
        String response = network.communicateQueryLogSingleNode(testNodeId, testNodeIp, query);
        
        // Verify response
        assertEquals(expectedResponse, response);
    }
    
    @Test
    @DisplayName("Communicate query log to all nodes")
    void testCommunicateQueryLogAllNodes() {
        // Set up mock nodes
        Map<String, String> nodeMap = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            String nodeId = generateRandomNodeId();
            String nodeIp = generateRandomIp();
            nodeMap.put(nodeId, nodeIp);
            addMockNode(nodeId, nodeIp);
        }
        
        // Set up mock responses
        String query = "{\"type\":\"test\"}";
        String expectedResponse = "{\"status\":\"success\"}";
        
        for (String nodeId : nodeMap.keySet()) {
            setMockResponse(nodeId, query, expectedResponse);
        }
        
        // Communicate query log to all nodes
        JSONArray responses = network.communicateQueryLogAllNodes(query);
        
        // Verify responses
        assertEquals(nodeMap.size(), responses.length());
        for (int i = 0; i < responses.length(); i++) {
            assertEquals(expectedResponse, responses.getString(i));
        }
    }
    
    @Test
    @DisplayName("Get primary and secondary nodes")
    void testGetPrimarySecondary() {
        // Add mock nodes
        for (int i = 0; i < 3; i++) {
            String nodeId = generateRandomNodeId();
            String nodeIp = generateRandomIp();
            addMockNode(nodeId, nodeIp);
        }
        
        // Get primary and secondary nodes
        JSONObject result = network.getPrimarySecondary();
        
        // Verify result
        assertNotNull(result);
        assertNotNull(result.getJSONObject("primary"));
        assertNotNull(result.getJSONObject("secondary"));
        assertNotEquals(
            result.getJSONObject("primary").getString("id"),
            result.getJSONObject("secondary").getString("id")
        );
    }
    
    @Test
    @DisplayName("Get random available node")
    void testGetRandomAvailableNode() {
        // Add mock nodes
        for (int i = 0; i < 3; i++) {
            String nodeId = generateRandomNodeId();
            String nodeIp = generateRandomIp();
            addMockNode(nodeId, nodeIp);
        }
        
        // Get random available node
        JSONObject result = network.getRandomAvailableNode();
        
        // Verify result
        assertNotNull(result);
        assertTrue(network.availableNodes.containsKey(result.getString("id")));
    }
    
    @Test
    @DisplayName("Get random available node with no nodes")
    void testGetRandomAvailableNodeWithNoNodes() {
        // Clear available nodes
        network.availableNodes.clear();
        
        // Get random available node
        JSONObject result = network.getRandomAvailableNode();
        
        // Verify result
        assertNull(result);
    }
}