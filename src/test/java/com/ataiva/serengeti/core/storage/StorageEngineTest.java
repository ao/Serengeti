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
    void aDirectoryInUseIsRefusedUntilItsEngineCloses() throws IOException {
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            seed(e, 3);
            IOException in = assertThrows(IOException.class, () -> StorageEngine.open(dir, FsyncPolicy.ALWAYS));
            assertTrue(in.getMessage().contains("in use by another node"), in.getMessage());
            // A restore into it is refused too, and writes nothing.
            Path backup = dir.resolve("a-backup.jsonl");
            Snapshot.writeAtomically(List.of(new Op.CreateDb("x", at(1))), backup);
            IOException r = assertThrows(IOException.class,
                    () -> com.ataiva.serengeti.core.server.Backup.restore(backup, dir));
            assertTrue(r.getMessage().contains("already holds"), r.getMessage());
            seed(e, 2);
        }
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            assertEquals(3L, count(e), "the rows are as written, with nothing from the refused restore");
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
    void snapshotChecksumCatchesAChangeThatKeepsTheJsonValid() throws IOException {
        List<Op> ops = List.of(new Op.CreateDb("d", at(1)),
                new Op.Put("d", "t", "x", Map.of("k", (Object) "v"), at(2)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Snapshot.write(ops, out);
        String text = out.toString(StandardCharsets.UTF_8);
        String changed = text.replace("\"v\"", "\"w\"");
        assertThrows(IOException.class,
                () -> Snapshot.read(new ByteArrayInputStream(changed.getBytes(StandardCharsets.UTF_8)), false));
        String noChecksum = text.replaceAll(",\"sha256\":\"[0-9a-f]{64}\"", "");
        assertEquals(ops, Snapshot.read(new ByteArrayInputStream(noChecksum.getBytes(StandardCharsets.UTF_8))),
                "start-up still loads a snapshot without a checksum");
        assertThrows(IOException.class,
                () -> Snapshot.read(new ByteArrayInputStream(noChecksum.getBytes(StandardCharsets.UTF_8)), true));
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

    /** Every op in the log and the snapshot, as a 2.0 binary would read them. */
    private static List<Op> loggedOps(Path dir) throws IOException {
        List<Op> out = new java.util.ArrayList<>();
        if (java.nio.file.Files.exists(dir.resolve(StorageEngine.SNAPSHOT_FILE))) {
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(dir.resolve(StorageEngine.SNAPSHOT_FILE))) {
                out.addAll(Snapshot.read(in));
            }
        }
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(dir.resolve(StorageEngine.WAL_FILE))) {
            Wal.read(ch).batches().forEach(out::addAll);
        }
        return out;
    }

    @Test
    void indexDefinitionsStayOutOfTheLogAndTheSnapshot() throws IOException {
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS, 4096, 0)) {
            seed(e, 40);
            e.update(s -> new StorageEngine.Planned<>(List.of(new Op.CreateIndex("d", "t", "i", at(100))), null));
            e.merge(List.of(new Op.CreateIndex("d", "t", "j", at(101)),
                    new Op.Put("d", "t", "late", Map.of("i", (Object) 7L), at(102))));
            e.apply(List.of(new Op.DropIndex("d", "t", "j", at(103))));
            e.checkpoint();
            e.apply(List.of(new Op.Put("d", "t", "after", Map.of("i", (Object) 7L), at(104))));
        }
        for (Op op : loggedOps(dir)) {
            assertTrue(Op.legacy(op), "a 2.0 binary could not read " + op);
        }
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            assertEquals(List.of("i"), e.read(s -> s.indexes("d", "t")));
            assertEquals(new java.util.TreeSet<>(List.of("after", "late", "r7")),
                    e.read(s -> s.indexLookup("d", "t", "i", "=", 7L)));
            assertEquals(42L, count(e));
        }
    }
}
