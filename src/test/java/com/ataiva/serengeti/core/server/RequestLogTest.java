package com.ataiva.serengeti.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequestLogTest {
    private static final String PASSWORD = "correct horse battery";
    private static final String QUERY = "SELECT secret_column FROM shop.items";

    @TempDir
    Path dir;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord r) {
            records.add(r);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };
    private final Logger log = Logger.getLogger(HttpApi.class.getName());
    private Level oldLevel;
    private HttpApi api;

    @BeforeEach
    void start() throws Exception {
        Users users = Users.open(dir, 4, now::get);
        users.setPassword(Users.ADMIN, PASSWORD, Users.Role.ADMIN);
        AtomicReference<SetupToken> token = new AtomicReference<>();
        api = HttpApi.start("127.0.0.1", 0, new HttpApi.Deps(users, token, (q, r) -> Map.of("echo", q),
                () -> Map.of("node", "test"), now::get));
        oldLevel = log.getLevel();
        log.setLevel(Level.INFO);
        log.addHandler(capture);
    }

    @AfterEach
    void stop() {
        log.removeHandler(capture);
        log.setLevel(oldLevel);
        api.close();
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String url(String path) {
        return "http://127.0.0.1:" + api.address().getPort() + path;
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private List<String> requestLines() {
        return records.stream().map(LogRecord::getMessage)
                .filter(m -> m.startsWith("request method=")).toList();
    }

    /** The line is written just after the response, so a client can finish first. Wait for n lines, up to 5 s. */
    private List<String> awaitLines(int n) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        List<String> lines = requestLines();
        while (lines.size() < n && System.nanoTime() < deadline) {
            Thread.sleep(10);
            lines = requestLines();
        }
        return lines;
    }

    @Test
    void oneLinePerRequestWithoutBodyQueryCredentialsOrPassword() throws Exception {
        HttpResponse<String> ok = send(HttpRequest.newBuilder(URI.create(url("/v1/query?password=in-the-query")))
                .header("Authorization", basic("admin", PASSWORD))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\": \"" + QUERY + "\"}")));
        assertEquals(200, ok.statusCode());
        HttpResponse<String> wrong = send(HttpRequest.newBuilder(URI.create(url("/v1/status")))
                .header("Authorization", basic("root", "wrong password 9")));
        assertEquals(401, wrong.statusCode());
        HttpResponse<String> unknown = send(HttpRequest.newBuilder(URI.create(url("/probe/secret-path?x=1"))));
        assertEquals(401, unknown.statusCode());
        assertEquals(200, send(HttpRequest.newBuilder(URI.create(url("/health")))).statusCode());

        List<String> lines = awaitLines(4);
        assertEquals(4, lines.size(), lines.toString());
        String all = String.join("\n", lines);
        assertTrue(lines.get(0).matches("request method=POST route=/v1/query status=200 duration_ms=\\d+\\.\\d{3}"
                + " remote=127\\.0\\.0\\.1 user=admin"), lines.get(0));
        assertTrue(lines.get(1).matches("request method=GET route=/v1/status status=401 duration_ms=\\d+\\.\\d{3}"
                + " remote=127\\.0\\.0\\.1 user=-"), lines.get(1));
        assertTrue(lines.get(2).contains("route=other status=401"), lines.get(2));
        assertTrue(lines.get(3).contains("route=/health status=200"), lines.get(3));

        for (String secret : List.of(PASSWORD, "wrong password 9", "in-the-query", QUERY, "secret_column",
                "secret-path", "Authorization", "Basic ", basic("admin", PASSWORD).substring(6),
                basic("root", "wrong password 9").substring(6), "root", "SELECT", "x=1")) {
            assertFalse(all.contains(secret), "log must not contain " + secret + ":\n" + all);
        }
    }

    @Test
    void theLogRecordsAreAtInfoAndComeFromTheApiLogger() throws Exception {
        assertEquals(200, send(HttpRequest.newBuilder(URI.create(url("/health")))).statusCode());
        awaitLines(1);
        LogRecord r = records.stream().filter(x -> x.getMessage().startsWith("request ")).findFirst().orElseThrow();
        assertEquals(Level.INFO, r.getLevel());
        assertEquals(HttpApi.class.getName(), r.getLoggerName());
        // Named when the record is made, so a formatter never walks the stack for it (and here, read on the
        // test's thread, a walk would find this class).
        assertEquals(HttpApi.class.getName(), r.getSourceClassName());
        assertEquals("handle", r.getSourceMethodName());
    }

    @Test
    void fieldsFromTheClientAreCleanedToOneLine() {
        String line = RequestLog.line("GET\nFAKE", "/v1/status", 200, 1_500_000, "10.0.0.1", "ad\nmin");
        assertEquals("request method=GET?FAKE route=/v1/status status=200 duration_ms=1.500 remote=10.0.0.1 user=ad?min",
                line);
        assertFalse(line.contains("\n"));
    }

    /** The line as it was built with String.format before, which it must still equal. */
    private static String formatted(String method, String route, int status, long nanos, String remote, String user) {
        return String.format(java.util.Locale.ROOT,
                "request method=%s route=%s status=%d duration_ms=%.3f remote=%s user=%s",
                clean(method), clean(route), status, nanos / 1e6, clean(remote), user == null ? "-" : clean(user));
    }

    private static String clean(String v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length() && b.length() < 64; i++) {
            char c = v.charAt(i);
            b.append(c > 0x20 && c < 0x7f ? c : '?');
        }
        return b.toString();
    }

    @Test
    void theLineIsTheOneStringFormatWrote() {
        java.util.Random random = new java.util.Random(7);
        List<Long> durations = new java.util.ArrayList<>(List.of(0L, 1L, 499L, 500L, 501L, 999L, 1_000L, 1_499L,
                1_500L, 2_500L, 9_999_499L, 9_999_500L, 99_999_500L, 999_999_999L, 30_000_000_000L,
                86_400_000_000_000L, 999_999_999_999_999L, 1_000_000_000_000_000L, -1L, -1_500L, Long.MAX_VALUE));
        for (int i = 0; i < 100_000; i++) {
            long scale = (long) Math.pow(10, random.nextInt(13));
            durations.add(Math.floorMod(random.nextLong(), scale * 10));
            durations.add((Math.floorMod(random.nextLong(), scale * 10) / 1000) * 1000 + 500); // halves
        }
        for (long nanos : durations) {
            assertEquals(formatted("POST", "/v1/query", 200, nanos, "10.0.0.1", "admin"),
                    RequestLog.line("POST", "/v1/query", 200, nanos, "10.0.0.1", "admin"), "nanos " + nanos);
        }
        String odd = (char) 0xe9 + "\t \u007f" + "x".repeat(70);
        assertEquals(formatted(odd, "", 0, 0, odd, null), RequestLog.line(odd, "", 0, 0, odd, null));
        assertEquals(formatted("GET", "other", 503, 5, "::1", odd), RequestLog.line("GET", "other", 503, 5, "::1", odd));
    }
}
