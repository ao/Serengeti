package com.ataiva.serengeti.core.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.Node;
import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.server.InternalRoutes;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClusterTest {
    private static final String KEY = "a cluster key of at least 32 characters";
    private static final String PW = "correct horse battery";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @TempDir
    Path dir;

    private final int[] ports = new int[3];
    private final Node[] nodes = new Node[3];

    @BeforeEach
    void ports() throws IOException {
        for (int i = 0; i < 3; i++) {
            try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                ports[i] = s.getLocalPort();
            }
        }
    }

    @AfterEach
    void stopAll() throws IOException {
        for (int i = 0; i < 3; i++) {
            stop(i);
        }
    }

    private Config config(int i, String key) {
        List<String> peers = new ArrayList<>();
        for (int j = 0; j < 3; j++) {
            if (j != i) {
                peers.add("127.0.0.1:" + ports[j]);
            }
        }
        Map<String, String> env = new HashMap<>();
        env.put("SERENGETI_DATA_DIR", dir.resolve("n" + i).toString());
        env.put("SERENGETI_PORT", Integer.toString(ports[i]));
        env.put("SERENGETI_PEERS", String.join(",", peers));
        env.put("SERENGETI_CLUSTER_KEY", key);
        env.put("SERENGETI_CLUSTER_PLAINTEXT", "allow");
        env.put("SERENGETI_ADMIN_PASSWORD", PW);
        return Config.load(null, env);
    }

    private void start(int i) throws IOException {
        start(i, KEY);
    }

    private void start(int i, String key) throws IOException {
        nodes[i] = Node.start(config(i, key), Node.QUERIES);
    }

    private void stop(int i) throws IOException {
        if (nodes[i] != null) {
            nodes[i].close();
            nodes[i] = null;
        }
    }

    private HttpResponse<String> query(int i, String q) throws Exception {
        return query(i, q, "local");
    }

    private HttpResponse<String> query(int i, String q, String consistency) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports[i] + "/v1/query"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(("admin:" + PW).getBytes(StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("query", q, "consistency", consistency))))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private List<Object> rows(int i, String q) throws Exception {
        HttpResponse<String> r = query(i, q);
        assertEquals(200, r.statusCode(), r.body());
        return (List<Object>) Json.parseObject(r.body()).get("rows");
    }

    private static void eventually(String what, BooleanSupplier ok) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (ok.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    private void waitWritable(int i) throws InterruptedException {
        eventually("node " + i + " accepts writes", () -> nodes[i].cluster().caughtUp()
                && nodes[i].cluster().reachable() >= nodes[i].cluster().quorum());
    }

    private boolean has(int i, String q, Object expected) {
        try {
            return rows(i, q).equals(expected);
        } catch (Exception | AssertionError e) {
            return false;
        }
    }

    @Test
    void writesOnOneNodeReachEveryNode() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        waitWritable(0);
        assertEquals(200, query(0, "CREATE DATABASE shop").statusCode());
        assertEquals(200, query(0, "CREATE TABLE shop.items").statusCode());
        assertEquals(200, query(0, "INSERT INTO shop.items (_id, name) VALUES ('a', 'bolt')").statusCode());
        List<Object> expected = List.of(List.of("a", "bolt"));
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("row on node " + n, () -> has(n, "SELECT _id, name FROM shop.items", expected));
        }
        waitWritable(2);
        assertEquals(200, query(2, "UPDATE shop.items SET name = 'nut' WHERE _id = 'a'").statusCode());
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("update on node " + n, () -> has(n, "SELECT name FROM shop.items", List.of(List.of("nut"))));
        }
    }

    @Test
    void aLoneNodeRefusesWritesUntilAMajorityIsUp() throws Exception {
        start(0);
        Thread.sleep(1500);
        HttpResponse<String> r = query(0, "CREATE DATABASE d");
        assertEquals(503, r.statusCode(), r.body());
        assertTrue(r.body().contains("catching up"), r.body());
        start(1);
        waitWritable(0);
        assertEquals(200, query(0, "CREATE DATABASE d").statusCode());
    }

    @Test
    void writesContinueWithOneNodeDownAndStopWithTwoDown() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        waitWritable(0);
        query(0, "CREATE DATABASE d");
        query(0, "CREATE TABLE d.t");
        stop(2);
        assertEquals(200, query(0, "INSERT INTO d.t (_id) VALUES ('one-down')").statusCode());
        stop(1);
        eventually("node 0 notices", () -> nodes[0].cluster().reachable() < 2);
        HttpResponse<String> r = query(0, "INSERT INTO d.t (_id) VALUES ('two-down')");
        assertEquals(503, r.statusCode(), r.body());
        assertTrue(r.body().contains("1 of 3 nodes are reachable"), r.body());
        // Reads still work, from this node's data.
        assertEquals(200, query(0, "SELECT * FROM d.t").statusCode());
        // A majority read cannot be served.
        assertEquals(503, query(0, "SELECT * FROM d.t", "majority").statusCode());
    }

    @Test
    void aRestartedNodeCatchesUpOnWhatItMissed() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        waitWritable(0);
        query(0, "CREATE DATABASE d");
        query(0, "CREATE TABLE d.t");
        stop(2);
        for (int k = 0; k < 20; k++) {
            assertEquals(200, query(0, "INSERT INTO d.t (_id) VALUES ('r" + k + "')").statusCode());
        }
        assertEquals(200, query(1, "DELETE FROM d.t WHERE _id = 'r0'").statusCode());
        start(2);
        eventually("node 2 has 19 rows", () -> has(2, "SELECT COUNT(*) FROM d.t", List.of(List.of(19L))));
        waitWritable(2);
    }

    @Test
    void concurrentWritesToOneRowConvergeOnEveryNode() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        for (int i = 0; i < 3; i++) {
            waitWritable(i);
        }
        query(0, "CREATE DATABASE d");
        query(0, "CREATE TABLE d.t");
        query(0, "INSERT INTO d.t (_id, v) VALUES ('x', 0)");
        List<Thread> writers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int n = i;
            Thread t = new Thread(() -> {
                for (int k = 1; k <= 10; k++) {
                    try {
                        query(n, "UPDATE d.t SET v = " + (n * 100 + k) + " WHERE _id = 'x'");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            writers.add(t);
            t.start();
        }
        for (Thread t : writers) {
            t.join();
        }
        eventually("every node agrees on x", () -> {
            try {
                List<Object> a = rows(0, "SELECT v FROM d.t");
                return a.equals(rows(1, "SELECT v FROM d.t")) && a.equals(rows(2, "SELECT v FROM d.t"));
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    void majorityReadsSeeAcknowledgedWrites() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        waitWritable(0);
        query(0, "CREATE DATABASE d");
        query(0, "CREATE TABLE d.t");
        for (int k = 0; k < 10; k++) {
            assertEquals(200, query(0, "INSERT INTO d.t (_id) VALUES ('m" + k + "')").statusCode());
            HttpResponse<String> r = query(2, "SELECT COUNT(*) FROM d.t", "majority");
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().contains("[[" + (k + 1) + "]]"), r.body());
        }
        assertEquals(400, query(0, "SELECT * FROM d.t", "bogus").statusCode());
    }

    @Test
    void internalRoutesNeedTheClusterKey() throws Exception {
        start(0);
        URI uri = URI.create("http://127.0.0.1:" + ports[0] + "/internal/digests");
        assertEquals(401, HTTP.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, HTTP.send(HttpRequest.newBuilder(uri).header(InternalRoutes.KEY_HEADER, KEY + "x").build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, HTTP.send(HttpRequest.newBuilder(uri).header(InternalRoutes.KEY_HEADER, KEY).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpResponse<String> apply = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + ports[0] + "/internal/apply"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"ops\": []}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, apply.statusCode());
    }

    @Test
    void aNodeWithTheWrongKeyNeverJoins() throws Exception {
        start(0);
        start(1);
        start(2, "a different key of at least 32 characters");
        waitWritable(0);
        Thread.sleep(2500);
        assertFalse(nodes[2].cluster().caughtUp());
        assertEquals(503, query(2, "CREATE DATABASE d").statusCode());
        assertEquals(200, query(0, "CREATE DATABASE d").statusCode());
        assertEquals(List.of(), rows(2, "SHOW DATABASES"));
    }

    @Test
    void singleNodesHaveNoInternalRoutes() throws Exception {
        Map<String, String> env = Map.of("SERENGETI_DATA_DIR", dir.resolve("single").toString(),
                "SERENGETI_PORT", Integer.toString(ports[0]), "SERENGETI_ADMIN_PASSWORD", PW);
        try (Node n = Node.start(Config.load(null, env), Node.QUERIES)) {
            assertEquals(404, HTTP.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + ports[0] + "/internal/digests"))
                    .header(InternalRoutes.KEY_HEADER, KEY).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
