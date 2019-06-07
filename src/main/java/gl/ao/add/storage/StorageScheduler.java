package gl.ao.add.storage;

import gl.ao.add.ADD;
import gl.ao.add.helpers.Globals;
import gl.ao.add.schema.DatabaseObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;

public class StorageScheduler {

    public static boolean running = false;

    public StorageScheduler() {}

    public void init() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        Thread.sleep(60 * 1000);
                        System.out.println("StorageScheduler Initiated..");
                        performPersistToDisk();
                        System.out.println("StorageScheduler Completed\n");
                    }
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                }
            }
        }).start();
    }

    public boolean performPersistToDisk() {
        System.out.println(" * Persisting to disk at "+new Date());

        if (ADD.network.online && running==false) {
            running = true;

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
                        System.out.println(" └- Written table: '" + dbName + "'#'" + table + "' storage to disk ("+ADD.storage.tableStorageObjects.get(dbName + "#" + table).rows.size()+" rows)");
                        ADD.storage.tableReplicaObjects.get(dbName + "#" + table).saveToDisk();
                        System.out.println(" └- Written table: '" + dbName + "'#'" + table + "' replica to disk ("+ADD.storage.tableReplicaObjects.get(dbName + "#" + table).row_replicas.size()+" rows)");
                    }
                }

            } catch (Exception ex) {
                ex.printStackTrace();
                running = false;
                return false;
            }

        } else {
            System.out.println(" * Node reported as not having started fully, so skipping disk persistence..");
            running = false;
            return false;
        }

        running = false;
        return true;

    }
}
