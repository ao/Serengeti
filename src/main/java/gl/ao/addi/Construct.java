package gl.ao.addi;

import gl.ao.addi.index.Indexer;
import gl.ao.addi.network.Network;
import gl.ao.addi.storage.Storage;

import java.util.Date;

public class Construct {

    public static String data_path = System.getProperty("user.dir") != null ? System.getProperty("user.dir")+"/data/" : "./data/";
    public Construct instance = null;
    public static Storage storage = new Storage();
    public Server server = new Server();
    public static Date currentDate = new Date();
    public static String me = null;
    public static Network network = new Network();
    public static Indexer indexer = new Indexer();

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
        this.instance = this;
        this.server.serve();
        this.network.findNodes();
        this.network.listenForCommunications();
    }

}
