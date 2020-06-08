package gl.ao.serengeti.storage;

import gl.ao.serengeti.helpers.Globals;
import gl.ao.serengeti.schema.DatabaseObject;

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

        if (gl.ao.serengeti.Serengeti.network.online && running==false) {
            running = true;

            try {

                if (gl.ao.serengeti.Serengeti.storage.databases.size()==0) {
                    System.out.println(" * No databases found, nothing to persist..");
                } else {
                    // Make sure to save current in-memory objects to disk before terminating the server
                    for (String key : gl.ao.serengeti.Serengeti.storage.databases.keySet()) {
                        DatabaseObject dbo = gl.ao.serengeti.Serengeti.storage.databases.get(key);

                        String dbName = dbo.name;
                        List tables = dbo.tables;

                        byte data[] = dbo.returnDBObytes();
                        Path file = Paths.get(Globals.data_path + dbName + Globals.meta_extention);
                        Files.write(file, data);
                        System.out.println(" * Written db: '" + dbName + "' to disk");

                        for (Object table : tables) {
                            gl.ao.serengeti.Serengeti.storage.tableStorageObjects.get(dbName + "#" + table).saveToDisk();
                            System.out.println(" └- Written table: '" + dbName + "'#'" + table + "' storage to disk ("
                                    + gl.ao.serengeti.Serengeti.storage.tableStorageObjects.get(dbName + "#"
                                    + table).rows.size() + " rows)");
                            gl.ao.serengeti.Serengeti.storage.tableReplicaObjects.get(dbName + "#" + table).saveToDisk();
                            System.out.println(" └- Written table: '" + dbName + "'#'" + table + "' replica to disk ("
                                    + gl.ao.serengeti.Serengeti.storage.tableReplicaObjects.get(dbName + "#"
                                    + table).row_replicas.size() + " rows)");
                        }
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
