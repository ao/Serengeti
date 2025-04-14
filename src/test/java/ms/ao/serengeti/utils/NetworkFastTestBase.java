package ms.ao.serengeti.utils;

import ms.ao.serengeti.network.Network;

import java.lang.reflect.Field;

/**
 * Lightweight base class for fast Network tests.
 * Initializes only the Network component without the full system.
 */
public class NetworkFastTestBase extends LightweightTestBase {
    
    protected Network network;
    
    /**
     * Initializes only the Network component for testing.
     * 
     * @throws Exception If an error occurs during initialization
     */
    @Override
    protected void initializeComponents() throws Exception {
        // Initialize only network, not the full system
        network = new Network();
        
        // Disable actual network operations
        Field onlineField = Network.class.getDeclaredField("online");
        onlineField.setAccessible(true);
        onlineField.set(null, false);
    }
}