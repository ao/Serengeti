package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.server.Backup;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The change feed across restarts, crashes, checkpoints and restores (see StorageEngine and FeedFiles). */
class ChangeFeedRestartTest {
    @TempDir
    Path tmp;

    private static final long NO_CHECKPOINT = 64L << 20;

    private static Stamp at(long t) {
        return new Stamp(t, 0, "n1");
    }

    private static StorageEngine open(Path dir, int capacity) throws IOException {
        return StorageEngine.open(dir, FsyncPolicy.ALWAYS, NO_CHECKPOINT, 0, capacity);
    }

    private static void write(StorageEngine e, Op... ops) throws IOException {
        e.update(s -> new StorageEngine.Planned<>(List.of(ops), null));
    }

    private static long rows(StorageEngine e) {
        Long n = e.read(s -> s.rowCount("d", "t"));
        return n;
    }

    private static void schema(StorageEngine e) throws IOException {
        write(e, new Op.CreateDb("d", at(1)), new Op.CreateTable("d", "t", at(2)));
    }

    private static Op put(String id, long v, long t) {
        return new Op.Put("d", "t", id, Map.of("v", v), at(t));
    }

    /** Every change after {@code cursor}, written out whole: number, kind, table, row id, value and stamp. */
    static List<String> after(ChangeFeed f, String cursor) throws Exception {
        List<String> out = new ArrayList<>();
        while (true) {
            ChangeFeed.Page p = f.read(cursor, "d", null, 1000, 0);
            if (p.changes().isEmpty()) {
                return out;
            }
            for (ChangeFeed.Change c : p.changes()) {
                out.add(render(c));
            }
            cursor = p.cursor();
        }
    }

    static String render(ChangeFeed.Change c) {
        return c.seq() + " " + c.kind() + " " + c.table() + " " + c.id() + " " + Json.write(c.row()) + " " + c.stamp();
    }

    private static String epoch(String cursor) {
        return cursor.substring(0, cursor.indexOf(':'));
    }

    @Test
    void aCursorFromBeforeARestartGetsTheSameChangesAndNumberingGoesOn() throws Exception {
        Path dir = tmp.resolve("d");
        String c0;
        String c1;
        List<String> before;
        try (StorageEngine e = open(dir, 100)) {
            schema(e);
            c0 = e.changes().now();
            write(e, put("a", 1, 10), put("b", 2, 11));
            write(e, put("a", 3, 12));
            write(e, new Op.Delete("d", "t", "b", at(13)));
            c1 = e.changes().now();
            before = after(e.changes(), c0);
            assertEquals(4, before.size(), before.toString());
        }
        try (StorageEngine e = open(dir, 100)) {
            assertTrue(e.openStats().changes().contains("kept"), e.openStats().changes());
            assertEquals(c1, e.changes().now(), "the same epoch, and the same newest change");
            assertEquals(before, after(e.changes(), c0));
            assertEquals(List.of(), after(e.changes(), c1));
            write(e, put("c", 4, 14));
            List<String> next = after(e.changes(), c1);
            assertEquals(1, next.size());
            assertTrue(next.get(0).startsWith((Long.parseLong(c1.substring(c1.indexOf(':') + 1)) + 1) + " row t c"),
                    next.toString());
        }
    }

    @Test
    void aCrashKeepsTheEpochAndATornTailOnlyLosesWhatWasNeverListed() throws Exception {
        Path dir = tmp.resolve("d");
        String c0;
        List<String> before;
        StorageEngine e = open(dir, 100);
        schema(e);
        c0 = e.changes().now();
        write(e, put("a", 1, 10), put("b", 2, 11));
        write(e, put("a", 3, 12));
        before = after(e.changes(), c0);
        e.abandonForTest();
        // A write that never finished: the first half of a record.
        byte[] rec = Wal.encode(List.of(put("lost", 9, 20)));
        Files.write(dir.resolve(StorageEngine.WAL_FILE), Arrays.copyOf(rec, rec.length / 2), StandardOpenOption.APPEND);
        try (StorageEngine again = open(dir, 100)) {
            assertTrue(again.openStats().walDiscardedBytes() > 0);
            assertEquals(epoch(c0), epoch(again.changes().now()));
            assertEquals(before, after(again.changes(), c0));
            write(again, put("z", 5, 21));
            assertEquals(before.size() + 1, after(again.changes(), c0).size());
        }
    }

