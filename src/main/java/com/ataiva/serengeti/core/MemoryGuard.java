package com.ataiva.serengeti.core;

import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.StorageEngine;
import com.ataiva.serengeti.core.storage.Store;
import com.ataiva.serengeti.core.storage.WriteRefusedException;
import java.util.List;

/**
 * Refuses writes that add data once the data held is estimated to take {@code memory_limit_percent} of the
 * maximum heap, so the node answers 503 instead of running out of memory and exiting. Every database lives in
 * memory, so this is the node's capacity.
 *
 * <p>The estimate ({@link Store#weight()}) is kept in step with every change from the structure of the rows,
 * not read from the garbage collector, so it needs no collection to be current: a delete frees budget at
 * once. Deletes and drops are always taken, since they are how room is made.
 */
public final class MemoryGuard implements StorageEngine.Admission {
    private final long budget;
    private final int percent;

    MemoryGuard(long budgetBytes, int percent) {
        this.budget = budgetBytes;
        this.percent = percent;
    }

    /** A budget of {@code limitPercent} of this JVM's maximum heap. */
    public static MemoryGuard ofHeap(int limitPercent) {
        if (limitPercent < 10 || limitPercent > 100) {
            throw new IllegalArgumentException("limitPercent must be 10 to 100");
        }
        return new MemoryGuard(Runtime.getRuntime().maxMemory() / 100 * limitPercent, limitPercent);
    }

    public long budget() {
        return budget;
    }

    public boolean full(Store store) {
        return store.weight() >= budget;
    }

    @Override
    public void admit(Store store, List<Op> planned) {
        long held = store.weight();
        if (held < budget) {
            return;
        }
        for (Op op : planned) {
            if (op instanceof Op.Put || op instanceof Op.CreateDb || op instanceof Op.CreateTable
                    || op instanceof Op.CreateIndex) {
                throw new WriteRefusedException(String.format("the data held takes an estimated %d MiB, the most"
                        + " this node allows (memory_limit_percent: %d%% of a %d MiB heap): writes that add data are"
                        + " refused until data is deleted or the node is given more memory",
                        held >> 20, percent, Runtime.getRuntime().maxMemory() >> 20));
            }
        }
    }
}
