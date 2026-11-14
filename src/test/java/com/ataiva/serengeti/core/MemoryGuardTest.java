package com.ataiva.serengeti.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
import com.ataiva.serengeti.core.storage.WriteRefusedException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryGuardTest {
    @TempDir
    Path dir;

    private static Stamp at(long t) {
        return new Stamp(t, 0, "n1");
    }

    private static List<Op> rows(int from, int to, long t) {
        List<Op> out = new ArrayList<>();
        for (int i = from; i < to; i++) {
            out.add(new Op.Put("d", "t", "r" + (1000 + i), Map.of("p", "x".repeat(1000)), at(t)));
        }
        return out;
    }

    @Test
    void writesThatAddDataAreRefusedAtTheBudgetAndTakenAgainAsSoonAsADeleteMakesRoom() throws Exception {
        try (StorageEngine e = StorageEngine.open(dir, FsyncPolicy.ALWAYS)) {
            e.apply(List.of(new Op.CreateDb("d", at(1)), new Op.CreateTable("d", "t", at(2))));
            long before = e.read(s -> s.weight());
            e.update(s -> new StorageEngine.Planned<>(rows(0, 1, 3), null)); // as a client write: with a context
            long perRow = e.read(s -> s.weight()) - before;
            assertTrue(perRow > 1000 && perRow < 1500, "a row of 1,000 characters is estimated at " + perRow);
            MemoryGuard g = new MemoryGuard(before + 100 * perRow, 50);
            e.setAdmission(g);
            int taken = 1;
            while (true) {
                int i = taken;
                try {
                    e.update(s -> new StorageEngine.Planned<>(rows(i, i + 1, 10 + i), null));
                    taken++;
                } catch (WriteRefusedException refused) {
                    assertTrue(refused.getMessage().contains("memory_limit_percent: 50%"), refused.getMessage());
                    break;
                }
                assertTrue(taken < 200, "never refused");
            }
            assertEquals(100, taken, "refused once the budget of 100 rows is reached");
            assertTrue(g.full(e.read(s -> s)));
            long logged = e.walBytes();
            assertThrows(WriteRefusedException.class,
                    () -> e.update(s -> new StorageEngine.Planned<>(List.of(new Op.CreateTable("d", "u", at(500))), null)));
            assertEquals(logged, e.walBytes(), "a refused write logs nothing");
            // A delete is taken while full, and makes room at once: no garbage collection is involved.
            e.update(s -> new StorageEngine.Planned<>(List.of(new Op.Delete("d", "t", "r1005", at(600))), null));
            assertFalse(g.full(e.read(s -> s)));
            e.update(s -> new StorageEngine.Planned<>(rows(1000, 1001, 700), null));
            assertTrue(g.full(e.read(s -> s)));
        }
    }

    @Test
    void theBudgetIsAShareOfTheHeap() {
        MemoryGuard g = MemoryGuard.ofHeap(60);
        long max = Runtime.getRuntime().maxMemory();
        assertTrue(Math.abs(g.budget() - max * 0.6) < max * 0.01);
        assertThrows(IllegalArgumentException.class, () -> MemoryGuard.ofHeap(5));
    }
}
