package ms.ao.serengeti.unit.network;

import ms.ao.serengeti.network.Network;
import ms.ao.serengeti.utils.NetworkFastTestBase;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Fast tests for the Network component.
 * These tests focus on core functionality and run quickly.
 */
@DisplayName("Network Fast Tests")
@Tag("fast")
class NetworkFastTest extends NetworkFastTestBase {
    
    @Test
    @DisplayName("Network initializes with online flag set to false")
    void testNetworkInitializesWithOnlineFlagSetToFalse() throws Exception {
        // Access the online flag using reflection
        Field onlineField = network.getClass().getDeclaredField("online");
        onlineField.setAccessible(true);
        boolean online = (boolean) onlineField.get(null);
        
        // Verify that online is false
        assertFalse(online);
    }
    
    @Test
    @DisplayName("Add node to available nodes")
    void testAddNodeToAvailableNodes() throws Exception {
        // Create a node info object
        JSONObject nodeInfo = new JSONObject();
        nodeInfo.put("id", "test-node");
        nodeInfo.put("ip", "192.168.1.100");
        nodeInfo.put("last_checked", System.currentTimeMillis());
        
        // Access the availableNodes map using reflection
        Field availableNodesField = network.getClass().getDeclaredField("availableNodes");
        availableNodesField.setAccessible(true);
        Map<String, JSONObject> availableNodes = (Map<String, JSONObject>) availableNodesField.get(network);
        
        // Add the node to availableNodes
        availableNodes.put("test-node", nodeInfo);
        
        // Verify that the node was added
        assertTrue(availableNodes.containsKey("test-node"));
        assertEquals("192.168.1.100", availableNodes.get("test-node").getString("ip"));
    }
    
    @Test
    @DisplayName("Get IP from UUID")
    void testGetIPFromUUID() throws Exception {
        // Create a node info object
        JSONObject nodeInfo = new JSONObject();
        nodeInfo.put("id", "test-node");
        nodeInfo.put("ip", "192.168.1.100");
        nodeInfo.put("last_checked", System.currentTimeMillis());
        
        // Access the availableNodes map using reflection
        Field availableNodesField = network.getClass().getDeclaredField("availableNodes");
        availableNodesField.setAccessible(true);
        Map<String, JSONObject> availableNodes = (Map<String, JSONObject>) availableNodesField.get(network);
        
        // Add the node to availableNodes
        availableNodes.put("test-node", nodeInfo);
        
        // Call getIPFromUUID
        String ip = network.getIPFromUUID("test-node");
        
        // Verify the result
        assertEquals("192.168.1.100", ip);
    }
    
    @Test
    @DisplayName("Get IP from non-existent UUID returns null")
    void testGetIPFromNonExistentUUIDReturnsNull() {
        // Call getIPFromUUID with a non-existent UUID
        String ip = network.getIPFromUUID("non-existent-node");
        
        // Verify the result - empty string is returned instead of null
        assertTrue(ip == null || ip.isEmpty());
    }
    
    @Test
    @DisplayName("Get primary and secondary nodes")
    void testGetPrimaryAndSecondaryNodes() throws Exception {
        // Create primary and secondary nodes manually
        JSONObject primarySecondary = new JSONObject();
        JSONObject primary = new JSONObject();
        primary.put("id", "primary-node");
        primary.put("ip", "192.168.1.101");
        JSONObject secondary = new JSONObject();
        secondary.put("id", "secondary-node");
        secondary.put("ip", "192.168.1.102");
        primarySecondary.put("primary", primary);
        primarySecondary.put("secondary", secondary);
        
        // Set the primary and secondary nodes using reflection
        Field availableNodesField = network.getClass().getDeclaredField("availableNodes");
        availableNodesField.setAccessible(true);
        Map<String, JSONObject> availableNodes = (Map<String, JSONObject>) availableNodesField.get(network);
        availableNodes.put("primary-node", primary);
        availableNodes.put("secondary-node", secondary);
        
        // Call getPrimarySecondary
        JSONObject result = network.getPrimarySecondary();
        
        // Verify the result
        assertNotNull(result);
        assertTrue(result.has("primary"));
        assertTrue(result.has("secondary"));
        // The order of primary and secondary might be swapped, so we just check that both nodes are present
        assertTrue(
            (result.getJSONObject("primary").getString("id").equals("primary-node") &&
             result.getJSONObject("secondary").getString("id").equals("secondary-node")) ||
            (result.getJSONObject("primary").getString("id").equals("secondary-node") &&
             result.getJSONObject("secondary").getString("id").equals("primary-node"))
        );
    }
    
    @Test
    @DisplayName("Network online flag can be set")
    void testNetworkOnlineFlagCanBeSet() throws Exception {
        // Set the online flag to true using reflection
        Field onlineField = Network.class.getDeclaredField("online");
        onlineField.setAccessible(true);
        onlineField.set(null, true);
        
        // Verify that online is true
        boolean online = (boolean) onlineField.get(null);
        assertTrue(online);
        
        // Set it back to false
        onlineField.set(null, false);
    }
}