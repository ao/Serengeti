package gl.ao.serengeti;

import gl.ao.serengeti.helpers.ShutdownHandler;
import gl.ao.serengeti.network.Network;
import gl.ao.serengeti.server.Server;
import gl.ao.serengeti.storage.Storage;
import gl.ao.serengeti.storage.StorageReshuffle;
import gl.ao.serengeti.storage.StorageScheduler;
import gl.ao.serengeti.ui.Interactive;

import java.util.Date;

public class Serengeti {

    public static Storage storage = null;
    public static Server server = new Server();
    public static Date currentDate = new Date();
    public static Network network = new Network();
    public static StorageScheduler storageScheduler = new StorageScheduler();
    public static StorageReshuffle storageReshuffle = new StorageReshuffle();
    public Interactive interactive;
    public Serengeti instance;

    public static long startTime;

    /***
     * Main application entry point
     * @param args
     */
    public static void main(String[] args) {
        new Serengeti();
    }


    /**
     * Constructor
     */
    public Serengeti() {
        interactive = new Interactive();
        startTime = System.currentTimeMillis();
        System.out.println("Starting Serengeti..\n");
        instance = this;
        server.init();
        storage = new Storage();
        network.init();

        storageScheduler.init();
        new ShutdownHandler();
    }

}
