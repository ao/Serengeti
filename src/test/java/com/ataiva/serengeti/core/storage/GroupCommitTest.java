package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GroupCommitTest {
    @TempDir
    Path dir;

    private final AtomicLong clock = new AtomicLong(100);

    private StorageEngine.Planned<Void> put(String id) {
        Stamp at = new Stamp(clock.incrementAndGet(), 0, "n1");
        List<Op> ops = new ArrayList<>();
        ops.add(new Op.Put("d", "t", id, Map.of("v", id), at));
        return new StorageEngine.Planned<>(ops, null);
    }

    @Test
    void concurrentWritersShareFsyncsAndEveryWriteIsDurable() throws Exception {
        int threads = 16;
        int each = 25;
        // 10 ms per fsync: without group commit, 400 writes would take at least 4 s and 400 fsyncs.
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS, 64L << 20, 10)) {
            e.apply(List.of(new Op.CreateDb("d", new Stamp(1, 0, "n1")), new Op.CreateTable("d", "t", new Stamp(2, 0, "n1"))));
            long fsyncsBefore = e.walStats().fsyncs();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            long t0 = System.nanoTime();
            for (int t = 0; t < threads; t++) {
                int n = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < each; i++) {
                        String id = n + "-" + i;
                        e.update(s -> put(id));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            pool.shutdown();
            long fsyncs = e.walStats().fsyncs() - fsyncsBefore;
            System.out.println("group commit: " + threads * each + " writes, " + fsyncs + " fsyncs, " + millis + " ms");
            // The count, not the time, is the property: the time depends on how busy the machine is.
            // Measured: about 30 fsyncs for 400 writes. Fewer than 100 means writes clearly share them.
            assertTrue(fsyncs < 100, "expected shared fsyncs, got " + fsyncs + " for " + threads * each);
        }
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            assertEquals((long) threads * each, (long) e.read(s -> s.rowCount("d", "t")));
        }
    }

    @Test
    void aFailedFsyncRefusesEveryLaterWrite() throws IOException {
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            e.apply(List.of(new Op.CreateDb("d", new Stamp(1, 0, "n1"))));
            e.failNextFsyncForTest(new IOException("injected EIO"));
            IOException first = assertThrows(IOException.class,
                    () -> e.apply(List.of(new Op.CreateTable("d", "t", new Stamp(2, 0, "n1")))));
            assertTrue(first.getMessage().contains("injected EIO"), first.getMessage());
            IOException later = assertThrows(IOException.class,
                    () -> e.apply(List.of(new Op.CreateTable("d", "u", new Stamp(3, 0, "n1")))));
            assertTrue(later.getMessage().contains("unusable after a failed fsync"), later.getMessage());
        }
    }

    @Test
    void tombstonesAreCollectedAfterTheirTtl() throws IOException {
        long now = System.currentTimeMillis();
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            e.apply(List.of(new Op.CreateDb("d", new Stamp(1, 0, "n1")), new Op.CreateTable("d", "t", new Stamp(2, 0, "n1")),
                    new Op.Put("d", "t", "old", Map.of(), new Stamp(3, 0, "n1")),
                    new Op.Delete("d", "t", "old", new Stamp(now - 10_000, 0, "n1")),
                    new Op.Put("d", "t", "new", Map.of(), new Stamp(now - 9_000, 0, "n1")),
                    new Op.Delete("d", "t", "new", new Stamp(now - 1_000, 0, "n1"))));
            assertEquals(2, (long) e.read(Store::tombstones));
            assertEquals(1, e.collectTombstones(5_000));
            assertEquals(1, (long) e.read(Store::tombstones));
            assertEquals(0, (long) e.read(s -> s.rowCount("d", "t")));
        }
    }
}
