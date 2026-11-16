package com.ataiva.serengeti.core.cluster;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs at most {@code permits} asynchronous calls at once; the others wait in order, without holding a
 * thread. A call gives its permit back when its future completes, or after {@code holdMillis} if it has not
 * by then (a response that stalls part way must not stop every later call): the call itself goes on.
 */
final class AsyncLimiter {
    private final int permits;
    private final long holdMillis;
    private final ArrayDeque<Runnable> waiting = new ArrayDeque<>();
    private int running;

    AsyncLimiter(int permits, long holdMillis) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be at least 1");
        }
        this.permits = permits;
        this.holdMillis = holdMillis;
    }

    <T> CompletableFuture<T> run(Supplier<CompletableFuture<T>> call) {
        CompletableFuture<T> out = new CompletableFuture<>();
        Runnable start = () -> {
            CompletableFuture<T> f;
            try {
                f = call.get();
            } catch (Throwable e) { // an Error too: the permit must come back and the caller hear of it
                f = CompletableFuture.failedFuture(e);
            }
            boolean[] released = {false};
            Runnable release = () -> {
                synchronized (released) {
                    if (released[0]) {
                        return;
                    }
                    released[0] = true;
                }
                done();
            };
            CompletableFuture.delayedExecutor(holdMillis, TimeUnit.MILLISECONDS).execute(release);
            f.whenComplete((v, err) -> {
                release.run();
                if (err != null) {
                    out.completeExceptionally(err);
                } else {
                    out.complete(v);
                }
            });
        };
        boolean now;
        synchronized (this) {
            now = running < permits;
            if (now) {
                running++;
            } else {
                waiting.add(start);
            }
        }
        if (now) {
            start.run();
        }
        return out;
    }

    private void done() {
        Runnable next;
        synchronized (this) {
            next = waiting.poll();
            if (next == null) {
                running--;
            }
        }
        if (next != null) {
            // Takes over the permit, on another thread: calls that fail at once (a client refusing work while
            // it stops) would otherwise start each other through the whole queue on one stack.
            CompletableFuture.runAsync(next);
        }
    }

    synchronized int running() {
        return running;
    }

    synchronized int waiting() {
        return waiting.size();
    }
}
