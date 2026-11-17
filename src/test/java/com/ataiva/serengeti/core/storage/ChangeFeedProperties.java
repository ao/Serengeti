package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.server.Backup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.statistics.Statistics;

/**
 * Random writes, merges, drops, tombstone collections, checkpoints, restarts, crashes (with torn tails, and part
 * way through a checkpoint) and restores: every cursor the node ever gave out gets exactly the changes it got
 * after it the first time, followed by newer ones, or 410. Never other changes.
 */
class ChangeFeedProperties {
    private static final String[] IDS = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"};

    @Property(tries = 30)
    void everyCursorGetsWhatItGotTheFirstTimeOr410(@ForAll long seed) throws Exception {
        Path root = Files.createTempDirectory("feed-properties");
        try {
            Run run = new Run(new Random(seed), root);
            run.go();
            // So that the property is not met by doing nothing: most runs carry cursors over crashes and
            // checkpoints, and drop tombstones.
            Statistics.label("a cursor carried over a crash").collect(run.carriedOverCrash > 0);
            Statistics.label("a cursor carried over a checkpoint and a restart").collect(run.carriedOverCheckpoint > 0);
            Statistics.label("tombstones dropped").collect(run.dropped > 0);
            Statistics.label("a cursor carried over a crash").coverage(c -> c.check(true).percentage(p -> p >= 50));
            Statistics.label("a cursor carried over a checkpoint and a restart")
                    .coverage(c -> c.check(true).percentage(p -> p >= 50));
            Statistics.label("tombstones dropped").coverage(c -> c.check(true).percentage(p -> p >= 30));
        } finally {
            try (Stream<Path> all = Files.walk(root)) {
                for (Path p : all.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static final class Run {
        final Random r;
        final Path root;
        final int capacity;
        final long checkpointBytes;
        Path dir;
        StorageEngine e;
        int dirs;
        long wall = 1_000;
        long cutoff = 0;
        /** Per epoch, what each change number was listed as. */
        final Map<String, TreeMap<Long, String>> known = new HashMap<>();
        final List<String> cursors = new ArrayList<>();
        /** Cursors taken before a checkpoint, as seq, so a restart can count the ones that carried over it. */
        final List<String> beforeCheckpoint = new ArrayList<>();
        /** Client deletes not yet used for a stale write. */
        final List<Op.Delete> deletes = new ArrayList<>();
        int carriedOverCrash;
        int carriedOverCheckpoint;
        int dropped;

        Run(Random r, Path root) {
            this.r = r;
            this.root = root;
            this.capacity = 10 + r.nextInt(50);
            this.checkpointBytes = 1_500 + r.nextInt(6_000);
        }

        StorageEngine open() throws IOException {
            return StorageEngine.open(dir, FsyncPolicy.ALWAYS, checkpointBytes, 0, capacity);
        }

        void go() throws Exception {
            dir = root.resolve("n" + dirs++);
            e = open();
            client(new Op.CreateDb("d", stamp()), new Op.CreateTable("d", "t1", stamp()),
                    new Op.CreateTable("d", "t2", stamp()));
            for (int step = 0; step < 250; step++) {
                int k = r.nextInt(100);
                if (k < 40) {
                    List<Op> ops = new ArrayList<>();
                    for (int i = 1 + r.nextInt(3); i > 0; i--) {
                        ops.add(rowOp(stamp(), null));
                    }
                    client(ops.toArray(new Op[0]));
                } else if (k < 55) {
                    List<Op> ops = new ArrayList<>();
                    for (int i = 1 + r.nextInt(3); i > 0; i--) {
                        // A peer's write, often older than this node's and concurrent with it.
                        Stamp s = new Stamp(Math.max(1, wall - r.nextInt(300)), r.nextInt(3), "p" + r.nextInt(2));
                        ops.add(rowOp(s, r.nextBoolean() ? Map.of() : null));
                    }
                    e.merge(ops);
                } else if (k < 58) {
                    client(r.nextBoolean() ? new Op.DropTable("d", "t2", stamp()) : new Op.CreateTable("d", "t2", stamp()));
                } else if (k < 61 && !deletes.isEmpty()) {
                    // A delete's tombstone dropped, then a stale peer's older write to that row: it comes back,
                    // and only if the replay drops the tombstone at the same place does it come back again.
                    Op.Delete del = deletes.remove(r.nextInt(deletes.size()));
                    cutoff = Math.max(cutoff, del.stamp().wall() + 1);
                    dropped += e.expireTombstones(cutoff, true);
                    Stamp old = new Stamp(Math.max(1, del.stamp().wall() - 1 - r.nextInt(20)), 0, "p1");
                    e.merge(List.of(new Op.Put("d", del.table(), del.id(), Map.of("v", -1L), old, Map.of())));
                } else if (k < 64) {
                    cutoff = Math.max(cutoff, wall - r.nextInt(150));
                    dropped += e.expireTombstones(cutoff, true);
                } else if (k < 74) {
                    cursors.add(e.changes().now());
                } else if (k < 78) {
                    beforeCheckpoint.addAll(cursors);
                    e.checkpoint();
                } else if (k < 83) {
                    restart();
                } else if (k < 88) {
                    crash();
                } else if (k < 90) {
                    crashInCheckpoint();
                } else if (k < 91) {
                    restore();
                } else {
                    verify(cursors.isEmpty() ? e.changes().now() : cursors.get(r.nextInt(cursors.size())));
                }
            }
            // At least one cursor must carry over, or the property would hold by answering 410 to everything.
            String last = e.changes().now();
            restart();
            assertTrue(verify(last), "a cursor taken just before a restart continues");
            e.close();
        }

        Stamp stamp() {
            wall += 1 + r.nextInt(3);
            return new Stamp(wall, 0, "n1");
        }

        Op rowOp(Stamp s, Map<String, Stamp> seen) {
            String table = r.nextInt(4) == 0 ? "t2" : "t1";
            String id = IDS[r.nextInt(IDS.length)];
            if (r.nextInt(3) == 0) {
                return new Op.Delete("d", table, id, s, seen);
            }
            Map<String, Object> row = new HashMap<>();
            row.put("v", (long) r.nextInt(1000));
            if (r.nextBoolean()) {
                row.put("s", "x".repeat(r.nextInt(40)));
            }
            return new Op.Put("d", table, id, row, s, seen);
        }

        void client(Op... ops) throws IOException {
            e.update(s -> new StorageEngine.Planned<>(List.of(ops), null));
            for (Op op : ops) {
                if (op instanceof Op.Delete d) {
                    deletes.add(d);
                }
            }
        }

        void restart() throws Exception {
            String before = epoch(e.changes().now());
            e.close();
            e = open();
            assertEquals(before, epoch(e.changes().now()), "a restart keeps the epoch: " + e.openStats().changes());
            verifyAll();
            for (String c : beforeCheckpoint) {
                if (verify(c)) {
                    carriedOverCheckpoint++;
                }
            }
        }

        void crash() throws Exception {
            String before = epoch(e.changes().now());
            List<Op> stateBefore = e.read(s -> s.toOps("d"));
            e.abandonForTest();
            Path wal = dir.resolve(StorageEngine.WAL_FILE);
            int kind = r.nextInt(3);
            if (kind == 1) {
                byte[] rec = Wal.encode(List.of(rowOp(stamp(), null)));
                Files.write(wal, Arrays.copyOf(rec, 1 + r.nextInt(rec.length - 1)), StandardOpenOption.APPEND);
            } else if (kind == 2) {
                byte[] junk = new byte[1 + r.nextInt(30)];
                Files.write(wal, junk, StandardOpenOption.APPEND); // zeros, as a file system can leave them
            }
            e = open();
            // The replay, tombstones dropped where they were, rebuilds exactly what the node held.
            assertEquals(new HashSet<>(stateBefore), new HashSet<>(e.read(s -> s.toOps("d"))));
            assertEquals(before, epoch(e.changes().now()), "a crash keeps the epoch: " + e.openStats().changes());
            carriedOverCrash += verifyAll();
        }

        void crashInCheckpoint() throws Exception {
            String before = epoch(e.changes().now());
            boolean logEmpty = e.walBytes() == 0;
            int step = 1 + r.nextInt(3);
            e.stopCheckpointAtForTest = step;
            assertThrows(IOException.class, e::checkpoint);
            e.abandonForTest();
            e = open();
            if (step != 2 || logEmpty) {
                assertEquals(before, epoch(e.changes().now()), "step " + step + ": " + e.openStats().changes());
            }
            verifyAll();
        }

        void restore() throws Exception {
            String before = epoch(e.changes().now());
            Path backup = root.resolve("backup" + dirs + ".jsonl");
            e.writeBackup(backup);
            e.close();
            dir = root.resolve("n" + dirs++);
            Backup.restore(backup, dir);
            e = open();
            assertNotEquals(before, epoch(e.changes().now()), "a restore starts a new epoch");
            verifyAll();
        }

        /** Checks every cursor given out; returns how many could be continued. */
        int verifyAll() throws Exception {
            int n = 0;
            for (String c : cursors) {
                if (verify(c)) {
                    n++;
                }
            }
            return n;
        }

        /** Checks one cursor; true if it could be continued. */
        boolean verify(String cursor) throws Exception {
            String epoch = epoch(cursor);
            long seq = Long.parseLong(cursor.substring(cursor.indexOf(':') + 1));
            List<ChangeFeed.Change> got = new ArrayList<>();
            String c = cursor;
            try {
                while (true) {
                    ChangeFeed.Page p = e.changes().read(c, "d", null, 1000, 0);
                    if (p.changes().isEmpty()) {
                        break;
                    }
                    got.addAll(p.changes());
                    c = p.cursor();
                }
            } catch (ChangeFeed.Gone g) {
                return false;
            }
            TreeMap<Long, String> k = known.computeIfAbsent(epoch, x -> new TreeMap<>());
            long expect = seq + 1;
            for (ChangeFeed.Change ch : got) {
                assertEquals(expect++, ch.seq(), "changes follow each other");
                String now = ChangeFeedRestartTest.render(ch);
                String was = k.putIfAbsent(ch.seq(), now);
                if (was != null) {
                    assertEquals(was, now, "change " + ch.seq() + " of epoch " + epoch + " after " + cursor);
                }
            }
            Long newest = k.isEmpty() ? null : k.lastKey();
            assertTrue(newest == null || newest < expect, "a change listed before, " + newest + ", is missing after "
                    + cursor + " (got up to " + (expect - 1) + ")");
            return true;
        }

        static String epoch(String cursor) {
            return cursor.substring(0, cursor.indexOf(':'));
        }
    }
}
