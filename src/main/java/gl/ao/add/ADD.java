package gl.ao.add;

import gl.ao.add.helpers.Globals;
import gl.ao.add.network.Network;
import gl.ao.add.schema.DatabaseObject;
import gl.ao.add.server.Server;
import gl.ao.add.storage.Storage;
import gl.ao.add.ui.Interactive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;

public class ADD {

    public ADD instance = null;
    public static Storage storage = null;
    public static Server server = new Server();
    public static Date currentDate = new Date();
    public static Network network = new Network();
    public Interactive interactive = new Interactive();

    public static long startTime;

    /***
     * Main application entry point
     * @param args
     */
    public static void main(String[] args) {
        new ADD().init();
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

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("Safe Shutdown Initiated..");

                if (ADD.network.online) {

                    try {

                        // Make sure to save current in-memory objects to disk before terminating the server
                        for (String key : ADD.storage.databases.keySet()) {
                            DatabaseObject dbo = ADD.storage.databases.get(key);

                            String dbName = dbo.name;
                            List tables = dbo.tables;

                            byte data[] = dbo.returnDBObytes();
                            Path file = Paths.get(Globals.data_path + dbName + Globals.meta_extention);
                            Files.write(file, data);
                            System.out.println(" * Written db: '" + dbName + "' to disk");

                            for (Object table : tables) {
                                ADD.storage.tableStorageObjects.get(dbName + "#" + table).saveToDisk();
                                System.out.println(" └- Written table: '" + dbName + "'#'" + table + "' storage to disk");
                                ADD.storage.tableReplicaObjects.get(dbName + "#" + table).saveToDisk();
                                System.out.println(" └- Written table: '" + dbName + "'#'" + table + "' replica to disk");
                            }
                        }

                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }

                } else {
                    System.out.println(" * Node reported as not having started fully, so skipping disk persistence..");
                }

                System.out.println("Safe Shutdown Successful");
            }
        }));
    }

}
