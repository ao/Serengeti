package com.ataiva.serengeti.storage;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.schema.DatabaseObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;

public class StorageScheduler {

    public static boolean running = false;

    public StorageScheduler() {}

    public void init() {
        new Thread(() -> {
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
        }).start();
    }

    public boolean performPersistToDisk() {
        System.out.println(" * Persisting to disk at "+new Date());

        if (Network.online && !running) {
            running = true;

            try {

                if (Storage.databases.size()==0) {
                    System.out.println(" * No databases found, nothing to persist..");
                } else {
                    // Make sure to save current in-memory objects to disk before terminating the server
                    for (String key : Storage.databases.keySet()) {
                        DatabaseObject dbo = Storage.databases.get(key);

                        String dbName = dbo.name;
                        List<String> tables = dbo.tables;

                        byte[] data = dbo.returnDBObytes();
                        Path file = Paths.get(Globals.data_path + dbName + Globals.meta_extention);
                        Files.write(file, data);
                        System.out.println(" * Written db: '" + dbName + "' to disk");

                        for (Object table : tables) {
                            Storage.tableStorageObjects.get(dbName + "#" + table).saveToDisk();
                            System.out.println(" └- Written table: '" + dbName + "'#'" + table + "' storage to disk ("
                                    + Storage.tableStorageObjects.get(dbName + "#"
                                    + table).rows.size() + " rows)");
                            Storage.tableReplicaObjects.get(dbName + "#" + table).saveToDisk();
                            System.out.println(" └- Written table: '" + dbName + "'#'" + table + "' replica to disk ("
                                    + Storage.tableReplicaObjects.get(dbName + "#"
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
