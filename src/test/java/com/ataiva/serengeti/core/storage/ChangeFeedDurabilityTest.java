package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.json.Json;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The change feed across crashes that leave the disk behind the process: what a start may list, and when it must
 * begin a new epoch instead (review 22f). A power cut is simulated by cutting the log back to what was forced.
 */
class ChangeFeedDurabilityTest {
    @TempDir
    Path tmp;

    private static final long NO_CHECKPOINT = 64L << 20;

    private static Stamp at(long t) {
        return new Stamp(t, 0, "n1");
    }

    private static StorageEngine open(Path dir) throws IOException {
        return StorageEngine.open(dir, FsyncPolicy.ALWAYS, NO_CHECKPOINT, 0, 100);
    }

    private static void write(StorageEngine e, Op... ops) throws IOException {
        e.update(s -> new StorageEngine.Planned<>(List.of(ops), null));
    }

    private static void schema(StorageEngine e) throws IOException {
        write(e, new Op.CreateDb("d", at(1)), new Op.CreateTable("d", "t", at(2)));
    }

    private static Op put(String id, long v, long t) {
        return new Op.Put("d", "t", id, Map.of("v", v), at(t));
    }

    private static List<String> after(ChangeFeed f, String cursor) throws Exception {
        return ChangeFeedRestartTest.after(f, cursor);
    }

    /**
     * The start line's torn bytes are what the log's open cut off, not the log a new epoch's checkpoint folds
     * into the snapshot (docs3: a clean stop with changes.jsonl deleted reported 587 torn bytes).
     */
    @Test
    void aNewEpochsCheckpointIsNotCountedAsTornBytes() throws Exception {
        Path dir = tmp.resolve("d");
        try (StorageEngine e = open(dir)) {
            schema(e);
            write(e, put("a", 1, 10));
            write(e, put("b", 2, 11));
        }
        Files.deleteIfExists(dir.resolve(StorageEngine.CHANGES_FILE));
        try (StorageEngine e = open(dir)) {
            assertTrue(e.openStats().changes().contains("new epoch"), e.openStats().changes());
            assertEquals(0, e.openStats().walDiscardedBytes());
            assertTrue(e.openStats().walBatches() > 0);
        }
        // A real tear: the bytes cut off, as the log counted them.
        try (StorageEngine e = open(dir)) {
            write(e, put("c", 3, 12));
        }
        Path wal = dir.resolve(StorageEngine.WAL_FILE);
        long whole = Files.size(wal);
        Files.write(wal, new byte[] {0, 0, 0, 40, 1, 2, 3}, StandardOpenOption.APPEND);
        try (StorageEngine e = open(dir)) {
            assertEquals(7, e.openStats().walDiscardedBytes());
            assertEquals(whole, Files.size(wal), "cut back to the last whole record");
        }
    }

