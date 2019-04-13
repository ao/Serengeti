package gl.ao.add;

import gl.ao.add.index.Indexer;
import gl.ao.add.network.Network;
import gl.ao.add.server.Server;
import gl.ao.add.storage.Storage;
import gl.ao.add.ui.Interactive;

import java.util.Date;

public class Construct {

    public Construct instance = null;
    public static Storage storage = null;
    public static Server server = new Server();
    public static Date currentDate = new Date();
    public static Network network = new Network();
    public static Indexer indexer = new Indexer();
    public Interactive interactive = new Interactive();

    public static long startTime;

    /***
     * Main application entry point
     * @param args
     */
    public static void main(String[] args) {
        new Construct().init();
    }

    /***
     * Initialisation object
     */
    private void init() {
        this.startTime = System.currentTimeMillis();
        System.out.println("Starting ADD..");
        this.instance = this;
        this.server.init();
        storage = new Storage();
        this.network.initiate();
    }

}
