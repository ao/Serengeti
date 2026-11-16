package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangeFeedTest {
    @TempDir
    Path dir;

    private static Stamp at(long t, String node) {
        return new Stamp(t, 0, node);
    }

    private static List<String> ids(ChangeFeed.Page p) {
        return p.changes().stream().map(c -> c.kind() + ":" + c.table() + ":" + c.id() + ":" + (c.row() == null ? "-" : c.row().get("v")))
                .toList();
    }

    private static Store store(ChangeFeed feed) {
        Store s = new Store();
        s.apply(new Op.CreateDb("d", at(1, "n1")));
        s.apply(new Op.CreateTable("d", "t", at(2, "n1")));
        s.apply(new Op.CreateTable("d", "u", at(3, "n1")));
        s.setFeed(feed);
        return s;
    }

    @Test
    void writesUpdatesAndDeletesAreReportedInOrderWithACursorToContinue() throws Exception {
        ChangeFeed f = new ChangeFeed(100);
        Store s = store(f);
        String start = f.now();
        s.apply(new Op.Put("d", "t", "a", Map.of("v", 1L), at(10, "n1")));
        s.apply(new Op.Put("d", "u", "x", Map.of("v", 9L), at(11, "n1")));
        s.apply(new Op.Put("d", "t", "a", Map.of("v", 2L), at(12, "n2")));
        s.apply(new Op.Delete("d", "t", "a", at(13, "n1")));
        ChangeFeed.Page all = f.read(start, "d", null, 100, 0);
        assertEquals(List.of("row:t:a:1", "row:u:x:9", "row:t:a:2", "row:t:a:-"), ids(all));
        ChangeFeed.Page onlyT = f.read(start, "d", "t", 2, 0);
        assertEquals(List.of("row:t:a:1", "row:t:a:2"), ids(onlyT), "filtered, and the limit counts matches");
        assertEquals(List.of("row:t:a:-"), ids(f.read(onlyT.cursor(), "d", "t", 100, 0)));
        assertEquals(List.of(), ids(f.read(all.cursor(), "d", null, 100, 0)), "nothing after the end");
        assertEquals(List.of(), ids(f.read(start, "other", null, 100, 0)));
        assertEquals(f.now(), f.read(start, "other", null, 100, 0).cursor(), "a filtered read moves past what it skipped");
    }

    @Test
    void aVersionThatDoesNotChangeTheValueIsNoChangeAndAnOldOneIsIgnored() throws Exception {
        ChangeFeed f = new ChangeFeed(100);
        Store s = store(f);
        String start = f.now();
        s.apply(new Op.Put("d", "t", "a", Map.of("v", 2L), at(20, "n2"), Map.of()));
        // Concurrent but older: kept as a conflict, the value stays v=2.
        s.apply(new Op.Put("d", "t", "a", Map.of("v", 1L), at(19, "n1"), Map.of()));
        // The same write again, and an older 2.0-style write it covers.
        s.apply(new Op.Put("d", "t", "a", Map.of("v", 2L), at(20, "n2"), Map.of()));
        s.apply(new Op.Put("d", "t", "a", Map.of("v", 0L), at(5, "n3")));
        assertEquals(List.of("row:t:a:2"), ids(f.read(start, "d", null, 100, 0)));
        assertEquals(1, s.conflicts());
    }

    @Test
    void aDroppedTableIsOneChangeNotOnePerRowAndRowsInMissingTablesAreNone() throws Exception {
        ChangeFeed f = new ChangeFeed(100);
        Store s = store(f);
        for (int i = 0; i < 50; i++) {
            s.apply(new Op.Put("d", "t", "r" + i, Map.of("v", (long) i), at(30 + i, "n1")));
        }
        String before = f.now();
        s.apply(new Op.DropTable("d", "t", at(100, "n1")));
        s.apply(new Op.DropTable("d", "t", at(99, "n1"))); // an older drop changes nothing
        s.apply(new Op.Put("d", "nope", "z", Map.of("v", 1L), at(101, "n1")));
        s.apply(new Op.DropDb("d", at(102, "n1")));
        assertEquals(List.of("drop_table:t:null:-", "drop_db:null:null:-"), ids(f.read(before, "d", "t", 100, 0)));
    }

    @Test
    void aTableOrDatabaseCreatedAgainIsAChangeSoFollowersReadItAgain() throws Exception {
        ChangeFeed f = new ChangeFeed(100);
        Store s = store(f);
        String start = f.now();
        s.apply(new Op.DropTable("d", "t", at(10, "n1")));
        // From n2, cut off, written after the drop it had not seen: visible once the table is made again.
        s.apply(new Op.Put("d", "t", "late", Map.of("v", 1L), at(11, "n2")));
        s.apply(new Op.CreateTable("d", "t", at(20, "n1")));
        s.apply(new Op.CreateTable("d", "t", at(21, "n1"))); // already there: no change
        s.apply(new Op.DropDb("d", at(30, "n1")));
        s.apply(new Op.CreateDb("d", at(40, "n1")));
        assertEquals(List.of("drop_table:t:null:-", "create_table:t:null:-", "drop_db:null:null:-", "create_db:null:null:-"),
                ids(f.read(start, "d", null, 100, 0)));
    }

    @Test
    void largeRowsAreHeldUpToTheByteBoundNotTheCount() throws Exception {
        String big = "x".repeat(10_000);
        long one = 64 + Weight.of("d") + Weight.of("t") + Weight.of("r0") + Weight.ofMap(Map.of("v", big));
        ChangeFeed f = new ChangeFeed(1000, one * 3 + one / 2);
        Store s = store(f);
        String start = f.now();
        for (int i = 0; i < 10; i++) {
            // The same row, rewritten: the store holds one value, the feed would hold ten.
            s.apply(new Op.Put("d", "t", "r0", Map.of("v", big + i), at(40 + i, "n1")));
        }
        assertEquals(3, f.held()[0], "three fit under the bound");
        assertTrue(f.held()[1] <= one * 3 + one / 2, "weight " + f.held()[1]);
        assertThrows(ChangeFeed.Gone.class, () -> f.read(start, "d", null, 100, 0), "the first seven are gone");
        List<ChangeFeed.Change> kept = f.read(start.replaceAll(":.*", ":7"), "d", null, 100, 0).changes();
        assertEquals(List.of(8L, 9L, 10L), kept.stream().map(ChangeFeed.Change::seq).toList());
        // A change larger than the bound on its own is still kept, alone.
        ChangeFeed tiny = new ChangeFeed(1000, 10);
        Store t = store(tiny);
        t.apply(new Op.Put("d", "t", "a", Map.of("v", big), at(60, "n1")));
        t.apply(new Op.Put("d", "t", "b", Map.of("v", big), at(61, "n1")));
        assertEquals(1, tiny.held()[0]);
    }

    @Test
    void cursorsThatCannotBeContinuedAreRefusedWithAReason() throws Exception {
        ChangeFeed f = new ChangeFeed(3);
        Store s = store(f);
        String start = f.now();
        for (int i = 0; i < 5; i++) {
            s.apply(new Op.Put("d", "t", "r" + i, Map.of("v", (long) i), at(40 + i, "n1")));
        }
        ChangeFeed.Gone gone = assertThrows(ChangeFeed.Gone.class, () -> f.read(start, "d", null, 10, 0));
        assertTrue(gone.getMessage().contains("last 3"), gone.getMessage());
        assertEquals(3, f.read(start.replaceAll(":.*", ":2"), "d", null, 10, 0).changes().size(), "the last 3 are held");
        assertThrows(ChangeFeed.Gone.class, () -> f.read("0123456789ab:1", "d", null, 10, 0), "another epoch");
        assertThrows(ChangeFeed.Gone.class, () -> f.read(start.replaceAll(":.*", ":99"), "d", null, 10, 0), "ahead");
        for (String bad : new String[] {"", "nocolon", start.replaceAll(":.*", ":x"), start.replaceAll(":.*", ":-1")}) {
            assertThrows(IllegalArgumentException.class, () -> f.read(bad, "d", null, 10, 0), bad);
        }
    }

    @Test
    void aReaderWaitsForTheNextChangeAndNoLonger() throws Exception {
        ChangeFeed f = new ChangeFeed(100);
        Store s = store(f);
        String start = f.now();
        long t0 = System.nanoTime();
        assertEquals(List.of(), ids(f.read(start, "d", null, 10, 300)));
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 280, "waited about 300 ms");
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (s) {
                s.apply(new Op.Put("d", "u", "skip", Map.of("v", 0L), at(60, "n1")));
                s.apply(new Op.Put("d", "t", "a", Map.of("v", 1L), at(61, "n1")));
            }
        });
        writer.start();
        t0 = System.nanoTime();
        ChangeFeed.Page p = f.read(start, "d", "t", 10, 5_000);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        writer.join();
        assertEquals(List.of("row:t:a:1"), ids(p), "a non-matching change does not end the wait");
        assertTrue(ms < 2_000, "returned when the change came, after " + ms + " ms");
    }

    @Test
    void theEngineReportsOnlyChangesMadeAfterTheFeedStarts() throws Exception {
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            e.apply(List.of(new Op.CreateDb("d", at(1, "n1")), new Op.CreateTable("d", "t", at(2, "n1")),
                    new Op.Put("d", "t", "old", Map.of("v", 1L), at(3, "n1"))));
        }
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            e.enableChanges(100);
            String start = e.changes().now();
            assertEquals(List.of(), ids(e.changes().read(start, "d", null, 10, 0)), "loading the log is not a change");
            e.update(s -> new StorageEngine.Planned<>(List.of(new Op.Put("d", "t", "new", Map.of("v", 2L), at(9, "n1"))), null));
            e.merge(List.of(new Op.Put("d", "t", "peer", Map.of("v", 3L), at(10, "n2"))));
            assertEquals(List.of("row:t:new:2", "row:t:peer:3"), ids(e.changes().read(start, "d", null, 10, 0)),
                    "client writes and merges from peers alike");
        }
    }
}
