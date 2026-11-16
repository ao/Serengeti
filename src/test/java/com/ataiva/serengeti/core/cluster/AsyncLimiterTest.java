package com.ataiva.serengeti.core.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AsyncLimiterTest {
    @Test
    void atMostThePermitsRunAndTheRestStartInOrderAsOthersFinish() throws Exception {
        AsyncLimiter l = new AsyncLimiter(2, 60_000);
        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        List<Integer> started = new ArrayList<>();
        List<CompletableFuture<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int n = i;
            CompletableFuture<Integer> call = new CompletableFuture<>();
            calls.add(call);
            results.add(l.run(() -> {
                synchronized (started) {
                    started.add(n);
                }
                return call;
            }));
        }
        assertEquals(List.of(0, 1), started);
        assertEquals(2, l.running());
        assertEquals(3, l.waiting());
        calls.get(1).complete(1);
        assertEquals(List.of(0, 1, 2), started, "a finished call lets the next one start");
        calls.get(0).completeExceptionally(new IllegalStateException("peer gone"));
        assertEquals(List.of(0, 1, 2, 3), started, "so does a failed one");
        ExecutionException e = assertThrows(ExecutionException.class, () -> results.get(0).get(1, TimeUnit.SECONDS));
        assertEquals("peer gone", e.getCause().getMessage());
        assertEquals(1, results.get(1).get(1, TimeUnit.SECONDS));
        for (int i = 2; i < 5; i++) {
            calls.get(i).complete(i);
        }
        assertEquals(List.of(0, 1, 2, 3, 4), started);
        assertEquals(4, results.get(4).get(1, TimeUnit.SECONDS));
        assertEquals(0, l.running());
        assertEquals(0, l.waiting());
    }

    @Test
    void aCallThatStallsGivesItsPermitBackAfterTheHoldTime() throws Exception {
        AsyncLimiter l = new AsyncLimiter(1, 200);
        CompletableFuture<String> stalled = new CompletableFuture<>();
        CompletableFuture<String> first = l.run(() -> stalled);
        CompletableFuture<String> second = l.run(() -> CompletableFuture.completedFuture("next"));
        assertEquals("next", second.get(5, TimeUnit.SECONDS), "started once the stalled call's hold ran out");
        assertTrue(!first.isDone(), "the stalled call itself goes on");
        stalled.complete("late");
        assertEquals("late", first.get(1, TimeUnit.SECONDS));
        assertEquals(0, l.running(), "the late completion does not give the permit back twice");
        CompletableFuture<String> a = new CompletableFuture<>();
        l.run(() -> a);
        CompletableFuture<String> b = l.run(() -> CompletableFuture.completedFuture("b"));
        assertTrue(!b.isDone() || a.isDone(), "one permit still means one at a time");
        a.complete("a");
        assertEquals("b", b.get(1, TimeUnit.SECONDS));
    }

    @Test
    void aCallThatThrowsInsteadOfReturningAFutureFailsAndFreesItsPermit() throws Exception {
        AsyncLimiter l = new AsyncLimiter(1, 60_000);
        CompletableFuture<Object> bad = l.run(() -> {
            throw new IllegalArgumentException("no such peer");
        });
        assertThrows(ExecutionException.class, () -> bad.get(1, TimeUnit.SECONDS));
        assertEquals("ok", l.run(() -> CompletableFuture.completedFuture("ok")).get(1, TimeUnit.SECONDS));
        assertThrows(IllegalArgumentException.class, () -> new AsyncLimiter(0, 1));
    }

    @Test
    void pagesShrinkForLargeRowsAndGrowForSmallOnes() {
        // 16 buckets held 16,000 ops of 1 KiB: 1,000 per bucket, so 2 buckets make the next page.
        assertEquals(2, Cluster.nextPullBuckets(16, 16_000, 16_000L * 1024));
        // Rows of 1 MiB: 4 per page at most.
        assertEquals(1, Cluster.nextPullBuckets(16, 64, 64L << 20));
        // A bucket that alone is over the target still gets a page of its own.
        assertEquals(1, Cluster.nextPullBuckets(1, 50_000, 500L << 20));
        // Small buckets: the page grows by at most 4 times, up to 200 buckets.
        assertEquals(64, Cluster.nextPullBuckets(16, 16, 16_000));
        assertEquals(200, Cluster.nextPullBuckets(64, 64, 64_000));
        assertEquals(200, Cluster.nextPullBuckets(200, 0, 0), "empty buckets");
        // 40 ops of 1 KiB per bucket (the soak's tables): 50 buckets, 2,000 ops, about 2 MB.
        assertEquals(50, Cluster.nextPullBuckets(16, 640, 640L * 1024));
    }
}
