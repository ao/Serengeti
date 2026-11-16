package com.ataiva.serengeti.core.storage;

import java.util.Objects;

/**
 * A hybrid logical clock reading: wall-clock milliseconds, a counter for events in the same
 * millisecond, and the id of the node that made it. Stamps are totally ordered; the node id breaks
 * ties, so two nodes never produce equal stamps.
 */
public record Stamp(long wall, int counter, String node) implements Comparable<Stamp> {
    public static final Stamp ZERO = new Stamp(0, 0, "");

    public Stamp {
        Objects.requireNonNull(node, "node");
        if (wall < 0 || counter < 0) {
            throw new IllegalArgumentException("negative stamp");
        }
        if (node.indexOf('.') >= 0) {
            throw new IllegalArgumentException("node id must not contain '.'");
        }
    }

    @Override
    public int compareTo(Stamp o) {
        int c = Long.compare(wall, o.wall);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(counter, o.counter);
        return c != 0 ? c : node.compareTo(o.node);
    }

    public boolean after(Stamp o) {
        return compareTo(o) > 0;
    }

    public static Stamp max(Stamp a, Stamp b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** {@code wall.counter.node}, e.g. {@code 1731400000000.0.n1}. */
    @Override
    public String toString() {
        return wall + "." + counter + "." + node;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, String> NODES =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** More distinct ids than any cluster has; past it, ids are not shared (a broken or hostile peer). */
    private static final int MAX_NODES = 4096;

    /**
     * One shared copy of a node id. Every row version names its node, and a stamp parsed from the log, a
     * snapshot or a peer would otherwise hold its own copy of the 36-character id, about 80 bytes each: after
     * a restart, a node full of small rows needed a fifth more heap than before it.
     */
    static String node(String id) {
        String known = NODES.get(id);
        if (known != null) {
            return known;
        }
        if (NODES.size() >= MAX_NODES) {
            return id;
        }
        String was = NODES.putIfAbsent(id, id);
        return was != null ? was : id;
    }

    public static Stamp parse(String s) {
        int a = s.indexOf('.');
        int b = a < 0 ? -1 : s.indexOf('.', a + 1);
        if (a <= 0 || b <= a + 1) {
            throw new IllegalArgumentException("bad stamp: " + s);
        }
        try {
            return new Stamp(Long.parseLong(s.substring(0, a)), Integer.parseInt(s.substring(a + 1, b)),
                    node(s.substring(b + 1)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad stamp: " + s, e);
        }
    }
}
