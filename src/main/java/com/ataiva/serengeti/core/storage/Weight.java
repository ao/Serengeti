package com.ataiva.serengeti.core.storage;

import java.util.List;
import java.util.Map;

/**
 * Estimates of how many bytes of heap the stored data takes, from its structure: the sizes of the maps,
 * strings and boxed numbers that hold a row on a 64-bit JVM with compressed pointers. They are estimates (a
 * JVM's layout and the JSON parser's choices differ), kept so that a node can refuse writes before its data
 * outgrows memory: the store adds and subtracts them as rows change, so the total is exact for the estimate
 * and needs no garbage collection to be read.
 */
public final class Weight {
    private Weight() {}

    /** A string: its object, its byte array, and one byte per char (two if any char is above U+00FF). */
    static long of(String s) {
        boolean latin1 = true;
        for (int i = 0; i < s.length() && latin1; i++) {
            latin1 = s.charAt(i) <= 0xFF;
        }
        return 24 + 16 + align((long) s.length() * (latin1 ? 1 : 2));
    }

    /** A JSON value as the parser holds it. */
    @SuppressWarnings("unchecked")
    static long ofValue(Object v) {
        if (v == null || v instanceof Boolean) {
            return 0; // shared instances
        }
        if (v instanceof String s) {
            return of(s);
        }
        if (v instanceof Number) {
            return 24;
        }
        if (v instanceof Map<?, ?> m) {
            return ofMap((Map<String, Object>) m);
        }
        if (v instanceof List<?> l) {
            long n = 24 + 16 + align(4L * l.size());
            for (Object o : l) {
                n += ofValue(o);
            }
            return n;
        }
        return 64;
    }

    /** A row's map: the map, its table, and per entry the entry, its key and its value. */
    static long ofMap(Map<String, Object> m) {
        long n = 56 + 16 + align(4L * Math.max(16, Integer.highestOneBit(Math.max(1, m.size()) * 2)));
        for (Map.Entry<String, Object> e : m.entrySet()) {
            n += 40 + of(e.getKey()) + ofValue(e.getValue());
        }
        return n;
    }

    /** One version of a row: the record, its stamp, its value and its causal context. */
    static long ofVersion(Map<String, Object> value, Map<String, Stamp> seen) {
        long n = 24 + 32 + (value == null ? 0 : ofMap(value));
        if (seen != null && !seen.isEmpty()) { // an empty context is one shared instance
            n += 48 + 40L * seen.size() + 64L * seen.size();
        }
        return n;
    }

    /** A row's place in its table: the tree entry, the row state, its list and its id. */
    static long ofRow(String id) {
        return 40 + 16 + 24 + of(id);
    }

    /**
     * The estimate of a list of ops as the node holds them on the way in: rows and their versions. Used to
     * size the next page of a sync, not for the memory limit.
     */
    public static long ofOps(List<Op> ops) {
        long n = 0;
        for (Op op : ops) {
            if (op instanceof Op.Put p) {
                n += ofRow(p.id()) + ofVersion(p.row(), p.seen());
            } else if (op instanceof Op.Delete d) {
                n += ofRow(d.id()) + ofVersion(null, d.seen());
            } else {
                n += 64;
            }
        }
        return n;
    }

    /** One id in one index key's set: the set's tree entry. */
    static final long INDEX_ENTRY = 40;

    /**
     * One distinct key of an index: its entry in the index's tree, and the set of ids (a TreeSet and the
     * TreeMap inside it). The key itself is the row's value, which the row already counts. On a column whose
     * values are all different, this is most of what the index takes.
     */
    static final long INDEX_KEY = 104;

    /**
     * One row whose versions are all deletes, in its table's index of them by stamp (what tombstone collection and
     * the expiry walk): the tree entry and the record holding the stamp and the id (the id is the row's own).
     */
    static final long DEAD_ENTRY = 72;

    /**
     * One distinct key of an index on {@code columns} columns (2 or more): as {@link #INDEX_KEY}, plus the array
     * that holds the key's values (the values themselves are the row's).
     */
    static long ofTupleKey(int columns) {
        return INDEX_KEY + 16 + align(4L * columns);
    }

    private static long align(long n) {
        return (n + 7) & ~7L;
    }
}
