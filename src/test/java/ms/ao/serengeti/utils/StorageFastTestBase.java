package ms.ao.serengeti.utils;

import ms.ao.serengeti.helpers.Globals;
import ms.ao.serengeti.mocks.InMemoryStorage;
import ms.ao.serengeti.storage.Storage;

import java.lang.reflect.Field;

/**
 * Lightweight base class for fast Storage tests.
 * Initializes only the Storage component without the full system.
 */
public class StorageFastTestBase extends LightweightTestBase {
    
    protected Storage storage;
    
    /**
     * Initializes only the Storage component for testing.
     * 
     * @throws Exception If an error occurs during initialization
     */
    @Override
    protected void initializeComponents() throws Exception {
        // Initialize in-memory storage instead of real storage
        storage = new InMemoryStorage();
        
        // Configure storage to use the test data path
        Field field = Globals.class.getDeclaredField("data_path");
        field.setAccessible(true);
        field.set(null, testDataPath);
    }
}