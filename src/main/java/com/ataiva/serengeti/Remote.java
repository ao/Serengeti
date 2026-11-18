package com.ataiva.serengeti;

import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.json.JsonException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Statements sent to one node for {@code serengeti import} and {@code export}: over kept-alive connections, and
 * sent again after an answer that says to wait (503, 429, or a proxy's 502 or 504) or after a connection that
 * failed, a bounded number of times with growing waits.
 */
final class Remote {
    /** The first wait before a statement is sent again; each next one is twice as long, up to {@link #MAX_WAIT_MS}. */
    static final long FIRST_WAIT_MS = 500;
    static final long MAX_WAIT_MS = 8_000;
    /** The longest a 429's Retry-After is followed. */
    static final long MAX_RETRY_AFTER_MS = 60_000;
    static final int DEFAULT_RETRIES = 8;

    /** One answer: the node's, or code 0 with the reason when there was none; and whether it took retries. */
    record Reply(Shell.Answer answer, int attempts, String retriedAfter) {
        int code() {
            return answer.code();
        }

        Map<?, ?> body() {
            return answer.json() instanceof Map<?, ?> m ? m : Map.of();
        }
    }

    /** The node did not answer, or kept saying to wait, through every retry. */
    static final class Unavailable extends Exception {
        Unavailable(String message) {
            super(message);
        }
    }

    private final Shell.Target target;
    private final int retries;
    private final String cmd;
    private final PrintStream err;

    Remote(Shell.Target target, int retries, String cmd, PrintStream err) {
        this.target = target;
        this.retries = retries;
        this.cmd = cmd;
        this.err = err;
    }

    String base() {
        return target.base().toString();
    }

    /** GET /v1/status, once: the first contact, where a failure ends the command at once. */
    Shell.Answer status() {
        return send("GET", "/v1/status", null).answer;
    }

    /** POST /v1/query with one statement, sent again while the node says to wait. */
    Reply query(String statement) throws Unavailable {
        byte[] body = body(statement);
        int attempt = 0;
        String retriedAfter = null;
        long wait = FIRST_WAIT_MS;
        for (;;) {
            attempt++;
            Sent s = send("POST", "/v1/query", body);
            Shell.Answer a = s.answer;
            if (!retryable(a.code())) {
                return new Reply(a, attempt, retriedAfter);
            }
            String why = a.message();
            if (attempt > retries) {
                throw new Unavailable(why + (retries > 0 ? " (sent " + attempt + " times)" : ""));
            }
            long ms = a.code() == 429 && s.retryAfterMs > 0 ? Math.min(Math.max(s.retryAfterMs, wait), MAX_RETRY_AFTER_MS)
                    : wait;
            err.println("serengeti " + cmd + ": " + why + "; sending it again in " + seconds(ms) + " (retry "
                    + attempt + " of " + retries + ")");
            retriedAfter = why;
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Unavailable("interrupted");
            }
            wait = Math.min(wait * 2, MAX_WAIT_MS);
        }
    }

    /** The request body for a statement: {"query": "..."}. */
    static byte[] body(String statement) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("query", statement);
        return Json.write(m).getBytes(StandardCharsets.UTF_8);
    }

    static boolean retryable(int code) {
        return code == 0 || code == 503 || code == 429 || code == 502 || code == 504;
    }

    private static String seconds(long ms) {
        return ms % 1000 == 0 ? ms / 1000 + " s" : String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0);
    }

    private record Sent(Shell.Answer answer, long retryAfterMs) {}

    /**
     * One request. The body of every answer is read to its end and closed, and the connection left open, so the
     * next request reuses it (a new TLS handshake per batch would cost more than many batches).
     */
    private Sent send(String method, String path, byte[] body) {
        HttpURLConnection c = null;
        try {
            c = Shell.open(target, path);
            c.setRequestMethod(method);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body);
                }
            }
            int code = c.getResponseCode();
            byte[] bytes;
            try (InputStream s = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
                bytes = s == null ? new byte[0] : s.readAllBytes();
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            Object json = null;
            try {
                json = text.isBlank() ? null : Json.parse(text);
            } catch (JsonException e) {
                // not JSON: shown as text
            }
            long retryAfter = 0;
            String ra = c.getHeaderField("Retry-After");
            if (ra != null) {
                try {
                    retryAfter = Long.parseLong(ra.trim()) * 1000;
                } catch (NumberFormatException e) {
                    // a date, or nonsense: the normal wait applies
                }
            }
            return new Sent(new Shell.Answer(code, text, json, null), retryAfter);
        } catch (IOException e) {
            if (c != null) {
                c.disconnect();
            }
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Sent(new Shell.Answer(0, "", null, "cannot reach " + target.base() + ": " + why), 0);
        }
    }
}
