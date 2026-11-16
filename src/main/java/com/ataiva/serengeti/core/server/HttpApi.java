package com.ataiva.serengeti.core.server;

import com.ataiva.serengeti.core.Version;
import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.json.JsonException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The HTTP API. Every route except {@code GET /health} needs HTTP Basic credentials. Until the first admin
 * password is set, every route except {@code /health} and {@code POST /v1/setup} answers 503.
 *
 * <p>Requests bodies are limited to {@link #MAX_BODY} bytes. Routes that take JSON require
 * {@code Content-Type: application/json}, which a cross-site HTML form cannot send. Errors are JSON with a
 * message and never contain a stack trace.
 */
public final class HttpApi implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(HttpApi.class.getName());
    public static final int MAX_BODY = 1 << 20;
    static final String REALM = "Basic realm=\"serengeti\", charset=\"UTF-8\"";

    /**
     * Per-request facts for the request log. Not an exchange attribute: the JDK keeps those on the context,
     * shared by every request to it, so a user name would leak into the next request's log line.
     */
    private static final class Caller {
        String user;
    }

    /** What the API needs from the rest of the node. */
    public record Deps(Users users, AtomicReference<SetupToken> setupToken, QueryService queries,
            StatusSource status, LongSupplier clock, InternalRoutes internal, NodeOps ops) {
        /** A single node without backups or gauges, for tests of the HTTP layer. */
        public Deps(Users users, AtomicReference<SetupToken> setupToken, QueryService queries,
                StatusSource status, LongSupplier clock) {
            this(users, setupToken, queries, status, clock, null, NodeOps.NONE);
        }

        /** A single node: no {@code /internal/} routes. */
        public Deps(Users users, AtomicReference<SetupToken> setupToken, QueryService queries,
                StatusSource status, LongSupplier clock, NodeOps ops) {
            this(users, setupToken, queries, status, clock, null, ops);
        }
    }

    /** Replication batches are larger than the client request that caused them. */
    static final int MAX_INTERNAL_BODY = 16 << 20;

    /** Facts for {@code GET /v1/status}. */
    @FunctionalInterface
    public interface StatusSource {
        Map<String, Object> status();
    }

    private final HttpServer server;
    private final ExecutorService pool;
    private final Deps deps;
    private final LoginLimiter limiter;
    private final Metrics metrics = new Metrics();
    private final Static pages = new Static();

    private HttpApi(HttpServer server, Deps deps, int threads) {
        this.server = server;
        this.deps = deps;
        this.limiter = new LoginLimiter(deps.clock());
        // The JDK server reads a request's headers and body on these threads, so a client that sends slowly
        // holds one until it finishes or the node's request time limit (30 s, Serengeti.main) closes it.
        // Threads are started as needed and stop after a minute idle.
        this.maxWaiting = Math.max(1, threads / 2);
        java.util.concurrent.ThreadPoolExecutor p = new java.util.concurrent.ThreadPoolExecutor(threads, threads,
                60, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "http");
                    t.setDaemon(true);
                    return t;
                });
        p.allowCoreThreadTimeOut(true);
        this.pool = p;
        server.setExecutor(pool);
        server.createContext("/", this::handle);
    }

    /** Binds and starts serving plain HTTP. Use port 0 for an ephemeral port. */
    public static HttpApi start(String bind, int port, Deps deps) throws IOException {
        return start(bind, port, null, deps);
    }

    static {
        // The JDK server leaves Nagle's algorithm on unless told otherwise. A response on a kept-alive
        // connection then waits for the client's delayed ACK, about 40 ms on Linux, so one connection did about
        // 24 requests a second, node-to-node calls included. Read when the server classes load, so set before
        // the first server is made; an operator's own -D setting wins.
        if (System.getProperty("sun.net.httpserver.nodelay") == null) {
            System.setProperty("sun.net.httpserver.nodelay", "true");
        }
    }

    /** With {@code tls} set, serves HTTPS only (TLS 1.2 and 1.3); there is no plaintext listener. */
    public static HttpApi start(String bind, int port, javax.net.ssl.SSLContext tls, Deps deps) throws IOException {
        return start(bind, port, tls, deps, THREADS);
    }

    /** As above, answering on {@code threads} request threads ({@code http_threads}). */
    public static HttpApi start(String bind, int port, javax.net.ssl.SSLContext tls, Deps deps, int threads)
            throws IOException {
        HttpServer s;
        InetSocketAddress addr = new InetSocketAddress(bind, port);
        if (tls == null) {
            s = HttpServer.create(addr, 128);
        } else {
            com.sun.net.httpserver.HttpsServer https = com.sun.net.httpserver.HttpsServer.create(addr, 128);
            https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(tls) {
                @Override
                public void configure(com.sun.net.httpserver.HttpsParameters params) {
                    params.setSSLParameters(Tls.parameters(tls));
                }
            });
            s = https;
        }
        HttpApi api = new HttpApi(s, deps, threads);
        s.start();
        return api;
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    /**
     * Stops taking requests. Those in flight get up to a second to finish; the JDK server goes on
     * dispatching requests on kept-alive connections meanwhile, and those get 503 "stopping" and the
     * connection closed, instead of reaching parts of the node that are shutting down.
     */
    public void close() {
        stopping = true;
        // stop() closes every connection, and closing a TLS connection waits for a write blocked on a peer that
        // stopped reading (cut off by a partition, say): it could hang until docker's 10 s grace ran out and
        // the node was killed. Waited for 3 s at most; the node then closes the rest, and exits.
        Thread stop = new Thread(() -> server.stop(1), "http-stop");
        stop.setDaemon(true);
        stop.start();
        try {
            stop.join(STOP_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (stop.isAlive()) {
            LOG.warning("the HTTP server did not stop within " + STOP_MILLIS / 1000 + " s (a connection's write is"
                    + " stuck); stopping the rest of the node anyway");
        }
        pool.shutdownNow();
    }

    /** How long close() waits for the JDK server to stop. */
    static final long STOP_MILLIS = 3_000;

    /** The routes an account with the metrics role may use; it gets 403 on every other authenticated one. */
    static final java.util.Set<String> METRICS_ROUTES = java.util.Set.of("/metrics", "/v1/status", "/v1/password");

    private volatile boolean stopping;

    private void handle(HttpExchange ex) throws IOException {
        long started = System.nanoTime();
        String route = Metrics.route(ex.getRequestURI().getRawPath());
        Caller caller = new Caller();
        try (ex) {
            Headers h = ex.getResponseHeaders();
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Cache-Control", "no-store");
            h.set("X-Frame-Options", "DENY");
            h.set("Referrer-Policy", "no-referrer");
            try {
                if (stopping) {
                    h.set("Connection", "close");
                    throw new HttpError(503, "this node is stopping");
                }
                route(ex, caller);
            } catch (HttpError e) {
                if (e.status == 401) {
                    h.set("WWW-Authenticate", REALM);
                }
                if (e.retryAfter > 0) {
                    h.set("Retry-After", Long.toString(e.retryAfter));
                }
                if (e.allow != null) {
                    h.set("Allow", e.allow);
                }
                drain(ex.getRequestBody());
                sendJson(ex, e.status, error(e.getMessage()));
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "request failed: " + ex.getRequestMethod() + " " + ex.getRequestURI().getPath(), e);
                drain(ex.getRequestBody());
                sendJson(ex, 500, error("internal error"));
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "client connection failed", e);
        } finally {
            // Code 0 means no response was sent, for instance when the client went away first.
            int status = Math.max(0, ex.getResponseCode());
            long nanos = System.nanoTime() - started;
            metrics.observe(route, status, nanos);
            String remote = ex.getRemoteAddress().getAddress().getHostAddress();
            // Peers sync every second; their successful calls would drown the log at INFO.
            Level level = route.startsWith("/internal/") && status == 200 ? Level.FINE : Level.INFO;
            if (LOG.isLoggable(level)) {
                LOG.log(level, RequestLog.line(ex.getRequestMethod(), route, status, nanos, remote, caller.user));
            }
        }
    }

    private void route(HttpExchange ex, Caller caller) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        String method = ex.getRequestMethod();
        if (!method.equals("GET")) {
            checkOrigin(ex);
        }
        if (path.equals("/health")) {
            requireMethod(method, "GET");
            sendJson(ex, 200, Map.of("status", "ok"));
            return;
        }
        if (path.startsWith("/internal/")) {
            internal(ex, path, method);
            return;
        }
        if (path.equals("/ready")) {
            requireMethod(method, "GET");
            boolean ready = ready();
            sendJson(ex, ready ? 200 : 503, Map.of("status", ready ? "ready" : "not ready"));
            return;
        }
        if (path.equals("/v1/setup")) {
            requireMethod(method, "POST");
            setup(ex);
            return;
        }
        // Once any account exists, every route authenticates as usual, also when no admin is left (see Users).
        if (!deps.users().hasAccounts()) {
            throw new HttpError(503, "not set up: set SERENGETI_ADMIN_PASSWORD, or POST /v1/setup with the token "
                    + "from the data directory");
        }
        Users.Role role = authenticate(ex, caller);
        if (!role.mayRead() && !METRICS_ROUTES.contains(path)) {
            // Named routes, so anything added later is closed to this role until it is listed here.
            throw new HttpError(403, "the metrics role may only use GET /metrics, GET /v1/status and POST /v1/password");
        }
        if (path.equals("/v1/users") || path.startsWith("/v1/users/")) {
            users(ex, method, path, role);
            return;
        }
        switch (path) {
            case "/v1/query", "/post" -> {
                // /post is the v1 route, kept for one release. It takes the same JSON body: a raw text/plain
                // body could be sent by a cross-site form with the browser's saved credentials.
                requireMethod(method, "POST");
                query(ex, role);
            }
            case "/v1/status" -> {
                requireMethod(method, "GET");
                sendJson(ex, 200, deps.status().status());
            }
            case "/metrics" -> {
                requireMethod(method, "GET");
                send(ex, 200, Metrics.CONTENT_TYPE, metrics.render(deps.ops().gauges(), ready())
                        .getBytes(StandardCharsets.UTF_8));
            }
            case "/v1/backup" -> {
                requireMethod(method, "GET");
                if (role != Users.Role.ADMIN) {
                    throw new HttpError(403, "backups need the admin role");
                }
                java.nio.file.Path file;
                try {
                    file = deps.ops().backup()
                            .orElseThrow(() -> new HttpError(501, "backups are not available in this build"));
                } catch (IOException e) {
                    // Nothing is sent yet, so the client can be told; the path and the cause go to the log only.
                    LOG.log(Level.WARNING, "backup failed: the copy could not be written in the data directory", e);
                    throw new HttpError(503, "the backup could not be written in the data directory (is the disk full?)");
                }
                try {
                    Backup.send(ex, file);
                } finally {
                    java.nio.file.Files.deleteIfExists(file);
                }
            }
            case "/v1/password" -> {
                requireMethod(method, "POST");
                changePassword(ex, role);
            }
            case "/v1/changes" -> {
                requireMethod(method, "GET");
                changes(ex);
            }
            default -> {
                // A path that is not a route is 404 whatever the method; 405 is for routes that exist.
                Static.Page p = pages.get(path).orElseThrow(() -> new HttpError(404, "no such route"));
                requireMethod(method, "GET");
                ex.getResponseHeaders().set("Content-Security-Policy", Static.CSP);
                send(ex, 200, p.contentType(), p.body());
            }
        }
    }

    /** Runs every readiness check. The reason a check failed is logged, never sent to the client. */
    private boolean ready() {
        for (BooleanSupplier check : deps.ops().readiness()) {
            try {
                if (!check.getAsBoolean()) {
                    return false;
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "readiness check failed", e);
                return false;
            }
        }
        return true;
    }

    private void setup(HttpExchange ex) throws IOException {
        if (deps.users().isSetUp()) {
            throw new HttpError(409, "already set up");
        }
        String address = ex.getRemoteAddress().getAddress().getHostAddress();
        limited(address);
        Map<String, Object> body = jsonBody(ex);
        SetupToken token = deps.setupToken().get();
        if (token == null || !(body.get("token") instanceof String t) || !token.matches(t)) {
            limiter.failed(address);
            throw new HttpError(403, "wrong or missing setup token");
        }
        if (!(body.get("password") instanceof String pw)) {
            throw new HttpError(400, "password is required");
        }
        String problem = Users.passwordProblem(pw);
        if (problem != null) {
            throw new HttpError(400, problem);
        }
        synchronized (deps.setupToken()) {
            if (deps.users().isSetUp()) {
                throw new HttpError(409, "already set up");
            }
            deps.users().setPassword(Users.ADMIN, pw, Users.Role.ADMIN);
            token.delete();
            deps.setupToken().set(null);
        }
        LOG.info("admin password set through /v1/setup; the setup token is deleted");
        sendJson(ex, 200, Map.of("user", Users.ADMIN));
    }

    /**
     * Account management, admin only. {@code GET /v1/users} lists names and roles (never hashes);
     * {@code PUT /v1/users/NAME} with {@code {"password": ..., "role": ...}} creates an account or changes
     * its password, its role or both; {@code DELETE /v1/users/NAME} deletes it. The last admin cannot be
     * deleted or demoted. In a cluster the change spreads to the other nodes.
     */
    /** {"user": ..., "role": ...} in that order, so clients and logs see the same text every time. */
    private static Map<String, Object> account(String user, String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", user);
        m.put("role", role);
        return m;
    }

    private void users(HttpExchange ex, String method, String path, Users.Role role) throws IOException {
        if (role != Users.Role.ADMIN) {
            throw new HttpError(403, "managing accounts needs the admin role");
        }
        Users users = deps.users();
        if (path.equals("/v1/users")) {
            requireMethod(method, "GET");
            List<Object> out = new ArrayList<>();
            users.list().forEach((u, r) -> out.add(account(u, r.label())));
            sendJson(ex, 200, Map.of("users", out));
            return;
        }
        String name = path.substring("/v1/users/".length());
        String bad = Users.nameProblem(name);
        if (bad != null) {
            throw new HttpError(400, bad);
        }
        try {
            switch (method) {
                case "PUT" -> {
                    Map<String, Object> body = jsonBody(ex);
                    for (String k : body.keySet()) {
                        if (!k.equals("password") && !k.equals("role")) {
                            throw new HttpError(400, "unknown field '" + k + "'; use password and role");
                        }
                    }
                    Users.Role r = null;
                    if (body.get("role") != null) {
                        if (!(body.get("role") instanceof String s)) {
                            throw new HttpError(400, "role must be admin, write, read or metrics");
                        }
                        try {
                            r = Users.Role.parse(s);
                        } catch (IllegalArgumentException e) {
                            throw new HttpError(400, e.getMessage());
                        }
                    }
                    Users.Role existing = users.list().get(name);
                    if (body.get("password") != null) {
                        if (!(body.get("password") instanceof String pw)) {
                            throw new HttpError(400, "password must be a string");
                        }
                        String problem = Users.passwordProblem(pw);
                        if (problem != null) {
                            throw new HttpError(400, problem);
                        }
                        Users.Role to = r != null ? r : existing != null ? existing : Users.Role.WRITE;
                        users.setPassword(name, pw, to);
                        sendJson(ex, existing == null ? 201 : 200, account(name, to.label()));
                    } else if (r == null) {
                        throw new HttpError(400, "give a password, a role, or both");
                    } else if (existing == null) {
                        throw new HttpError(400, "a new account needs a password");
                    } else {
                        users.setRole(name, r);
                        sendJson(ex, 200, account(name, r.label()));
                    }
                }
                case "DELETE" -> {
                    if (!users.delete(name)) {
                        throw new HttpError(404, "no account '" + name + "'");
                    }
                    Map<String, Object> gone = new LinkedHashMap<>();
                    gone.put("user", name);
                    gone.put("deleted", true);
                    sendJson(ex, 200, gone);
                }
                default -> {
                    HttpError e = new HttpError(405, "use PUT or DELETE");
                    e.allow = "PUT, DELETE";
                    throw e;
                }
            }
        } catch (IllegalStateException e) {
            throw new HttpError(409, e.getMessage());
        }
    }

    /** Request threads, by default (http_threads). */
    static final int THREADS = 64;

    /** At most this many /v1/changes requests wait at once; the rest answer at once. Half the threads stay free. */
    private final int maxWaiting;
    private final java.util.concurrent.atomic.AtomicInteger waiting = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * {@code GET /v1/changes?db=D[&table=T]&after=CURSOR|now[&wait=SECONDS][&limit=N]}: the changes this node
     * applied after the cursor, waiting up to {@code wait} seconds (at most 60) for one. See docs/API.md.
     */
    private void changes(HttpExchange ex) throws IOException {
        com.ataiva.serengeti.core.storage.ChangeFeed feed = deps.ops().changes();
        if (feed == null) {
            throw new HttpError(404, "this node keeps no change feed (changes_buffer is 0)");
        }
        if (deps.ops().diskFailed()) {
            throw new HttpError(503, "a write to this node's disk failed, so it no longer serves its data; see its log");
        }
        Map<String, String> q = queryParams(ex);
        String db = q.get("db");
        String after = q.get("after");
        if (db == null || db.isEmpty() || after == null) {
            throw new HttpError(400, "db and after are required; after=now gives a cursor to follow from");
        }
        int limit = intParam(q, "limit", 1000, 1, 10_000);
        int wait = intParam(q, "wait", 0, 0, 60);
        if (after.equals("now")) {
            sendJson(ex, 200, page(List.of(), feed.now()));
            return;
        }
        boolean mayWait = wait > 0 && waiting.incrementAndGet() <= maxWaiting;
        com.ataiva.serengeti.core.storage.ChangeFeed.Page page;
        try {
            page = feed.read(after, db, q.get("table"), limit, mayWait ? wait * 1000L : 0);
        } catch (com.ataiva.serengeti.core.storage.ChangeFeed.Gone e) {
            throw new HttpError(410, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpError(503, "interrupted");
        } finally {
            if (wait > 0) {
                waiting.decrementAndGet();
            }
        }
        List<Object> out = new ArrayList<>(page.changes().size());
        for (com.ataiva.serengeti.core.storage.ChangeFeed.Change c : page.changes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", c.kind());
            if (c.table() != null) {
                m.put("table", c.table());
            }
            if (c.id() != null) {
                m.put("_id", c.id());
                m.put("deleted", c.row() == null);
                m.put("row", c.row());
            }
            m.put("at", c.stamp().wall());
            m.put("node", c.stamp().node());
            out.add(m);
        }
        sendJson(ex, 200, page(out, page.cursor()));
    }

    private static Map<String, Object> page(List<Object> changes, String cursor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("changes", changes);
        m.put("cursor", cursor);
        return m;
    }

    private static Map<String, String> queryParams(HttpExchange ex) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = java.net.URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(k, v);
        }
        return out;
    }

    private static int intParam(Map<String, String> q, String name, int dflt, int min, int max) {
        String v = q.get(name);
        if (v == null) {
            return dflt;
        }
        try {
            int n = Integer.parseInt(v);
            if (n < min || n > max) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new HttpError(400, name + " must be a whole number from " + min + " to " + max);
        }
    }

    private void changePassword(HttpExchange ex, Users.Role role) throws IOException {
        String[] creds = basic(ex).orElseThrow();
        Map<String, Object> body = jsonBody(ex);
        if (!(body.get("password") instanceof String pw)) {
            throw new HttpError(400, "password is required");
        }
        String problem = Users.passwordProblem(pw);
        if (problem != null) {
            throw new HttpError(400, problem);
        }
        // The account's own change: checked again against the account as it is now, and it keeps the current
        // role, so it cannot bring back an account an admin deleted or demoted meanwhile.
        if (!deps.users().changeOwnPassword(creds[0], creds[1], pw)) {
            throw new HttpError(401, "wrong user name or password");
        }
        sendJson(ex, 200, Map.of("user", creds[0]));
    }

    private void query(HttpExchange ex, Users.Role role) throws IOException {
        Map<String, Object> body = jsonBody(ex);
        if (!(body.get("query") instanceof String q)) {
            throw new HttpError(400, "body must be {\"query\": \"...\"}");
        }
        Object c = body.getOrDefault("consistency", "local");
        if (!"local".equals(c) && !"majority".equals(c)) {
            throw new HttpError(400, "consistency must be \"local\" or \"majority\"");
        }
        Object ack = body.get("ack");
        if (ack != null && !"local".equals(ack) && !"majority".equals(ack)) {
            throw new HttpError(400, "ack must be \"local\" or \"majority\"");
        }
        Object result;
        try {
            result = deps.queries().run(q, role, "majority".equals(c), (String) ack);
        } catch (QueryService.Rejected r) {
            throw new HttpError(r.status(), r.getMessage());
        }
        sendJson(ex, 200, result);
    }

    /** Addresses already logged for a wrong cluster key, so a misconfigured peer logs once, not every second. */
    private final Map<String, Boolean> keyWarnings = new java.util.concurrent.ConcurrentHashMap<>();

    private void internal(HttpExchange ex, String path, String method) throws IOException {
        InternalRoutes routes = deps.internal();
        if (routes == null) {
            throw new HttpError(404, "no such route");
        }
        // Not rate-limited like logins: a key of 32 or more characters cannot be guessed, and a limit per
        // address would let one misconfigured node behind the same NAT lock its healthy peers out.
        if (!routes.authorized(ex.getRequestHeaders().getFirst(InternalRoutes.KEY_HEADER))) {
            String address = ex.getRemoteAddress().getAddress().getHostAddress();
            if (keyWarnings.putIfAbsent(address, Boolean.TRUE) == null) {
                LOG.warning("refused a node-to-node request from " + address + ": wrong or missing cluster key");
            }
            throw new HttpError(401, "cluster key required");
        }
        Map<String, Object> body = Map.of();
        if (method.equals("POST")) {
            requireContentType(ex, "application/json");
            try {
                body = Json.parseObject(utf8(readBody(ex, MAX_INTERNAL_BODY)));
            } catch (JsonException e) {
                throw new HttpError(400, "body is not a JSON object");
            }
        } else if (!method.equals("GET")) {
            HttpError e = new HttpError(405, "use GET or POST");
            e.allow = "GET, POST";
            throw e;
        }
        Map<String, String> query = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    query.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        try {
            sendJson(ex, 200, routes.handle(method, path, query, body));
        } catch (QueryService.Rejected r) {
            throw new HttpError(r.status(), r.getMessage());
        }
    }

    private Users.Role authenticate(HttpExchange ex, Caller caller) {
        String address = ex.getRemoteAddress().getAddress().getHostAddress();
        Optional<String[]> creds = basic(ex);
        if (creds.isEmpty()) {
            throw new HttpError(401, "credentials required");
        }
        limited(address);
        Optional<Users.Role> role = deps.users().verify(creds.get()[0], creds.get()[1]);
        if (role.isEmpty()) {
            limiter.failed(address);
            metrics.loginFailed();
            throw new HttpError(401, "wrong user name or password");
        }
        caller.user = creds.get()[0];
        return role.get();
    }

    private void limited(String address) {
        long wait = limiter.retryAfterSeconds(address);
        if (wait > 0) {
            metrics.loginLimited();
            HttpError e = new HttpError(429, "too many failed attempts; try again later");
            e.retryAfter = wait;
            throw e;
        }
    }

    private static Optional<String[]> basic(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Basic ", 0, 6)) {
            return Optional.empty();
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(auth.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        return Optional.of(new String[] {decoded.substring(0, colon), decoded.substring(colon + 1)});
    }

    /** Browsers send Origin on cross-site writes; refuse one that names a different site than Host. */
    private static void checkOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            return;
        }
        String host = ex.getRequestHeaders().getFirst("Host");
        String originHost;
        try {
            originHost = java.net.URI.create(origin.trim()).getRawAuthority();
        } catch (IllegalArgumentException e) {
            originHost = null;
        }
        if (host == null || originHost == null || !originHost.equalsIgnoreCase(host.trim())) {
            throw new HttpError(403, "cross-origin request refused");
        }
    }

    /** A 405 names the method the route takes in {@code Allow}, as RFC 9110 requires. */
    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) {
            HttpError e = new HttpError(405, "use " + expected);
            e.allow = expected;
            throw e;
        }
    }

    private static void requireContentType(HttpExchange ex, String type) {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        String base = ct == null ? "" : ct.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!base.equals(type)) {
            throw new HttpError(415, "Content-Type must be " + type);
        }
    }

    private static Map<String, Object> jsonBody(HttpExchange ex) throws IOException {
        requireContentType(ex, "application/json");
        String text = utf8(readBody(ex));
        try {
            return Json.parseObject(text);
        } catch (JsonException e) {
            throw new HttpError(400, "body is not a JSON object: " + e.getMessage());
        }
    }

    /**
     * Decodes a body that must be UTF-8 (RFC 8259). {@code new String(bytes, UTF_8)} would turn bad bytes into
     * U+FFFD and store that in place of what the client sent.
     */
    static String utf8(byte[] body) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(body)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new HttpError(400, "body is not valid UTF-8");
        }
    }

    /** Oversized bodies up to this size are read and discarded before the 413, see {@link #tooLarge}. */
    static final long DRAIN_LIMIT = 8L << 20;

    static byte[] readBody(HttpExchange ex) throws IOException {
        return readBody(ex, MAX_BODY);
    }

    static byte[] readBody(HttpExchange ex, int max) throws IOException {
        String len = ex.getRequestHeaders().getFirst("Content-Length");
        if (len != null) {
            long declared;
            try {
                declared = Long.parseLong(len.trim());
            } catch (NumberFormatException e) {
                throw new HttpError(400, "bad Content-Length");
            }
            if (declared > max) {
                throw tooLarge(ex.getRequestBody(), max);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        // Not try-with-resources: after a failed read the JDK's request stream throws the same exception
        // again from close(), and adding an exception to itself as suppressed throws IllegalArgumentException,
        // which became a 500. The exchange closes the stream.
        InputStream in = ex.getRequestBody();
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > max) {
                    throw tooLarge(in, max);
                }
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            // A malformed chunked body, a chunked trailer (which the JDK cannot read), or a client that went
            // away mid-body; the last one never sees the answer.
            throw new HttpError(400, "the request body could not be read");
        }
        return out.toByteArray();
    }

    /**
     * Closing a socket with unread input makes TCP send a reset, which can destroy the 413 before the client
     * reads it. So a body of up to {@link #DRAIN_LIMIT} is read to the end first; past that the connection
     * is simply cut off.
     */
    private static HttpError tooLarge(InputStream in, int max) {
        drain(in);
        return new HttpError(413, "request body is larger than " + max + " bytes");
    }

    /**
     * Reads and discards what is left of a request body, up to {@link #DRAIN_LIMIT}. Done before every error
     * response: a refusal sent while the client is still sending (401 before the body is read, for one) is
     * otherwise sometimes destroyed by the TCP reset that closing a socket with unread input causes.
     */
    private static void drain(InputStream in) {
        byte[] buf = new byte[65536];
        long left = DRAIN_LIMIT;
        try {
            int n;
            while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                left -= n;
            }
        } catch (IOException ignored) {
            // The client went away; nothing to answer.
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8",
                (Json.write(body) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /** An error the client caused, sent as {@code {"error": message}}. */
    static final class HttpError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;
        long retryAfter;
        /** For a 405: the methods the route takes. */
        String allow;

        HttpError(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }
    }

    static String version() {
        return Version.current();
    }
}
