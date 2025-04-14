package ms.ao.serengeti.utils;

import ms.ao.serengeti.Serengeti;
import ms.ao.serengeti.mocks.InMemoryStorage;
import ms.ao.serengeti.mocks.MockNetwork;
import ms.ao.serengeti.server.Server;

/**
 * Lightweight base class for fast Server tests.
 * Initializes only the Server component without the full system.
 */
public class ServerFastTestBase extends LightweightTestBase {
    
    protected Server server;
    protected InMemoryStorage inMemoryStorage;
    protected MockNetwork mockNetwork;
    
    /**
     * Initializes only the Server component for testing.
     *
     * @throws Exception If an error occurs during initialization
     */
    @Override
    protected void initializeComponents() throws Exception {
        // Initialize dependencies
        inMemoryStorage = new InMemoryStorage();
        mockNetwork = new MockNetwork();
        
        // Set up the dependencies in Serengeti
        Serengeti.storage = inMemoryStorage;
        Serengeti.network = mockNetwork;
        
        // Initialize server
        server = new Server();
        server.init();
        
        // Don't actually start the server to avoid port conflicts
    }
}