    @Test
    void aLogThatLostAWholeRecordStartsANewEpoch() throws Exception {
        Path dir = tmp.resolve("d");
        String c0;
        try (StorageEngine e = open(dir, 100)) {
            schema(e);
            c0 = e.changes().now();
            for (int i = 0; i < 4; i++) {
                write(e, put("r" + i, i, 10 + i));
            }
        }
        // Damage the payload of the second-to-last record: a whole record follows it.
        Path wal = dir.resolve(StorageEngine.WAL_FILE);
        byte[] b = Files.readAllBytes(wal);
        List<Integer> starts = new ArrayList<>();
        for (int pos = 0; pos + Wal.HEADER <= b.length; ) {
            starts.add(pos);
            pos += Wal.HEADER + ByteBuffer.wrap(b, pos, 4).getInt();
        }
        int victim = starts.get(starts.size() - 2);
        b[victim + Wal.HEADER + 3] ^= 0x20;
        Files.write(wal, b);
        try (StorageEngine e = open(dir, 100)) {
            assertTrue(e.openStats().changes().contains("lost a record"), e.openStats().changes());
            ChangeFeed.Gone gone = assertThrows(ChangeFeed.Gone.class, () -> e.changes().read(c0, "d", null, 10, 0));
            assertTrue(gone.getMessage().contains("after=now"), gone.getMessage());
        }
    }

