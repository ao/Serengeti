package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Review 22e, finding 4: moving the expiry recounted the full sums of every row (a SHA-256 per version), and
 * StorageEngine.expireTombstones runs it under the write lock every 10 minutes: about 0.2 s per million rows,
 * measured by the review on c7g.4xlarge. Only the tombstones the expiry passes are visited now.
 */
class ExpiryCostTest {
    @Test
    void movingTheExpiryOnAMillionRowsIsQuick() {
        int rows = Integer.getInteger("serengeti.expiry.rows", 1_000_000);
        Store s = new Store();
        s.apply(new Op.CreateDb("d", new Stamp(1, 0, "n1")));
        s.apply(new Op.CreateTable("d", "t", new Stamp(2, 0, "n1")));
        for (int i = 0; i < rows; i++) {
            s.apply(new Op.Put("d", "t", "row-" + i, Map.of("n", (long) i), new Stamp(10 + i, 0, "n1"), Map.of()));
        }
        // And 1,000 tombstones, half of which the expiry passes.
        for (int i = 0; i < 1_000; i++) {
            s.apply(new Op.Put("d", "t", "gone-" + i, Map.of(), new Stamp(1_000 + i, 0, "n2"), Map.of()));
            s.apply(new Op.Delete("d", "t", "gone-" + i, new Stamp(1_000 + i, 1, "n2"), Map.of("n2", new Stamp(1_000 + i, 0, "n2"))));
        }
        long[] ms = new long[5];
        for (int k = 0; k < ms.length; k++) {
            long t0 = System.nanoTime();
            s.setExpiry(1_000 + 100 * k); // a new value each time, as at each boundary
            ms[k] = (System.nanoTime() - t0) / 1_000_000;
        }
        long best = Long.MAX_VALUE;
        for (long m : ms) {
            best = Math.min(best, m);
        }
        long t0 = System.nanoTime();
        int n = s.collectTombstones(1_500);
        long collectMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("ExpiryCostTest: setExpiry on " + rows + " rows: " + java.util.Arrays.toString(ms)
                + " ms; collectTombstones: " + collectMs + " ms (" + n + " dropped)");
        assertEquals(500, n);
        assertTrue(best < 100, "setExpiry took at best " + best + " ms on " + rows + " rows, under the write lock");
        assertTrue(collectMs < 100, "collectTombstones took " + collectMs + " ms");
    }
}
