package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalTest {
    @TempDir
    Path dir;

    static List<Op> batch(int i) {
        return List.of(new Op.Put("d", "t", "id" + i, Map.of("i", (Object) (long) i, "s", "x\né\"" + i),
                new Stamp(i + 1, 0, "n1")));
    }

    private Path writeBatches(int n) throws IOException {
        Path p = dir.resolve("wal.log");
        try (Wal w = Wal.open(p, FsyncPolicy.ALWAYS, 0, new ArrayList<>())) {
            for (int i = 0; i < n; i++) {
                w.append(batch(i));
            }
        }
        return p;
    }

    private static List<List<Op>> reopen(Path p) throws IOException {
        List<List<Op>> got = new ArrayList<>();
        Wal.open(p, FsyncPolicy.ALWAYS, 0, got).close();
        return got;
    }

    @Test
    void reopenReturnsEveryBatchInOrder() throws IOException {
        Path p = writeBatches(20);
        List<List<Op>> got = reopen(p);
        assertEquals(20, got.size());
        for (int i = 0; i < 20; i++) {
            assertEquals(batch(i), got.get(i));
        }
    }

    @Test
    void appendAfterReopenContinuesTheLog() throws IOException {
        Path p = writeBatches(3);
        try (Wal w = Wal.open(p, FsyncPolicy.ALWAYS, 0, new ArrayList<>())) {
            w.append(batch(3));
        }
        assertEquals(4, reopen(p).size());
    }

    /** Cut the file at every byte offset: the log keeps exactly the records that are whole. */
    @Test
    void truncationAtAnyOffsetKeepsTheCompleteRecords() throws IOException {
        Path p = writeBatches(4);
        byte[] full = Files.readAllBytes(p);
        List<Integer> ends = new ArrayList<>();
        int pos = 0;
        for (int i = 0; i < 4; i++) {
            pos += Wal.encode(batch(i)).length;
            ends.add(pos);
        }
        for (int cut = 0; cut <= full.length; cut++) {
            Path q = dir.resolve("cut.log");
            Files.write(q, java.util.Arrays.copyOf(full, cut));
            int whole = 0;
            while (whole < ends.size() && ends.get(whole) <= cut) {
                whole++;
            }
            List<List<Op>> got = reopen(q);
            assertEquals(whole, got.size(), "cut at " + cut);
            long expectedSize = whole == 0 ? 0 : ends.get(whole - 1);
            assertEquals(expectedSize, Files.size(q), "file truncated to the last whole record, cut " + cut);
        }
    }

    @Test
    void corruptRecordEndsTheLog() throws IOException {
        Path p = writeBatches(3);
        int first = Wal.encode(batch(0)).length;
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.seek(first + Wal.HEADER + 3);
            int b = f.read();
            f.seek(first + Wal.HEADER + 3);
            f.write(b ^ 0x40);
        }
        assertEquals(1, reopen(p).size());
        assertEquals(first, Files.size(p));
    }

    @Test
    void absurdLengthIsTreatedAsTornTail() throws IOException {
        Path p = writeBatches(1);
        long good = Files.size(p);
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.seek(good);
            f.writeInt(Integer.MAX_VALUE);
            f.writeInt(0);
        }
        assertEquals(1, reopen(p).size());
        assertEquals(good, Files.size(p));
    }

    @Test
    void resetEmptiesTheLog() throws IOException {
        Path p = dir.resolve("wal.log");
        try (Wal w = Wal.open(p, FsyncPolicy.OFF, 0, new ArrayList<>())) {
            w.append(batch(0));
            w.reset();
            assertEquals(0, w.size());
            w.append(batch(1));
        }
        assertEquals(List.of(batch(1)), reopen(p));
    }
}
