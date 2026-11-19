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

    /**
     * A statement that names a database or table this node has not got yet pulls the schema from its peers and
     * runs again: through a load balancer, the statement after a CREATE DATABASE may reach a node the create has
     * not reached (examples/kubernetes failed once so). Sync rounds are paused, so only that pull can bring it.
     * Review 22o: each statement must get a pull that began after it failed, also right after another pull.
     */
    @Test
    void aStatementNamingADatabaseMadeElsewherePullsItsSchema() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        waitWritable(0);
        waitWritable(2);
        Cluster.syncPausedForTest = true;
        try {
            // A database that exists nowhere is refused, having been pulled for.
            HttpResponse<String> r = query(2, "CREATE TABLE shop.items");
            assertEquals(400, r.statusCode(), r.body());
            assertTrue(r.body().contains("database 'shop' does not exist"), r.body());
            // Made on node 0 only, as if replicating it to the others had not happened yet; asked for again at once.
            hereOnly(0, new com.ataiva.serengeti.core.storage.Op.CreateDb("shop", stampOn0(0)));
            r = query(2, "CREATE TABLE shop.items");
            assertEquals(200, r.statusCode(), "right after a pull that found nothing: " + r.body());
            hereOnly(0, new com.ataiva.serengeti.core.storage.Op.CreateTable("shop", "orders", stampOn0(1)));
            r = query(2, "INSERT INTO shop.orders (_id) VALUES ('o1')");
            assertEquals(200, r.statusCode(), "right after a pull that found the database: " + r.body());
            // Statements naming it at once share pulls, and each gets its answer.
            hereOnly(0, new com.ataiva.serengeti.core.storage.Op.CreateTable("shop", "lines", stampOn0(2)));
            List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> together = new ArrayList<>();
            for (int k = 0; k < 6; k++) {
                String stmt = "INSERT INTO shop.lines (_id) VALUES ('l" + k + "')";
                together.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return query(2, stmt);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
            }
            for (java.util.concurrent.CompletableFuture<HttpResponse<String>> f : together) {
                HttpResponse<String> each = f.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(200, each.statusCode(), "one of six at once: " + each.body());
            }
            r = query(2, "SELECT * FROM nowhere.t");
            assertEquals(400, r.statusCode());
            assertTrue(r.body().contains("database 'nowhere' does not exist"), r.body());
        } finally {
            Cluster.syncPausedForTest = false;
        }
    }

    private void hereOnly(int i, com.ataiva.serengeti.core.storage.Op op) throws IOException {
        nodes[i].cluster().applyHereOnlyForTest(List.of(op));
    }

    private static com.ataiva.serengeti.core.storage.Stamp stampOn0(int n) {
        return new com.ataiva.serengeti.core.storage.Stamp(System.currentTimeMillis(), n, "n0");
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
    void aListOfDatabasesMadeOnOneNodeHoldsOnEveryNodeAndAMalformedOneIsLeftOut() throws Exception {
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
            // Left out, the rest of a batch merging (review 22m); nothing of it is taken.
            assertEquals(200, r.statusCode(), bad + ": " + r.body());
            assertEquals(0L, Json.parseObject(r.body()).get("merged"), bad + ": " + r.body());
        }
        assertEquals(403, as(0, "app", pw, "POST", "/v1/query", inE), "still limited");
        assertEquals(200, as(0, "app", pw, "POST", "/v1/query", inD));
    }

    private static String q(String statement) {
        return Json.write(Map.of("query", statement));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aListOfTablesMadeOnOneNodeHoldsOnEveryNodeAndItsChangesMerge() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        waitWritable(0);
        for (String s : List.of("CREATE DATABASE d", "CREATE TABLE d.t", "CREATE TABLE d.u", "CREATE DATABASE e",
                "CREATE TABLE e.t")) {
            assertEquals(200, query(0, s).statusCode(), s);
        }
        String pw = "tabled password 1";
        assertEquals(201, as(0, "admin", PW, "PUT", "/v1/users/app",
                "{\"password\": \"" + pw + "\", \"role\": \"write\", \"databases\": [\"d.t\"]}"));
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " knows app", () -> as(n, "app", pw, "POST", "/v1/query", q("SELECT * FROM d.t")) == 200);
            // It arrives with its list, and the list means the same on every node.
            for (String s : List.of("SELECT * FROM d.u", "SELECT * FROM e.t", "CREATE DATABASE IF NOT EXISTS d",
                    "DROP TABLE d.u", "SHOW TABLES IN e")) {
                assertEquals(403, as(n, "app", pw, "POST", "/v1/query", q(s)), "node " + n + ": " + s);
            }
            assertEquals(403, as(n, "app", pw, "GET", "/v1/changes?db=d&after=now", null), "the feed of all of d");
            assertEquals(200, as(n, "app", pw, "GET", "/v1/changes?db=d&table=t&after=now", null), "the feed of d.t");
            assertEquals(403, as(n, "app", pw, "GET", "/v1/changes?db=d&table=u&after=now", null));
            String tables = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports[n] + "/v1/query"))
                    .header("Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString(("app:" + pw).getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(q("SHOW TABLES IN d"))).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertEquals(List.of(List.of("t")), Json.parseObject(tables).get("rows"), "node " + n + ": only d.t is listed");
        }
        assertEquals(200, as(2, "app", pw, "POST", "/v1/query", q("INSERT INTO d.t (_id) VALUES ('by-app')")));
        // A change of the list made on another node spreads as any list does: a table added on node 1.
        assertEquals(200, as(1, "admin", PW, "PUT", "/v1/users/app", "{\"databases\": [\"d.t\", \"e.t\"]}"));
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " has e.t in the list",
                    () -> as(n, "app", pw, "POST", "/v1/query", q("SELECT * FROM e.t")) == 200);
            assertEquals(403, as(n, "app", pw, "POST", "/v1/query", q("SELECT * FROM d.u")));
        }
        // Then the whole of database d, and nothing of e, on node 2.
        assertEquals(200, as(2, "admin", PW, "PUT", "/v1/users/app", "{\"databases\": [\"d\"]}"));
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " has d whole", () -> as(n, "app", pw, "GET", "/v1/changes?db=d&after=now", null) == 200);
            assertEquals(200, as(n, "app", pw, "POST", "/v1/query", q("SELECT * FROM d.u")));
            assertEquals(403, as(n, "app", pw, "POST", "/v1/query", q("SELECT * FROM e.t")));
        }
        // A node refuses a peer's entry whose table entries are malformed, rather than read them as something else.
        URI users = URI.create("http://127.0.0.1:" + ports[0] + "/internal/v3/users");
        Map<String, Object> entry = null;
        for (Object o : (List<Object>) Json.parseObject(HTTP.send(HttpRequest.newBuilder(users)
                .header(InternalRoutes.KEY_HEADER, KEY).build(), HttpResponse.BodyHandlers.ofString()).body()).get("users")) {
            if ("app".equals(((Map<String, Object>) o).get("user"))) {
                entry = new java.util.LinkedHashMap<>((Map<String, Object>) o);
            }
        }
        assertEquals(List.of("d"), entry.get("databases"));
        String[] stamp = ((String) entry.get("db_at") != null ? (String) entry.get("db_at") : (String) entry.get("at"))
                .split("\\.", 3);
        entry.put("db_at", (Long.parseLong(stamp[0]) + 1) + "." + stamp[1] + "." + stamp[2]); // newer than node 0's
        for (List<String> bad : List.of(List.of("d.T"), List.of("d."), List.of("d.t.x"), List.of("d.t", "d"), List.of(".t"))) {
            Map<String, Object> e = new java.util.LinkedHashMap<>(entry);
            e.put("databases", bad);
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(users).header(InternalRoutes.KEY_HEADER, KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("users", List.of(e))))).build(),
                    HttpResponse.BodyHandlers.ofString());
            // Left out, the rest of a batch merging (review 22m); nothing of it is taken.
            assertEquals(200, r.statusCode(), bad + ": " + r.body());
            assertEquals(0L, Json.parseObject(r.body()).get("merged"), bad + ": " + r.body());
        }
        assertEquals(200, as(0, "app", pw, "POST", "/v1/query", q("SELECT * FROM d.u")), "the list is as it was");
        assertEquals(403, as(0, "app", pw, "POST", "/v1/query", q("SELECT * FROM e.t")));
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

    @Test
    void concurrentIncrementsThroughOneNodeAreAllOnEveryNode() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        for (int i = 0; i < 3; i++) {
            waitWritable(i);
        }
        assertEquals(200, query(0, "CREATE DATABASE d").statusCode());
        assertEquals(200, query(0, "CREATE TABLE d.t").statusCode());
        assertEquals(200, query(0, "INSERT INTO d.t (_id, n) VALUES ('c', 0)").statusCode());
        java.util.Queue<String> failed = new java.util.concurrent.ConcurrentLinkedQueue<>();
        List<Thread> writers = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread w = new Thread(() -> {
                for (int k = 0; k < 50; k++) {
                    try {
                        HttpResponse<String> r = write(0, "UPDATE d.t SET n = n + 1 WHERE _id = 'c'", null);
                        if (r.statusCode() != 200 || !r.body().contains("\"affected\":1")) {
                            failed.add(r.statusCode() + " " + r.body());
                        }
                    } catch (Exception e) {
                        failed.add(e.toString());
                    }
                }
            });
            writers.add(w);
            w.start();
        }
        for (Thread w : writers) {
            w.join();
        }
        assertEquals(List.of(), List.copyOf(failed));
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " has all 400 increments", () -> has(n, "SELECT n FROM d.t", List.of(List.of(400L))));
            assertEquals(List.of(), conflictValues(n, "t", "n"), "node " + n);
        }
    }

    @Test
    void incrementsOnTwoNodesThatHadNotSeenEachOtherAreAConflictThatTheNextWriteResolves() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        for (int i = 0; i < 3; i++) {
            waitWritable(i);
        }
        assertEquals(200, query(0, "CREATE DATABASE d").statusCode());
        assertEquals(200, query(0, "CREATE TABLE d.t").statusCode());
        assertEquals(200, query(0, "INSERT INTO d.t (_id, n) VALUES ('c', 0)").statusCode());
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " has the row", () -> has(n, "SELECT n FROM d.t", List.of(List.of(0L))));
        }
        // Node 0 adds 2 while the others are down, then node 1 adds 1 while the others are down: each computes
        // from the row as it holds it, and neither has seen the other's write.
        stop(1);
        stop(2);
        for (int k = 0; k < 2; k++) {
            assertEquals(200, write(0, "UPDATE d.t SET n = n + 1 WHERE _id = 'c'", "local").statusCode());
        }
        stop(0);
        start(1);
        Thread.sleep(1200);
        assertEquals(200, write(1, "UPDATE d.t SET n = n + 1 WHERE _id = 'c'", "local").statusCode());
        start(0);
        start(2);
        // Both versions are kept on every node, and the row's value is the later write's: node 1's 1.
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " lists both versions, node 1's current",
                    () -> conflictValues(n, "t", "n").equals(List.of(1L, 2L)));
            assertTrue(has(n, "SELECT n FROM d.t", List.of(List.of(1L))), "node " + n);
        }
        // The next write has seen both versions, so it replaces them; it computes from the current one.
        waitWritable(0);
        assertEquals(200, query(0, "UPDATE d.t SET n = n + 1 WHERE _id = 'c'").statusCode());
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " has no conflict left", () -> conflictValues(n, "t", "n").isEmpty());
            eventually("node " + n + " has n = 2", () -> has(n, "SELECT n FROM d.t", List.of(List.of(2L))));
        }
    }

    @Test
    void upsertsOnTwoNodesThatHadNotSeenEachOtherAreAConflictAsTwoInsertsAre() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        for (int i = 0; i < 3; i++) {
            waitWritable(i);
        }
        assertEquals(200, query(0, "CREATE DATABASE d").statusCode());
        assertEquals(200, query(0, "CREATE TABLE d.t").statusCode());
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " has the table", () -> has(n, "SELECT COUNT(*) FROM d.t", List.of(List.of(0L))));
        }
        String upsert = "INSERT INTO d.t (_id, n) VALUES ('c', 1) ON CONFLICT DO UPDATE SET n = n + 1";
        // Node 0 inserts the row and adds 1 while the others are down; then node 1, which has no row, inserts it.
        stop(1);
        stop(2);
        HttpResponse<String> r = write(0, upsert, "local");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"inserted\":1,\"updated\":0,\"skipped\":0"), r.body());
        r = write(0, upsert, "local");
        assertTrue(r.body().contains("\"inserted\":0,\"updated\":1,\"skipped\":0"), r.body());
        stop(0);
        start(1);
        Thread.sleep(1200);
        r = write(1, upsert, "local");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"inserted\":1,\"updated\":0,\"skipped\":0"), "node 1 had no row: " + r.body());
        start(0);
        start(2);
        // Both versions are kept on every node, and the row's value is the later write's: node 1's 1.
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " lists both versions, node 1's current",
                    () -> conflictValues(n, "t", "n").equals(List.of(1L, 2L)));
            assertTrue(has(n, "SELECT n FROM d.t", List.of(List.of(1L))), "node " + n);
        }
        // DO NOTHING writes nothing, so the conflict stays; the next upsert has seen both versions and resolves it.
        waitWritable(0);
        r = query(0, "INSERT INTO d.t (_id, n) VALUES ('c', 9) ON CONFLICT DO NOTHING");
        assertTrue(r.body().contains("\"affected\":0,\"inserted\":0,\"updated\":0,\"skipped\":1"), r.body());
        assertEquals(List.of(1L, 2L), conflictValues(0, "t", "n"));
        r = query(0, upsert);
        assertTrue(r.body().contains("\"inserted\":0,\"updated\":1,\"skipped\":0"), r.body());
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " has no conflict left", () -> conflictValues(n, "t", "n").isEmpty());
            eventually("node " + n + " has n = 2", () -> has(n, "SELECT n FROM d.t", List.of(List.of(2L))));
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

    /**
     * Review 22e, suspicion 1: a node that comes back just under the TTL had until the next boundary after
     * each missed delete's expiry, possibly seconds, to pull it before every peer left it out. Now it is stale
     * from the TTL less 20 minutes on, so one that is not has at least that long. TTL 1 h: 45 minutes away is
     * stale, 30 minutes is not.
     */
    @Test
    void aNodeAwayLongerThanTheTtlLessTwentyMinutesStartsStale() throws Exception {
        Path data = dir.resolve("n0");
        java.nio.file.Files.createDirectories(data);
        Map<String, String> env = new HashMap<>();
        env.put("SERENGETI_DATA_DIR", data.toString());
        env.put("SERENGETI_PORT", Integer.toString(ports[0]));
        env.put("SERENGETI_PEERS", "127.0.0.1:" + ports[1]);
        env.put("SERENGETI_CLUSTER_KEY", KEY);
        env.put("SERENGETI_CLUSTER_PLAINTEXT", "allow");
        env.put("SERENGETI_ADMIN_PASSWORD", PW);
        env.put("SERENGETI_TOMBSTONE_TTL_HOURS", "1");
        assertEquals(40 * 60_000L, 3_600_000L - Cluster.STALE_MARGIN_MILLIS);
        for (long away : new long[] {45 * 60_000L, 30 * 60_000L}) {
            java.nio.file.Files.writeString(data.resolve(Cluster.LAST_SYNC_FILE), (System.currentTimeMillis() - away) + "\n");
            try (Node n = Node.start(Config.load(null, env), Node.QUERIES)) {
                assertEquals(away > 40 * 60_000L, n.cluster().stale(), "away " + away / 60_000 + " minutes");
            }
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

    // ---- catching up by rows that differ (/internal/v3/hashes and /internal/v3/rows) ----

    private HttpResponse<String> internal(int i, String path, String body, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports[i] + path))
                .timeout(Duration.ofSeconds(20));
        if (key != null) {
            b.header(InternalRoutes.KEY_HEADER, key);
        }
        if (body != null) {
            b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A row op in its JSON form; {@code seen} null leaves the context out (as from 2.0). */
    private static Map<String, Object> rowOp(String table, String id, Map<String, Object> row, String at,
            Map<String, Object> seen) {
        Map<String, Object> m = new HashMap<>();
        m.put("op", row == null ? "delete" : "put");
        m.put("db", "d");
        m.put("table", table);
        m.put("id", id);
        if (row != null) {
            m.put("row", row);
        }
        m.put("at", at);
        if (seen != null) {
            m.put("seen", seen);
        }
        return m;
    }

    /** Merges ops on node i as a peer's would be: they are not passed on, so other nodes get them by sync only. */
    private void merge(int i, List<? extends Map<String, ?>> ops) throws Exception {
        HttpResponse<String> r = internal(i, "/internal/apply", Json.write(Map.of("ops", ops)), KEY);
        assertEquals(200, r.statusCode(), r.body());
    }

    @SuppressWarnings("unchecked")
    private static List<String> kinds(HttpResponse<String> r) {
        List<String> out = new ArrayList<>();
        for (Object o : (List<Object>) Json.parseObject(r.body()).get("ops")) {
            out.add(((Map<String, Object>) o).get("op") + ":" + ((Map<String, Object>) o).getOrDefault("id", ""));
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRowRoutesAnswerWithinTheirLimits() throws Exception {
        start(0);
        long now = System.currentTimeMillis();
        merge(0, List.of(Map.of("op", "create_db", "db", "d", "at", now + ".0.z1"),
                Map.of("op", "create_table", "db", "d", "table", "t", "at", now + ".1.z1"),
                rowOp("t", "x", Map.of("n", 1L), now + ".2.z1", Map.of()),
                rowOp("t", "x", Map.of("n", 2L), now + ".3.z2", Map.of()), // concurrent with the one before
                rowOp("t", "y", Map.of("n", 3L), now + ".4.z1", null),
                rowOp("t", "y", null, now + ".5.z1", null),
                rowOp("t", "z", Map.of("n", 4L), now + ".6.z1", Map.of())));
        java.util.TreeSet<Integer> buckets = new java.util.TreeSet<>();
        for (String id : List.of("x", "y", "z")) {
            buckets.add(com.ataiva.serengeti.core.storage.Store.bucket(id));
        }
        String list = String.join(",", buckets.stream().map(String::valueOf).toList());
        HttpResponse<String> h = internal(0, "/internal/v3/hashes?db=d&table=t&buckets=" + list, null, KEY);
        assertEquals(200, h.statusCode(), h.body());
        Map<String, Object> m = Json.parseObject(h.body());
        assertEquals(List.of("x", "y", "z"), m.get("ids"), "the tombstone of y included");
        assertEquals(3, ((List<Object>) m.get("h")).size());
        assertFalse(((List<Object>) m.get("h")).contains(0L), h.body());
        List<Object> w = (List<Object>) m.get("w");
        assertEquals(3, w.size(), "each row's size");
        assertTrue((Long) w.get(0) > (Long) w.get(2), "x holds two versions, z one: " + w);
        assertEquals(Map.of("ids", List.of(), "h", List.of(), "w", List.of()),
                Json.parseObject(internal(0, "/internal/v3/hashes?db=d&table=nope&buckets=1", null, KEY).body()));
        StringBuilder many = new StringBuilder("0");
        for (int b = 1; b <= Cluster.BUCKETS_PER_REQUEST; b++) {
            many.append(',').append(b);
        }
        for (String bad : List.of("db=d&table=t&buckets=" + many, "db=d&table=t&buckets=1024", "db=d&table=t&buckets=-1",
                "db=d&table=t&buckets=x", "db=d&table=t", "table=t&buckets=1", "db=d&buckets=1")) {
            assertEquals(400, internal(0, "/internal/v3/hashes?" + bad, null, KEY).statusCode(), bad);
        }
        assertEquals(401, internal(0, "/internal/v3/hashes?db=d&table=t&buckets=1", null, null).statusCode());
        assertEquals(401, internal(0, "/internal/v3/rows", "{\"db\": \"d\", \"table\": \"t\", \"ids\": []}", "wrong key")
                .statusCode());

        HttpResponse<String> r = internal(0, "/internal/v3/rows", Json.write(Map.of("db", "d", "table", "t",
                "ids", List.of("x", "y", "nope", "x"))), KEY);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(List.of("create_db:", "create_table:", "put:x", "put:x", "delete:y"), kinds(r),
                "the schema, both versions of x and the tombstone of y; nothing for an id it does not hold");
        assertEquals(List.of("create_db:", "create_table:"), kinds(internal(0, "/internal/v3/rows",
                "{\"db\": \"d\", \"table\": \"t\", \"ids\": []}", KEY)));
        assertEquals(List.of(), kinds(internal(0, "/internal/v3/rows", "{\"db\": \"e\", \"table\": \"t\", \"ids\": [\"x\"]}", KEY)));
        List<String> tooMany = new ArrayList<>();
        for (int k = 0; k <= Cluster.IDS_PER_REQUEST; k++) {
            tooMany.add("r" + k);
        }
        for (String bad : List.of(Json.write(Map.of("db", "d", "table", "t", "ids", tooMany)),
                "{\"db\": \"d\", \"table\": \"t\", \"ids\": \"x\"}", "{\"db\": \"d\", \"table\": \"t\", \"ids\": [1]}",
                "{\"db\": \"d\", \"table\": \"t\", \"ids\": [null]}", "{\"db\": \"d\", \"table\": \"t\"}",
                "{\"db\": \"d\", \"ids\": []}", "{\"table\": \"t\", \"ids\": []}", "{\"db\": 1, \"table\": \"t\", \"ids\": []}",
                "{\"db\": \"\", \"table\": \"t\", \"ids\": []}", "[]", "not json")) {
            assertEquals(400, internal(0, "/internal/v3/rows", bad, KEY).statusCode(), bad);
        }
        tooMany.remove(0);
        assertEquals(200, internal(0, "/internal/v3/rows", Json.write(Map.of("db", "d", "table", "t", "ids", tooMany)), KEY)
                .statusCode(), "2,000 ids is the limit");
        assertEquals(405, internal(0, "/internal/v3/rows", null, KEY).statusCode(), "a GET");

        // A node without the routes (2.1, or a test hook) answers 404, which is what the fallback goes by.
        nodes[0].cluster().rowRoutesForTest(false);
        assertEquals(404, internal(0, "/internal/v3/hashes?db=d&table=t&buckets=1", null, KEY).statusCode());
        assertEquals(404, internal(0, "/internal/v3/rows", "{\"db\": \"d\", \"table\": \"t\", \"ids\": []}", KEY).statusCode());
        nodes[0].cluster().rowRoutesForTest(true);
        assertEquals(200, internal(0, "/internal/v3/hashes?db=d&table=t&buckets=1", null, KEY).statusCode());
    }

    /** The values of {@code column} in each version SHOW CONFLICTS lists on node i, current first. */
    @SuppressWarnings("unchecked")
    private List<Object> conflictValues(int i, String table, String column) {
        try {
            HttpResponse<String> r = query(i, "SHOW CONFLICTS IN d." + table);
            if (r.statusCode() != 200) {
                return List.of("status " + r.statusCode());
            }
            List<Object> out = new ArrayList<>();
            for (Object row : (List<Object>) Json.parseObject(r.body()).get("rows")) {
                out.add(((Map<String, Object>) ((List<Object>) row).get(5)).get(column));
            }
            return out;
        } catch (Exception e) {
            return List.of(e.toString());
        }
    }

    private static String values(String prefix, int from, int to) {
        StringBuilder b = new StringBuilder();
        for (int k = from; k < to; k++) {
            b.append(k == from ? "" : ", ").append("('").append(prefix).append(k).append("', ").append(k).append(')');
        }
        return b.toString();
    }

    @Test
    void aRestartedNodeCatchesUpByPullingOnlyTheRowsThatDiffer() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        for (int i = 0; i < 3; i++) {
            waitWritable(i);
        }
        assertEquals(200, query(0, "CREATE DATABASE d").statusCode());
        assertEquals(200, query(0, "CREATE TABLE d.t").statusCode());
        assertEquals(200, query(0, "INSERT INTO d.t (_id, n) VALUES " + values("r", 0, 3000)).statusCode());
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " has 3000 rows", () -> has(n, "SELECT COUNT(*) FROM d.t", List.of(List.of(3000L))));
        }
        // Node 2 changes r5 on its own, then is away while the others change it too (a conflict), insert, update,
        // delete, and create a table.
        stop(0);
        stop(1);
        assertEquals(200, write(2, "UPDATE d.t SET n = -2 WHERE _id = 'r5'", "local").statusCode());
        stop(2);
        start(0);
        start(1);
        waitWritable(0);
        waitWritable(1);
        assertEquals(200, query(0, "UPDATE d.t SET n = -1 WHERE _id = 'r5'").statusCode());
        assertEquals(200, query(0, "INSERT INTO d.t (_id, n) VALUES " + values("s", 10_000, 10_500)).statusCode());
        assertEquals(200, query(0, "UPDATE d.t SET n = -7 WHERE n >= 1000 AND n < 1300").statusCode());
        assertEquals(200, query(1, "DELETE FROM d.t WHERE n >= 2000 AND n < 2200").statusCode());
        assertEquals(200, query(1, "CREATE TABLE d.u").statusCode());
        assertEquals(200, query(1, "INSERT INTO d.u (_id, n) VALUES " + values("u", 0, 100)).statusCode());
        start(2);
        eventually("node 2 has every change", () -> has(2, "SELECT COUNT(*) FROM d.t", List.of(List.of(3300L)))
                && has(2, "SELECT COUNT(*) FROM d.t WHERE n = -7", List.of(List.of(300L)))
                && has(2, "SELECT COUNT(*) FROM d.t WHERE n >= 2000 AND n < 2200", List.of(List.of(0L)))
                && has(2, "SELECT COUNT(*) FROM d.u", List.of(List.of(100L))));
        for (int i : new int[] {0, 2}) {
            eventually("node " + i + " holds both versions of r5", () -> conflictValues(i, "t", "n").equals(List.of(-1L, -2L)));
        }
        long[] pages = nodes[2].cluster().pagesForTest();
        assertTrue(pages[1] > 0 && pages[2] > 0, "pulled by row hashes and ids: " + java.util.Arrays.toString(pages));
        assertTrue(pages[0] > 0, "the new table's buckets, empty here, pulled whole: " + java.util.Arrays.toString(pages));
        // Pulling whole buckets would take every row of d.t from each peer: 6,600 ops or more.
        long pulled = nodes[2].cluster().pulledOps();
        assertTrue(pulled < 3_300, "pulled " + pulled + " ops");
        waitWritable(2);
    }

    @Test
    void aPeerWithoutTheRowRoutesIsPulledByWholeBucketsAndAskedAgainLater() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        for (int i = 0; i < 3; i++) {
            waitWritable(i);
        }
        nodes[0].cluster().rowRoutesForTest(false);
        nodes[1].cluster().rowRoutesForTest(false);
        assertEquals(200, query(0, "CREATE DATABASE d").statusCode());
        assertEquals(200, query(0, "CREATE TABLE d.t").statusCode());
        assertEquals(200, query(0, "INSERT INTO d.t (_id, n) VALUES " + values("r", 0, 1000)).statusCode());
        eventually("node 2 has 1000 rows", () -> has(2, "SELECT COUNT(*) FROM d.t", List.of(List.of(1000L))));
        stop(2);
        assertEquals(200, query(0, "INSERT INTO d.t (_id, n) VALUES " + values("s", 5000, 5100)).statusCode());
        assertEquals(200, query(1, "DELETE FROM d.t WHERE n < 10").statusCode());
        start(2);
        nodes[2].cluster().rowProbeMillisForTest(300);
        eventually("node 2 caught up from peers without the routes",
                () -> has(2, "SELECT COUNT(*) FROM d.t", List.of(List.of(1090L))));
        long[] pages = nodes[2].cluster().pagesForTest();
        assertEquals(0, pages[1], java.util.Arrays.toString(pages));
        assertEquals(0, pages[2], java.util.Arrays.toString(pages));
        assertTrue(pages[0] > 0, java.util.Arrays.toString(pages));
        // Upgraded: once asked again, the peers send rows by id. A change to a row node 2 holds (so its bucket
        // is compared, not pulled whole), merged on both and not pushed:
        nodes[0].cluster().rowRoutesForTest(true);
        nodes[1].cluster().rowRoutesForTest(true);
        Thread.sleep(400);
        String at = System.currentTimeMillis() + ".0.z9";
        for (int i = 0; i < 2; i++) {
            merge(i, List.of(rowOp("t", "r500", Map.of("n", -5L), at, Map.of())));
        }
        eventually("node 2 has the change", () -> has(2, "SELECT n FROM d.t WHERE _id = 'r500'", List.of(List.of(-5L))));
        pages = nodes[2].cluster().pagesForTest();
        assertTrue(pages[1] > 0 && pages[2] > 0, java.util.Arrays.toString(pages));
    }

    @Test
    void majorityReadsCatchUpByRowsThatDiffer() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        for (int i = 0; i < 3; i++) {
            waitWritable(i);
        }
        assertEquals(200, query(0, "CREATE DATABASE d").statusCode());
        assertEquals(200, query(0, "CREATE TABLE d.t").statusCode());
        assertEquals(200, query(0, "INSERT INTO d.t (_id, n) VALUES " + values("r", 0, 500)).statusCode());
        eventually("node 2 has 500 rows", () -> has(2, "SELECT COUNT(*) FROM d.t", List.of(List.of(500L))));
        // Changes that a majority (nodes 0 and 1) holds and node 2 gets only by sync: a majority read there
        // must see each at once.
        for (int k = 0; k < 10; k++) {
            String at = System.currentTimeMillis() + "." + k + ".z9";
            Map<String, Object> op = k % 2 == 0 ? rowOp("t", "m" + k, Map.of("n", -1L), at, Map.of())
                    : rowOp("t", "r" + k, null, at, null);
            for (int i = 0; i < 2; i++) {
                merge(i, List.of(op));
            }
            HttpResponse<String> r = query(2, "SELECT COUNT(*) FROM d.t WHERE n = -1", "majority");
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().contains("[[" + (k / 2 + 1) + "]]"), k + ": " + r.body());
            HttpResponse<String> all = query(2, "SELECT COUNT(*) FROM d.t", "majority");
            assertTrue(all.body().contains("[[" + (500 + (k / 2 + 1) - (k + 1) / 2) + "]]"), k + ": " + all.body());
        }
        long[] pages = nodes[2].cluster().pagesForTest();
        assertTrue(pages[1] > 0 && pages[2] > 0, java.util.Arrays.toString(pages));
    }

    // ---- tombstones that differ (the full bucket sums, /internal/v3/buckets) ----

    /** The versions node i holds of row {@code id} of d.t, newest first, as "kind:stamp". */
    @SuppressWarnings("unchecked")
    private List<String> versions(int i, String id) {
        try {
            HttpResponse<String> r = internal(i, "/internal/v3/rows", Json.write(Map.of("db", "d", "table", "t",
                    "ids", List.of(id))), KEY);
            List<String> out = new ArrayList<>();
            for (Object o : (List<Object>) Json.parseObject(r.body()).get("ops")) {
                Map<String, Object> op = (Map<String, Object>) o;
                if (id.equals(op.get("id"))) {
                    out.add(op.get("op") + ":" + op.get("at"));
                }
            }
            return out;
        } catch (Exception e) {
            return List.of(e.toString());
        }
    }

    /**
     * The soak's check 8 (TombstoneBlindTest): node 0 holds an older delete of x, nodes 1 and 2 a newer one.
     * Compared by the live sums alone, as with a 2.1 peer, the two count nothing and the newer delete never
     * comes; by the full sums it does, so a concurrent update older than it, arriving later, stays hidden.
     */
    @Test
    void aNewerDeleteReachesANodeThatHoldsAnOlderOne() throws Exception {
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        for (int i = 0; i < 3; i++) {
            waitWritable(i);
        }
        assertEquals(200, query(0, "CREATE DATABASE d").statusCode());
        assertEquals(200, query(0, "CREATE TABLE d.t").statusCode());
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " has d.t", () -> has(n, "SHOW TABLES IN d", List.of(List.of("t"))));
        }
        long now = System.currentTimeMillis();
        String xs = (now - 5_000) + ".0.z1";
        String ts = (now - 4_000) + ".0.z2";
        String us = (now - 3_000) + ".0.z4";
        String ds = (now - 2_000) + ".0.z3";
        for (int i = 0; i < 3; i++) {
            merge(i, List.of(rowOp("t", "x", Map.of("n", 1L), xs, Map.of())));
        }
        merge(0, List.of(rowOp("t", "x", null, ts, Map.of("z1", xs))));
        // Peers without the protocol 3 routes: node 0 falls back to the live sums with them.
        nodes[1].cluster().rowRoutesForTest(false);
        nodes[2].cluster().rowRoutesForTest(false);
        nodes[0].cluster().rowProbeMillisForTest(300);
        for (int i = 1; i < 3; i++) {
            merge(i, List.of(rowOp("t", "x", null, ds, Map.of("z1", xs, "z2", ts))));
        }
        Thread.sleep(2_500);
        assertEquals(List.of("delete:" + ts), versions(0, "x"), "the live sums of both count nothing for x");
        nodes[1].cluster().rowRoutesForTest(true);
        nodes[2].cluster().rowRoutesForTest(true);
        eventually("node 0 has the newer delete", () -> versions(0, "x").equals(List.of("delete:" + ds)));
        // The update of a node that was cut off: concurrent with both deletes, newer than the older one.
        merge(0, List.of(rowOp("t", "x", Map.of("n", 2L), us, Map.of("z1", xs))));
        assertEquals(List.of("delete:" + ds, "put:" + us), versions(0, "x"));
        assertEquals(List.of(List.of(0L)), rows(0, "SELECT COUNT(*) FROM d.t"));
        HttpResponse<String> r = query(0, "SELECT COUNT(*) FROM d.t", "majority");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("[[0]]"), r.body());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theFullSumsCountTombstonesExceptExpiredOnes() throws Exception {
        start(0); // its peers are down, so it may not collect tombstones; it still leaves expired ones out
        long now = System.currentTimeMillis();
        long old = now - 8 * 24 * 3_600_000L; // older than the default TTL (7 days) and its 10-minute step
        merge(0, List.of(Map.of("op", "create_db", "db", "d", "at", old + ".0.z1"),
                Map.of("op", "create_table", "db", "d", "table", "t", "at", old + ".1.z1"),
                rowOp("t", "gone", Map.of("n", 1L), old + ".2.z1", Map.of()),
                rowOp("t", "gone", null, old + ".3.z1", Map.of("z1", old + ".2.z1")),
                rowOp("t", "dead", null, (now - 1_000) + ".0.z1", Map.of()),
                rowOp("t", "x", Map.of("n", 1L), (now - 900) + ".0.z1", Map.of()),
                rowOp("t", "lost", null, old + ".5.z1", Map.of())));
        assertEquals(List.of("delete:" + old + ".3.z1"), versions(0, "gone"), "held: it may not be collected yet");
        assertEquals(List.of(), versions(0, "lost"), "an expired delete of a row not held is not merged");
        assertEquals(List.of("delete:" + (now - 1_000) + ".0.z1"), versions(0, "dead"));

        java.util.TreeSet<Integer> buckets = new java.util.TreeSet<>();
        for (String id : List.of("gone", "dead", "x", "lost")) {
            buckets.add(com.ataiva.serengeti.core.storage.Store.bucket(id));
        }
        String list = String.join(",", buckets.stream().map(String::valueOf).toList());
        Map<String, Object> h = Json.parseObject(internal(0, "/internal/v3/hashes?db=d&table=t&buckets=" + list, null, KEY).body());
        assertEquals(List.of("dead", "x"), h.get("ids"), "the expired tombstone is left out");
        HttpResponse<String> b = internal(0, "/internal/v3/buckets?db=d&table=t", null, KEY);
        assertEquals(200, b.statusCode(), b.body());
        List<Object> sums = (List<Object>) Json.parseObject(b.body()).get("sums");
        assertEquals(com.ataiva.serengeti.core.storage.Store.BUCKETS, sums.size());
        // The rows (expired tombstones too: they are still held, and sent with a whole bucket) and bytes per bucket.
        List<Object> counts = (List<Object>) Json.parseObject(b.body()).get("rows");
        List<Object> bytes = (List<Object>) Json.parseObject(b.body()).get("bytes");
        assertEquals(3L, counts.stream().mapToLong(v -> (Long) v).sum(), "gone, dead and x");
        int xb = com.ataiva.serengeti.core.storage.Store.bucket("x");
        assertTrue((Long) counts.get(xb) >= 1 && (Long) bytes.get(xb) > 0, counts.get(xb) + " rows, " + bytes.get(xb) + " bytes");
        long total = 0;
        for (Object v : sums) {
            total += (Long) v;
        }
        long hashes = 0;
        for (Object v : (List<Object>) h.get("h")) {
            hashes += (Long) v;
        }
        assertEquals(hashes, total, "the full sums add up the hashes of the rows sync compares, the recent tombstone too");
        List<Object> v2 = (List<Object>) Json.parseObject(internal(0, "/internal/v2/buckets?db=d&table=t", null, KEY).body())
                .get("sums");
        assertFalse(v2.equals(sums), "the live sums leave out every tombstone");
        Map<String, Object> roots = (Map<String, Object>) ((Map<String, Object>) Json.parseObject(
                internal(0, "/internal/v2/roots", null, KEY).body()).get("dbs")).get("d");
        assertTrue(roots.containsKey("full") && roots.containsKey("tables"), roots.toString());
        for (String bad : List.of("db=d", "table=t", "")) {
            assertEquals(400, internal(0, "/internal/v3/buckets?" + bad, null, KEY).statusCode(), bad);
        }
        assertEquals(401, internal(0, "/internal/v3/buckets?db=d&table=t", null, null).statusCode());
        List<Object> none = (List<Object>) Json.parseObject(internal(0, "/internal/v3/buckets?db=d&table=nope", null, KEY)
                .body()).get("sums");
        assertTrue(none.stream().allMatch(v -> ((Long) v) == 0L));
        nodes[0].cluster().rowRoutesForTest(false);
        assertEquals(404, internal(0, "/internal/v3/buckets?db=d&table=t", null, KEY).statusCode());
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
