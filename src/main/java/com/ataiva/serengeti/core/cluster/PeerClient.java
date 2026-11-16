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

    // ---- protocol 2: bucket digests ----

    /** A peer that answers 404 to the protocol 2 routes runs 2.0; the caller falls back to whole-database sync. */
    static final class NotSupported extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotSupported() {
            super("peer has no protocol 2 routes", null, false, false);
        }
    }

    @SuppressWarnings("unchecked")
    CompletableFuture<Map<String, Map<String, Object>>> roots(String peer) {
        return send(request(peer, "/internal/v2/roots", CALL_TIMEOUT).GET().build(), true).thenApply(m -> {
            if (!(m.get("dbs") instanceof Map<?, ?> d)) {
                throw new IllegalStateException("peer sent no roots");
            }
            return (Map<String, Map<String, Object>>) d;
        });
    }

    CompletableFuture<List<Op>> schema(String peer, String db) {
        return get(peer, "/internal/v2/schema?db=" + enc(db), CALL_TIMEOUT).thenApply(m -> Op.listFromJson(m.get("ops")));
    }

    CompletableFuture<long[]> buckets(String peer, String db, String table) {
        return get(peer, "/internal/v2/buckets?db=" + enc(db) + "&table=" + enc(table), CALL_TIMEOUT)
                .thenApply(PeerClient::sums);
    }

    /** The full bucket sums (tombstones counted); NotSupported from a peer without the route (2.1). */
    CompletableFuture<long[]> fullBuckets(String peer, String db, String table) {
        String path = "/internal/v3/buckets?db=" + enc(db) + "&table=" + enc(table);
        return send(request(peer, path, CALL_TIMEOUT).GET().build(), true).thenApply(PeerClient::sums);
    }

    private static long[] sums(Map<String, Object> m) {
        if (!(m.get("sums") instanceof List<?> l) || l.size() != com.ataiva.serengeti.core.storage.Store.BUCKETS) {
            throw new IllegalStateException("peer sent bad bucket sums");
        }
        long[] out = new long[l.size()];
        for (int i = 0; i < out.length; i++) {
            if (!(l.get(i) instanceof Number n)) {
                throw new IllegalStateException("peer sent bad bucket sums");
            }
            out[i] = n.longValue();
        }
        return out;
    }

    CompletableFuture<List<Op>> rows(String peer, String db, String table, java.util.Collection<Integer> buckets) {
        return get(peer, "/internal/v2/rows?db=" + enc(db) + "&table=" + enc(table) + "&buckets=" + join(buckets), STATE_TIMEOUT)
                .thenApply(m -> Op.listFromJson(m.get("ops")));
    }

    // ---- protocol 3: rows by id ----

    /** A peer's rows in some buckets: ids in id order, the hash of each row's versions, and the answer's size. */
    record RowHashes(List<String> ids, long[] hashes, long bytes) {}

    /** The peer's row hashes in these buckets; NotSupported from a peer without the route (before rowsync). */
    CompletableFuture<RowHashes> hashes(String peer, String db, String table, java.util.Collection<Integer> buckets) {
        String path = "/internal/v3/hashes?db=" + enc(db) + "&table=" + enc(table) + "&buckets=" + join(buckets);
        return send(request(peer, path, STATE_TIMEOUT).GET().build(), true).thenApply(m -> {
            if (!(m.get("ids") instanceof List<?> ids) || !(m.get("h") instanceof List<?> h) || ids.size() != h.size()) {
                throw new IllegalStateException("peer sent bad row hashes");
            }
            List<String> outIds = new java.util.ArrayList<>(ids.size());
            long[] outH = new long[h.size()];
            long bytes = 0;
            for (int i = 0; i < outH.length; i++) {
                if (!(ids.get(i) instanceof String id) || !(h.get(i) instanceof Long v)) {
                    throw new IllegalStateException("peer sent bad row hashes");
                }
                outIds.add(id);
                outH[i] = v;
                bytes += id.length() + 24; // quotes, commas and up to 20 digits
            }
            return new RowHashes(outIds, outH, bytes);
        });
    }

    /** All versions of the rows with these ids, and the schema ops; NotSupported from a peer without the route. */
    CompletableFuture<List<Op>> rowsById(String peer, String db, String table, List<String> ids) {
        String body = Json.write(Map.of("db", db, "table", table, "ids", ids));
        HttpRequest r = request(peer, "/internal/v3/rows", STATE_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return send(r, true).thenApply(m -> Op.listFromJson(m.get("ops")));
    }

    private static String join(java.util.Collection<Integer> buckets) {
        StringBuilder b = new StringBuilder();
        for (int i : buckets) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(i);
        }
        return b.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    CompletableFuture<Void> apply(String peer, List<Op> ops) {
        String body = Json.write(Map.of("ops", Op.toJson(ops)));
        HttpRequest r = request(peer, "/internal/apply", CALL_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return send(r).thenApply(m -> null);
    }

    /** The peer's account digest, or NotSupported from a peer older than 2.2 (no such route). */
    CompletableFuture<String> accountDigest(String peer) {
        return send(request(peer, "/internal/v3/users?digest=1", CALL_TIMEOUT).GET().build(), true)
                .thenApply(m -> str(m, "digest"));
    }

    /** The peer's accounts and tombstones (see Users.entries). */
    CompletableFuture<List<?>> accounts(String peer) {
        return send(request(peer, "/internal/v3/users", CALL_TIMEOUT).GET().build(), true).thenApply(m -> {
            if (!(m.get("users") instanceof List<?> l)) {
                throw new IllegalStateException("peer sent no accounts");
            }
            return l;
        });
    }

    CompletableFuture<Void> pushAccounts(String peer, List<Map<String, Object>> entries) {
        String body = Json.write(Map.of("users", entries));
        HttpRequest r = request(peer, "/internal/v3/users", CALL_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return send(r, true).thenApply(m -> null);
    }

    private CompletableFuture<Map<String, Object>> get(String peer, String path, Duration timeout) {
        return send(request(peer, path, timeout).GET().build());
    }

    private HttpRequest.Builder request(String peer, String path, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(scheme + "://" + peer + path)).timeout(timeout)
                .header(InternalRoutes.KEY_HEADER, key);
    }

    private CompletableFuture<Map<String, Object>> send(HttpRequest r) {
        return send(r, false);
    }

    private CompletableFuture<Map<String, Object>> send(HttpRequest r, boolean probe) {
        return http.sendAsync(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(res -> {
            if (probe && res.statusCode() == 404) {
                throw new NotSupported();
            }
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
