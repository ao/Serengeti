package gl.ao.addi;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

public class Indexer {

    public Queue<Object> queue = new LinkedList<>();

    public Indexer() {
        executeIndexer();
    }

    public synchronized boolean addToQueue(Object obj) {
        queue.add(obj);
        return true;
    }
    public synchronized Object pollQueue() {
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
                    Object item;
                    if (null != (item=pollQueue())) {
                        Map<String, String> index = new HashMap<>();
                        index.put(Integer.toString(item.hashCode()), Integer.toString(item.hashCode())+"_1");
                    }
                }
            }
        }).start();

    }

    public void runIndexer() {

    }
}
