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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
            StatusSource status, LongSupplier clock, NodeOps ops) {
        public Deps(Users users, AtomicReference<SetupToken> setupToken, QueryService queries,
                StatusSource status, LongSupplier clock) {
            this(users, setupToken, queries, status, clock, NodeOps.NONE);
        }
    }

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

    private HttpApi(HttpServer server, Deps deps) {
        this.server = server;
        this.deps = deps;
        this.limiter = new LoginLimiter(deps.clock());
        this.pool = Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
    }

    /** Binds and starts serving plain HTTP. Use port 0 for an ephemeral port. */
    public static HttpApi start(String bind, int port, Deps deps) throws IOException {
        return start(bind, port, null, deps);
    }

    /** With {@code tls} set, serves HTTPS only (TLS 1.2 and 1.3); there is no plaintext listener. */
    public static HttpApi start(String bind, int port, javax.net.ssl.SSLContext tls, Deps deps) throws IOException {
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
        HttpApi api = new HttpApi(s, deps);
        s.start();
        return api;
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public void close() {
        server.stop(1);
        pool.shutdownNow();
    }

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
                route(ex, caller);
            } catch (HttpError e) {
                if (e.status == 401) {
                    h.set("WWW-Authenticate", REALM);
                }
                if (e.retryAfter > 0) {
                    h.set("Retry-After", Long.toString(e.retryAfter));
                }
                sendJson(ex, e.status, error(e.getMessage()));
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "request failed: " + ex.getRequestMethod() + " " + ex.getRequestURI().getPath(), e);
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
            LOG.info(RequestLog.line(ex.getRequestMethod(), route, status, nanos, remote, caller.user));
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
        if (!deps.users().isSetUp()) {
            throw new HttpError(503, "not set up: set SERENGETI_ADMIN_PASSWORD, or POST /v1/setup with the token "
                    + "from the data directory");
        }
        Users.Role role = authenticate(ex, caller);
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
                send(ex, 200, Metrics.CONTENT_TYPE, metrics.render(deps.ops().gauges())
                        .getBytes(StandardCharsets.UTF_8));
            }
            case "/v1/password" -> {
                requireMethod(method, "POST");
                changePassword(ex, role);
            }
            default -> {
                requireMethod(method, "GET");
                Static.Page p = pages.get(path).orElseThrow(() -> new HttpError(404, "no such route"));
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

    private void changePassword(HttpExchange ex, Users.Role role) throws IOException {
        String user = basic(ex).orElseThrow()[0];
        Map<String, Object> body = jsonBody(ex);
        if (!(body.get("password") instanceof String pw)) {
            throw new HttpError(400, "password is required");
        }
        String problem = Users.passwordProblem(pw);
        if (problem != null) {
            throw new HttpError(400, problem);
        }
        deps.users().setPassword(user, pw, role);
        sendJson(ex, 200, Map.of("user", user));
    }

    private void query(HttpExchange ex, Users.Role role) throws IOException {
        Map<String, Object> body = jsonBody(ex);
        if (!(body.get("query") instanceof String q)) {
            throw new HttpError(400, "body must be {\"query\": \"...\"}");
        }
        runQuery(ex, q, role);
    }

    private void runQuery(HttpExchange ex, String q, Users.Role role) throws IOException {
        Object result;
        try {
            result = deps.queries().run(q, role);
        } catch (QueryService.Rejected r) {
            throw new HttpError(r.status(), r.getMessage());
        }
        sendJson(ex, 200, result);
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

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) {
            HttpError e = new HttpError(405, "use " + expected);
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
        String text = new String(readBody(ex), StandardCharsets.UTF_8);
        try {
            return Json.parseObject(text);
        } catch (JsonException e) {
            throw new HttpError(400, "body is not a JSON object: " + e.getMessage());
        }
    }

    /** Oversized bodies up to this size are read and discarded before the 413, see {@link #tooLarge}. */
    static final long DRAIN_LIMIT = 8L << 20;

    static byte[] readBody(HttpExchange ex) throws IOException {
        String len = ex.getRequestHeaders().getFirst("Content-Length");
        if (len != null) {
            long declared;
            try {
                declared = Long.parseLong(len.trim());
            } catch (NumberFormatException e) {
                throw new HttpError(400, "bad Content-Length");
            }
            if (declared > MAX_BODY) {
                throw tooLarge(ex.getRequestBody());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try (InputStream in = ex.getRequestBody()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > MAX_BODY) {
                    throw tooLarge(in);
                }
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    /**
     * Closing a socket with unread input makes TCP send a reset, which can destroy the 413 before the client
     * reads it. So a body of up to {@link #DRAIN_LIMIT} is read to the end first; past that the connection
     * is simply cut off.
     */
    private static HttpError tooLarge(InputStream in) {
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
        return new HttpError(413, "request body is larger than " + MAX_BODY + " bytes");
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

        HttpError(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }
    }

    static String version() {
        return Version.current();
    }
}
