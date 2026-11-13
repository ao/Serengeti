package com.ataiva.serengeti.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpApiTest {
    private static final String PASSWORD = "correct horse battery";
    private static final List<String> PROTECTED = List.of("GET /", "GET /app.js", "GET /v1/status",
            "POST /v1/query", "POST /post", "POST /v1/password", "GET /nowhere");

    @TempDir
    Path dir;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final HttpClient http = HttpClient.newHttpClient();
    private Users users;
    private AtomicReference<SetupToken> token;
    private HttpApi api;
    private QueryService queries = (q, r) -> Map.of("echo", q, "role", r.name());

    @BeforeEach
    void start() throws IOException {
        users = Users.open(dir, 4, now::get);
        token = new AtomicReference<>(SetupToken.create(dir));
        api = HttpApi.start("127.0.0.1", 0, new HttpApi.Deps(users, token, (q, r) -> queries.run(q, r),
                () -> Map.of("node", "test"), now::get));
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private HttpRequest.Builder req(String method, String path, String contentType, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.address().getPort() + path));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        return b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws IOException, InterruptedException {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> call(String route, String user, String password) throws Exception {
        String[] parts = route.split(" ", 2);
        HttpRequest.Builder b = req(parts[0], parts[1], parts[0].equals("POST") ? "application/json" : null,
                parts[0].equals("POST") ? "{\"query\": \"SHOW DATABASES\", \"password\": \"another long one\"}" : null);
        if (user != null) {
            b.header("Authorization", basic(user, password));
        }
        return send(b);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder authed(HttpRequest.Builder b) {
        return b.header("Authorization", basic("admin", PASSWORD));
    }

    private void setUp() throws IOException {
        users.setPassword(Users.ADMIN, PASSWORD, Users.Role.ADMIN);
    }

    private static Map<String, Object> json(HttpResponse<String> r) {
        return Json.parseObject(r.body());
    }

    private String setupBody(String tokenValue, String password) {
        return Json.write(Map.of("token", tokenValue, "password", password));
    }

    private String tokenText() throws IOException {
        return Files.readString(dir.resolve(SetupToken.FILE)).trim();
    }

    @Test
    void healthNeedsNoCredentialsAndSaysNothingElse() throws Exception {
        HttpResponse<String> r = send(req("GET", "/health", null, null));
        assertEquals(200, r.statusCode());
        assertEquals(Map.of("status", "ok"), json(r));
        setUp();
        assertEquals(200, send(req("GET", "/health", null, null)).statusCode());
        assertEquals(405, send(req("POST", "/health", "application/json", "{}")).statusCode());
    }

    @Test
    void beforeSetupEveryOtherRouteAnswers503EvenWithCredentials() throws Exception {
        for (String route : PROTECTED) {
            HttpResponse<String> r = call(route, "admin", PASSWORD);
            assertEquals(503, r.statusCode(), route);
            assertTrue(((String) json(r).get("error")).contains("not set up"), route);
        }
    }

    @Test
    void setupWithTheTokenCreatesTheAdminAndDeletesTheToken() throws Exception {
        String t = tokenText();
        HttpResponse<String> r = send(req("POST", "/v1/setup", "application/json", setupBody(t, PASSWORD)));
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(users.isSetUp());
        assertFalse(Files.exists(dir.resolve(SetupToken.FILE)));
        assertNull(token.get());
        assertEquals(200, send(authed(req("GET", "/v1/status", null, null))).statusCode());

        HttpResponse<String> again = send(req("POST", "/v1/setup", "application/json", setupBody(t, "a different password")));
        assertEquals(409, again.statusCode());
        assertEquals(401, call("GET /v1/status", "admin", "a different password").statusCode());
    }

    @Test
    void setupRefusesWrongTokensBadPasswordsAndWrongContentTypes() throws Exception {
        String t = tokenText();
        assertEquals(403, send(req("POST", "/v1/setup", "application/json", setupBody("x" + t, PASSWORD))).statusCode());
        assertEquals(403, send(req("POST", "/v1/setup", "application/json", "{\"password\": \"" + PASSWORD + "\"}")).statusCode());
        assertEquals(400, send(req("POST", "/v1/setup", "application/json", setupBody(t, "short"))).statusCode());
        assertEquals(400, send(req("POST", "/v1/setup", "application/json", setupBody(t, "x".repeat(73)))).statusCode());
        assertEquals(415, send(req("POST", "/v1/setup", "text/plain", setupBody(t, PASSWORD))).statusCode());
        assertEquals(415, send(req("POST", "/v1/setup", "application/x-www-form-urlencoded",
                "token=" + t + "&password=" + PASSWORD)).statusCode());
        assertEquals(400, send(req("POST", "/v1/setup", "application/json", "not json")).statusCode());
        assertEquals(405, send(req("GET", "/v1/setup", null, null)).statusCode());
        assertFalse(users.isSetUp());
        assertTrue(Files.exists(dir.resolve(SetupToken.FILE)));
    }

    @Test
    void setupTokenGuessingIsRateLimited() throws Exception {
        for (int i = 0; i < LoginLimiter.MAX_FAILURES; i++) {
            assertEquals(403, send(req("POST", "/v1/setup", "application/json", setupBody("guess" + i, PASSWORD))).statusCode());
        }
        HttpResponse<String> r = send(req("POST", "/v1/setup", "application/json", setupBody(tokenText(), PASSWORD)));
        assertEquals(429, r.statusCode());
        assertFalse(users.isSetUp());
        now.addAndGet(LoginLimiter.WINDOW_MILLIS);
        assertEquals(200, send(req("POST", "/v1/setup", "application/json", setupBody(tokenText(), PASSWORD))).statusCode());
    }

    @Test
    void everyRouteButHealthRefusesMissingOrWrongCredentials() throws Exception {
        setUp();
        for (String route : PROTECTED) {
            HttpResponse<String> none = call(route, null, null);
            assertEquals(401, none.statusCode(), route);
            assertEquals(HttpApi.REALM, none.headers().firstValue("WWW-Authenticate").orElse(null), route);
            assertEquals(401, call(route, "admin", PASSWORD + "x").statusCode(), route);
            assertEquals(401, call(route, "root", PASSWORD).statusCode(), route);
            now.addAndGet(LoginLimiter.WINDOW_MILLIS);
        }
        HttpResponse<String> garbled = send(req("GET", "/v1/status", null, null).header("Authorization", "Basic %%%"));
        assertEquals(401, garbled.statusCode());
        HttpResponse<String> bearer = send(req("GET", "/v1/status", null, null).header("Authorization", "Bearer abc"));
        assertEquals(401, bearer.statusCode());
    }

    @Test
    void theOldAdminAdminLoginDoesNotWork() throws Exception {
        setUp();
        assertEquals(401, call("GET /v1/status", "admin", "admin").statusCode());
    }

    @Test
    void rightCredentialsReachEachRoute() throws Exception {
        setUp();
        assertEquals(200, call("GET /", "admin", PASSWORD).statusCode());
        assertEquals(200, call("GET /app.js", "admin", PASSWORD).statusCode());
        assertEquals(Map.of("node", "test"), json(call("GET /v1/status", "admin", PASSWORD)));
        assertEquals("SHOW DATABASES", json(call("POST /v1/query", "admin", PASSWORD)).get("echo"));
        assertEquals("SHOW DATABASES", json(call("POST /post", "admin", PASSWORD)).get("echo"));
        HttpResponse<String> nowhere = call("GET /nowhere", "admin", PASSWORD);
        assertEquals(404, nowhere.statusCode());
        assertEquals(405, send(authed(req("DELETE", "/v1/query", null, null))).statusCode());
    }

    @Test
    void failedLoginsAreRateLimitedPerAddress() throws Exception {
        setUp();
        for (int i = 0; i < LoginLimiter.MAX_FAILURES; i++) {
            assertEquals(401, call("GET /v1/status", "admin", "wrong" + i).statusCode());
        }
        HttpResponse<String> limited = call("GET /v1/status", "admin", PASSWORD);
        assertEquals(429, limited.statusCode());
        assertEquals("60", limited.headers().firstValue("Retry-After").orElse(null));
        now.addAndGet(LoginLimiter.WINDOW_MILLIS);
        assertEquals(200, call("GET /v1/status", "admin", PASSWORD).statusCode());
    }

    @Test
    void queryNeedsAJsonBodyWithAQueryString() throws Exception {
        setUp();
        for (String path : List.of("/v1/query", "/post")) {
            assertEquals(415, send(authed(req("POST", path, "text/plain", "SHOW DATABASES"))).statusCode(), path);
            assertEquals(415, send(authed(req("POST", path, "application/x-www-form-urlencoded", "query=x"))).statusCode());
            assertEquals(415, send(authed(req("POST", path, "multipart/form-data; boundary=x", "x"))).statusCode());
            assertEquals(415, send(authed(req("POST", path, null, "{\"query\": \"x\"}"))).statusCode());
            assertEquals(400, send(authed(req("POST", path, "application/json", "{\"query\": 1}"))).statusCode());
            assertEquals(400, send(authed(req("POST", path, "application/json", "[1]"))).statusCode());
            assertEquals(400, send(authed(req("POST", path, "application/json", "{"))).statusCode());
            assertEquals(200, send(authed(req("POST", path, "application/json; charset=utf-8", "{\"query\": \"x\"}"))).statusCode());
        }
    }

    // Repeated: a 413 sent before the body is read used to be lost to a TCP reset now and then.
    @org.junit.jupiter.api.RepeatedTest(10)
    void bodiesOverOneMebibyteAreRefused() throws Exception {
        setUp();
        String big = "{\"query\": \"" + "x".repeat(HttpApi.MAX_BODY) + "\"}";
        assertEquals(413, send(authed(req("POST", "/v1/query", "application/json", big))).statusCode());
        HttpRequest.Builder chunked = authed(req("POST", "/v1/query", "application/json", null)
                .POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString(big))));
        assertEquals(413, send(chunked).statusCode(), "without Content-Length");
        String fits = "{\"query\": \"" + "x".repeat(HttpApi.MAX_BODY - 20) + "\"}";
        assertEquals(200, send(authed(req("POST", "/v1/query", "application/json", fits))).statusCode());
    }

    @Test
    void crossOriginWritesAreRefused() throws Exception {
        setUp();
        HttpResponse<String> evil = send(authed(req("POST", "/v1/query", "application/json", "{\"query\": \"x\"}"))
                .header("Origin", "https://evil.example"));
        assertEquals(403, evil.statusCode());
        HttpResponse<String> nul = send(authed(req("POST", "/v1/query", "application/json", "{\"query\": \"x\"}"))
                .header("Origin", "null"));
        assertEquals(403, nul.statusCode());
        HttpResponse<String> same = send(authed(req("POST", "/v1/query", "application/json", "{\"query\": \"x\"}"))
                .header("Origin", "http://127.0.0.1:" + api.address().getPort()));
        assertEquals(200, same.statusCode());
        HttpResponse<String> setup = send(req("POST", "/v1/setup", "application/json", "{}")
                .header("Origin", "https://evil.example"));
        assertEquals(403, setup.statusCode());
    }

    @Test
    void rejectedQueriesReturnTheirStatusAndMessage() throws Exception {
        setUp();
        queries = (q, r) -> {
            throw new QueryService.Rejected(400, "expected FROM at position 9");
        };
        HttpResponse<String> r = send(authed(req("POST", "/v1/query", "application/json", "{\"query\": \"SELECT *\"}")));
        assertEquals(400, r.statusCode());
        assertEquals(Map.of("error", "expected FROM at position 9"), json(r));
    }

    @Test
    void internalErrorsDoNotLeakDetails() throws Exception {
        setUp();
        queries = (q, r) -> {
            throw new IllegalStateException("secret detail at /var/lib/serengeti");
        };
        HttpResponse<String> r = send(authed(req("POST", "/v1/query", "application/json", "{\"query\": \"x\"}")));
        assertEquals(500, r.statusCode());
        assertEquals(Map.of("error", "internal error"), json(r));
        assertFalse(r.body().contains("secret"));
        assertFalse(r.body().contains("at com."));
    }

    @Test
    void responsesCarrySecurityHeadersAndPagesACsp() throws Exception {
        setUp();
        HttpResponse<String> page = call("GET /", "admin", PASSWORD);
        assertEquals(Static.CSP, page.headers().firstValue("Content-Security-Policy").orElse(null));
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        HttpResponse<String> api = call("GET /v1/status", "admin", PASSWORD);
        assertEquals("nosniff", api.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("no-store", api.headers().firstValue("Cache-Control").orElse(null));
        assertEquals("DENY", api.headers().firstValue("X-Frame-Options").orElse(null));
        assertTrue(api.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    }

    @Test
    void consoleNeverInsertsServerDataAsHtml() throws Exception {
        setUp();
        String js = call("GET /app.js", "admin", PASSWORD).body();
        assertNotNull(js);
        for (String sink : List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(")) {
            assertFalse(js.contains(sink), sink);
        }
        String html = call("GET /", "admin", PASSWORD).body();
        assertFalse(html.contains("<script>"), "no inline script; the CSP forbids it");
    }

    @Test
    void passwordChangeTakesEffectAtOnce() throws Exception {
        setUp();
        assertEquals(200, call("GET /v1/status", "admin", PASSWORD).statusCode());
        HttpResponse<String> r = send(authed(req("POST", "/v1/password", "application/json",
                "{\"password\": \"a new long password\"}")));
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(401, call("GET /v1/status", "admin", PASSWORD).statusCode());
        assertEquals(200, call("GET /v1/status", "admin", "a new long password").statusCode());
        assertEquals(400, send(req("POST", "/v1/password", "application/json", "{\"password\": \"short\"}")
                .header("Authorization", basic("admin", "a new long password"))).statusCode());
    }

    @Test
    void listensOnLoopbackOnly() {
        assertTrue(api.address().getAddress().isLoopbackAddress());
    }
}
