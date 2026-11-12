package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageEngineTest {
    @TempDir
    Path dir;

    private static Stamp at(long t) {
        return new Stamp(t, 0, "n1");
    }

    private static long count(StorageEngine e) {
        Long n = e.read(s -> s.rowCount("d", "t"));
        return n;
    }

    private static void seed(StorageEngine e, int rows) throws IOException {
        e.apply(List.of(new Op.CreateDb("d", at(1)), new Op.CreateTable("d", "t", at(2))));
        for (int i = 0; i < rows; i++) {
            e.apply(List.of(new Op.Put("d", "t", "r" + i, Map.of("i", (Object) (long) i), at(10 + i))));
        }
    }

    @Test
    void stateSurvivesReopen() throws IOException {
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            seed(e, 50);
        }
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            assertEquals(50L, count(e));
            assertEquals(51, e.openStats().walBatches());
        }
    }

    @Test
    void checkpointFoldsTheLogIntoASnapshot() throws IOException {
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            seed(e, 10);
            e.checkpoint();
            assertEquals(0, e.walBytes());
            e.apply(List.of(new Op.Delete("d", "t", "r0", at(100))));
        }
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            assertEquals(9L, count(e));
            assertEquals(1, e.openStats().walBatches());
            assertTrue(e.openStats().snapshotOps() > 0);
        }
    }

    @Test
    void automaticCheckpointAtThreshold() throws IOException {
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.OFF, 2048, 0)) {
            seed(e, 200);
            assertTrue(e.walBytes() < 2048);
            assertTrue(Files.exists(dir.resolve(StorageEngine.SNAPSHOT_FILE)));
        }
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.OFF)) {
            assertEquals(200L, count(e));
        }
    }

    /** A crash after the snapshot rename but before the log reset replays the log over the snapshot. */
    @Test
    void logReplayedOverASnapshotThatAlreadyHoldsIt() throws IOException {
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            seed(e, 5);
            Snapshot.writeAtomically(e.snapshotOps(), dir.resolve(StorageEngine.SNAPSHOT_FILE));
        }
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            assertEquals(5L, count(e));
        }
    }

    @Test
    void tornTailIsDiscardedAndReported() throws IOException {
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            seed(e, 3);
        }
        Path wal = dir.resolve(StorageEngine.WAL_FILE);
        Files.write(wal, new byte[] {0, 0, 0, 40, 1, 2}, java.nio.file.StandardOpenOption.APPEND);
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            assertEquals(3L, count(e));
            assertEquals(6, e.openStats().walDiscardedBytes());
        }
    }

    @Test
    void damagedSnapshotStopsStartupInsteadOfLosingData() throws IOException {
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            seed(e, 3);
            e.checkpoint();
        }
        Path snap = dir.resolve(StorageEngine.SNAPSHOT_FILE);
        List<String> lines = Files.readAllLines(snap);
        Files.write(snap, lines.subList(0, lines.size() - 1));
        assertThrows(IOException.class, () -> StorageEngine.open(dir, FsyncPolicy.ALWAYS));
    }

    @Test
    void updateIsAtomicCheckThenWrite() throws IOException {
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            seed(e, 1);
            assertThrows(IllegalStateException.class, () -> e.update(s -> {
                if (s.row("d", "t", "r0") != null) {
                    throw new IllegalStateException("exists");
                }
                return new StorageEngine.Planned<>(List.of(), null);
            }));
            String r = e.update(s -> new StorageEngine.Planned<>(
                    List.of(new Op.Put("d", "t", "r1", Map.of(), at(99))), "ok"));
            assertEquals("ok", r);
            assertEquals(2L, count(e));
        }
    }

    @Test
    void snapshotFormatRoundTripsAndRejectsGarbage() throws IOException {
        List<Op> ops = List.of(new Op.CreateDb("d", at(1)),
                new Op.Put("d", "t", "x", Map.of("k", (Object) "v"), at(2)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Snapshot.write(ops, out);
        assertEquals(ops, Snapshot.read(new ByteArrayInputStream(out.toByteArray())));
        for (String bad : new String[] {"", "{}\n", "{\"format\":\"serengeti-snapshot\",\"version\":2}\n",
            out.toString(StandardCharsets.UTF_8) + "{}\n"}) {
            assertThrows(IOException.class,
                    () -> Snapshot.read(new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8))));
        }
    }

    @Test
    void intervalPolicyFlushesInTheBackground() throws Exception {
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.INTERVAL)) {
            seed(e, 2);
            Thread.sleep(1500);
        }
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.INTERVAL)) {
            assertEquals(2L, count(e));
        }
    }
}
