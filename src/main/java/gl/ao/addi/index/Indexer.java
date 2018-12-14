package gl.ao.addi.index;

import gl.ao.addi.storage.StorageResponseObject;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

public class Indexer {

    public Queue<StorageResponseObject> queue = new LinkedList<>();

    public Indexer() {
        executeIndexer();
    }

    public synchronized  boolean addToQueue(StorageResponseObject sro) {
        queue.add(sro);
        return true;
    }
    public synchronized StorageResponseObject pollQueue() {
        if (queue.size()>0) {
            return queue.poll();
        } else {
            return null;
        }
    }

    private void executeIndexer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (;;) {
                    StorageResponseObject item;
                    if (null != (item=pollQueue())) {
                        Map<String, String> index = new HashMap<>();
                        //write out to index
                    }
                }
            }
        }).start();

    }

    public void loadIndex(String db, String table) {

    }
    public void saveIndex(String db, String table) {

    }
}
