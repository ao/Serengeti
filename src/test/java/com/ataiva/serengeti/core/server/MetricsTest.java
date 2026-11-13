package com.ataiva.serengeti.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetricsTest {
    private static final String PASSWORD = "correct horse battery";

    @TempDir
    Path dir;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final HttpClient http = HttpClient.newHttpClient();
    private Users users;
    private HttpApi api;

    private static final NodeOps OPS = new NodeOps() {
        @Override
        public NodeOps.Gauges gauges() {
            return new NodeOps.Gauges(1234, 7, 9_000_000L, 2);
        }
    };

    @BeforeEach
    void start() throws IOException {
        users = Users.open(dir, 4, now::get);
        AtomicReference<SetupToken> token = new AtomicReference<>(SetupToken.create(dir));
        api = HttpApi.start("127.0.0.1", 0, new HttpApi.Deps(users, token,
                (q, r) -> Map.of("echo", q), () -> Map.of("node", "test"), now::get, OPS));
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private HttpResponse<String> send(String method, String path, String user, String password) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.address().getPort() + path));
        if (user != null) {
            b.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        if (method.equals("POST")) {
            b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"query\": \"x\"}"));
        } else {
            b.GET();
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void setUp() throws IOException {
        users.setPassword(Users.ADMIN, PASSWORD, Users.Role.ADMIN);
    }

    private Exposition scrape() throws Exception {
        HttpResponse<String> r = send("GET", "/metrics", "admin", PASSWORD);
        assertEquals(200, r.statusCode());
        assertEquals(Metrics.CONTENT_TYPE, r.headers().firstValue("Content-Type").orElse(null));
        return Exposition.parse(r.body());
    }

    /**
     * Scrapes until the counters show the given number of requests, scrapes not counted. Counters are updated
     * just after each response is sent, so a scrape made right after a response can miss the last request.
     */
    private Exposition settled(int requests) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        Exposition e = scrape();
        while (e.requestsExcept("/metrics") != requests && System.nanoTime() < deadline) {
            Thread.sleep(10);
            e = scrape();
        }
        return e;
    }

    @Test
    void theOutputParsesAndTheCountersMoveWithTraffic() throws Exception {
        setUp();
        for (int i = 0; i < 2; i++) {
            assertEquals(200, send("GET", "/v1/status", "admin", PASSWORD).statusCode());
        }
        assertEquals(200, send("GET", "/health", null, null).statusCode());
        assertEquals(200, send("POST", "/v1/query", "admin", PASSWORD).statusCode());
        assertEquals(404, send("GET", "/nowhere", "admin", PASSWORD).statusCode());
        assertEquals(401, send("GET", "/v1/status", "admin", "wrong password here").statusCode());

        Exposition before = settled(6);
        assertEquals(2, before.value("serengeti_http_requests_total", "route", "/v1/status", "code", "200"));
        assertEquals(1, before.value("serengeti_http_requests_total", "route", "/health", "code", "200"));
        assertEquals(1, before.value("serengeti_http_requests_total", "route", "/v1/query", "code", "200"));
        assertEquals(1, before.value("serengeti_http_requests_total", "route", "other", "code", "404"));
        assertEquals(1, before.value("serengeti_http_requests_total", "route", "/v1/status", "code", "401"));
        assertEquals(1, before.value("serengeti_login_failures_total"));
        assertEquals(0, before.value("serengeti_login_limited_total"));
        assertEquals(1234, before.value("serengeti_wal_bytes"));
        assertEquals(7, before.value("serengeti_wal_fsyncs_total"));
        assertEquals(0.009, before.value("serengeti_wal_fsync_seconds_total"), 1e-9);
        assertEquals(2, before.value("serengeti_databases"));
        assertEquals(3, before.value("serengeti_http_request_duration_seconds_count", "route", "/v1/status"));

        assertEquals(200, send("GET", "/health", null, null).statusCode());
        Exposition after = settled(7);
        assertEquals(2, after.value("serengeti_http_requests_total", "route", "/health", "code", "200"));
        assertTrue(after.value("serengeti_http_requests_total", "route", "/metrics", "code", "200") >= 1);
    }

    @Test
    void histogramBucketsAreCumulativeAndEndAtTheCount() throws Exception {
        setUp();
        assertEquals(200, send("GET", "/v1/status", "admin", PASSWORD).statusCode());
        Exposition e = settled(1);
        assertEquals(1, e.value("serengeti_http_request_duration_seconds_bucket", "route", "/v1/status", "le", "+Inf"));
        assertEquals(1, e.value("serengeti_http_request_duration_seconds_count", "route", "/v1/status"));
        assertTrue(e.value("serengeti_http_request_duration_seconds_sum", "route", "/v1/status") >= 0);
    }

    @Test
    void unknownPathsShareOneRouteLabel() throws Exception {
        setUp();
        for (int i = 0; i < 5; i++) {
            assertEquals(404, send("GET", "/probe/" + i, "admin", PASSWORD).statusCode());
        }
        Exposition e = settled(5);
        assertEquals(5, e.value("serengeti_http_requests_total", "route", "other", "code", "404"));
        assertFalse(e.text.contains("/probe"), "a path must not become a label");
        assertTrue(e.samples.stream().noneMatch(s -> s.labels().containsValue("/probe/0")));
    }

    @Test
    void metricsNeedCredentialsOfAnyRoleAndAnAdminAccount() throws Exception {
        assertEquals(503, send("GET", "/metrics", "admin", PASSWORD).statusCode(), "before setup");
        setUp();
        users.setPassword("reader", "reader password 1", Users.Role.USER);
        assertEquals(401, send("GET", "/metrics", null, null).statusCode());
        assertEquals(401, send("GET", "/metrics", "admin", "wrong password here").statusCode());
        assertEquals(200, send("GET", "/metrics", "reader", "reader password 1").statusCode());
        assertEquals(405, send("POST", "/metrics", "admin", PASSWORD).statusCode());
    }

    @Test
    void failedLoginsAreCounted() throws Exception {
        setUp();
        for (int i = 0; i < 3; i++) {
            assertEquals(401, send("GET", "/v1/status", "admin", "wrong password " + i).statusCode());
        }
        assertEquals(3, settled(3).value("serengeti_login_failures_total"));
    }

    @Test
    void labelValuesAreEscaped() {
        assertEquals("a\\\"b\\\\c\\nd", Metrics.escape("a\"b\\c\nd"));
    }

    @Test
    void theParserRefusesWhatTheFormatForbids() {
        assertThrows(AssertionError.class, () -> Exposition.parse("a 1\n"), "sample without TYPE");
        assertThrows(AssertionError.class, () -> Exposition.parse("# TYPE a counter\na 1"), "no final line feed");
        assertThrows(AssertionError.class, () -> Exposition.parse("# TYPE a counter\na 1 1700000000\n"), "timestamp");
        assertThrows(AssertionError.class, () -> Exposition.parse("# TYPE 1a counter\n1a 1\n"), "bad name");
        assertThrows(AssertionError.class, () -> Exposition.parse("# TYPE a counter\na{x=\"1\" 1\n"), "unclosed");
        assertThrows(AssertionError.class, () -> Exposition.parse("# TYPE a counter\na{x=\"1\",} 1\n"), "trailing comma");
        assertThrows(AssertionError.class, () -> Exposition.parse("# TYPE a counter\na{x=\"\\q\"} 1\n"), "bad escape");
        assertThrows(AssertionError.class, () -> Exposition.parse("# TYPE a counter\na one\n"), "bad value");
        assertThrows(AssertionError.class, () -> Exposition.parse("# TYPE a counter\na 1\na 2\n"), "duplicate series");
        assertThrows(AssertionError.class, () -> Exposition.parse("# TYPE a counter\n# TYPE a gauge\na 1\n"), "two TYPE lines");
        assertThrows(AssertionError.class, () -> Exposition.parse("# TYPE a counter\na -1\n"), "negative counter");
        assertThrows(AssertionError.class, () -> Exposition.parse("# some comment\n"), "free comments");
        assertThrows(AssertionError.class, () -> Exposition.parse(
                "# TYPE h histogram\nh_bucket{le=\"1\"} 1\nh_sum 1\nh_count 1\n"), "no +Inf bucket");
        assertThrows(AssertionError.class, () -> Exposition.parse(
                "# TYPE h histogram\nh_bucket{le=\"1\"} 2\nh_bucket{le=\"+Inf\"} 1\nh_sum 1\nh_count 1\n"),
                "buckets must not decrease");
        assertThrows(AssertionError.class, () -> Exposition.parse(
                "# TYPE h histogram\nh_bucket{le=\"1\"} 1\nh_bucket{le=\"+Inf\"} 1\nh_sum 1\nh_count 2\n"),
                "count must equal the +Inf bucket");
        Exposition ok = Exposition.parse("# HELP a A counter.\n# TYPE a counter\na{x=\"1\"} 1\na{x=\"2\"} 0\n");
        assertEquals(1, ok.value("a", "x", "1"));
    }

    /**
     * A strict reader for the Prometheus text format 0.0.4. It accepts only HELP and TYPE lines and samples,
     * checks names, label syntax, escapes and values, refuses duplicate series and checks each histogram.
     */
    static final class Exposition {
        private static final Pattern NAME = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");
        private static final Pattern LABEL = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
        private static final Pattern VALUE = Pattern.compile("[+-]?(?:Inf|NaN|(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)");
        private static final Set<String> TYPES = Set.of("counter", "gauge", "histogram", "summary", "untyped");

        record Sample(String name, Map<String, String> labels, double value) {}

        final String text;
        final List<Sample> samples = new ArrayList<>();
        private final Map<String, String> types = new HashMap<>();
        private final Set<String> series = new HashSet<>();

        private Exposition(String text) {
            this.text = text;
        }

        static Exposition parse(String text) {
            Exposition e = new Exposition(text);
            assertTrue(text.endsWith("\n"), "the text must end with a line feed");
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length - 1; i++) {
                String line = lines[i];
                int no = i + 1;
                if (line.startsWith("# HELP ")) {
                    String[] p = line.split(" ", 4);
                    check(p.length >= 3 && NAME.matcher(p[2]).matches(), no, "bad HELP line");
                } else if (line.startsWith("# TYPE ")) {
                    String[] p = line.split(" ");
                    check(p.length == 4 && NAME.matcher(p[2]).matches(), no, "bad TYPE line");
                    check(TYPES.contains(p[3]), no, "unknown type " + p[3]);
                    check(!e.types.containsKey(p[2]), no, "second TYPE for " + p[2]);
                    e.types.put(p[2], p[3]);
                } else if (line.startsWith("#") || line.isEmpty()) {
                    check(false, no, "only HELP and TYPE comments are allowed");
                } else {
                    e.sample(line, no);
                }
            }
            e.checkHistograms();
            return e;
        }

        /** Total of the request counter over all routes except one. */
        long requestsExcept(String route) {
            return samples.stream()
                    .filter(s -> s.name().equals("serengeti_http_requests_total") && !route.equals(s.labels().get("route")))
                    .mapToLong(s -> (long) s.value()).sum();
        }

        /** The value of the one series with this name and exactly these labels (name, value pairs). */
        double value(String name, String... labelPairs) {
            Map<String, String> want = pairs(labelPairs);
            List<Sample> hits = samples.stream()
                    .filter(s -> s.name().equals(name) && s.labels().equals(want)).toList();
            assertEquals(1, hits.size(), "series " + name + " " + want + " in:\n" + text);
            return hits.get(0).value();
        }

        private void sample(String line, int no) {
            int i = 0;
            while (i < line.length() && line.charAt(i) != '{' && line.charAt(i) != ' ') {
                i++;
            }
            String name = line.substring(0, i);
            check(NAME.matcher(name).matches(), no, "bad metric name");
            Map<String, String> labels = new TreeMap<>();
            if (i < line.length() && line.charAt(i) == '{') {
                i++;
                boolean first = true;
                while (true) {
                    check(i < line.length(), no, "unterminated label set");
                    if (line.charAt(i) == '}' && first) {
                        i++;
                        break;
                    }
                    int eq = line.indexOf('=', i);
                    check(eq > i, no, "label without =");
                    String ln = line.substring(i, eq);
                    check(LABEL.matcher(ln).matches() && !ln.startsWith("__"), no, "bad label name");
                    check(!labels.containsKey(ln), no, "repeated label " + ln);
                    i = eq + 1;
                    check(i < line.length() && line.charAt(i) == '"', no, "label value must be quoted");
                    i++;
                    StringBuilder v = new StringBuilder();
                    while (true) {
                        check(i < line.length(), no, "unterminated label value");
                        char c = line.charAt(i++);
                        if (c == '"') {
                            break;
                        }
                        if (c == '\\') {
                            check(i < line.length(), no, "dangling backslash");
                            char esc = line.charAt(i++);
                            check(esc == '\\' || esc == '"' || esc == 'n', no, "bad escape \\" + esc);
                            v.append(esc == 'n' ? '\n' : esc);
                        } else {
                            v.append(c);
                        }
                    }
                    labels.put(ln, v.toString());
                    first = false;
                    check(i < line.length(), no, "unterminated label set");
                    if (line.charAt(i) == ',') {
                        i++;
                        check(i < line.length() && line.charAt(i) != '}', no, "trailing comma in labels");
                        continue;
                    }
                    check(line.charAt(i) == '}', no, "expected , or } after a label");
                    i++;
                    break;
                }
            }
            check(i < line.length() && line.charAt(i) == ' ', no, "expected a space before the value");
            String rest = line.substring(i + 1);
            check(!rest.contains(" "), no, "a timestamp or extra field is not allowed");
            check(VALUE.matcher(rest).matches(), no, "bad value " + rest);
            double value = parseValue(rest);
            String family = family(name, no);
            if (types.get(family).equals("counter")) {
                check(value >= 0, no, "negative counter");
            }
            check(series.add(name + labels), no, "duplicate series " + name + labels);
            samples.add(new Sample(name, labels, value));
        }

        private String family(String name, int no) {
            if (types.containsKey(name)) {
                return name;
            }
            for (String suffix : List.of("_bucket", "_sum", "_count")) {
                if (name.endsWith(suffix) && "histogram".equals(types.get(name.substring(0, name.length() - suffix.length())))) {
                    return name.substring(0, name.length() - suffix.length());
                }
            }
            check(false, no, "sample " + name + " has no TYPE before it");
            return null;
        }

        private void checkHistograms() {
            for (Map.Entry<String, String> t : types.entrySet()) {
                if (!t.getValue().equals("histogram")) {
                    continue;
                }
                String base = t.getKey();
                Map<Map<String, String>, List<Sample>> groups = new LinkedHashMap<>();
                for (Sample s : samples) {
                    if (s.name().equals(base + "_bucket")) {
                        Map<String, String> rest = new TreeMap<>(s.labels());
                        rest.remove("le");
                        groups.computeIfAbsent(rest, k -> new ArrayList<>()).add(s);
                    }
                }
                for (Map.Entry<Map<String, String>, List<Sample>> g : groups.entrySet()) {
                    double prevLe = Double.NEGATIVE_INFINITY;
                    double prevCount = -1;
                    double inf = Double.NaN;
                    for (Sample b : g.getValue()) {
                        String le = b.labels().get("le");
                        assertTrue(le != null && VALUE.matcher(le).matches(), "bucket without a valid le in " + base);
                        double bound = parseValue(le);
                        assertTrue(bound > prevLe, "le must increase in " + base);
                        assertTrue(b.value() >= prevCount, "buckets must not decrease in " + base);
                        prevLe = bound;
                        prevCount = b.value();
                        if (Double.isInfinite(bound) && bound > 0) {
                            inf = b.value();
                        }
                    }
                    assertFalse(Double.isNaN(inf), "histogram " + base + " has no +Inf bucket");
                    Map<String, String> plain = g.getKey();
                    assertEquals(inf, one(base + "_count", plain), "count of " + base + " must equal its +Inf bucket");
                    one(base + "_sum", plain);
                }
            }
        }

        private double one(String name, Map<String, String> labels) {
            List<Sample> hits = samples.stream().filter(s -> s.name().equals(name) && s.labels().equals(labels)).toList();
            assertEquals(1, hits.size(), name + " " + labels);
            return hits.get(0).value();
        }

        private static Map<String, String> pairs(String... kv) {
            Map<String, String> m = new TreeMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                m.put(kv[i], kv[i + 1]);
            }
            return m;
        }

        private static double parseValue(String v) {
            if (v.endsWith("Inf")) {
                return v.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            }
            if (v.equals("NaN")) {
                return Double.NaN;
            }
            return Double.parseDouble(v);
        }

        private static void check(boolean ok, int line, String problem) {
            if (!ok) {
                throw new AssertionError("line " + line + ": " + problem);
            }
        }
    }
}
