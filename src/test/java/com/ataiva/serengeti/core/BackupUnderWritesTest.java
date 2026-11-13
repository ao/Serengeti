package com.ataiva.serengeti.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.server.Backup;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The plan's acceptance test for backups: a backup taken while writers run over HTTP restores to a node that
 * holds every write acknowledged before the backup began. Real threads, real sockets, real fsync.
 */
class BackupUnderWritesTest {
    private static final String PW = "correct horse battery";
    private static final int WRITERS = 4;
    private static final int PER_WRITER = 400;

    @TempDir
    Path tmp;

    private final HttpClient http = HttpClient.newHttpClient();

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private Config config(Path data) throws Exception {
        return Config.load(null, Map.of("SERENGETI_DATA_DIR", data.toString(), "SERENGETI_ADMIN_PASSWORD", PW,
                "SERENGETI_PORT", Integer.toString(freePort())));
    }

    private static HttpRequest.Builder authed(String url) {
        return HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Basic "
                + Base64.getEncoder().encodeToString(("admin:" + PW).getBytes(StandardCharsets.UTF_8)));
    }

    private int query(Node n, String q) throws Exception {
        HttpRequest r = authed("http://127.0.0.1:" + n.address().getPort() + "/v1/query")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("query", q)))).build();
        return http.send(r, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private Set<String> presentIds(Node n) throws Exception {
        HttpRequest r = authed("http://127.0.0.1:" + n.address().getPort() + "/v1/query")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\": \"SELECT _id FROM d.t LIMIT 10000\"}")).build();
        HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), resp.body());
        Map<String, Object> body = Json.parseObject(resp.body());
        assertEquals(false, body.get("truncated"));
        Set<String> ids = new HashSet<>();
        for (Object row : (List<?>) body.get("rows")) {
            ids.add((String) ((List<?>) row).get(0));
        }
        return ids;
    }

    @Test
    void aBackupTakenDuringWritesHoldsEveryWriteAcknowledgedBeforeItBegan() throws Exception {
        Path data = tmp.resolve("source");
        Set<String> acked = ConcurrentHashMap.newKeySet();
        Path file = tmp.resolve("backup.jsonl");
        Set<String> before = null;
        try (Node n = Node.start(config(data), Node.QUERIES)) {
            assertEquals(200, query(n, "CREATE DATABASE d"));
            assertEquals(200, query(n, "CREATE TABLE d.t"));

            ExecutorService writers = Executors.newFixedThreadPool(WRITERS);
            AtomicBoolean stop = new AtomicBoolean();
            for (int w = 0; w < WRITERS; w++) {
                int writer = w;
                writers.submit(() -> {
                    for (int i = 0; i < PER_WRITER && !stop.get(); i++) {
                        String id = "w" + writer + "-" + i;
                        try {
                            // Recorded only after the 200 comes back: that is the acknowledgement.
                            if (query(n, "INSERT INTO d.t (_id, n) VALUES ('" + id + "', " + i + ")") == 200) {
                                acked.add(id);
                            } else {
                                return;
                            }
                        } catch (Exception e) {
                            return; // the node closed or the connection failed: this writer is done
                        }
                    }
                });
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (acked.size() < 200 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(acked.size() >= 200, "writers acknowledged " + acked.size());

            // Everything in this copy was acknowledged before the backup request was sent.
            before = Set.copyOf(acked);
            HttpRequest get = authed("http://127.0.0.1:" + n.address().getPort() + "/v1/backup").GET().build();
            HttpResponse<Path> resp = http.send(get, HttpResponse.BodyHandlers.ofFile(file));
            assertEquals(200, resp.statusCode());

            stop.set(true);
            writers.shutdown();
            assertTrue(writers.awaitTermination(60, TimeUnit.SECONDS));
        }

        Path restored = tmp.resolve("restored");
        int ops = Backup.restore(file, restored);
        assertTrue(ops > 0);
        Set<String> present;
        try (Node r = Node.start(config(restored), Node.QUERIES)) {
            present = presentIds(r);
        }
        Set<String> missing = new java.util.TreeSet<>(before);
        missing.removeAll(present);
        assertEquals(Set.of(), missing, "acknowledged before the backup began, but not restored");
        assertTrue(present.size() >= before.size());
        assertTrue(Files.exists(restored.resolve("snapshot.jsonl")));
    }
}
