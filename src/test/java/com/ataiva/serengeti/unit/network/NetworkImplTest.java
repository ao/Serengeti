package com.ataiva.serengeti.unit.network;

import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.network.NetworkImpl;
import com.ataiva.serengeti.network.NetworkFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import java.net.HttpURLConnection;
import java.net.InetAddress;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the NetworkImpl class.
 */
@RunWith(MockitoJUnitRunner.class)
public class NetworkImplTest {

    private NetworkImpl networkImpl;
    
    @Mock
    private HttpURLConnection mockConnection;
    
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        
        // Create a NetworkImpl instance with test configuration
        networkImpl = new NetworkImpl(
            8080,   // communicationPort
            1986,   // discoveryPort
            1000,   // heartbeatIntervalMs
            5000,   // nodeTimeoutMs
            1000,   // discoveryTimeoutMs
            2       // maxRetransmissions
        );
        
        // Mock the getURLConnection method to return our mock connection
        NetworkImpl spyNetwork = spy(networkImpl);
        doReturn(mockConnection).when(spyNetwork).getURLConnection(anyString());
        
        // Use the spy for testing
        networkImpl = spyNetwork;
    }
    
    @Test
    public void testGetSelfNode() {
        // Set up the test
        networkImpl.myIP = "192.168.1.100";
        
        // Call the method
        JSONObject selfNode = networkImpl.getSelfNode();
        
        // Verify the result
        assertNotNull("Self node should not be null", selfNode);
        assertTrue("Self node should have an ID", selfNode.has("id"));
        assertEquals("Self node should have the correct IP", "192.168.1.100", selfNode.getString("ip"));
    }
    
    @Test
    public void testNodeIsOnline_Success() throws Exception {
        // Set up the test
        when(mockConnection.getResponseCode()).thenReturn(200);
        
        // Call the method
        boolean result = networkImpl.nodeIsOnline("192.168.1.101");
        
        // Verify the result
        assertTrue("Node should be online", result);
    }
    
    @Test
    public void testNodeIsOnline_Failure() throws Exception {
        // Set up the test
        when(mockConnection.getResponseCode()).thenReturn(404);
        
        // Call the method
        boolean result = networkImpl.nodeIsOnline("192.168.1.101");
        
        // Verify the result
        assertFalse("Node should be offline", result);
    }
    
    @Test
    public void testNodeIsOnline_Exception() throws Exception {
        // Set up the test
        when(mockConnection.getResponseCode()).thenThrow(new RuntimeException("Connection error"));
        
        // Call the method
        boolean result = networkImpl.nodeIsOnline("192.168.1.101");
        
        // Verify the result
        assertFalse("Node should be offline when exception occurs", result);
    }
    
    @Test
    public void testGetPrimarySecondary_NotEnoughNodes() {
        // Set up the test - empty available nodes
        networkImpl.availableNodes.clear();
        networkImpl.myIP = "192.168.1.100";
        
        // Call the method
        JSONObject result = networkImpl.getPrimarySecondary();
        
        // Verify the result
        assertNotNull("Result should not be null", result);
        assertTrue("Result should have primary node", result.has("primary"));
        assertTrue("Result should have secondary node", result.has("secondary"));
        
        // Primary should be self
        JSONObject primary = result.getJSONObject("primary");
        assertEquals("Primary IP should be self IP", "192.168.1.100", primary.getString("ip"));
        
        // Secondary should be empty
        JSONObject secondary = result.getJSONObject("secondary");
        assertEquals("Secondary ID should be empty", "", secondary.getString("id"));
        assertEquals("Secondary IP should be empty", "", secondary.getString("ip"));
    }
    
    @Test
    public void testGetRandomAvailableNode_NoNodes() {
        // Set up the test - empty available nodes
        networkImpl.availableNodes.clear();
        
        // Call the method
        JSONObject result = networkImpl.getRandomAvailableNode();
        
        // Verify the result
        assertNull("Result should be null when no nodes available", result);
    }
    
    @Test
    public void testGetIPFromUUID_Found() {
        // Set up the test
        String testUuid = "test-uuid";
        JSONObject nodeInfo = new JSONObject();
        nodeInfo.put("ip", "192.168.1.101");
        networkImpl.availableNodes.put(testUuid, nodeInfo);
        
        // Call the method
        String result = networkImpl.getIPFromUUID(testUuid);
        
        // Verify the result
        assertEquals("IP should match the node info", "192.168.1.101", result);
    }
    
    @Test
    public void testGetIPFromUUID_NotFound() {
        // Set up the test - empty available nodes
        networkImpl.availableNodes.clear();
        
        // Call the method
        String result = networkImpl.getIPFromUUID("non-existent-uuid");
        
        // Verify the result
        assertEquals("IP should be empty when UUID not found", "", result);
    }
    
    @Test
    public void testNetworkFactory_CreateRealNetwork() {
        // Call the factory method
        Network network = NetworkFactory.createNetwork(NetworkFactory.NetworkType.REAL);
        
        // Verify the result
        assertNotNull("Network should not be null", network);
        assertTrue("Network should be an instance of NetworkImpl", network instanceof NetworkImpl);
    }
    
    @Test
    public void testNetworkFactory_CreateMockNetwork() {
        // Call the factory method
        Network network = NetworkFactory.createNetwork(NetworkFactory.NetworkType.MOCK);
        
        // Verify the result
        assertNotNull("Network should not be null", network);
        assertTrue("Network should be an instance of MockNetwork", network.getClass().getSimpleName().equals("MockNetwork"));
    }
}