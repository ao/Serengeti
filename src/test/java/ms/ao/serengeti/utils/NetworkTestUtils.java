package ms.ao.serengeti.utils;

import ms.ao.serengeti.network.Network;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Utilities for testing network functionality.
 */
public class NetworkTestUtils {
    
    /**
     * Creates a mock node info JSON object.
     * 
     * @param id The node ID
     * @param ip The node IP address
     * @return A JSONObject representing a node
     */
    public static JSONObject createMockNodeInfo(String id, String ip) {
        JSONObject nodeInfo = new JSONObject();
        nodeInfo.put("id", id);
        nodeInfo.put("ip", ip);
        nodeInfo.put("last_checked", System.currentTimeMillis());
        
        // Add disk info
        JSONObject diskInfo = new JSONObject();
        diskInfo.put("free", 1000000000L);
        diskInfo.put("free_human", "1G");
        diskInfo.put("usable", 1000000000L);
        diskInfo.put("usable_human", "1G");
        diskInfo.put("total", 10000000000L);
        diskInfo.put("total_human", "10G");
        nodeInfo.put("disk", diskInfo);
        
        // Add CPU info
        JSONObject cpuInfo = new JSONObject();
        cpuInfo.put("processors", 4);
        cpuInfo.put("load", 25);
        nodeInfo.put("cpu", cpuInfo);
        
        // Add memory info
        JSONObject memoryInfo = new JSONObject();
        memoryInfo.put("total", 8000000000L);
        memoryInfo.put("total_human", "8G");
        memoryInfo.put("free", 4000000000L);
        memoryInfo.put("free_human", "4G");
        nodeInfo.put("memory", memoryInfo);
        
        // Add OS info
        JSONObject osInfo = new JSONObject();
        osInfo.put("name", "Test OS");
        osInfo.put("version", "1.0");
        osInfo.put("arch", "x64");
        nodeInfo.put("os", osInfo);
        
        // Add version and uptime
        nodeInfo.put("version", "0.0.1");
        nodeInfo.put("uptime", "00:00:00");
        
        return nodeInfo;
    }
    
    /**
     * Creates a network with mock nodes.
     * 
     * @param network The network instance to populate
     * @param numNodes The number of mock nodes to create
     * @return A map of node IDs to their IP addresses
     */
    public static Map<String, String> populateNetworkWithMockNodes(Network network, int numNodes) {
        Map<String, String> nodeMap = new HashMap<>();
        
        for (int i = 0; i < numNodes; i++) {
            String id = UUID.randomUUID().toString();
            String ip = "192.168.1." + (100 + i);
            
            JSONObject nodeInfo = createMockNodeInfo(id, ip);
            network.availableNodes.put(id, nodeInfo);
            nodeMap.put(id, ip);
        }
        
        return nodeMap;
    }
    
    /**
     * Simulates a network partition by removing nodes from the available nodes list.
     * 
     * @param network The network instance
     * @param nodeIds Array of node IDs to remove
     */
    public static void simulateNetworkPartition(Network network, String... nodeIds) {
        for (String nodeId : nodeIds) {
            network.availableNodes.remove(nodeId);
        }
    }
    
    /**
     * Creates a mock HTTP response.
     * 
     * @param statusCode The HTTP status code
     * @param content The response content
     * @return A JSONObject representing the response
     */
    public static JSONObject createMockHttpResponse(int statusCode, String content) {
        JSONObject response = new JSONObject();
        response.put("statusCode", statusCode);
        response.put("content", content);
        return response;
    }
}