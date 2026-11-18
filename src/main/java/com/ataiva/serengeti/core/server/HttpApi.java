package com.ataiva.serengeti.core.server;

import com.ataiva.serengeti.core.Version;
import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.json.JsonException;
import com.ataiva.serengeti.core.query.Reach;
import com.ataiva.serengeti.core.query.Scope;
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
    /** The source class of the request log's records, as the logger would find it on the stack. */
    private static final String SOURCE = HttpApi.class.getName();
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
    private final ClientLimits limits;
    private final Deps deps;
    private final LoginLimiter limiter;
    private final Metrics metrics = new Metrics();
    private final Static pages = new Static();

    private HttpApi(HttpServer server, Deps deps, ClientLimits limits, int threads) {
        this.server = server;
        this.deps = deps;
        this.limiter = new LoginLimiter(deps.clock());
        // The JDK server reads a request's headers, and this class its body, on up to http_connections threads;
        // http_threads of them at once run requests. See ClientLimits for how long a slow client may hold one.
        this.maxWaiting = Math.max(1, threads / 2);
        this.limits = limits;
        server.setExecutor(limits);
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

    /** As above, running {@code threads} requests at once ({@code http_threads}). */
    public static HttpApi start(String bind, int port, javax.net.ssl.SSLContext tls, Deps deps, int threads)
            throws IOException {
        return start(bind, port, tls, deps, threads, Math.max(CONNECTIONS, threads));
    }

    /**
     * As above, reading and answering up to {@code connections} requests at once ({@code http_connections}), at
     * least {@code threads}.
     */
    public static HttpApi start(String bind, int port, javax.net.ssl.SSLContext tls, Deps deps, int threads,
            int connections) throws IOException {
        return start(bind, port, tls, deps, threads, new ClientLimits(connections, threads));
    }

    static HttpApi start(String bind, int port, javax.net.ssl.SSLContext tls, Deps deps, int threads,
            ClientLimits limits) throws IOException {
        HttpServer s;
        InetSocketAddress addr = new InetSocketAddress(bind, port);
        try {
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
        } catch (IOException | RuntimeException e) {
            limits.close();
            throw e;
        }
        HttpApi api = new HttpApi(s, deps, limits, threads);
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
        limits.close();
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
        ClientLimits.Slot slot = ClientLimits.slot();
        try {
            try {
                limits.leave(slot); // the headers are in
                ex.getRequestBody();
                ex.setStreams(null, limits.guard(ex.getResponseBody()));
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
                    readAhead(ex, slot);
                    if (!limits.acquire(slot)) {
                        HttpError busy = new HttpError(503, "busy: every request thread is in use; try again");
                        busy.retryAfter = 1;
                        throw busy;
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
            } finally {
                limits.release(slot);
                limits.close(slot, ex);
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
                // With the source named here, the record does not walk the stack to find it. The formatter asks
                // for it inside the console handler's lock, which every request's line passes through: at 8
                // clients that walk was 44% of the time the lock was held, and the lock bounded the rate.
                LOG.logp(level, SOURCE, "handle",
                        RequestLog.line(ex.getRequestMethod(), route, status, nanos, remote, caller.user));
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
        Users.Access access = authenticate(ex, caller);
        Users.Role role = access.role();
        if (!role.mayRead() && !METRICS_ROUTES.contains(path)) {
            // Named routes, so anything added later is closed to this role until it is listed here.
            throw new HttpError(403, "the metrics role may only use GET /metrics, GET /v1/status and POST /v1/password");
        }
        if (path.equals("/v1/users") || path.startsWith("/v1/users/")) {
            users(ex, method, path, role);
            return;
        }
        // An account limited by a list reaches data only through /v1/query and /v1/changes, which
        // check the database or table; backups and accounts are admin only, and admins have no list.
        switch (path) {
            case "/v1/query", "/post" -> {
                // /post is the v1 route, kept for one release. It takes the same JSON body: a raw text/plain
                // body could be sent by a cross-site form with the browser's saved credentials.
                requireMethod(method, "POST");
                query(ex, access);
            }
            case "/v1/status" -> {
                requireMethod(method, "GET");
                sendJson(ex, 200, deps.status().status());
            }
            case "/metrics" -> {
                requireMethod(method, "GET");
                send(ex, 200, Metrics.CONTENT_TYPE, metrics.render(deps.ops().gauges(), ready(), limits.cuts())
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
                changes(ex, access);
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
            deps.users().setUpAdmin(pw);
            token.delete();
            deps.setupToken().set(null);
        }
        LOG.info("admin password set through /v1/setup; the setup token is deleted");
        sendJson(ex, 200, Map.of("user", Users.ADMIN));
    }

    /**
     * {"user": ..., "role": ...} in that order, and "databases" after them for a limited account, so clients
     * and logs see the same text every time.
     */
    private static Map<String, Object> account(String user, Users.Access access) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", user);
        m.put("role", access.role().label());
        if (access.limited()) {
            m.put("databases", access.databases());
        }
        return m;
    }

    /**
     * Account management, admin only. {@code GET /v1/users} lists names, roles and databases (never hashes);
     * {@code PUT /v1/users/NAME} with {@code {"password": ..., "role": ..., "databases": [...]}} creates an
     * account or changes any of the three, as one change; {@code DELETE /v1/users/NAME} deletes it. The last
     * admin cannot be deleted or demoted. In a cluster the change spreads to the other nodes.
     */
    private void users(HttpExchange ex, String method, String path, Users.Role role) throws IOException {
        if (role != Users.Role.ADMIN) {
            throw new HttpError(403, "managing accounts needs the admin role");
        }
        Users users = deps.users();
        if (path.equals("/v1/users")) {
            requireMethod(method, "GET");
            List<Object> out = new ArrayList<>();
            users.accounts().forEach((u, a) -> out.add(account(u, a)));
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
                        if (!k.equals("password") && !k.equals("role") && !k.equals("databases")) {
                            throw new HttpError(400, "unknown field '" + k + "'; use password, role and databases");
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
                    String pw = null;
                    if (body.get("password") != null) {
                        if (!(body.get("password") instanceof String s)) {
                            throw new HttpError(400, "password must be a string");
                        }
                        String problem = Users.passwordProblem(s);
                        if (problem != null) {
                            throw new HttpError(400, problem);
                        }
                        pw = s;
                    }
                    // Left out: the account keeps its list. null: no limit. A list: these databases only.
                    Users.Limit limit = Users.Limit.KEEP;
                    if (body.containsKey("databases")) {
                        Object d = body.get("databases");
                        if (d == null) {
                            limit = Users.Limit.NONE;
                        } else if (d instanceof List<?> l) {
                            try {
                                limit = Users.Limit.to(l);
                            } catch (IllegalArgumentException e) {
                                throw new HttpError(400, e.getMessage());
                            }
                        } else {
                            throw new HttpError(400, "databases must be a list of names of databases (db) or tables"
                                    + " (db.table), or null for every database");
                        }
                    }
                    Users.Changed c;
                    try {
                        c = users.change(name, pw, r, limit);
                    } catch (IllegalArgumentException e) {
                        // A new account without a password, nothing to change, or a list on an admin or metrics account.
                        throw new HttpError(400, e.getMessage());
                    }
                    sendJson(ex, c.created() ? 201 : 200, account(name, c.access()));
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

    /** Requests run at once, by default (http_threads). */
    static final int THREADS = 64;
    /** Requests read or answered at once, by default (http_connections): the threads. */
    static final int CONNECTIONS = 1024;

    /** At most this many /v1/changes requests wait at once; the rest answer at once. Half the threads stay free. */
    private final int maxWaiting;
    private final java.util.concurrent.atomic.AtomicInteger waiting = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * {@code GET /v1/changes?db=D[&table=T]&after=CURSOR|now[&wait=SECONDS][&limit=N]}: the changes this node
     * applied after the cursor, waiting up to {@code wait} seconds (at most 60) for one. See docs/API.md.
     */
    private void changes(HttpExchange ex, Users.Access access) throws IOException {
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
        // As the query language checks a statement: whether or not the database or table exists, before anything
        // is read. The feed of a whole database needs the database's entry; with table, the table's will do. The
        // feed matches db and table exactly, so a name the account has in another case is refused, not folded.
        Reach reach = q.get("table") == null ? new Reach.Database(db) : new Reach.Table(db, q.get("table"));
        requireReach(access, reach);
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
        if (mayWait) {
            // The account may have changed during the wait (deleted, given another role or list, a new
            // password): answer with what it may reach now, as a new request would. Cached unless it changed.
            String[] creds = basic(ex).orElseThrow();
            Users.Access now = deps.users().authenticate(creds[0], creds[1])
                    .orElseThrow(() -> new HttpError(401, "wrong user name or password"));
            if (!now.role().mayRead()) {
                throw new HttpError(403, "the metrics role may only use GET /metrics, GET /v1/status and POST /v1/password");
            }
            requireReach(now, reach);
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

    /** 403 unless the account's list gives what {@code reach} names (anything, without a list). */
    private static void requireReach(Users.Access access, Reach reach) {
        Scope scope = access.scope();
        String refused = scope == null ? null : scope.refusal(reach);
        if (refused != null) {
            throw new HttpError(403, refused);
        }
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

    private void query(HttpExchange ex, Users.Access access) throws IOException {
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
        Object maxBytes = body.get("max_bytes");
        if (maxBytes != null && !(maxBytes instanceof Long n && n >= 1)) {
            throw new HttpError(400, "max_bytes must be a whole number of bytes, at least 1");
        }
        Object result;
        // A smaller bound than the node's on this answer's rows, for a client that holds its answers whole.
        com.ataiva.serengeti.core.query.Executor.answerBytesForThisRequest((Long) maxBytes);
        try {
            result = deps.queries().run(q, access, "majority".equals(c), (String) ack);
        } catch (QueryService.Rejected r) {
            throw new HttpError(r.status(), r.getMessage());
        } finally {
            com.ataiva.serengeti.core.query.Executor.answerBytesForThisRequest(null);
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
        if (!HEAVY_INTERNAL.contains(path)) {
            byte[] answer;
            try {
                answer = (Json.write(routes.handle(method, path, query, body)) + "\n").getBytes(StandardCharsets.UTF_8);
            } catch (QueryService.Rejected r) {
                throw new HttpError(r.status(), r.getMessage());
            }
            send(ex, 200, "application/json; charset=utf-8", answer);
            return;
        }
        heavy(ex, routes, method, path, query, body);
    }

    /**
     * A node-to-node answer that holds many rows: a page of rows, or a whole database for a 2.0 peer.
     *
     * <p>At most {@link #HEAVY_INTERNAL_AT_ONCE} are built at once, whatever asks: several peers catching up
     * at the same time, each asking for pages of 200 buckets (2.1 does), once took a node's whole heap. A
     * built answer stays in the heap until it is sent, and a send to a peer cut off by a partition can take
     * as long as TCP retransmits (about 15 minutes), so answers being sent are counted too: at most
     * {@link #HEAVY_UNSENT_PER_PEER} per peer address, and {@link #HEAVY_UNSENT_BYTES} in all. Over either,
     * the request gets 503 and the peer asks again at its next sync. A stalled peer so holds a few pages and
     * leaves the routes open to the others; only several stalled at once reach the shared bound. Both are
     * checked after the build permit is taken, so requests that arrive together cannot all pass them before
     * the first answer is counted (review 22d).
     */
    private void heavy(HttpExchange ex, InternalRoutes routes, String method, String path, Map<String, String> query,
            Map<String, Object> body) throws IOException {
        String peer = ex.getRemoteAddress().getAddress().getHostAddress();
        boolean got;
        try {
            got = heavyInternal.tryAcquire(HEAVY_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            got = false;
        }
        if (!got) {
            throw new HttpError(503, "busy sending rows to other nodes; try again");
        }
        java.util.concurrent.atomic.AtomicInteger mine = unsentByPeer.computeIfAbsent(peer,
                k -> new java.util.concurrent.atomic.AtomicInteger());
        byte[] answer;
        try {
            // The peer's slot is taken before building, so answers built at the same time cannot all pass.
            if (mine.incrementAndGet() > HEAVY_UNSENT_PER_PEER || heavyBytesUnsent.get() > HEAVY_UNSENT_BYTES) {
                mine.decrementAndGet();
                throw new HttpError(503, "busy sending rows to other nodes; try again");
            }
            try {
                answer = (Json.write(routes.handle(method, path, query, body)) + "\n").getBytes(StandardCharsets.UTF_8);
            } catch (QueryService.Rejected r) {
                mine.decrementAndGet();
                throw new HttpError(r.status(), r.getMessage());
            } catch (RuntimeException | Error e) {
                mine.decrementAndGet();
                throw e;
            }
            heavyBytesUnsent.addAndGet(answer.length);
        } finally {
            heavyInternal.release();
        }
        try {
            send(ex, 200, "application/json; charset=utf-8", answer);
        } finally {
            heavyBytesUnsent.addAndGet(-answer.length);
            // Kept at zero rather than removed, so a count is never split between two objects. Only peers
            // with the cluster key get here, so the map holds one entry per peer address.
            mine.decrementAndGet();
        }
    }

    static final java.util.Set<String> HEAVY_INTERNAL = java.util.Set.of("/internal/v2/rows", "/internal/state",
            "/internal/v3/hashes", "/internal/v3/rows");
    static final int HEAVY_INTERNAL_AT_ONCE = 4;
    static final long HEAVY_WAIT_MILLIS = 1_000;
    /** A peer pulls at most 2 background and 4 read pages at once; one that stopped reading holds no more. */
    static final int HEAVY_UNSENT_PER_PEER = 6;
    static final long HEAVY_UNSENT_BYTES = Math.max(32L << 20, Runtime.getRuntime().maxMemory() / 8);
    private final java.util.concurrent.Semaphore heavyInternal = new java.util.concurrent.Semaphore(HEAVY_INTERNAL_AT_ONCE, true);
    private final java.util.concurrent.atomic.AtomicLong heavyBytesUnsent = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> unsentByPeer =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Users.Access authenticate(HttpExchange ex, Caller caller) {
        String address = ex.getRemoteAddress().getAddress().getHostAddress();
        Optional<String[]> creds = basic(ex);
        if (creds.isEmpty()) {
            throw new HttpError(401, "credentials required");
        }
        limited(address);
        Optional<Users.Access> access = deps.users().authenticate(creds.get()[0], creds.get()[1]);
        if (access.isEmpty()) {
            limiter.failed(address);
            metrics.loginFailed();
            throw new HttpError(401, "wrong user name or password");
        }
        caller.user = creds.get()[0];
        return access.get();
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

    private static final byte[] NO_BYTES = new byte[0];

    /**
     * Reads the request's body into memory before the request takes a permit, so that a client that sends it
     * slowly holds a thread but no permit, and only as long as {@link ClientLimits} allows. The handlers then read
     * the copy as they read the stream before: the same limits, the same errors, in the same order (a 401 is still
     * decided before the body is looked at). A body over the route's limit is read and discarded up to
     * {@link #DRAIN_LIMIT}, keeping its first limit + 1 bytes, or none if its declared length is already too
     * large; one that cannot be read becomes a stream that fails.
     *
     * <p>Past {@link ClientLimits#SMALL_BODY} a body's bytes count against the node's budget for bodies being
     * read; when it is spent the request gets 503. Node-to-node requests with the cluster key are not counted.
     */
    private void readAhead(HttpExchange ex, ClientLimits.Slot slot) throws IOException {
        Headers rh = ex.getRequestHeaders();
        boolean chunked = rh.getFirst("Transfer-Encoding") != null;
        long declared = 0;
        String len = rh.getFirst("Content-Length");
        if (len != null) {
            try {
                declared = Long.parseLong(len.trim());
            } catch (NumberFormatException e) {
                return; // readBody answers 400
            }
        }
        if (!chunked && declared <= 0) {
            return;
        }
        boolean peer = ex.getRequestURI().getRawPath().startsWith("/internal/") && deps.internal() != null
                && deps.internal().authorized(rh.getFirst(InternalRoutes.KEY_HEADER));
        int max = peer ? MAX_INTERNAL_BODY : MAX_BODY;
        InputStream in = ex.getRequestBody();
        byte[] buf = NO_BYTES;
        int n = 0;
        boolean failed = false;
        boolean busy = false;
        limits.enter(slot, ClientLimits.BODY);
        try {
            if (chunked || declared <= max) {
                // Grown as bytes arrive, never ahead of them: a declared length costs nothing until it is sent.
                long cap = chunked ? (long) max + 1 : declared + 1;
                buf = new byte[(int) Math.min(cap, 8192)];
                while (n < cap) {
                    if (n == buf.length) {
                        int grown = (int) Math.min(cap, (long) buf.length * 2);
                        long counted = Math.max(0, grown - ClientLimits.SMALL_BODY)
                                - Math.max(0, buf.length - ClientLimits.SMALL_BODY);
                        if (!peer && counted > 0 && !limits.reserveBody(slot, counted)) {
                            busy = true;
                            break;
                        }
                        buf = java.util.Arrays.copyOf(buf, grown);
                    }
                    int r = in.read(buf, n, buf.length - n);
                    if (r < 0) {
                        break;
                    }
                    n += r;
                    ClientLimits.received(slot, r);
                }
            }
            if (busy || n > max || !chunked && declared > max) {
                byte[] skip = new byte[65536];
                long left = DRAIN_LIMIT;
                int r;
                while (left > 0 && (r = in.read(skip, 0, (int) Math.min(skip.length, left))) > 0) {
                    left -= r;
                    ClientLimits.received(slot, r);
                }
            }
        } catch (ClientLimits.Cut c) {
            throw c;
        } catch (IOException e) {
            // A malformed chunked body, or a client that went away; readBody answers 400 if the route reads it.
            failed = true;
        } finally {
            limits.leave(slot);
        }
        if (failed) {
            ex.setStreams(new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("the request body could not be read");
                }
            }, null);
        } else {
            ex.setStreams(new java.io.ByteArrayInputStream(busy ? NO_BYTES : buf, 0, busy ? 0 : n), null);
        }
        if (busy) {
            ex.getResponseHeaders().set("Connection", "close");
            HttpError e = new HttpError(503, "busy reading other large requests; try again");
            e.retryAfter = 1;
            throw e;
        }
    }

    static byte[] readBody(HttpExchange ex) throws IOException {
        return readBody(ex, MAX_BODY);
    }

    static byte[] readBody(HttpExchange ex, int max) throws IOException {
        String len = ex.getRequestHeaders().getFirst("Content-Length");
        long declared = -1;
        if (len != null) {
            try {
                declared = Long.parseLong(len.trim());
            } catch (NumberFormatException e) {
                throw new HttpError(400, "bad Content-Length");
            }
            if (declared > max) {
                throw tooLarge(ex.getRequestBody(), max);
            }
        }
        // Sized for the declared length when it is small, as a query's is: a fixed 8 KiB buffer was 15% of what
        // a lookup by _id allocated. The loop reads to the end either way, whatever the header said.
        // A chunked body is read as it comes, whatever a Content-Length beside it says (review 22g).
        boolean chunked = ex.getRequestHeaders().getFirst("Transfer-Encoding") != null;
        int size = !chunked && declared >= 0 && declared < 8192 ? (int) Math.max(declared + 1, 256) : 8192;
        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        byte[] buf = new byte[size];
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
        // Usually the copy readAhead made; the client's own stream when the request failed before that.
        ClientLimits.Slot s = ClientLimits.slot();
        byte[] buf = new byte[65536];
        long left = DRAIN_LIMIT;
        try {
            if (s != null) {
                s.owner.enter(s, ClientLimits.BODY);
            }
            try {
                int n;
                while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                    left -= n;
                    ClientLimits.received(s, n);
                }
            } finally {
                if (s != null) {
                    s.owner.leave(s);
                }
            }
        } catch (IOException ignored) {
            // The client went away or was cut; nothing to answer.
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        ClientLimits.Slot s = ClientLimits.slot();
        // Room for a large answer is taken before it is built: many built at once ran a node out of heap.
        if (s != null && !s.owner.reserveAnswer(s, 3 * Json.estimate(body))) {
            HttpError e = new HttpError(503, "busy: too many large answers in memory; try again");
            e.retryAfter = 1;
            throw e;
        }
        StringBuilder b = new StringBuilder();
        Json.write(body, b);
        b.append('\n');
        send(ex, status, "application/json; charset=utf-8", b.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Bytes written to the socket in one call. The JDK copies what one write passes into a temporary direct
     * buffer of that size and keeps it for the thread: with one write per answer, a node held its largest
     * answers once per HTTP thread outside the heap, where the memory limit does not see them, and a few
     * 20 MB SELECTs got it killed (review 22d). In slices, that buffer stays 64 KiB.
     */
    static final int WRITE_SLICE = 64 * 1024;

    /**
     * Writes the answer. The request's permit goes back first (the answer is built), and every write is a phase
     * with a deadline (see {@link ClientLimits}), so a client that does not read holds only its thread.
     */
    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ClientLimits.Slot s = ClientLimits.slot();
        if (s != null) {
            s.owner.unsent(s, body.length);
        }
        try {
            ClientLimits.sendHeaders(ex, status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = ex.getResponseBody()) {
                for (int off = 0; off < body.length; off += WRITE_SLICE) {
                    out.write(body, off, Math.min(WRITE_SLICE, body.length - off));
                }
            }
        } finally {
            if (s != null) {
                s.owner.sent(s);
            }
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
