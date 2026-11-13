package com.ataiva.serengeti.core.server;

import java.util.Locale;

/**
 * The line the API logs for each request, at INFO:
 * {@code request method=GET route=/v1/status status=200 duration_ms=0.512 remote=127.0.0.1 user=admin}.
 *
 * <p>The line is built from those fields only. It never holds a body, the query string, the Authorization
 * header or a password, and the route is a label from {@link Metrics#route}, so a path a client invents
 * cannot reach the log. The user is the account that authenticated, or {@code -}. A name a client typed
 * that matched no account is not logged.
 */
final class RequestLog {
    private static final int MAX_FIELD = 64;

    private RequestLog() {}

    static String line(String method, String route, int status, long nanos, String remote, String user) {
        return String.format(Locale.ROOT,
                "request method=%s route=%s status=%d duration_ms=%.3f remote=%s user=%s",
                field(method), field(route), status, nanos / 1e6, field(remote), user == null ? "-" : field(user));
    }

    /** Printable ASCII without spaces, at most {@link #MAX_FIELD} characters, so one request is one line. */
    static String field(String v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length() && b.length() < MAX_FIELD; i++) {
            char c = v.charAt(i);
            b.append(c > 0x20 && c < 0x7f ? c : '?');
        }
        return b.toString();
    }
}