    @Test
    void cursorsSurviveCheckpointsAsFarAsTheBufferReaches() throws Exception {
        Path dir = tmp.resolve("d");
        List<String> cursors = new ArrayList<>();
        List<List<String>> answers = new ArrayList<>();
        String last;
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS, 2_000, 0, 50)) {
            schema(e);
            for (int i = 0; i < 300; i++) {
                if (i % 30 == 0) {
                    cursors.add(e.changes().now());
                }
                write(e, put("r" + (i % 40), i, 10 + i));
            }
            last = e.changes().now();
            for (String c : cursors) {
                try {
                    answers.add(after(e.changes(), c));
                } catch (ChangeFeed.Gone g) {
                    answers.add(null);
                }
            }
            assertTrue(Files.size(dir.resolve(StorageEngine.WAL_FILE)) < 2_000, "the log was folded");
        }
        assertTrue(Files.exists(dir.resolve(StorageEngine.CHANGES_FILE)));
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS, 2_000, 0, 50)) {
            assertEquals(last, e.changes().now());
            assertEquals(50, e.changes().held()[0]);
            int kept = 0;
            for (int i = 0; i < cursors.size(); i++) {
                String c = cursors.get(i);
                if (answers.get(i) == null) {
                    assertThrows(ChangeFeed.Gone.class, () -> after(e.changes(), c));
                } else {
                    assertEquals(answers.get(i), after(e.changes(), c));
                    kept++;
                }
            }
            assertTrue(kept >= 1, "the newest cursor is within the last 50 changes");
            String old = cursors.get(0);
            assertThrows(ChangeFeed.Gone.class, () -> e.changes().read(old, "d", null, 10, 0), "older than what is held");
        }
    }

    @Test
    void aCrashAtAnyStepOfACheckpointKeepsTheDataAndNeverMisnumbers() throws Exception {
        for (int step = 1; step <= 3; step++) {
            Path dir = tmp.resolve("step" + step);
            StorageEngine e = open(dir, 100);
            schema(e);
            String c0 = e.changes().now();
            write(e, put("a", 1, 10), put("b", 2, 11));
            write(e, put("a", 3, 12));
            List<String> before = after(e.changes(), c0);
            e.stopCheckpointAtForTest = step;
            int s = step;
            assertThrows(IOException.class, e::checkpoint, "step " + s);
            e.abandonForTest();
            try (StorageEngine again = open(dir, 100)) {
                assertEquals(2L, rows(again), "step " + step);
                String note = again.openStats().changes();
                if (step == 2) {
                    // The snapshot is in place and the log not yet emptied: the log cannot be told from a new one.
                    assertTrue(note.contains("stopped during a checkpoint"), note);
                    assertThrows(ChangeFeed.Gone.class, () -> again.changes().read(c0, "d", null, 10, 0));
                } else {
                    assertTrue(note.contains("kept"), "step " + step + ": " + note);
                    assertEquals(before, after(again.changes(), c0), "step " + step);
                }
                assertFalse(Files.exists(dir.resolve(StorageEngine.CHANGES_NEXT)), "step " + step);
                write(again, put("c", 4, 13));
            }
            try (StorageEngine again = open(dir, 100)) {
                assertTrue(again.openStats().changes().contains("kept"), "step " + step + " then a restart");
            }
        }
    }

    @Test
    void aRestoreStartsANewEpoch() throws Exception {
        Path dir = tmp.resolve("d");
        Path backup = tmp.resolve("backup.jsonl");
        String c0;
        try (StorageEngine e = open(dir, 100)) {
            schema(e);
            c0 = e.changes().now();
            write(e, put("a", 1, 10));
            e.writeBackup(backup);
        }
        Path restored = tmp.resolve("restored");
        Backup.restore(backup, restored);
        try (StorageEngine e = open(restored, 100)) {
            assertEquals(1L, rows(e));
            assertTrue(e.openStats().changes().contains("new epoch"), e.openStats().changes());
            assertNotEquals(epoch(c0), epoch(e.changes().now()));
            assertThrows(ChangeFeed.Gone.class, () -> e.changes().read(c0, "d", null, 10, 0));
        }
        // Copied back over the same directory, as one restores a node in place: a new epoch too.
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                if (!p.getFileName().toString().equals(DirLock.FILE)) {
                    Files.delete(p);
                }
            }
        }
        Backup.restore(backup, dir);
        try (StorageEngine e = open(dir, 100)) {
            assertThrows(ChangeFeed.Gone.class, () -> e.changes().read(c0, "d", null, 10, 0));
        }
    }

    @Test
    void mergesFromPeersAreListedAgainAsTheyWereTheFirstTime() throws Exception {
        Path dir = tmp.resolve("d");
        String c0;
        List<String> before;
        try (StorageEngine e = open(dir, 100)) {
            schema(e);
            c0 = e.changes().now();
            write(e, put("a", 1, 10));
            // Concurrent with the local write (it had seen nothing), newer and older; and a row of its own.
            e.merge(List.of(new Op.Put("d", "t", "a", Map.of("v", 7L), new Stamp(11, 0, "p2"), Map.of())));
            e.merge(List.of(new Op.Put("d", "t", "a", Map.of("v", 0L), new Stamp(9, 0, "p3"), Map.of())));
            e.merge(List.of(new Op.Put("d", "t", "p", Map.of("v", 5L), new Stamp(12, 0, "p2"))));
            before = after(e.changes(), c0);
            assertEquals(3, before.size(), "the older concurrent write does not become the value: " + before);
        }
        try (StorageEngine e = open(dir, 100)) {
            assertEquals(before, after(e.changes(), c0));
        }
    }

    @Test
    void droppedTombstonesAreDroppedAgainAtTheSamePlaceInTheLog() throws Exception {
        Path dir = tmp.resolve("d");
        String c0;
        List<String> before;
        try (StorageEngine e = open(dir, 100)) {
            schema(e);
            write(e, put("x", 1, 100));
            write(e, new Op.Delete("d", "t", "x", at(200)));
            c0 = e.changes().now();
            assertEquals(1, e.expireTombstones(300, true));
            // A stale peer's older write, which the dropped tombstone would have hidden, brings the row back.
            e.merge(List.of(new Op.Put("d", "t", "x", Map.of("v", 0L), new Stamp(150, 0, "p2"), Map.of())));
            write(e, put("y", 5, 400));
            before = after(e.changes(), c0);
            assertEquals(2, before.size(), before.toString());
            assertTrue(before.get(0).contains("row t x {\"v\":0}"), before.toString());
        }
        assertTrue(Files.readString(dir.resolve(StorageEngine.MARKS_FILE)).contains("\"collect\""));
        Path without = tmp.resolve("without-marks");
        copy(dir, without);
        Files.delete(without.resolve(StorageEngine.MARKS_FILE));
        try (StorageEngine e = open(dir, 100)) {
            assertEquals(before, after(e.changes(), c0));
        }
        try (StorageEngine e = open(without, 100)) {
            // What the marks are for: replayed without the drop, the tombstone hides the stale write, and the
            // changes after the cursor are not the ones listed before.
            assertNotEquals(before, after(e.changes(), c0));
        }
    }

    @Test
    void withFsyncOffOnlyACleanStopKeepsTheEpoch() throws Exception {
        Path dir = tmp.resolve("d");
        String c0;
        List<String> before;
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.OFF, NO_CHECKPOINT, 0, 100)) {
            schema(e);
            c0 = e.changes().now();
            write(e, put("a", 1, 10));
            before = after(e.changes(), c0);
        }
        StorageEngine e = StorageEngine.open(dir, FsyncPolicy.OFF, NO_CHECKPOINT, 0, 100);
        assertEquals(before, after(e.changes(), c0), "a clean stop");
        write(e, put("b", 2, 11));
        e.abandonForTest();
        try (StorageEngine again = StorageEngine.open(dir, FsyncPolicy.OFF, NO_CHECKPOINT, 0, 100)) {
            assertTrue(again.openStats().changes().contains("fsync off"), again.openStats().changes());
            assertThrows(ChangeFeed.Gone.class, () -> again.changes().read(c0, "d", null, 10, 0));
            assertEquals(2L, rows(again), "the data is all there");
        }
    }

    @Test
    void withFsyncIntervalAChangeIsListedOnceTheTimerForcedIt() throws Exception {
        Path dir = tmp.resolve("d");
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.INTERVAL, NO_CHECKPOINT, 1_500, 100)) {
            schema(e);
            long deadline = System.currentTimeMillis() + 10_000;
            while (e.changes().now().endsWith(":0") && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            String c0 = e.changes().now();
            assertTrue(c0.endsWith(":2"), c0);
            write(e, put("a", 1, 10));
            assertEquals(List.of(), after(e.changes(), c0), "acknowledged, not yet forced");
            List<String> got = List.of();
            while (got.isEmpty() && System.currentTimeMillis() < deadline + 10_000) {
                Thread.sleep(50);
                got = after(e.changes(), c0);
            }
            assertEquals(1, got.size());
        }
    }

    @Test
    void aFeedTurnedOffDropsItsFilesAndOneTurnedOnAgainStartsANewEpoch() throws Exception {
        Path dir = tmp.resolve("d");
        String c0;
        try (StorageEngine e = open(dir, 100)) {
            schema(e);
            c0 = e.changes().now();
            write(e, put("a", 1, 10));
        }
        assertTrue(Files.exists(dir.resolve(StorageEngine.CHANGES_FILE)));
        try (StorageEngine e = open(dir, 0)) {
            assertEquals(null, e.changes());
            write(e, put("b", 2, 11));
        }
        assertFalse(Files.exists(dir.resolve(StorageEngine.CHANGES_FILE)));
        try (StorageEngine e = open(dir, 100)) {
            assertThrows(ChangeFeed.Gone.class, () -> e.changes().read(c0, "d", null, 10, 0));
        }
    }

    @Test
    void aSmallerBufferAfterARestartKeepsTheNewestChanges() throws Exception {
        Path dir = tmp.resolve("d");
        String c0;
        String c45;
        List<String> tail;
        try (StorageEngine e = open(dir, 100)) {
            schema(e);
            c0 = e.changes().now();
            for (int i = 0; i < 30; i++) {
                write(e, put("r" + i, i, 10 + i));
            }
            e.checkpoint(); // these are now only in changes.jsonl
            for (int i = 30; i < 50; i++) {
                write(e, put("r" + i, i, 10 + i));
            }
            c45 = c0.replaceAll(":.*", ":" + (e.changes().last() - 5));
            tail = after(e.changes(), c45);
        }
        try (StorageEngine e = open(dir, 10)) {
            assertEquals(10, e.changes().held()[0]);
            assertThrows(ChangeFeed.Gone.class, () -> e.changes().read(c0, "d", null, 10, 0));
            assertEquals(tail, after(e.changes(), c45));
        }
    }

    @Test
    void aSnapshotWithoutAnIdStartsANewEpochOnceAndIsRewrittenWithOne() throws Exception {
        Path dir = tmp.resolve("d");
        Files.createDirectories(dir);
        // What 2.0, 2.1 or a restore leaves: a snapshot with no checkpoint id and no changes.jsonl.
        Snapshot.writeAtomically(List.of(new Op.CreateDb("d", at(1)), new Op.CreateTable("d", "t", at(2)),
                put("a", 1, 3)), dir.resolve(StorageEngine.SNAPSHOT_FILE));
        String c0;
        try (StorageEngine e = open(dir, 100)) {
            assertTrue(e.openStats().changes().contains("new epoch"), e.openStats().changes());
            assertEquals(0, e.changes().held()[0], "loading the snapshot is not a change");
            c0 = e.changes().now();
            write(e, put("b", 2, 4));
        }
        assertTrue(Files.readAllLines(dir.resolve(StorageEngine.SNAPSHOT_FILE)).get(0).contains("\"checkpoint\""));
        try (StorageEngine e = open(dir, 100)) {
            assertTrue(e.openStats().changes().contains("kept"), e.openStats().changes());
            assertEquals(1, after(e.changes(), c0).size());
            assertEquals(2L, rows(e));
        }
    }

    @Test
    void aDamagedMarksLineStartsANewEpochAndATornLastOneIsLeftOut() throws Exception {
        Path dir = tmp.resolve("d");
        String c0;
        try (StorageEngine e = open(dir, 100)) {
            schema(e);
            c0 = e.changes().now();
            write(e, put("a", 1, 10));
        }
        Path marks = dir.resolve(StorageEngine.MARKS_FILE);
        Files.writeString(marks, "{\"checkpoint\":\"x\",\"mark\":\"col", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (StorageEngine e = open(dir, 100)) {
            assertTrue(e.openStats().changes().contains("kept"), "a torn last line: " + e.openStats().changes());
            assertEquals(1, after(e.changes(), c0).size());
        }
        Files.writeString(marks, "garbage\n{\"checkpoint\":\"x\",\"mark\":\"open\"}\n");
        try (StorageEngine e = open(dir, 100)) {
            assertTrue(e.openStats().changes().contains("damaged"), e.openStats().changes());
            assertThrows(ChangeFeed.Gone.class, () -> e.changes().read(c0, "d", null, 10, 0));
        }
    }

    static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> files = Files.list(from)) {
            for (Path p : files.toList()) {
                if (!p.getFileName().toString().equals(DirLock.FILE)) {
                    Files.copy(p, to.resolve(p.getFileName()));
                }
            }
        }
    }
}
