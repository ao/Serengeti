package com.ataiva.serengeti.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.config.ConfigException;
import com.ataiva.serengeti.core.server.SetupToken;
import com.ataiva.serengeti.core.server.Users;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeTest {
    private static final String PW = "correct horse battery";

    @TempDir
    Path tmp;

    private Config config(Path data, String... kv) {
        Map<String, String> env = new HashMap<>();
        env.put("SERENGETI_DATA_DIR", data.toString());
        for (int i = 0; i < kv.length; i += 2) {
            env.put(kv[i], kv[i + 1]);
        }
        return loadWithEphemeralPort(env);
    }

    /** Config refuses port 0, which is right for operators; tests pick a free port instead. */
    private static Config loadWithEphemeralPort(Map<String, String> env) {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            env.put("SERENGETI_PORT", Integer.toString(s.getLocalPort()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return Config.load(null, env);
    }

    private static HttpResponse<String> get(Node n, String path, String password) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + n.address().getPort() + path));
        if (password != null) {
            b.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(("admin:" + password).getBytes(StandardCharsets.UTF_8)));
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void startsWithAMissingNestedDataDirectoryAndServesHealth() throws Exception {
        Path data = tmp.resolve("a/b/data");
        try (Node n = Node.start(config(data), Node.NO_QUERIES)) {
            assertTrue(n.address().getAddress().isLoopbackAddress());
            assertEquals(200, get(n, "/health", null).statusCode());
            assertEquals(503, get(n, "/v1/status", null).statusCode());
            assertTrue(Files.exists(data.resolve(SetupToken.FILE)), "a setup token is written");
            assertTrue(Files.exists(data.resolve(Node.NODE_ID_FILE)));
        }
    }

    @Test
    void readyAnswersWithoutCredentialsOnceTheNodeServes() throws Exception {
        try (Node n = Node.start(config(tmp.resolve("data")), Node.NO_QUERIES)) {
            HttpResponse<String> r = get(n, "/ready", null);
            assertEquals(200, r.statusCode());
            assertEquals("{\"status\":\"ready\"}", r.body().trim());
        }
    }

    @Test
    void adminPasswordFromTheEnvironmentAndRestartKeepsItAndTheNodeId() throws Exception {
        Path data = tmp.resolve("data");
        String id;
        try (Node n = Node.start(config(data, "SERENGETI_ADMIN_PASSWORD", PW), Node.NO_QUERIES)) {
            id = n.id();
            assertEquals(200, get(n, "/v1/status", PW).statusCode());
            assertFalse(Files.exists(data.resolve(SetupToken.FILE)));
            HttpResponse<String> q = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + n.address().getPort() + "/v1/query"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString(("admin:" + PW).getBytes(StandardCharsets.UTF_8)))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"query\": \"SHOW DATABASES\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(501, q.statusCode());
        }
        try (Node n = Node.start(config(data, "SERENGETI_ADMIN_PASSWORD", "ignored once set up"), Node.NO_QUERIES)) {
            assertEquals(id, n.id());
            assertEquals(200, get(n, "/v1/status", PW).statusCode());
            assertEquals(401, get(n, "/v1/status", "ignored once set up").statusCode());
        }
    }

    private static HttpResponse<String> query(Node n, String q) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + n.address().getPort() + "/v1/query"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(("admin:" + PW).getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(
                        com.ataiva.serengeti.core.json.Json.write(Map.of("query", q)))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> changes(Node n, String query) throws Exception {
        HttpResponse<String> r = get(n, "/v1/changes?" + query, PW);
        Map<String, Object> m = new java.util.HashMap<>(com.ataiva.serengeti.core.json.Json.parseObject(r.body()));
        m.put("status", (long) r.statusCode());
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aClientFollowsATableThroughTheChangeFeed() throws Exception {
        Path data = tmp.resolve("data");
        Config c = config(data, "SERENGETI_ADMIN_PASSWORD", PW);
        String old;
        try (Node n = Node.start(c, Node.QUERIES)) {
            assertEquals(200, query(n, "CREATE DATABASE shop").statusCode());
            assertEquals(200, query(n, "CREATE TABLE shop.items").statusCode());
            String cursor = (String) changes(n, "db=shop&after=now").get("cursor");
            assertEquals(200, query(n, "INSERT INTO shop.items (_id, qty) VALUES ('bolt', 1)").statusCode());
            assertEquals(200, query(n, "UPDATE shop.items SET qty = 2 WHERE _id = 'bolt'").statusCode());
            Map<String, Object> page = changes(n, "db=shop&table=items&after=" + cursor);
            List<Map<String, Object>> list = (List<Map<String, Object>>) page.get("changes");
            assertEquals(2, list.size(), page.toString());
            assertEquals("bolt", list.get(1).get("_id"));
            assertEquals(Map.of("qty", 2L), list.get(1).get("row"));
            assertEquals(false, list.get(1).get("deleted"));
            // A long poll returns as soon as the next change comes.
            String next = (String) page.get("cursor");
            Thread writer = new Thread(() -> {
                try {
                    Thread.sleep(300);
                    query(n, "DELETE FROM shop.items WHERE _id = 'bolt'");
                } catch (Exception e) {
                    // the poll below then times out and the test fails
                }
            });
            writer.start();
            long t0 = System.nanoTime();
            Map<String, Object> waited = changes(n, "db=shop&table=items&wait=10&after=" + next);
            writer.join();
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000, "returned when the delete came");
            assertEquals(true, ((List<Map<String, Object>>) waited.get("changes")).get(0).get("deleted"));
            for (String bad : List.of("after=now", "db=shop", "db=shop&after=xyz", "db=shop&after=now&wait=61",
                    "db=shop&after=now&limit=0")) {
                assertEquals(400L, changes(n, bad).get("status"), bad);
            }
            old = (String) waited.get("cursor");
        }
        try (Node n = Node.start(c, Node.QUERIES)) {
            Map<String, Object> gone = changes(n, "db=shop&after=" + old);
            assertEquals(410L, gone.get("status"), "a cursor from before the restart");
            assertTrue(((String) gone.get("error")).contains("after=now"), gone.toString());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theFeedShowsAWriteOnlyOnceItIsOnDiskAndNothingAfterAFailedFsync() throws Exception {
        Config c = config(tmp.resolve("data"), "SERENGETI_ADMIN_PASSWORD", PW, "SERENGETI_TEST_FSYNC_DELAY_MS", "1500");
        try (Node n = Node.start(c, Node.QUERIES)) {
            assertEquals(200, query(n, "CREATE DATABASE shop").statusCode());
            assertEquals(200, query(n, "CREATE TABLE shop.items").statusCode());
            String cursor = (String) changes(n, "db=shop&after=now").get("cursor");
            Thread writer = new Thread(() -> {
                try {
                    query(n, "INSERT INTO shop.items (_id) VALUES ('slow')");
                } catch (Exception e) {
                    // the checks below then fail
                }
            });
            writer.start();
            Thread.sleep(500); // the insert is applied, its fsync still running
            Map<String, Object> during = changes(n, "db=shop&after=" + cursor);
            assertEquals(List.of(), during.get("changes"), "not yet on disk: " + during);
            writer.join();
            List<Map<String, Object>> after = (List<Map<String, Object>>) changes(n, "db=shop&after=" + cursor).get("changes");
            assertEquals("slow", after.get(0).get("_id"));
        }
        Path data2 = tmp.resolve("data2");
        try (Node n = Node.start(config(data2, "SERENGETI_ADMIN_PASSWORD", PW), Node.QUERIES)) {
            assertEquals(200, query(n, "CREATE DATABASE shop").statusCode());
            assertEquals(200, query(n, "CREATE TABLE shop.items").statusCode());
            String cursor = (String) changes(n, "db=shop&after=now").get("cursor");
            n.storageForTest().failNextFsyncForTest(new IOException("injected EIO"));
            assertEquals(500, query(n, "INSERT INTO shop.items (_id) VALUES ('lost')").statusCode());
            assertEquals(503L, changes(n, "db=shop&after=" + cursor).get("status"), "the feed stops serving too");
        }
    }

    @Test
    void aStoppingNodeAnswers503OnAKeptAliveConnection() throws Exception {
        Node n = Node.start(config(tmp.resolve("data"), "SERENGETI_ADMIN_PASSWORD", PW), Node.QUERIES);
        assertEquals(200, query(n, "CREATE DATABASE shop").statusCode());
        String cursor = (String) changes(n, "db=shop&after=now").get("cursor");
        HttpClient kept = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest health = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + n.address().getPort() + "/health")).build();
        assertEquals(200, kept.send(health, HttpResponse.BodyHandlers.ofString()).statusCode()); // opens the connection
        // A long poll stays in flight, so close() waits up to a second for it, and meanwhile the JDK server
        // still dispatches requests on connections it has.
        Thread poll = new Thread(() -> {
            try {
                changes(n, "db=shop&wait=5&after=" + cursor);
            } catch (Exception e) {
                // cut off by the stop: expected
            }
        });
        poll.start();
        Thread.sleep(300);
        Thread closer = new Thread(() -> {
            try {
                n.close();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        closer.start();
        Thread.sleep(200);
        HttpResponse<String> r = kept.send(health, HttpResponse.BodyHandlers.ofString());
        closer.join();
        poll.join();
        assertEquals(503, r.statusCode(), r.body());
        assertTrue(r.body().contains("stopping"), r.body());
        assertEquals("close", r.headers().firstValue("Connection").orElse(""));
    }

    @Test
    void requestsOnAKeptAliveConnectionDoNotWaitForDelayedAcks() throws Exception {
        // With Nagle's algorithm on (the JDK server's default), each response on a kept-alive connection
        // waited about 40 ms for the client's delayed ACK: 40 requests took about 1.6 s.
        try (Node n = Node.start(config(tmp.resolve("data"), "SERENGETI_ADMIN_PASSWORD", PW), Node.QUERIES)) {
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            // /health needs no password, so no password hashing sits in the timing.
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + n.address().getPort() + "/health")).build();
            for (int i = 0; i < 5; i++) {
                client.send(req, HttpResponse.BodyHandlers.ofString()); // warm up, open the connection
            }
            long start = System.nanoTime();
            for (int i = 0; i < 40; i++) {
                assertEquals(200, client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis < 600, "40 requests on one connection took " + millis + " ms");
        }
    }

    @Test
    void afterAFailedFsyncTheNodeServesNothingFromItsDataUntilRestarted() throws Exception {
        Path data = tmp.resolve("data");
        Config c = config(data, "SERENGETI_ADMIN_PASSWORD", PW);
        try (Node n = Node.start(c, Node.QUERIES)) {
            assertEquals(200, query(n, "CREATE DATABASE shop").statusCode());
            assertEquals(200, query(n, "CREATE TABLE shop.items").statusCode());
            assertEquals(200, get(n, "/ready", null).statusCode());
            n.storageForTest().failNextFsyncForTest(new java.io.IOException("injected EIO"));
            HttpResponse<String> w = query(n, "INSERT INTO shop.items (_id) VALUES ('lost')");
            assertTrue(w.statusCode() >= 500, w.statusCode() + " " + w.body());
            // The insert is in memory but maybe not on disk: nobody may read it now.
            HttpResponse<String> r = query(n, "SELECT _id FROM shop.items");
            assertEquals(503, r.statusCode(), r.body());
            assertTrue(r.body().contains("restart"), r.body());
            assertEquals(503, get(n, "/ready", null).statusCode());
            assertEquals(200, get(n, "/health", null).statusCode(), "the process is alive; it needs a restart");
        }
        try (Node n = Node.start(c, Node.QUERIES)) {
            assertEquals(200, query(n, "SELECT _id FROM shop.items").statusCode(), "a restart recovers from disk");
        }
    }

    @Test
    void queriesOverHttpAreDurableAcrossARestart() throws Exception {
        Path data = tmp.resolve("data");
        Config c = config(data, "SERENGETI_ADMIN_PASSWORD", PW);
        try (Node n = Node.start(c, Node.QUERIES)) {
            assertEquals(200, query(n, "CREATE DATABASE shop").statusCode());
            assertEquals(200, query(n, "CREATE TABLE shop.items").statusCode());
            HttpResponse<String> ins = query(n, "INSERT INTO shop.items (_id, name, qty) VALUES "
                    + "('a', '<img src=x onerror=alert(1)>', 2), ('b', 'nut', 10)");
            assertEquals(200, ins.statusCode(), ins.body());
            HttpResponse<String> bad = query(n, "SELECT * FROM");
            assertEquals(400, bad.statusCode());
            assertTrue(bad.body().contains("position"), bad.body());
            assertEquals(400, query(n, "SELECT * FROM shop.missing").statusCode());
        }
        try (Node n = Node.start(c, Node.QUERIES)) {
            HttpResponse<String> r = query(n, "SELECT _id, name FROM shop.items WHERE qty > 1 ORDER BY qty DESC");
            assertEquals(200, r.statusCode(), r.body());
            Map<String, Object> body = com.ataiva.serengeti.core.json.Json.parseObject(r.body());
            assertEquals(java.util.List.of(java.util.List.of("b", "nut"),
                    java.util.List.of("a", "<img src=x onerror=alert(1)>")), body.get("rows"));
        }
    }

    @Test
    void aStaleSetupTokenIsRemovedWhenThePasswordComesFromTheEnvironment() throws Exception {
        Path data = tmp.resolve("data");
        try (Node n = Node.start(config(data), Node.NO_QUERIES)) {
            assertTrue(Files.exists(data.resolve(SetupToken.FILE)));
        }
        try (Node n = Node.start(config(data, "SERENGETI_ADMIN_PASSWORD", PW), Node.NO_QUERIES)) {
            assertFalse(Files.exists(data.resolve(SetupToken.FILE)));
            assertTrue(Users.open(data).isSetUp());
        }
    }

    @Test
    void aDataPathThatIsAFileIsAClearError() throws Exception {
        Path f = tmp.resolve("file");
        Files.writeString(f, "x");
        assertThrows(Exception.class, () -> Node.start(config(f), Node.NO_QUERIES));
    }

    @Test
    void aBusyPortIsAClearError() throws Exception {
        Path data = tmp.resolve("data");
        Config c = config(data, "SERENGETI_ADMIN_PASSWORD", PW);
        try (Node n = Node.start(c, Node.NO_QUERIES)) {
            ConfigException e = assertThrows(ConfigException.class,
                    () -> Node.start(Config.load(null, Map.of("SERENGETI_DATA_DIR", tmp.resolve("other").toString(),
                            "SERENGETI_PORT", Integer.toString(n.address().getPort()))), Node.NO_QUERIES));
            assertTrue(e.getMessage().startsWith("cannot listen on 127.0.0.1:"), e.getMessage());
        }
    }

    @Test
    void aJunkKeyStoreStopsTheNode() throws Exception {
        Path ks = tmp.resolve("ks.p12");
        Files.writeString(ks, "x");
        Config tls = config(tmp.resolve("t"), "SERENGETI_TLS_KEYSTORE", ks.toString(),
                "SERENGETI_TLS_KEYSTORE_PASSWORD", "changeit");
        assertThrows(ConfigException.class, () -> Node.start(tls, Node.NO_QUERIES));
    }
}
