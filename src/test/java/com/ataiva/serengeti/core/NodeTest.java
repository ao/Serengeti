package com.ataiva.serengeti.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.config.ConfigException;
import com.ataiva.serengeti.core.server.SetupToken;
import com.ataiva.serengeti.core.server.Users;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
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
