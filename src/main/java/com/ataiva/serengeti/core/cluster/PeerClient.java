package com.ataiva.serengeti.core.cluster;

import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.server.InternalRoutes;
import com.ataiva.serengeti.core.storage.Op;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;

/** Calls a peer's {@code /internal/} routes. With TLS, the peer's certificate and host name are verified. */
final class PeerClient {
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(3);
    static final Duration STATE_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final String scheme;
    private final String key;

    PeerClient(String scheme, SSLContext tls, String key, Executor executor) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).executor(executor)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (tls != null) {
            b.sslContext(tls);
        }
        this.http = b.build();
        this.scheme = scheme;
        this.key = key;
    }

    /** Facts a peer reports about itself. */
    record Hello(String node, long clusterSize) {}

    CompletableFuture<Hello> hello(String peer) {
        return get(peer, "/internal/hello", CALL_TIMEOUT)
                .thenApply(m -> new Hello(str(m, "node"), num(m, "cluster_size")));
    }

    @SuppressWarnings("unchecked")
    CompletableFuture<Map<String, String>> digests(String peer) {
        return get(peer, "/internal/digests", CALL_TIMEOUT).thenApply(m -> {
            if (!(m.get("digests") instanceof Map<?, ?> d)) {
                throw new IllegalStateException("peer sent no digests");
            }
            return (Map<String, String>) d;
        });
    }

    CompletableFuture<List<Op>> state(String peer, String db) {
        return get(peer, "/internal/state?db=" + URLEncoder.encode(db, StandardCharsets.UTF_8), STATE_TIMEOUT)
                .thenApply(m -> Op.listFromJson(m.get("ops")));
    }

    CompletableFuture<Void> apply(String peer, List<Op> ops) {
        String body = Json.write(Map.of("ops", Op.toJson(ops)));
        HttpRequest r = request(peer, "/internal/apply", CALL_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return send(r).thenApply(m -> null);
    }

    private CompletableFuture<Map<String, Object>> get(String peer, String path, Duration timeout) {
        return send(request(peer, path, timeout).GET().build());
    }

    private HttpRequest.Builder request(String peer, String path, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(scheme + "://" + peer + path)).timeout(timeout)
                .header(InternalRoutes.KEY_HEADER, key);
    }

    private CompletableFuture<Map<String, Object>> send(HttpRequest r) {
        return http.sendAsync(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(res -> {
            if (res.statusCode() != 200) {
                throw new PeerError(r.uri().getAuthority() + " answered " + res.statusCode() + " to "
                        + r.uri().getPath());
            }
            return Json.parseObject(res.body());
        });
    }

    private static String str(Map<String, Object> m, String k) {
        if (!(m.get(k) instanceof String s)) {
            throw new IllegalStateException("peer sent no " + k);
        }
        return s;
    }

    private static long num(Map<String, Object> m, String k) {
        if (!(m.get(k) instanceof Number n)) {
            throw new IllegalStateException("peer sent no " + k);
        }
        return n.longValue();
    }

    /** A peer answered, but not with 200. */
    static final class PeerError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PeerError(String message) {
            super(message, null, false, false);
        }
    }

    static String describe(Throwable t) {
        Throwable c = t;
        while ((c instanceof java.util.concurrent.CompletionException
                || c instanceof java.util.concurrent.ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        if (c instanceof IOException && c.getMessage() == null) {
            return c.getClass().getSimpleName();
        }
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}
