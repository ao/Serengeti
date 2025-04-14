package ms.ao.serengeti.utils;

import ms.ao.serengeti.Serengeti;
import ms.ao.serengeti.mocks.InMemoryStorage;
import ms.ao.serengeti.query.QueryEngine;

/**
 * Lightweight base class for fast Query tests.
 * Initializes only the Query component without the full system.
 */
public class QueryFastTestBase extends LightweightTestBase {
    
    protected QueryEngine queryEngine;
    protected InMemoryStorage inMemoryStorage;
    
    /**
     * Initializes only the Query component for testing.
     *
     * @throws Exception If an error occurs during initialization
     */
    @Override
    protected void initializeComponents() throws Exception {
        // Initialize in-memory storage
        inMemoryStorage = new InMemoryStorage();
        Serengeti.storage = inMemoryStorage;
        
        // QueryEngine is a static class, so we don't need to instantiate it
        // Just ensure it has the dependencies it needs
    }
}