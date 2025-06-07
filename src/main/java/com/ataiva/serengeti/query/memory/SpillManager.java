package com.ataiva.serengeti.query.memory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SpillManager handles spilling data to disk when memory is constrained.
 * It provides an interface for operations that can spill their data to disk.
 */
public abstract class SpillManager {
    private static final Logger LOGGER = Logger.getLogger(SpillManager.class.getName());
    
    // Query and operation IDs
    protected final String queryId;
    protected final String operationId;
    
    // Spill directory
    protected final Path spillDirectory;
    
    // List of spill files
    protected final List<Path> spillFiles;
    
    // Statistics
    protected long bytesSpilled;
    protected int spillCount;
    
    // Default spill directory
    private static final String DEFAULT_SPILL_DIRECTORY = System.getProperty("java.io.tmpdir") + 
                                                         File.separator + "serengeti-spill";
    
    /**
     * Constructor
     * @param queryId Query ID
     * @param operationId Operation ID
     */
    protected SpillManager(String queryId, String operationId) {
        this(queryId, operationId, Paths.get(DEFAULT_SPILL_DIRECTORY));
    }
    
    /**
     * Constructor with custom spill directory
     * @param queryId Query ID
     * @param operationId Operation ID
     * @param spillDirectory Spill directory
     */
    protected SpillManager(String queryId, String operationId, Path spillDirectory) {
        this.queryId = queryId;
        this.operationId = operationId;
        this.spillDirectory = spillDirectory;
        this.spillFiles = new ArrayList<>();
        this.bytesSpilled = 0;
        this.spillCount = 0;
        
        // Create the spill directory if it doesn't exist
        try {
            Files.createDirectories(spillDirectory);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to create spill directory: " + spillDirectory, e);
        }
    }
    
    /**
     * Spill data to disk
     * @return True if spill succeeded, false if it failed
     */
    public abstract boolean spillToDisk();
    
    /**
     * Read spilled data from disk
     * @return True if read succeeded, false if it failed
     */
    public abstract boolean readFromDisk();
    
    /**
     * Clean up spill files
     */
    public void cleanup() {
        for (Path spillFile : spillFiles) {
            try {
                Files.deleteIfExists(spillFile);
                LOGGER.fine("Deleted spill file: " + spillFile);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to delete spill file: " + spillFile, e);
            }
        }
        
        spillFiles.clear();
    }
    
    /**
     * Create a new spill file
     * @return Path to the spill file
     */
    protected Path createSpillFile() {
        String fileName = "spill_" + queryId + "_" + operationId + "_" + 
                         UUID.randomUUID().toString() + ".tmp";
        Path spillFile = spillDirectory.resolve(fileName);
        spillFiles.add(spillFile);
        return spillFile;
    }
    
    /**
     * Get the number of bytes spilled
     * @return Bytes spilled
     */
    public long getBytesSpilled() {
        return bytesSpilled;
    }
    
    /**
     * Get the number of spill operations
     * @return Spill count
     */
    public int getSpillCount() {
        return spillCount;
    }
    
    /**
     * Get the list of spill files
     * @return List of spill files
     */
    public List<Path> getSpillFiles() {
        return new ArrayList<>(spillFiles);
    }
    
    /**
     * Set the spill directory
     * @param directory Spill directory
     */
    public static void setDefaultSpillDirectory(String directory) {
        System.setProperty("serengeti.spill.directory", directory);
    }
    
    /**
     * Get the default spill directory
     * @return Default spill directory
     */
    public static String getDefaultSpillDirectory() {
        return System.getProperty("serengeti.spill.directory", DEFAULT_SPILL_DIRECTORY);
    }
}