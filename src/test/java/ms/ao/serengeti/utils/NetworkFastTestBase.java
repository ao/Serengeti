package ms.ao.serengeti.utils;

import ms.ao.serengeti.Serengeti;
import ms.ao.serengeti.mocks.MockNetwork;
import ms.ao.serengeti.server.ServerConstants;

import java.util.UUID;

/**
 * Lightweight base class for fast Network tests.
 * Provides minimal setup for Network component tests without initializing the entire system.
 */
public class NetworkFastTestBase extends LightweightTestBase {
    
    protected MockNetwork network;
    
    /**
     * Initializes only the Network component for testing.
     * 
     * @throws Exception If an error occurs during initialization
     */
    @Override
    protected void initializeComponents() throws Exception {
        // Initialize mock network
        network = new MockNetwork();
        Serengeti.network = network;
        
        // Set up minimal server constants
        ServerConstants serverConstants = new ServerConstants();
        serverConstants.id = UUID.randomUUID().toString();
        Serengeti.server = new ms.ao.serengeti.mocks.MockServer();
        Serengeti.server.server_constants = serverConstants;
    }
    
    /**
     * Creates a mock node with the given ID and IP.
     * 
     * @param id The node ID
     * @param ip The node IP address
     */
    protected void addMockNode(String id, String ip) {
        network.addMockNode(id, NetworkTestUtils.createMockNodeInfo(id, ip));
    }
    
    /**
     * Sets a mock response for a specific node and query.
     * 
     * @param nodeId The node ID
     * @param query The query
     * @param response The response
     */
    protected void setMockResponse(String nodeId, String query, String response) {
        network.setMockResponse(nodeId, query, response);
    }
    
    /**
     * Generates a random node ID for testing.
     * 
     * @return A random node ID
     */
    protected String generateRandomNodeId() {
        return "node-" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    /**
     * Generates a random IP address for testing.
     * 
     * @return A random IP address
     */
    protected String generateRandomIp() {
        return "192.168." + (1 + (int)(Math.random() * 254)) + "." + (1 + (int)(Math.random() * 254));
    }
}