    /**
     * A collect line torn by a crash is left out at the next start (that run dropped nothing). The next collect
     * must not be appended to the torn bytes, on the same line, or it would be left out too although its
     * tombstones were dropped, and the replay would list other changes.
     */
    @Test
    void aTornMarksLineDoesNotSwallowTheNextCollect() throws Exception {
        Path dir = tmp.resolve("d");
        try (StorageEngine e = open(dir)) {
            schema(e);
            write(e, put("x", 1, 100));
            write(e, new Op.Delete("d", "t", "x", at(200)));
        }
        // What a power cut during a collect line's append leaves: part of the line, without its newline. That run
        // dropped nothing (the drop comes after the force).
        Map<String, Object> header = Json.parseObject(Files.readAllLines(dir.resolve(StorageEngine.SNAPSHOT_FILE)).get(0));
        Path marks = dir.resolve(StorageEngine.MARKS_FILE);
        Files.writeString(marks, "{\"checkpoint\":\"" + header.get("checkpoint") + "\",\"mark\":\"coll",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        String c0;
        List<String> before;
        try (StorageEngine e = open(dir)) {
            assertTrue(e.openStats().changes().contains("kept"), e.openStats().changes());
            c0 = e.changes().now();
            assertEquals(1, e.expireTombstones(300, true));
            // A stale peer's older write, which the dropped tombstone would have hidden, brings the row back.
            e.merge(List.of(new Op.Put("d", "t", "x", Map.of("v", 0L), new Stamp(150, 0, "p2"), Map.of())));
            write(e, put("y", 5, 400));
            before = after(e.changes(), c0);
            assertEquals(2, before.size(), before.toString());
        }
        String file = Files.readString(marks);
        assertTrue(file.endsWith("\n") && !file.contains("coll{"), file);
        try (StorageEngine e = open(dir)) {
            String note = e.openStats().changes();
            assertTrue(note.contains("kept"), note);
            assertEquals(before, after(e.changes(), c0), "start: " + note + "; marks file: " + file);
        }
    }

    /**
     * With fsync off a change is listed before it is on disk, so only a clean stop keeps the epoch, whatever the
     * next run has: a run with fsync always after a crash under fsync off must not number new changes with the
     * numbers of lost ones.
     */
    @Test
    void aCrashUnderFsyncOffStartsANewEpochWhateverTheNextRunHas() throws Exception {
        Path dir = tmp.resolve("d");
        Path wal = dir.resolve(StorageEngine.WAL_FILE);
        String c0;
        List<String> listed;
        long beforeB;
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.OFF, NO_CHECKPOINT, 0, 100)) {
            schema(e);
            write(e, put("a", 1, 10));
        }
        StorageEngine e = StorageEngine.open(dir, FsyncPolicy.OFF, NO_CHECKPOINT, 0, 100);
        c0 = e.changes().now();
        beforeB = Files.size(wal);
        write(e, put("b", 2, 11));
        listed = after(e.changes(), c0);
        assertEquals(1, listed.size(), "fsync off lists b at once: " + listed);
        e.abandonForTest();
        // The machine crashes: with fsync off, b's record had not reached the disk.
        try (FileChannel ch = FileChannel.open(wal, StandardOpenOption.WRITE)) {
            ch.truncate(beforeB);
        }
        try (StorageEngine again = open(dir)) {
            String note = again.openStats().changes();
            assertTrue(note.contains("new epoch") && note.contains("fsync off"), note);
            write(again, put("c", 3, 12));
            assertThrows(ChangeFeed.Gone.class, () -> again.changes().read(c0, "d", null, 10, 0));
        }
        // A clean stop under fsync off, then fsync always: the epoch is kept.
        String c1;
        try (StorageEngine off = StorageEngine.open(dir, FsyncPolicy.OFF, NO_CHECKPOINT, 0, 100)) {
            c1 = off.changes().now();
            write(off, put("d", 4, 13));
        }
        try (StorageEngine again = open(dir)) {
            assertTrue(again.openStats().changes().contains("kept"), again.openStats().changes());
            assertEquals(1, after(again.changes(), c1).size());
        }
    }

    /**
     * A log that lost a record it had whole starts a new epoch, also when the records right after the bad one
     * are bad too (one bad sector under both, say): a whole record further on shows it was not a torn tail.
     */
    @Test
    void twoDamagedRecordsInARowStartANewEpoch() throws Exception {
        Path dir = tmp.resolve("d");
        Path wal = dir.resolve(StorageEngine.WAL_FILE);
        String c0;
        List<String> before;
        try (StorageEngine e = open(dir)) {
            schema(e);
            c0 = e.changes().now();
            for (int i = 0; i < 6; i++) {
                write(e, put("r" + i, i, 10 + i));
            }
            before = after(e.changes(), c0);
        }
        byte[] b = Files.readAllBytes(wal);
        List<Integer> starts = new java.util.ArrayList<>();
        for (int pos = 0; pos + Wal.HEADER <= b.length; ) {
            starts.add(pos);
            pos += Wal.HEADER + java.nio.ByteBuffer.wrap(b, pos, 4).getInt();
        }
        for (int k : List.of(starts.size() - 4, starts.size() - 3)) {
            b[starts.get(k) + Wal.HEADER + 3] ^= 0x20;
        }
        Files.write(wal, b);
        try (StorageEngine e = open(dir)) {
            String note = e.openStats().changes();
            assertTrue(note.contains("new epoch") && note.contains("lost a record"), note);
            write(e, put("z", 9, 30));
            assertThrows(ChangeFeed.Gone.class, () -> e.changes().read(c0, "d", null, 10, 0));
        }
    }

    /**
     * As below, after a kill -9 under fsync interval within the second between a write and the timer's force:
     * the killed run never listed b, and the next start lists it only once it has forced the log.
     */
    @Test
    void withFsyncIntervalAStartForcesWhatTheKilledRunHadNotForced() throws Exception {
        Path dir = tmp.resolve("d");
        Path wal = dir.resolve(StorageEngine.WAL_FILE);
        try (StorageEngine e = open(dir)) {
            schema(e);
            write(e, put("a", 1, 10));
        }
        long onDisk = Files.size(wal);
        StorageEngine e = StorageEngine.open(dir, FsyncPolicy.INTERVAL, NO_CHECKPOINT, 0, 100);
        String c0 = e.changes().now();
        write(e, put("b", 2, 11));
        boolean timerRan = e.walStats().fsyncs() > 0;
        List<String> killedRunListed = after(e.changes(), c0);
        e.abandonForTest(); // kill -9
        if (timerRan) {
            return; // the box was too slow to hit the window; nothing to check
        }
        assertEquals(List.of(), killedRunListed, "the killed run had not listed b");

        StorageEngine e2 = StorageEngine.open(dir, FsyncPolicy.INTERVAL, NO_CHECKPOINT, 0, 100);
        List<String> listed = after(e2.changes(), c0);
        boolean forced = e2.walStats().fsyncs() > 0;
        e2.abandonForTest();
        assertTrue(forced || listed.isEmpty(), "listed unforced: " + listed);
        if (!forced) {
            // A power cut within the OS's writeback delay (30 s by default on Linux).
            try (FileChannel ch = FileChannel.open(wal, StandardOpenOption.WRITE)) {
                ch.truncate(onDisk);
            }
        }
        try (StorageEngine e3 = StorageEngine.open(dir, FsyncPolicy.INTERVAL, NO_CHECKPOINT, 0, 100)) {
            String note = e3.openStats().changes();
            write(e3, put("c", 3, 12));
            if (note.contains("new epoch")) {
                assertThrows(ChangeFeed.Gone.class, () -> e3.changes().read(c0, "d", null, 10, 0));
                return;
            }
            List<String> now = List.of();
            long deadline = System.currentTimeMillis() + 10_000;
            while (now.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
                now = after(e3.changes(), c0);
            }
            assertEquals(listed, now.subList(0, Math.min(1, now.size())), "start: " + note);
        }
    }

    /**
     * A record whose write reached the file and whose force did not (its fsync failed here; a kill -9 between
     * write and force leaves the same) may not be on disk. A start forces the log before it lists it, so a
     * power cut after the start cannot take back a change a cursor got.
     */
    @Test
    void aStartForcesReplayedRecordsBeforeListingThem() throws Exception {
        Path dir = tmp.resolve("d");
        Path wal = dir.resolve(StorageEngine.WAL_FILE);
        String c0;
        long onDisk;
        StorageEngine e = open(dir);
        schema(e);
        write(e, put("a", 1, 10));
        c0 = e.changes().now();
        onDisk = Files.size(wal); // every byte so far was forced (fsync always)
        e.failNextFsyncForTest(new IOException("injected EIO"));
        assertThrows(IOException.class, () -> write(e, put("b", 2, 11)));
        assertTrue(e.failed());
        assertEquals(List.of(), after(e.changes(), c0), "b is not listed: its force failed");
        e.abandonForTest();
        assertTrue(Files.size(wal) > onDisk, "b's record is in the file, not known to be on disk");

        List<String> listed;
        StorageEngine e2 = open(dir);
        listed = after(e2.changes(), c0);
        assertEquals(1, listed.size(), "the start lists b: " + listed);
        boolean forced = e2.walStats().fsyncs() > 0;
        assertTrue(forced, "the start forced the log");
        e2.abandonForTest();

        // A power cut now: the disk holds what was forced (if the start forced the log, b too).
        if (!forced) {
            try (FileChannel ch = FileChannel.open(wal, StandardOpenOption.WRITE)) {
                ch.truncate(onDisk);
            }
        }
        try (StorageEngine e3 = open(dir)) {
            String note = e3.openStats().changes();
            write(e3, put("c", 3, 12));
            assertTrue(note.contains("kept"), note);
            assertEquals(listed, after(e3.changes(), c0).subList(0, 1), "start: " + note);
        }
    }
}
