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

    public boolean addToQueue(Object obj) {
        queue.add(obj);
        return true;
    }

    private void executeIndexer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (;;) {
                    if (queue.size()>0) {
                        Object item = queue.poll();

                        Map<String, String> index = new HashMap<>();
                        index.put(Integer.toString(index.hashCode()), Integer.toString(index.hashCode())+"_1");
                    }
                }
            }
        }).start();

    }

    public void runIndexer() {

    }
}
