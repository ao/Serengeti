 package com.ataiva.serengeti;

import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.server.Server;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageReshuffle;
import com.ataiva.serengeti.storage.StorageScheduler;

import java.util.Date;

public class Serengeti {
    public static Network network;
    public static Server server;
    public static Storage storage;
    public static StorageReshuffle storageReshuffle;
    public static StorageScheduler storageScheduler;
    public static long startTime = System.currentTimeMillis();
    public static Date currentDate = new Date();
    public static void main(String[] args) {
        System.out.println("Serengeti Database System");
        
        // Initialize components
        storage = new Storage();
        
        network = new Network();
        network.init();
        
        server = new Server();
        server.init();
        
        storageReshuffle = new StorageReshuffle();
        storageScheduler = new StorageScheduler();
        
        // Start the server
        server.serve();
    }
}
