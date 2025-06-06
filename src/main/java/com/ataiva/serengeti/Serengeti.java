 package com.ataiva.serengeti;

import com.ataiva.serengeti.index.IndexManager;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.network.NetworkFactory;
import com.ataiva.serengeti.search.FullTextSearch;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageFactory;
import com.ataiva.serengeti.server.Server;
import com.ataiva.serengeti.server.ServerFactory;
import com.ataiva.serengeti.server.ServerImpl;
import com.ataiva.serengeti.storage.StorageReshuffle;
import com.ataiva.serengeti.storage.StorageScheduler;

import java.util.Date;

public class Serengeti {
    public static Network network;
    public static Server server;
    public static Storage storage;
    public static StorageReshuffle storageReshuffle;
    public static StorageScheduler storageScheduler;
    public static IndexManager indexManager;
    public static FullTextSearch fullTextSearch;
    public static long startTime = System.currentTimeMillis();
    public static Date currentDate = new Date();
    public static void main(String[] args) {
        System.out.println("Serengeti Database System");
        
        // Initialize components
        storage = StorageFactory.createStorage(StorageFactory.StorageType.REAL);
        
        // Create network using factory
        network = NetworkFactory.createNetwork(NetworkFactory.NetworkType.REAL);
        network.init();
        
        // Create server using factory
        server = ServerFactory.createServer(ServerFactory.ServerType.REAL);
        server.init();
        
        storageReshuffle = new StorageReshuffle();
        storageScheduler = new StorageScheduler();
        
        // Initialize the index manager
        indexManager = new IndexManager();
        
        // Initialize the full-text search
        fullTextSearch = new FullTextSearch();
        
        // Start the server
        server.serve();
        
        System.out.println("Serengeti system initialized successfully");
        
        // Add shutdown hook to gracefully shutdown components
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down Serengeti...");
            
            // Shutdown server if it's a ServerImpl
            if (server instanceof ServerImpl) {
                ((ServerImpl) server).shutdown();
            }
            
            // Shutdown network
            if (network != null) {
                network.shutdown();
            }
            
            // Shutdown storage
            if (storage != null) {
                storage.shutdown();
            }
            
            System.out.println("Serengeti shutdown complete");
        }));
    }
}
