package com.ataiva.serengeti.core.storage;

import java.util.function.LongSupplier;

/**
 * Hybrid logical clock (Kulkarni et al., 2014). {@link #now()} is strictly greater than every stamp
 * this clock has produced or {@link #observe observed}, and stays close to wall-clock time.
 */
public final class Hlc {
    private final String node;
    private final LongSupplier wallClock;
    private long wall;
    private int counter;

    public Hlc(String node) {
        this(node, System::currentTimeMillis);
    }

    public Hlc(String node, LongSupplier wallClock) {
        this.node = node;
        this.wallClock = wallClock;
    }

    public String node() {
        return node;
    }

    public synchronized Stamp now() {
        long pt = wallClock.getAsLong();
        if (pt > wall) {
            wall = pt;
            counter = 0;
        } else {
            counter = Math.addExact(counter, 1);
        }
        return new Stamp(wall, counter, node);
    }

    /** Moves the clock past a stamp received from elsewhere (a peer, or the log on startup). */
    public synchronized void observe(Stamp s) {
        if (s.wall() > wall || (s.wall() == wall && s.counter() > counter)) {
            wall = s.wall();
            counter = s.counter();
        }
    }
}
