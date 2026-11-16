package com.ataiva.serengeti.core.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.Store;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Found by the 5-node soak with seed 31337 (check 8): a majority read returned a cut-off node's update
 * although a newer delete had been acknowledged by a majority before the read began. The reader held an
 * older tombstone T of the row, a peer the newer delete D; the live bucket sums count an all-delete row as 0,
 * so the two compared equal and D was never pulled. When the cut-off node's update U (newer than T, older than
 * D, concurrent with both) arrived, the reader held {U, T} and showed U, where the merge of all three is
 * deleted. The full sums count tombstones, so D is pulled.
 */
class TombstoneBlindTest {
    static Stamp at(long t, String n) {
        return new Stamp(t, 0, n);
    }

    static Store base() {
        Store s = new Store();
        s.apply(new Op.CreateDb("d", at(1, "a")));
        s.apply(new Op.CreateTable("d", "t", at(2, "a")));
        s.apply(new Op.Put("d", "t", "h", Map.of("v", 0L), at(10, "a"), Map.of()));
        return s;
    }

    /** The reader, a peer x holding the newer delete, a peer y holding the cut-off node's update. */
    static Store[] scenario() {
        Map<String, Stamp> sawBase = Map.of("a", at(10, "a"));
        Store reader = base();
        Store x = base();
        Store y = base();
        Op t = new Op.Delete("d", "t", "h", at(20, "r"), sawBase);
        Op u = new Op.Put("d", "t", "h", Map.of("v", 1L), at(30, "y"), sawBase);
        Op d = new Op.Delete("d", "t", "h", at(40, "x"), Map.of("a", at(10, "a"), "r", at(20, "r")));
        reader.apply(t);
        x.apply(t);
        x.apply(d);
        y.apply(u);
        Store union = base();
        union.apply(t);
        union.apply(u);
        union.apply(d);
        return new Store[] {reader, x, y, union};
    }

    @Test
    void aNewerDeleteReachesANodeThatHoldsAnOlderOne() {
        Store[] s = scenario();
        Store reader = s[0];
        // The read's sync with x, then with y, by the full sums as between 2.2 nodes.
        RowSyncProperties.sync(s[1], reader, RowSyncProperties::byRows, true);
        RowSyncProperties.sync(s[2], reader, RowSyncProperties::byRows, true);
        assertNull(s[3].row("d", "t", "h"), "the merge of all three: deleted");
        assertNull(reader.row("d", "t", "h"), "what the reader shows after syncing with both");
        assertEquals(s[3].toOps(), reader.toOps());
    }

    @Test
    void withTheLiveSumsAloneTheOlderTombstoneHidesTheNewer() {
        // What a node still does with a 2.1 peer, which has only the live sums.
        Store[] s = scenario();
        Store reader = s[0];
        assertEquals(0, RowSyncProperties.sync(s[1], reader, RowSyncProperties::byRows, false), "nothing pulled from x");
        RowSyncProperties.sync(s[2], reader, RowSyncProperties::byRows, false);
        assertEquals(Map.of("v", 1L), reader.row("d", "t", "h"));
    }
}
