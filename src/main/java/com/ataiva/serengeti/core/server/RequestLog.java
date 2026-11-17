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
        // Built by hand rather than with String.format, which parses the pattern again for every request.
        StringBuilder b = new StringBuilder(160).append("request method=");
        field(b, method);
        b.append(" route=");
        field(b, route);
        b.append(" status=").append(status).append(" duration_ms=");
        millis(b, nanos);
        b.append(" remote=");
        field(b, remote);
        b.append(" user=");
        if (user == null) {
            b.append('-');
        } else {
            field(b, user);
        }
        return b.toString();
    }

    /** {@code nanos} in milliseconds with three decimals, rounded half up, as {@code %.3f} writes it. */
    static void millis(StringBuilder b, long nanos) {
        if (nanos < 0 || nanos >= 1_000_000_000_000_000L) {
            b.append(String.format(Locale.ROOT, "%.3f", nanos / 1e6)); // not a request's duration
            return;
        }
        long micros = (nanos + 500) / 1000;
        long frac = micros % 1000;
        b.append(micros / 1000).append('.');
        if (frac < 100) {
            b.append(frac < 10 ? "00" : "0");
        }
        b.append(frac);
    }

    /** Appends {@code v} as printable ASCII without spaces, at most {@link #MAX_FIELD} characters, so one request is one line. */
    private static void field(StringBuilder b, String v) {
        int n = Math.min(v.length(), MAX_FIELD);
        for (int i = 0; i < n; i++) {
            char c = v.charAt(i);
            b.append(c > 0x20 && c < 0x7f ? c : '?');
        }
    }
}
