package com.ataiva.serengeti.core.cluster;

import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.server.InternalRoutes;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Forwards node-to-node requests to a node on 127.0.0.1, recording the largest answer and the number of
 * requests per route. It can answer as an older node: as 2.0 (404 to the /internal/v2 and /internal/v3
 * routes, whole-database answers slowed), as 2.1 (404 to /internal/v3, roots without "full"), or as a 2.2
 * node that does not send row and bucket sizes. Taken from review 22d's and 22e's probes.
 */
final class SyncProxy implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final int target;
    /** The largest answer forwarded, and the requests forwarded, by path. */
    final ConcurrentHashMap<String, Long> maxBytes = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Integer> requests = new ConcurrentHashMap<>();
    /** Answer as a 2.0 node: 404 to the /internal/v2 and /internal/v3 routes. */
    volatile boolean legacy;
    /** Delay of each whole-database answer (/internal/state). */
    volatile long stateDelayMillis;
    /** Leave out the row sizes of /internal/v3/hashes and the rows and bytes of /internal/v3/buckets. */
    volatile boolean noSizes;
    /** Answer as 2.1: 404 to the /internal/v3 routes, and roots without "full" or "expiry". */
    volatile boolean like21;

    SyncProxy(int target) throws IOException {
        this.target = target;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    long largest(String path) {
        return maxBytes.getOrDefault(path, 0L);
    }

    int count(String path) {
        return requests.getOrDefault(path, 0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            forward(ex);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            try {
                ex.sendResponseHeaders(502, -1);
            } catch (IOException ignored) {
                // the exchange is gone
            }
        } finally {
            ex.close();
        }
    }

    private void forward(HttpExchange ex) throws IOException, InterruptedException {
        String path = ex.getRequestURI().getRawPath();
        String q = ex.getRequestURI().getRawQuery();
        byte[] body = ex.getRequestBody().readAllBytes();
        if (legacy && (path.startsWith("/internal/v2/") || path.startsWith("/internal/v3/"))
                || like21 && path.startsWith("/internal/v3/")) {
            byte[] b = "{\"error\":\"no such route\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(404, b.length);
            ex.getResponseBody().write(b);
            return;
        }
        if (path.equals("/internal/state") && stateDelayMillis > 0) {
            Thread.sleep(stateDelayMillis);
        }
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target + path
                + (q == null ? "" : "?" + q))).timeout(Duration.ofSeconds(60));
        String key = ex.getRequestHeaders().getFirst(InternalRoutes.KEY_HEADER);
        if (key != null) {
            r.header(InternalRoutes.KEY_HEADER, key);
        }
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type != null) {
            r.header("Content-Type", type);
        }
        r.method(ex.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<byte[]> res = http.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] b = res.body();
        if (noSizes && res.statusCode() == 200 && (path.equals("/internal/v3/hashes") || path.equals("/internal/v3/buckets"))) {
            Map<String, Object> m = new java.util.LinkedHashMap<>(Json.parseObject(new String(b, StandardCharsets.UTF_8)));
            m.remove("w");
            m.remove("rows");
            m.remove("bytes");
            b = Json.write(m).getBytes(StandardCharsets.UTF_8);
        }
        if (like21 && res.statusCode() == 200 && path.equals("/internal/v2/roots")) {
            Map<String, Object> m = new java.util.LinkedHashMap<>(Json.parseObject(new String(b, StandardCharsets.UTF_8)));
            m.remove("expiry");
            @SuppressWarnings("unchecked")
            Map<String, Object> dbs = (Map<String, Object>) m.get("dbs");
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            dbs.forEach((db, v) -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> roots = new java.util.LinkedHashMap<>((Map<String, Object>) v);
                roots.remove("full");
                out.put(db, roots);
            });
            m.put("dbs", out);
            b = Json.write(m).getBytes(StandardCharsets.UTF_8);
        }
        res.headers().firstValue("Content-Type").ifPresent(v -> ex.getResponseHeaders().set("Content-Type", v));
        maxBytes.merge(path, (long) b.length, Math::max);
        requests.merge(path, 1, Integer::sum);
        ex.sendResponseHeaders(res.statusCode(), b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            ex.getResponseBody().write(b);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }
}
