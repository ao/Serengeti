package com.ataiva.serengeti.core.server;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Counts failed logins (and failed setup-token attempts) per source address in a fixed window. Once an
 * address reaches the limit, it is refused without a password check until the window ends.
 */
final class LoginLimiter {
    static final int MAX_FAILURES = 10;
    static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_TRACKED = 100_000;

    private record Window(long start, int failures) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    LoginLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /** Seconds until this address may try again, or 0 if it may try now. */
    long retryAfterSeconds(String address) {
        Window w = windows.get(address);
        long now = clock.getAsLong();
        if (w == null || now - w.start() >= WINDOW_MILLIS || w.failures() < MAX_FAILURES) {
            return 0;
        }
        return Math.max(1, (w.start() + WINDOW_MILLIS - now + 999) / 1000);
    }

    void failed(String address) {
        long now = clock.getAsLong();
        if (windows.size() >= MAX_TRACKED) {
            prune(now);
        }
        windows.compute(address, (k, w) -> w == null || now - w.start() >= WINDOW_MILLIS
                ? new Window(now, 1) : new Window(w.start(), w.failures() + 1));
    }

    private void prune(long now) {
        for (Iterator<Window> it = windows.values().iterator(); it.hasNext();) {
            if (now - it.next().start() >= WINDOW_MILLIS) {
                it.remove();
            }
        }
    }
}
