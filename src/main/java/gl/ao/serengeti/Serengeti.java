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

    public gl.ao.serengeti.Serengeti instance = null;
    public static Storage storage = null;
    public static Server server = new Server();
    public static Date currentDate = new Date();
    public static Network network = new Network();
    public static StorageScheduler storageScheduler = new StorageScheduler();
    public static StorageReshuffle storageReshuffle = new StorageReshuffle();
    public Interactive interactive = new Interactive();

    public static long startTime;

    /***
     * Main application entry point
     * @param args
     */
    public static void main(String[] args) {
        new gl.ao.serengeti.Serengeti().init();
    }

    /***
     * Initialisation object
     */
    private void init() {
        startTime = System.currentTimeMillis();
        System.out.println("Starting Serengeti..\n");
        this.instance = this;
        server.init();
        storage = new Storage();
        network.init();

        storageScheduler.init();
        new ShutdownHandler();
    }

}
