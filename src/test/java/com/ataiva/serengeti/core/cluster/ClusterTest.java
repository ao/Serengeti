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
        assertEquals(503, ready(0), "a lone node of three is not ready");
        start(1);
        waitWritable(0);
        assertEquals(200, query(0, "CREATE DATABASE d").statusCode());
        eventually("node 0 is ready", () -> ready(0) == 200);
    }

    private int ready(int i) {
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports[i] + "/ready")).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException | InterruptedException e) {
            return -1;
        }
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

    // ---- 2.1: bucket sync, local acknowledgement, tombstone TTL ----

    private HttpResponse<String> write(int i, String q, String ack) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("query", q);
        if (ack != null) {
            body.put("ack", ack);
        }
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports[i] + "/v1/query"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(("admin:" + PW).getBytes(StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aPeerThatNeverAnswersDoesNotMakeTheOthersLookUnreachable() throws Exception {
        // Node 2's port is held by a socket that accepts connections and never answers, as a partitioned
        // node does when its packets are dropped: every call to it runs until its timeout.
        try (ServerSocket hole = new ServerSocket(ports[2], 50, InetAddress.getLoopbackAddress())) {
            List<java.net.Socket> held = new java.util.concurrent.CopyOnWriteArrayList<>();
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        held.add(hole.accept());
                    }
                } catch (IOException e) {
                    // closed at the end of the test
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            start(0);
            start(1);
            waitWritable(0);
            assertEquals(200, query(0, "CREATE DATABASE d").statusCode());
            assertEquals(200, query(0, "CREATE TABLE d.t").statusCode());
            // Several sync rounds with no writes (a write's acknowledgements would also count as contact):
            // node 1 must stay reachable from node 0 throughout, and a write after that must succeed.
            long end = System.currentTimeMillis() + 12_000;
            int samples = 0;
            int low = 0;
            while (System.currentTimeMillis() < end) {
                if (nodes[0].cluster().reachable() < 2) {
                    low++;
                }
                samples++;
                Thread.sleep(50);
            }
            assertEquals(0, low, "node 1 counted as unreachable from node 0 in " + low + " of " + samples + " samples");
            HttpResponse<String> r = query(0, "INSERT INTO d.t (n) VALUES (1)");
            assertEquals(200, r.statusCode(), r.body());
            for (java.net.Socket s : held) {
                s.close();
            }
        }
    }

    private int as(int i, String user, String password, String method, String path, String body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports[i] + path))
                    .header("Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(20));
            if (body != null) {
                b.header("Content-Type", "application/json");
            }
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    @Test
    void accountsMadeOnOneNodeWorkOnEveryNodeAndDeletionsSpread() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        waitWritable(0);
        assertEquals(200, query(0, "CREATE DATABASE d").statusCode());
        assertEquals(200, query(0, "CREATE TABLE d.t").statusCode());
        assertEquals(201, as(0, "admin", PW, "PUT", "/v1/users/reader", "{\"password\": \"reader password 1\", \"role\": \"read\"}"));
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " knows the reader", () -> as(n, "reader", "reader password 1", "GET", "/v1/status", null) == 200);
        }
        // The read role holds on every node, not only where the account was made.
        assertEquals(403, as(2, "reader", "reader password 1", "POST", "/v1/query", "{\"query\": \"INSERT INTO d.t (n) VALUES (1)\"}"));
        assertEquals(200, as(2, "reader", "reader password 1", "POST", "/v1/query", "{\"query\": \"SELECT * FROM d.t\"}"));
        // A node that is down while accounts change catches up when it comes back.
        stop(2);
        assertEquals(200, as(0, "admin", PW, "DELETE", "/v1/users/reader", null));
        assertEquals(201, as(1, "admin", PW, "PUT", "/v1/users/writer", "{\"password\": \"writer password 1\", \"role\": \"write\"}"));
        start(2);
        eventually("node 2 learns the deletion", () -> as(2, "reader", "reader password 1", "GET", "/v1/status", null) == 401);
        eventually("node 2 learns the new account", () -> as(2, "writer", "writer password 1", "GET", "/v1/status", null) == 200);
        // A password change spreads, and the old password stops working everywhere.
        assertEquals(200, as(1, "admin", PW, "POST", "/v1/password", "{\"password\": \"a new admin password\"}"));
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " has the new admin password",
                    () -> as(n, "admin", "a new admin password", "GET", "/v1/users", null) == 200
                            && as(n, "admin", PW, "GET", "/v1/status", null) == 401);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aListOfDatabasesMadeOnOneNodeHoldsOnEveryNodeAndAMalformedOneIsRefused() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        waitWritable(0);
        for (String q : List.of("CREATE DATABASE d", "CREATE TABLE d.t", "CREATE DATABASE e", "CREATE TABLE e.t")) {
            assertEquals(200, query(0, q).statusCode(), q);
        }
        String pw = "limited password 1";
        assertEquals(201, as(0, "admin", PW, "PUT", "/v1/users/app",
                "{\"password\": \"" + pw + "\", \"role\": \"read\", \"databases\": [\"d\"]}"));
        String inD = "{\"query\": \"SELECT * FROM d.t\"}";
        String inE = "{\"query\": \"SELECT * FROM e.t\"}";
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " knows app", () -> as(n, "app", pw, "POST", "/v1/query", inD) == 200);
            // The account arrives with its list: no node has it without the list, even for a moment.
            assertEquals(403, as(n, "app", pw, "POST", "/v1/query", inE));
            assertEquals(403, as(n, "app", pw, "GET", "/v1/changes?db=e&after=now", null));
        }
        // Removing the list on another node spreads like a role change.
        assertEquals(200, as(1, "admin", PW, "PUT", "/v1/users/app", "{\"databases\": null}"));
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " learns the list is gone", () -> as(n, "app", pw, "POST", "/v1/query", inE) == 200);
        }
        // A node refuses a peer's account entry whose list is malformed, rather than read it as no limit.
        assertEquals(200, as(2, "admin", PW, "PUT", "/v1/users/app", "{\"databases\": [\"d\"]}"));
        URI users = URI.create("http://127.0.0.1:" + ports[0] + "/internal/v3/users");
        eventually("node 0 has the list again", () -> as(0, "app", pw, "POST", "/v1/query", inE) == 403);
        Map<String, Object> entry = null;
        for (Object o : (List<Object>) Json.parseObject(HTTP.send(HttpRequest.newBuilder(users)
                .header(InternalRoutes.KEY_HEADER, KEY).build(), HttpResponse.BodyHandlers.ofString()).body()).get("users")) {
            if ("app".equals(((Map<String, Object>) o).get("user"))) {
                entry = new java.util.LinkedHashMap<>((Map<String, Object>) o);
            }
        }
        assertEquals(List.of("d"), entry.get("databases"));
        String[] stamp = ((String) entry.get("at")).split("\\.", 3);
        entry.put("at", (Long.parseLong(stamp[0]) + 1) + "." + stamp[1] + "." + stamp[2]); // newer than what node 0 has
        for (Object bad : java.util.Arrays.asList("d", List.of(), List.of("e", "d"), List.of("D"), null)) {
            Map<String, Object> e = new java.util.LinkedHashMap<>(entry);
            e.put("databases", bad);
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(users).header(InternalRoutes.KEY_HEADER, KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("users", List.of(e))))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, r.statusCode(), bad + ": " + r.body());
        }
        assertEquals(403, as(0, "app", pw, "POST", "/v1/query", inE), "still limited");
        assertEquals(200, as(0, "app", pw, "POST", "/v1/query", inD));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aWriteOnOneNodeShowsInAnotherNodesChangeFeed() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        waitWritable(0);
        assertEquals(200, query(0, "CREATE DATABASE d").statusCode());
        assertEquals(200, query(0, "CREATE TABLE d.t").statusCode());
        eventually("node 1 has the table", () -> has(1, "SHOW TABLES IN d", List.of(List.of("t"))));
        String cursor = (String) Json.parseObject(get(1, "/v1/changes?db=d&after=now")).get("cursor");
        assertEquals(200, query(0, "INSERT INTO d.t (_id, n) VALUES ('a', 1)").statusCode());
        Map<String, Object> page = Json.parseObject(get(1, "/v1/changes?db=d&table=t&wait=10&after=" + cursor));
        List<Map<String, Object>> changes = (List<Map<String, Object>>) page.get("changes");
        assertEquals(1, changes.size(), page.toString());
        assertEquals("a", changes.get(0).get("_id"));
        assertEquals(nodes[0].id(), changes.get(0).get("node"), "the change names the node that wrote it");
    }

    private String get(int i, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports[i] + path))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(("admin:" + PW).getBytes(StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void anIndexCreatedOnOneNodeIsBuiltOnEveryNode() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        waitWritable(0);
        assertEquals(200, query(0, "CREATE DATABASE d").statusCode());
        assertEquals(200, query(0, "CREATE TABLE d.t").statusCode());
        assertEquals(200, query(0, "INSERT INTO d.t (_id, n) VALUES ('a', 1), ('b', 2), ('c', 2)").statusCode());
        assertEquals(200, query(1, "CREATE INDEX ON d.t (n)").statusCode());
        stop(2);
        start(2); // a node that restarts rebuilds it from its log
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " has the index", () -> has(n, "SHOW INDEXES IN d.t", List.of(List.of("n"))));
            eventually("node " + n + " finds rows through it",
                    () -> has(n, "SELECT _id FROM d.t WHERE n = 2", List.of(List.of("b"), List.of("c"))));
        }
        waitWritable(2);
        assertEquals(200, query(2, "DROP INDEX ON d.t (n)").statusCode());
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " dropped the index", () -> has(n, "SHOW INDEXES IN d.t", List.of()));
        }
    }

    @Test
    void localWritesOnNodesThatRanAloneConvergeWhenTheyMeet() throws Exception {
        start(0);
        Thread.sleep(1200);
        assertEquals(503, write(0, "CREATE DATABASE d", null).statusCode(), "a lone node refuses majority writes");
        assertEquals(200, write(0, "CREATE DATABASE d", "local").statusCode());
        assertEquals(200, write(0, "CREATE TABLE d.t", "local").statusCode());
        assertEquals(200, write(0, "INSERT INTO d.t (_id, side) VALUES ('a', 'zero')", "local").statusCode());
        stop(0);
        // Node 1 has never seen node 0's data: it was down while node 0 ran alone.
        start(1);
        Thread.sleep(1200);
        assertEquals(200, write(1, "CREATE DATABASE d", "local").statusCode());
        assertEquals(200, write(1, "CREATE TABLE d.t", "local").statusCode());
        assertEquals(200, write(1, "INSERT INTO d.t (_id, side) VALUES ('b', 'one')", "local").statusCode());
        // Both nodes write row 'c' without having seen each other's write: a conflict once they meet.
        assertEquals(200, write(1, "INSERT INTO d.t (_id, side) VALUES ('c', 'one')", "local").statusCode());
        assertEquals(400, write(1, "SHOW DATABASES", "eventually").statusCode());
        stop(1);
        start(0);
        Thread.sleep(1200);
        assertEquals(200, write(0, "INSERT INTO d.t (_id, side) VALUES ('c', 'zero')", "local").statusCode());
        start(1);
        start(2);
        List<Object> all = List.of(List.of("a", "zero"), List.of("b", "one"), List.of("c", "zero"));
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " has both sides' rows, the later write to c winning",
                    () -> has(n, "SELECT _id, side FROM d.t", all));
            eventually("node " + n + " shows both versions of c",
                    () -> conflictSides(n).equals(List.of("zero", "one")));
        }
        // A write that has seen both versions resolves the conflict on every node.
        waitWritable(0);
        assertEquals(200, query(0, "UPDATE d.t SET side = 'both' WHERE _id = 'c'").statusCode());
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " has no conflict left", () -> conflictSides(n).isEmpty());
        }
    }

    /** The 'side' of each version SHOW CONFLICTS lists on node i, current first. */
    @SuppressWarnings("unchecked")
    private List<Object> conflictSides(int i) {
        try {
            HttpResponse<String> r = query(i, "SHOW CONFLICTS IN d.t");
            if (r.statusCode() != 200) {
                return List.of("status " + r.statusCode());
            }
            List<Object> out = new java.util.ArrayList<>();
            for (Object row : (List<Object>) ((Map<String, Object>) Json.parse(r.body())).get("rows")) {
                out.add(((Map<String, Object>) ((List<Object>) row).get(5)).get("side"));
            }
            return out;
        } catch (Exception e) {
            return List.of(e.toString());
        }
    }

    @Test
    void aSteadyClusterPullsNoRows() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        waitWritable(0);
        query(0, "CREATE DATABASE d");
        query(0, "CREATE TABLE d.t");
        StringBuilder values = new StringBuilder();
        for (int k = 0; k < 2000; k++) {
            values.append(k == 0 ? "" : ", ").append("('r").append(k).append("', ").append(k).append(')');
        }
        assertEquals(200, query(0, "INSERT INTO d.t (_id, n) VALUES " + values).statusCode());
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " has 2000 rows", () -> has(n, "SELECT COUNT(*) FROM d.t", List.of(List.of(2000L))));
        }
        Thread.sleep(2500);
        long[] before = new long[3];
        for (int i = 0; i < 3; i++) {
            before[i] = nodes[i].cluster().pulledOps();
        }
        Thread.sleep(3500);
        for (int i = 0; i < 3; i++) {
            assertEquals(before[i], nodes[i].cluster().pulledOps(), "node " + i + " pulled ops while nothing changed");
        }
        // One row changed on one node reaches the others by push; nothing like the whole table is pulled.
        stop(2);
        assertEquals(200, query(0, "UPDATE d.t SET n = -1 WHERE _id = 'r7'").statusCode());
        start(2);
        eventually("node 2 has the update", () -> has(2, "SELECT n FROM d.t WHERE _id = 'r7'", List.of(List.of(-1L))));
        Thread.sleep(1500);
        assertTrue(nodes[2].cluster().pulledOps() < 200, "pulled " + nodes[2].cluster().pulledOps() + " ops to catch up one row");
    }

    @Test
    void aNodeAwayLongerThanTheTombstoneTtlStartsStaleAndHandsOnOnlyItsNewerWrites() throws Exception {
        long now = System.currentTimeMillis();
        long lastSync = now - 2 * 3_600_000L;
        Path data = dir.resolve("n0");
        // Its data: a row from before it lost the cluster, and one it wrote while cut off.
        try (com.ataiva.serengeti.core.storage.StorageEngine e = com.ataiva.serengeti.core.storage.StorageEngine.open(
                data, com.ataiva.serengeti.core.storage.FsyncPolicy.ALWAYS)) {
            e.apply(List.of(new com.ataiva.serengeti.core.storage.Op.CreateDb("d", stampAt(lastSync - 60_000, "z0")),
                    new com.ataiva.serengeti.core.storage.Op.CreateTable("d", "t", stampAt(lastSync - 59_000, "z0")),
                    new com.ataiva.serengeti.core.storage.Op.Put("d", "t", "old", Map.of("n", 1L), stampAt(lastSync - 1_000, "z0")),
                    new com.ataiva.serengeti.core.storage.Op.Put("d", "t", "isolated", Map.of("n", 2L), stampAt(now - 60_000, "z0"))));
        }
        java.nio.file.Files.writeString(data.resolve(Cluster.LAST_SYNC_FILE), lastSync + "\n");
        Map<String, String> env = new HashMap<>();
        env.put("SERENGETI_DATA_DIR", data.toString());
        env.put("SERENGETI_PORT", Integer.toString(ports[0]));
        env.put("SERENGETI_PEERS", "127.0.0.1:" + ports[1]);
        env.put("SERENGETI_CLUSTER_KEY", KEY);
        env.put("SERENGETI_CLUSTER_PLAINTEXT", "allow");
        env.put("SERENGETI_ADMIN_PASSWORD", PW);
        env.put("SERENGETI_TOMBSTONE_TTL_HOURS", "1");
        Map<String, String> env1 = new HashMap<>(env);
        env1.put("SERENGETI_DATA_DIR", dir.resolve("n1").toString());
        env1.put("SERENGETI_PORT", Integer.toString(ports[1]));
        env1.put("SERENGETI_PEERS", "127.0.0.1:" + ports[0]);
        try (Node peer = Node.start(Config.load(null, env1), Node.QUERIES);
                Node n = Node.start(Config.load(null, env), Node.QUERIES)) {
            assertTrue(n.cluster().stale());
            assertEquals(503, query(0, "INSERT INTO d.t (n) VALUES (3)").statusCode(), "a stale node takes no writes");
            assertFalse(n.cluster().mayCollectTombstones());
            // It hands on what it wrote after it last synced, and nothing older.
            eventually("the peer has the write made while cut off",
                    () -> has(1, "SELECT _id FROM d.t", List.of(List.of("isolated"))));
            Thread.sleep(2500);
            assertTrue(has(1, "SELECT _id FROM d.t", List.of(List.of("isolated"))), "and never the old row");
        }
        // With a recent sync, the same node starts normally.
        java.nio.file.Files.writeString(data.resolve(Cluster.LAST_SYNC_FILE), System.currentTimeMillis() + "\n");
        try (Node n = Node.start(Config.load(null, env), Node.QUERIES)) {
            assertFalse(n.cluster().stale());
        }
    }

    private static com.ataiva.serengeti.core.storage.Stamp stampAt(long wall, String node) {
        return new com.ataiva.serengeti.core.storage.Stamp(wall, 0, node);
    }

    @Test
    void tombstonesAreCollectedOnlyOnceEveryPeerHasSyncedByBuckets() throws Exception {
        start(0);
        start(1);
        // Node 2 has not been seen since node 0 started: it may be a 2.0 node, or a restored backup.
        waitWritable(0);
        Thread.sleep(1500);
        assertFalse(nodes[0].cluster().mayCollectTombstones());
        start(2);
        eventually("every peer has synced by buckets", () -> nodes[0].cluster().mayCollectTombstones());
    }

    @Test
    void changesStampedFarInTheFutureAreRefused() throws Exception {
        start(0);
        long future = System.currentTimeMillis() + 10 * 60_000;
        String ops = "{\"ops\": [{\"op\": \"create_db\", \"db\": \"ahead\", \"at\": \"" + future + ".0.other\"}]}";
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports[0] + "/internal/apply"))
                .header(InternalRoutes.KEY_HEADER, KEY).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ops)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(409, r.statusCode(), r.body());
        assertTrue(r.body().contains("ahead of this node's clock"), r.body());
        long now = System.currentTimeMillis();
        String fine = "{\"ops\": [{\"op\": \"create_db\", \"db\": \"near\", \"at\": \"" + (now + 5_000) + ".0.other\"}]}";
        HttpResponse<String> ok = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports[0] + "/internal/apply"))
                .header(InternalRoutes.KEY_HEADER, KEY).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(fine)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals(List.of(List.of("near")), rows(0, "SHOW DATABASES"));
    }

    @Test
    void the20RoutesStillAnswerForRollingUpgrades() throws Exception {
        start(0);
        for (String path : List.of("/internal/digests", "/internal/state?db=x", "/internal/v2/roots",
                "/internal/v2/schema?db=x", "/internal/v2/buckets?db=x&table=t", "/internal/v2/rows?db=x&table=t&buckets=1,2")) {
            assertEquals(200, HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports[0] + path))
                    .header(InternalRoutes.KEY_HEADER, KEY).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), path);
        }
        assertEquals(400, HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports[0]
                + "/internal/v2/rows?db=x&table=t&buckets=9999")).header(InternalRoutes.KEY_HEADER, KEY).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    /**
     * Found by tests/soak (docs/SOAK.md, finding 1): Node.close() closed the cluster first and the HTTP server
     * after it, and the server went on answering for up to a second, so a local write already in the log, and
     * a majority read, got 500. A node whose cluster layer is closed answers 503 (or 200 for a write it has
     * logged).
     */
    @Test
    void aStoppingNodeDoesNotAnswerInternalError() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        for (int i = 0; i < 3; i++) {
            waitWritable(i);
        }
        assertEquals(200, query(0, "CREATE DATABASE d").statusCode());
        assertEquals(200, query(0, "CREATE TABLE d.t").statusCode());
        eventually("n1 has the table", () -> has(1, "SHOW TABLES IN d", List.of(List.of("t"))));
        nodes[1].cluster().close();
        HttpResponse<String> w = write(1, "INSERT INTO d.t (_id) VALUES ('x')", "local");
        HttpResponse<String> m = write(1, "INSERT INTO d.t (_id) VALUES ('y')", "majority");
        HttpResponse<String> r = query(1, "SELECT COUNT(*) FROM d.t", "majority");
        assertTrue(w.statusCode() == 200 || w.statusCode() == 503, "local write: " + w.statusCode() + " " + w.body());
        assertTrue(m.statusCode() == 200 || m.statusCode() == 503, "majority write: " + m.statusCode() + " " + m.body());
        assertEquals(503, r.statusCode(), "majority read: " + r.body());
    }
}
