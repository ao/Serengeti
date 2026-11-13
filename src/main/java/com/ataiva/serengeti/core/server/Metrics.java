package com.ataiva.serengeti.core.server;

import com.ataiva.serengeti.core.Version;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Counters and a duration histogram for the HTTP API, rendered as Prometheus text format 0.0.4.
 *
 * <p>Label values stay bounded. The route is one of {@link #ROUTES} or {@code other}, so a client cannot
 * create series by asking for paths. The status is the code the node answered with, or 0 when no response
 * was sent. No series carries a user name, an address or query text.
 */
final class Metrics {
    static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";
    static final String OTHER = "other";
    static final List<String> ROUTES = List.of("/", "/app.css", "/app.js", "/health", "/metrics", "/post",
            "/ready", "/v1/backup", "/v1/password", "/v1/query", "/v1/setup", "/v1/status",
            "/internal/apply", "/internal/digests", "/internal/hello", "/internal/state");

    /** Upper bounds in seconds, as they appear in the le label, and in nanoseconds for the comparison. */
    private static final String[] BOUND_LABELS = {"0.005", "0.01", "0.025", "0.05", "0.1", "0.25", "0.5", "1",
        "2.5", "5", "10"};
    private static final long[] BOUND_NANOS = {5_000_000L, 10_000_000L, 25_000_000L, 50_000_000L, 100_000_000L,
        250_000_000L, 500_000_000L, 1_000_000_000L, 2_500_000_000L, 5_000_000_000L, 10_000_000_000L};

    private record Request(String route, int code) {}

    private static final class Histogram {
        /** Observations per bucket, not cumulative; the last slot holds those above every bound. */
        final long[] counts = new long[BOUND_NANOS.length + 1];
        long sumNanos;
        long count;
    }

    private final long startSeconds = System.currentTimeMillis() / 1000;
    private final TreeMap<Request, Long> requests = new TreeMap<>(
            (a, b) -> a.route().equals(b.route()) ? Integer.compare(a.code(), b.code()) : a.route().compareTo(b.route()));
    private final TreeMap<String, Histogram> durations = new TreeMap<>();
    private long loginFailures;
    private long loginLimited;

    /** The label for a request path: the path itself for a known route, {@link #OTHER} for anything else. */
    static String route(String path) {
        return path != null && ROUTES.contains(path) ? path : OTHER;
    }

    synchronized void observe(String route, int code, long nanos) {
        requests.merge(new Request(route, code), 1L, Long::sum);
        Histogram h = durations.computeIfAbsent(route, r -> new Histogram());
        int bucket = BOUND_NANOS.length;
        for (int i = 0; i < BOUND_NANOS.length; i++) {
            if (nanos <= BOUND_NANOS[i]) {
                bucket = i;
                break;
            }
        }
        h.counts[bucket]++;
        h.sumNanos += nanos;
        h.count++;
    }

    synchronized void loginFailed() {
        loginFailures++;
    }

    synchronized void loginLimited() {
        loginLimited++;
    }

    synchronized String render(NodeOps.Gauges g) {
        StringBuilder b = new StringBuilder();
        family(b, "serengeti_build_info", "gauge", "The version of the running node.");
        b.append("serengeti_build_info{version=\"").append(escape(Version.current())).append("\"} 1\n");
        family(b, "serengeti_start_time_seconds", "gauge", "Unix time at which the node started.");
        sample(b, "serengeti_start_time_seconds", startSeconds);
        family(b, "serengeti_http_requests_total", "counter", "Requests answered, by route and status code.");
        requests.forEach((r, n) -> b.append("serengeti_http_requests_total{route=\"").append(escape(r.route()))
                .append("\",code=\"").append(r.code()).append("\"} ").append(n).append('\n'));
        family(b, "serengeti_http_request_duration_seconds", "histogram",
                "Time from receiving a request to finishing its response, by route.");
        durations.forEach((route, h) -> histogram(b, route, h));
        family(b, "serengeti_login_failures_total", "counter", "Requests with a wrong user name or password.");
        sample(b, "serengeti_login_failures_total", loginFailures);
        family(b, "serengeti_login_limited_total", "counter",
                "Requests refused because their address had too many failed attempts.");
        sample(b, "serengeti_login_limited_total", loginLimited);
        family(b, "serengeti_wal_bytes", "gauge", "Size of the write-ahead log in bytes.");
        sample(b, "serengeti_wal_bytes", g.walBytes());
        family(b, "serengeti_wal_fsyncs_total", "counter", "Forces of the write-ahead log to disk.");
        sample(b, "serengeti_wal_fsyncs_total", g.walFsyncs());
        family(b, "serengeti_wal_fsync_seconds_total", "counter", "Time spent forcing the write-ahead log.");
        b.append("serengeti_wal_fsync_seconds_total ").append(seconds(g.walFsyncNanos())).append('\n');
        family(b, "serengeti_databases", "gauge", "Databases that exist.");
        sample(b, "serengeti_databases", g.databases());
        return b.toString();
    }

    private static void histogram(StringBuilder b, String route, Histogram h) {
        String labels = "route=\"" + escape(route) + "\"";
        long cumulative = 0;
        for (int i = 0; i < BOUND_LABELS.length; i++) {
            cumulative += h.counts[i];
            b.append("serengeti_http_request_duration_seconds_bucket{").append(labels).append(",le=\"")
                    .append(BOUND_LABELS[i]).append("\"} ").append(cumulative).append('\n');
        }
        cumulative += h.counts[BOUND_NANOS.length];
        b.append("serengeti_http_request_duration_seconds_bucket{").append(labels).append(",le=\"+Inf\"} ")
                .append(cumulative).append('\n');
        b.append("serengeti_http_request_duration_seconds_sum{").append(labels).append("} ")
                .append(seconds(h.sumNanos)).append('\n');
        b.append("serengeti_http_request_duration_seconds_count{").append(labels).append("} ")
                .append(h.count).append('\n');
    }

    private static void family(StringBuilder b, String name, String type, String help) {
        b.append("# HELP ").append(name).append(' ').append(help).append('\n');
        b.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static void sample(StringBuilder b, String name, long value) {
        b.append(name).append(' ').append(value).append('\n');
    }

    private static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.9f", nanos / 1e9);
    }

    /** Escapes a label value as the text format requires: backslash, double quote and line feed. */
    static String escape(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
