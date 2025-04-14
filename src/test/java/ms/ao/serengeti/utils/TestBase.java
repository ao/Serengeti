package ms.ao.serengeti.utils;

import ms.ao.serengeti.Serengeti;
import ms.ao.serengeti.helpers.Globals;
import ms.ao.serengeti.network.Network;
import ms.ao.serengeti.server.Server;
import ms.ao.serengeti.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Base class for all Serengeti tests.
 * Provides common setup and teardown functionality.
 */
public abstract class TestBase {
    
    protected Serengeti serengeti;
    protected Storage storage;
    protected Network network;
    protected Server server;
    protected String testDataPath;
    protected String originalDataPath;
    
    /**
     * Sets up the test environment before each test.
     * Creates a temporary data directory and initializes Serengeti components.
     * 
     * @throws Exception If an error occurs during setup
     */
    @BeforeEach
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        
        // Create a temporary data directory for tests
        Path tempDir = Files.createTempDirectory("serengeti_test_");
        testDataPath = tempDir.toString() + "/";
        
        // Save the original data path
        originalDataPath = getGlobalsDataPath();
        
        // Set the data path to the temporary directory
        setGlobalsDataPath(testDataPath);
        
        // Initialize Serengeti components
        initializeComponents();
    }
    
    /**
     * Cleans up the test environment after each test.
     * Restores the original data path and deletes the temporary data directory.
     * 
     * @throws Exception If an error occurs during cleanup
     */
    @AfterEach
    public void tearDown() throws Exception {
        // Clean up any data created during the test
        if (storage != null) {
            storage.deleteEverything();
        }
        
        // Restore the original data path
        setGlobalsDataPath(originalDataPath);
        
        // Delete the temporary data directory
        deleteDirectory(new File(testDataPath));
    }
    
    /**
     * Initializes the Serengeti components for testing.
     * This method can be overridden by subclasses to customize the initialization.
     * 
     * @throws Exception If an error occurs during initialization
     */
    protected void initializeComponents() throws Exception {
        // Initialize server
        server = new Server();
        server.init();
        Serengeti.server = server;
        
        // Initialize network
        network = new Network();
        Serengeti.network = network;
        
        // Initialize storage
        storage = new Storage();
        Serengeti.storage = storage;
        
        // Initialize serengeti instance
        serengeti = new Serengeti();
    }
    
    /**
     * Gets the current data path from Globals.
     * 
     * @return The current data path
     * @throws Exception If an error occurs
     */
    protected String getGlobalsDataPath() throws Exception {
        Field field = Globals.class.getDeclaredField("data_path");
        field.setAccessible(true);
        return (String) field.get(null);
    }
    
    /**
     * Sets the data path in Globals.
     * 
     * @param path The new data path
     * @throws Exception If an error occurs
     */
    protected void setGlobalsDataPath(String path) throws Exception {
        Field field = Globals.class.getDeclaredField("data_path");
        field.setAccessible(true);
        field.set(null, path);
    }
    
    /**
     * Recursively deletes a directory and all its contents.
     * 
     * @param directory The directory to delete
     * @return true if successful, false otherwise
     */
    protected boolean deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
        }
        return directory.delete();
    }
    
    /**
     * Generates a random database name for testing.
     * 
     * @return A random database name
     */
    protected String generateRandomDatabaseName() {
        return "test_db_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    /**
     * Generates a random table name for testing.
     * 
     * @return A random table name
     */
    protected String generateRandomTableName() {
        return "test_table_" + UUID.randomUUID().toString().substring(0, 8);
    }
}