package com.ataiva.serengeti.core.storage;

import com.ataiva.serengeti.core.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * The in-memory state of all databases, tables and rows.
 *
 * <p>The state is a join-semilattice: each database and table keeps the newest stamp at which it was
 * created and the newest at which it was dropped (it exists while created is newer), and each row keeps
 * its newest write (a value or a tombstone). A drop at stamp D removes every row older than D. So
 * {@link #apply} is idempotent, commutative and associative: replaying the log twice, loading a snapshot
 * and then the log, or merging a peer's state all give the same result regardless of order.
 *
 * <p>Not thread-safe; {@link StorageEngine} guards it with a lock.
 */
public final class Store {
    /**
     * Rows of a table are spread over this many buckets by a hash of their id. Each bucket keeps the sum
     * (mod 2^64) of {@link #rowHash} over its live rows, updated as rows change, so replicas compare a
     * table in O(1) and find the differing rows by bucket without hashing the whole table. A second sum per
     * bucket also counts tombstones (see {@code TableState.full}); 2.1 compares only the first.
     */
    public static final int BUCKETS = 1024;

    private static final class TableState {
        Stamp created = Stamp.ZERO;
        Stamp dropped;
        final TreeMap<String, RowState> rows = new TreeMap<>();
        /** Estimated heap bytes of the rows held (see Weight), kept in step with every change. */
        long weight;
        /** Rows a SELECT sees (the winning version holds a value), kept in step with every change. */
        long visible;
        final long[] buckets = new long[BUCKETS];
        /**
         * Per bucket, the sum of {@link RowState#versionsHash} over its rows, tombstones included, except
         * expired ones (see {@link #setExpiry}): what protocol 3 compares, so that a newer delete reaches a
         * replica that holds an older one.
         */
        final long[] full = new long[BUCKETS];
        /** Per bucket, the rows held (tombstones included) and their weight: what a peer sizes its pulls by. */
        final int[] count = new int[BUCKETS];
        final long[] bytes = new long[BUCKETS];

        TableState(Stamp dropped) {
            this.dropped = dropped;
        }

        boolean exists() {
            return created.after(dropped);
        }

        /**
         * Indexes by the {@code column} field of their ops (one column, or several joined by commas), dropped
         * ones included (their stamps still replicate).
         */
        final TreeMap<String, IndexState> indexes = new TreeMap<>();

        IndexState index(String column) {
            return indexes.computeIfAbsent(column, c -> {
                IndexState ix = c.indexOf(',') >= 0 ? new TupleIndex(c, dropped) : new ColumnIndex(c, dropped);
                ix.current = this::current;
                return ix;
            });
        }

        /** The current value of row {@code id}, null if it has none. */
        Map<String, Object> current(String id) {
            RowState r = rows.get(id);
            return r == null ? null : r.value();
        }

        void drop(Stamp at, long expiry) {
            if (at.after(dropped)) {
                dropped = at;
                for (IndexState ix : indexes.values()) {
                    ix.setDropped(at, rows);
                }
                for (String id : new ArrayList<>(rows.keySet())) {
                    RowState r = rows.get(id);
                    RowState kept = r.newerThan(at);
                    if (kept != r) {
                        replace(id, r, kept, expiry);
                    }
                }
            }
        }

        /**
         * Replaces a row's state ({@code next} null removes it), keeping bucket sums and indexes in step.
         * {@code expiry} is the store's (the full sums were last counted with it).
         */
        void replace(String id, RowState old, RowState next, long expiry) {
            int b = bucket(id);
            if (old != null) {
                unaccount(id, old, expiry);
                long w = old.weight(id);
                weight -= w;
                bytes[b] -= w;
                count[b]--;
                if (!old.live()) {
                    dead.remove(new Dead(old.winner().stamp().wall(), id));
                }
            }
            if (next == null) {
                rows.remove(id);
            } else {
                account(id, next, expiry);
                long w = next.weight(id);
                weight += w;
                bytes[b] += w;
                count[b]++;
                rows.put(id, next);
                if (!next.live()) {
                    dead.add(new Dead(next.winner().stamp().wall(), id));
                }
            }
            Map<String, Object> was = old == null ? null : old.winner().value();
            Map<String, Object> now = next == null ? null : next.winner().value();
            visible += (now != null ? 1 : 0) - (was != null ? 1 : 0);
            if (was != now) {
                for (IndexState ix : indexes.values()) {
                    if (ix.exists()) {
                        ix.move(id, was, now);
                    }
                }
            }
        }

        void account(String id, RowState r, long expiry) {
            int b = bucket(id);
            buckets[b] += r.hash(id);
            full[b] += r.counted(id, expiry);
        }

        void unaccount(String id, RowState r, long expiry) {
            int b = bucket(id);
            buckets[b] -= r.hash(id);
            full[b] -= r.counted(id, expiry);
        }

        /** The full sums counted again from the rows, for a new expiry. */
        /**
         * The rows whose versions are all deletes, by their newest stamp's wall time: what moving the expiry
         * and collecting tombstones visit, instead of every row (review 22e, finding 4).
         */
        final java.util.TreeSet<Dead> dead = new java.util.TreeSet<>();

        /** The expiry moves from {@code from} to {@code to}: the tombstones in between leave or rejoin the full sums. */
        void moveExpiry(long from, long to) {
            boolean forward = to > from;
            for (Dead x : dead.subSet(new Dead(Math.min(from, to), ""), true, new Dead(Math.max(from, to), ""), false)) {
                long h = rows.get(x.id()).versionsHash(x.id());
                full[bucket(x.id())] += forward ? -h : h;
            }
        }

        long root() {
            return root(buckets);
        }

        long fullRoot() {
            return root(full);
        }

        static long root(long[] sums) {
            long sum = 0;
            for (long b : sums) {
                sum = sum * 31 + b;
            }
            return sum;
        }
    }

    /** The bucket of a row id. Stable across nodes and versions (String.hashCode is specified). */
    public static int bucket(String id) {
        int h = id.hashCode();
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        return Math.floorMod(h, BUCKETS);
    }

    /** 64 bits of SHA-256 over the id and the stamp. A row's version is identified by its stamp. */
    static long rowHash(String id, Stamp at) {
        MessageDigest md = SHA256.get();
        md.reset();
        md.update(id.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(at.toString().getBytes(StandardCharsets.UTF_8));
        byte[] d = md.digest();
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (d[i] & 0xff);
        }
        return v;
    }

    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    /**
     * A secondary index. Like a table it exists while its newest create is newer than its newest drop (or its
     * table's); its entries are rebuilt from the rows when it comes into existence, and only its create and
     * drop stamps replicate.
     */
    private abstract static class IndexState {
        Stamp created = Stamp.ZERO;
        Stamp dropped;
        /** Ids held across all keys, and distinct keys, for the weight estimate. */
        long entries;
        long keys;
        /**
         * The current value of a row of the table. A key is the value of the row that put it there; when that
         * row leaves and others stay, the key is put again with one of theirs, or it would keep the old row's
         * value alive, uncounted (review 22f).
         */
        java.util.function.Function<String, Map<String, Object>> current = id -> null;

        IndexState(Stamp dropped) {
            this.dropped = dropped;
        }

        boolean exists() {
            return created.after(dropped);
        }

        void setCreated(Stamp at, Map<String, RowState> rows) {
            boolean was = exists();
            created = Stamp.max(created, at);
            if (!was && exists()) {
                rows.forEach((id, r) -> add(id, r.value()));
            }
        }

        void setDropped(Stamp at, Map<String, RowState> rows) {
            boolean was = exists();
            dropped = Stamp.max(dropped, at);
            if (was && !exists()) {
                clear();
                entries = 0;
                keys = 0;
            }
        }

        void move(String id, Map<String, Object> was, Map<String, Object> now) {
            remove(id, was);
            add(id, now);
        }

        abstract void clear();

        abstract void add(String id, Map<String, Object> row);

        abstract void remove(String id, Map<String, Object> row);

        /** Estimated heap bytes of the entries (see Weight). */
        abstract long weight();

        /** How many columns the index is on. */
        abstract int width();

        /**
         * The keys a read in ORDER BY order of {@code where} meets (see {@link #forEachInOrder}), as streams that
         * each give their keys in the order of the columns after the fixed ones, ascending or descending; null
         * when this index cannot read {@code where} so.
         */
        abstract List<java.util.Iterator<Entry>> streams(Lookup.Composite where, boolean descending);

        /** True if this index can answer {@code l}; the methods below are called only for such lookups. */
        abstract boolean answers(Lookup l);

        /** The matching keys' id sets, without copying them. */
        abstract java.util.Collection<java.util.TreeSet<String>> sets(Lookup l);

        /**
         * The ids in id order, without copying where that can be avoided; a range that must be copied to be in
         * that order ({@link #gathered}) only when it holds at most {@code limit} ids, else null: the caller
         * reads another way.
         */
        abstract Iterable<String> ids(Lookup l, long limit);

        long estimate(Lookup l) {
            return estimate(l, Long.MAX_VALUE);
        }

        /** As {@link #estimate(Lookup)}, but once the count passes {@code atMost} it may stop and return it. */
        long estimate(Lookup l, long atMost) {
            long n = 0;
            for (java.util.TreeSet<String> s : sets(l)) {
                n += s.size();
                if (n > atMost) {
                    break;
                }
            }
            return n;
        }

        java.util.SortedSet<String> lookup(Lookup l) {
            java.util.TreeSet<String> out = new java.util.TreeSet<>();
            for (java.util.TreeSet<String> s : sets(l)) {
                out.addAll(s);
            }
            return out;
        }
    }

    /**
     * An index on one column: for each boolean, number and string value, the ids of the rows whose current value
     * holds it; and apart from those, the ids of the rows where the column is missing or null and of those where
     * it holds an object or an array. No lookup finds the last two (no comparison with them is true); they are
     * there so that a read in ORDER BY order can place those rows, first and last.
     */
    private static final class ColumnIndex extends IndexState {
        final String column;
        final TreeMap<Boolean, java.util.TreeSet<String>> bools = new TreeMap<>();
        final TreeMap<Number, java.util.TreeSet<String>> numbers = new TreeMap<>(ScalarOrder::compareNumbers);
        final TreeMap<String, java.util.TreeSet<String>> strings = new TreeMap<>();
        final java.util.TreeSet<String> absent = new java.util.TreeSet<>();
        final java.util.TreeSet<String> others = new java.util.TreeSet<>();

        ColumnIndex(String column, Stamp dropped) {
            super(dropped);
            this.column = column;
        }

        @Override
        int width() {
            return 1;
        }

        @Override
        void clear() {
            bools.clear();
            numbers.clear();
            strings.clear();
            absent.clear();
            others.clear();
        }

        @Override
        long weight() {
            return entries * Weight.INDEX_ENTRY + keys * Weight.INDEX_KEY;
        }

        @Override
        boolean answers(Lookup l) {
            return l instanceof Lookup.Compare || l instanceof Lookup.AnyOf || l instanceof Lookup.Prefix;
        }

        @SuppressWarnings("unchecked")
        <K> TreeMap<K, java.util.TreeSet<String>> mapFor(Object v) {
            return (TreeMap<K, java.util.TreeSet<String>>) (v instanceof Boolean ? bools
                    : v instanceof Number ? numbers : v instanceof String ? strings : null);
        }

        @Override
        void add(String id, Map<String, Object> row) {
            if (row == null) {
                return;
            }
            Object v = row.get(column);
            TreeMap<Object, java.util.TreeSet<String>> m = mapFor(v);
            if (m == null) {
                if ((v == null ? absent : others).add(id)) {
                    entries++;
                }
                return;
            }
            java.util.TreeSet<String> ids = m.get(v);
            if (ids == null) {
                ids = new java.util.TreeSet<>();
                m.put(v, ids);
                keys++;
            }
            if (ids.add(id)) {
                entries++;
            }
        }

        @Override
        void remove(String id, Map<String, Object> row) {
            if (row == null) {
                return;
            }
            Object v = row.get(column);
            TreeMap<Object, java.util.TreeSet<String>> m = mapFor(v);
            if (m == null) {
                if ((v == null ? absent : others).remove(id)) {
                    entries--;
                }
                return;
            }
            java.util.TreeSet<String> ids = m.get(v);
            if (ids != null && ids.remove(id)) {
                entries--;
                if (ids.isEmpty()) {
                    m.remove(v);
                    keys--;
                } else if (v instanceof String && m.ceilingKey(v) == v) {
                    // Only a string can be large; numbers and booleans are a few bytes.
                    Map<String, Object> other = current.apply(ids.first());
                    Object w = other == null ? null : other.get(column);
                    if (w instanceof String && w != v && w.equals(v)) {
                        m.remove(v);
                        m.put(w, ids);
                    }
                }
            }
        }

        /** The matching keys' id sets, without copying them. */
        java.util.Collection<java.util.TreeSet<String>> sets(String op, Object v) {
            TreeMap<Object, java.util.TreeSet<String>> m = mapFor(v);
            if (m == null) {
                return List.of();
            }
            return switch (op) {
                case "=" -> {
                    java.util.TreeSet<String> s = m.get(v);
                    yield s == null ? List.of() : List.of(s);
                }
                case "<" -> m.headMap(v, false).values();
                case "<=" -> m.headMap(v, true).values();
                case ">" -> m.tailMap(v, false).values();
                case ">=" -> m.tailMap(v, true).values();
                default -> throw new IllegalArgumentException("no index lookup for " + op);
            };
        }

        @Override
        java.util.Collection<java.util.TreeSet<String>> sets(Lookup l) {
            if (l instanceof Lookup.Compare c) {
                return sets(c.op(), c.value());
            }
            if (l instanceof Lookup.AnyOf a) {
                // Equal values (1 and 1.0) share a key, so the same set may come up twice: keep it once.
                java.util.Set<java.util.TreeSet<String>> out =
                        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for (Object v : a.values()) {
                    out.addAll(sets("=", v));
                }
                return out;
            }
            String p = ((Lookup.Prefix) l).prefix();
            String end = prefixEnd(p);
            return end == null ? strings.tailMap(p, true).values() : strings.subMap(p, true, end, false).values();
        }

        /**
         * The ids in id order: one key's live set; the sets of several equal-value keys merged as they are
         * read; a range gathered if small enough, else null.
         */
        @Override
        Iterable<String> ids(Lookup l, long limit) {
            java.util.Collection<java.util.TreeSet<String>> sets = sets(l);
            if (sets.size() <= 1) {
                return sets.isEmpty() ? List.of() : sets.iterator().next();
            }
            if (l instanceof Lookup.AnyOf) {
                return merged(sets);
            }
            return gathered(sets, limit);
        }

        /**
         * With a list of values for the column, one stream per distinct value, of its one key; else one stream
         * over the whole order (rows without a value, booleans, numbers, strings, objects and arrays) or, with
         * comparisons or a prefix, over the values of their type that satisfy them all.
         */
        @Override
        List<java.util.Iterator<Entry>> streams(Lookup.Composite where, boolean descending) {
            if (where.equal().size() > 1 || where.equal().size() == 1 && !where.range().isEmpty()) {
                return null;
            }
            List<java.util.Iterator<Entry>> out = new ArrayList<>();
            if (where.equal().size() == 1) {
                java.util.TreeSet<Object> values = new java.util.TreeSet<>(ScalarOrder::sortOrder);
                for (Object v : where.equal().get(0)) {
                    if (ScalarOrder.scalar(v)) {
                        values.add(v);
                    }
                }
                for (Object v : values) {
                    java.util.TreeSet<String> ids = this.<Object>mapFor(v).get(v);
                    if (ids != null) {
                        out.add(List.of(new Entry(new Object[] {v}, ids)).iterator());
                    }
                }
                return out;
            }
            List<java.util.Iterator<Entry>> parts = new ArrayList<>();
            if (where.range().isEmpty()) {
                if (!absent.isEmpty()) {
                    parts.add(List.of(new Entry(new Object[] {null}, absent)).iterator());
                }
                parts.add(entries(bools, descending));
                parts.add(entries(numbers, descending));
                parts.add(entries(strings, descending));
                if (!others.isEmpty()) {
                    parts.add(List.of(new Entry(new Object[] {OTHER}, others)).iterator());
                }
            } else {
                parts.add(entries(within(where.range()), descending));
            }
            if (descending) {
                java.util.Collections.reverse(parts);
            }
            out.add(concat(parts));
            return out;
        }

        /** The keys of the one type whose values satisfy every lookup of {@code range}; none if they name two types. */
        java.util.NavigableMap<Object, java.util.TreeSet<String>> within(List<Lookup> range) {
            TreeMap<Object, java.util.TreeSet<String>> m = null;
            Object lo = null;
            Object hi = null;
            boolean loIn = true;
            boolean hiIn = true;
            for (Lookup r : range) {
                Object v = r instanceof Lookup.Prefix p ? p.prefix() : ((Lookup.Compare) r).value();
                TreeMap<Object, java.util.TreeSet<String>> of = mapFor(v);
                if (of == null || m != null && of != m) {
                    return java.util.Collections.emptyNavigableMap();
                }
                m = of;
                Object low = null;
                Object high = null;
                boolean lowIn = true;
                boolean highIn = true;
                if (r instanceof Lookup.Prefix p) {
                    low = p.prefix();
                    high = prefixEnd(p.prefix());
                    highIn = false;
                } else {
                    switch (((Lookup.Compare) r).op()) {
                        case "=" -> {
                            low = v;
                            high = v;
                        }
                        case "<" -> {
                            high = v;
                            highIn = false;
                        }
                        case "<=" -> high = v;
                        case ">" -> {
                            low = v;
                            lowIn = false;
                        }
                        case ">=" -> low = v;
                        default -> throw new IllegalArgumentException("no index lookup for " + ((Lookup.Compare) r).op());
                    }
                }
                if (low != null) {
                    int c = lo == null ? 1 : ScalarOrder.order(low, lo);
                    if (c > 0 || c == 0 && !lowIn) {
                        lo = low;
                        loIn = lowIn;
                    }
                }
                if (high != null) {
                    int c = hi == null ? -1 : ScalarOrder.order(high, hi);
                    if (c < 0 || c == 0 && !highIn) {
                        hi = high;
                        hiIn = highIn;
                    }
                }
            }
            if (m == null) {
                return java.util.Collections.emptyNavigableMap();
            }
            if (lo != null && hi != null) {
                int c = ScalarOrder.order(lo, hi);
                if (c > 0 || c == 0 && !(loIn && hiIn)) {
                    return java.util.Collections.emptyNavigableMap();
                }
                return m.subMap(lo, loIn, hi, hiIn);
            }
            return lo != null ? m.tailMap(lo, loIn) : hi != null ? m.headMap(hi, hiIn) : m;
        }

        private static java.util.Iterator<Entry> entries(java.util.NavigableMap<?, java.util.TreeSet<String>> m,
                boolean descending) {
            java.util.Iterator<? extends Map.Entry<?, java.util.TreeSet<String>>> it =
                    (descending ? m.descendingMap() : m).entrySet().iterator();
            return new java.util.Iterator<>() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public Entry next() {
                    Map.Entry<?, java.util.TreeSet<String>> e = it.next();
                    return new Entry(new Object[] {e.getKey()}, e.getValue());
                }
            };
        }
    }

    /** One key of an index as a read in ORDER BY order meets it: its values, one per column, and its rows' ids. */
    private record Entry(Object[] key, java.util.NavigableSet<String> ids) {}

    /** The entries of {@code parts}, one after the other. */
    private static java.util.Iterator<Entry> concat(List<java.util.Iterator<Entry>> parts) {
        return new java.util.Iterator<>() {
            private int at;

            @Override
            public boolean hasNext() {
                while (at < parts.size() && !parts.get(at).hasNext()) {
                    at++;
                }
                return at < parts.size();
            }

            @Override
            public Entry next() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                return parts.get(at).next();
            }
        };
    }

    /** The union of {@code sets} copied in id order, or null when they hold more than {@code limit} ids. */
    static Gathered gathered(java.util.Collection<java.util.TreeSet<String>> sets, long limit) {
        long size = 0;
        for (java.util.TreeSet<String> s : sets) {
            size += s.size();
        }
        if (size > limit) {
            return null;
        }
        Gathered out = new Gathered();
        for (java.util.TreeSet<String> s : sets) {
            out.addAll(s);
        }
        return out;
    }

    /** Ids copied out of an index to put a range in id order ({@link #gathered}), so a union can count them. */
    static final class Gathered extends java.util.TreeSet<String> {}

    /**
     * An index on 2 to 4 columns: the rows by their values of the columns, in the ORDER BY order of each
     * (null and missing first, then booleans, numbers and strings; objects and arrays last, all equal), as a
     * map from each distinct tuple of values to the ids of the rows that hold it. Every live row is in it; no
     * lookup matches one whose first value is missing, null, an object or an array, but a read in ORDER BY
     * order places it. Its name in {@link #indexes} and in the index ops is the columns joined by commas
     * ({@link Op#indexKey}). One whose definition is not {@link #usable} holds nothing.
     */
    private static final class TupleIndex extends IndexState {
        final String[] columns;
        /** The definition holds 2 to 4 distinct column names, none of them {@code _id}: what the planner may use. */
        final boolean usable;
        final TreeMap<Object[], java.util.TreeSet<String>> map = new TreeMap<>(Store::compareTuples);

        TupleIndex(String key, Stamp dropped) {
            super(dropped);
            this.columns = key.split(",", -1);
            java.util.Set<String> distinct = new java.util.HashSet<>(List.of(columns));
            this.usable = columns.length >= 2 && columns.length <= MAX_INDEX_COLUMNS
                    && distinct.size() == columns.length && !distinct.contains("_id") && !distinct.contains("");
        }

        /**
         * A row's key: its values of the columns, null for a missing one, {@link #OTHER} for an object or an
         * array (so the key keeps no reference to them); null for no row, or when the index is not usable.
         */
        Object[] keyOf(Map<String, Object> row) {
            if (row == null || !usable) {
                return null;
            }
            Object[] k = new Object[columns.length];
            for (int i = 0; i < k.length; i++) {
                Object v = row.get(columns[i]);
                k[i] = v == null || ScalarOrder.scalar(v) ? v : OTHER;
            }
            return k;
        }

        @Override
        void clear() {
            map.clear();
        }

        @Override
        void add(String id, Map<String, Object> row) {
            Object[] k = keyOf(row);
            if (k == null) {
                return;
            }
            java.util.TreeSet<String> ids = map.get(k);
            if (ids == null) {
                ids = new java.util.TreeSet<>();
                map.put(k, ids);
                keys++;
            }
            if (ids.add(id)) {
                entries++;
            }
        }

        @Override
        void remove(String id, Map<String, Object> row) {
            Object[] k = keyOf(row);
            if (k == null) {
                return;
            }
            java.util.TreeSet<String> ids = map.get(k);
            if (ids != null && ids.remove(id)) {
                entries--;
                if (ids.isEmpty()) {
                    map.remove(k);
                    keys--;
                } else if (holdsStringOf(map.ceilingKey(k), k)) {
                    Object[] w = keyOf(current.apply(ids.first()));
                    if (w != null && compareTuples(w, k) == 0) {
                        map.remove(k);
                        map.put(w, ids);
                    }
                }
            }
        }

        /** True if {@code stored} holds one of the strings of {@code leaving} itself (only a string can be large). */
        private static boolean holdsStringOf(Object[] stored, Object[] leaving) {
            for (int i = 0; i < leaving.length; i++) {
                if (leaving[i] instanceof String && stored[i] == leaving[i]) {
                    return true;
                }
            }
            return false;
        }

        @Override
        long weight() {
            return entries * Weight.INDEX_ENTRY + keys * Weight.ofTupleKey(columns.length);
        }

        @Override
        boolean answers(Lookup l) {
            if (!usable || !(l instanceof Lookup.Composite c)) {
                return false;
            }
            int named = c.equal().size() + (c.range().isEmpty() ? 0 : 1);
            if (named == 0 || named > columns.length) {
                return false;
            }
            for (Lookup r : c.range()) {
                if (!(r instanceof Lookup.Prefix || r instanceof Lookup.Compare x && List.of("=", "<", "<=", ">", ">=").contains(x.op()))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        int width() {
            return columns.length;
        }

        @Override
        java.util.Collection<java.util.TreeSet<String>> sets(Lookup l) {
            List<java.util.TreeSet<String>> out = new ArrayList<>();
            for (java.util.NavigableMap<Object[], java.util.TreeSet<String>> r : ranges((Lookup.Composite) l)) {
                out.addAll(r.values());
            }
            return out;
        }

        @Override
        long estimate(Lookup l, long atMost) {
            long n = 0;
            for (java.util.NavigableMap<Object[], java.util.TreeSet<String>> r : ranges((Lookup.Composite) l)) {
                for (java.util.TreeSet<String> s : r.values()) {
                    n += s.size();
                    if (n > atMost) {
                        return n;
                    }
                }
            }
            return n;
        }

        /** One stream per tuple of values of the fixed columns: its keys, within the range on the next column. */
        @Override
        List<java.util.Iterator<Entry>> streams(Lookup.Composite where, boolean descending) {
            int k = where.equal().size();
            if (!usable || k > columns.length || k == columns.length && !where.range().isEmpty()) {
                return null;
            }
            List<java.util.Iterator<Entry>> out = new ArrayList<>();
            for (java.util.NavigableMap<Object[], java.util.TreeSet<String>> r : ranges(where)) {
                java.util.Iterator<Map.Entry<Object[], java.util.TreeSet<String>>> it =
                        (descending ? r.descendingMap() : r).entrySet().iterator();
                out.add(new java.util.Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return it.hasNext();
                    }

                    @Override
                    public Entry next() {
                        Map.Entry<Object[], java.util.TreeSet<String>> e = it.next();
                        return new Entry(e.getKey(), e.getValue());
                    }
                });
            }
            return out;
        }

        /**
         * The key ranges {@code c} reads, as views of the map in key order, one per tuple of equal values at most.
         * With nothing fixed and no range, the whole map.
         */
        private List<java.util.NavigableMap<Object[], java.util.TreeSet<String>>> ranges(Lookup.Composite c) {
            List<java.util.NavigableMap<Object[], java.util.TreeSet<String>>> out = new ArrayList<>();
            for (Object[] p : tuples(c.equal())) {
                Object[] lo = bound(p, null, LOW);
                Object[] hi = bound(p, null, HIGH);
                for (Lookup r : c.range()) {
                    Object[][] b = range(p, r);
                    if (b == null) {
                        lo = null;
                        break;
                    }
                    if (compareTuples(b[0], lo) > 0) {
                        lo = b[0];
                    }
                    if (compareTuples(b[1], hi) < 0) {
                        hi = b[1];
                    }
                }
                if (lo != null && compareTuples(lo, hi) <= 0) {
                    out.add(map.subMap(lo, true, hi, true));
                }
            }
            return out;
        }

        /**
         * The distinct tuples (as ORDER BY compares them, so 1 and 1.0 are one) of the product of the lists,
         * leaving out null, which no comparison equals.
         */
        static java.util.Collection<Object[]> tuples(List<List<Object>> lists) {
            java.util.TreeSet<Object[]> out = new java.util.TreeSet<>(Store::compareTuples);
            out.add(new Object[0]);
            for (List<Object> values : lists) {
                java.util.TreeSet<Object[]> next = new java.util.TreeSet<>(Store::compareTuples);
                for (Object[] p : out) {
                    for (Object v : values) {
                        if (ScalarOrder.scalar(v)) {
                            Object[] q = java.util.Arrays.copyOf(p, p.length + 1);
                            q[p.length] = v;
                            next.add(q);
                        }
                    }
                }
                out = next;
            }
            return out;
        }

        /**
         * A bound: {@code p}, then {@code v} if not null, then {@code fill} for the other columns and once more
         * past the last, so that it is one longer than a key and never equal to one (see {@link #compareTuples}).
         */
        Object[] bound(Object[] p, Object v, Edge fill) {
            Object[] b = java.util.Arrays.copyOf(p, columns.length + 1);
            int at = p.length;
            if (v != null) {
                b[at++] = v;
            }
            java.util.Arrays.fill(b, at, b.length, fill);
            return b;
        }

        /**
         * The lowest and highest key of the rows whose value of the column after {@code p} makes {@code r} true,
         * after the values {@code p}: {@code [lo, hi]}, or null when no value does. Values of other types never
         * compare true, so a range stays within its value's type.
         */
        Object[][] range(Object[] p, Lookup r) {
            if (r instanceof Lookup.Prefix x) {
                String end = prefixEnd(x.prefix());
                return new Object[][] {bound(p, x.prefix(), LOW),
                        end == null ? bound(p, after(x.prefix()), HIGH) : bound(p, end, LOW)};
            }
            Lookup.Compare c = (Lookup.Compare) r;
            Object v = c.value();
            if (!ScalarOrder.scalar(v)) {
                return null;
            }
            return switch (c.op()) {
                case "=" -> new Object[][] {bound(p, v, LOW), bound(p, v, HIGH)};
                case "<" -> new Object[][] {bound(p, before(v), LOW), bound(p, v, LOW)};
                case "<=" -> new Object[][] {bound(p, before(v), LOW), bound(p, v, HIGH)};
                case ">" -> new Object[][] {bound(p, v, HIGH), bound(p, after(v), HIGH)};
                default -> new Object[][] {bound(p, v, LOW), bound(p, after(v), HIGH)};
            };
        }

        /**
         * The ids in id order: one key's set; the sets of several keys that name every column merged as they
         * are read (an IN list); any other range gathered if small enough, else null.
         */
        @Override
        Iterable<String> ids(Lookup l, long limit) {
            java.util.Collection<java.util.TreeSet<String>> sets = sets(l);
            if (sets.size() <= 1) {
                return sets.isEmpty() ? List.of() : sets.iterator().next();
            }
            Lookup.Composite c = (Lookup.Composite) l;
            if (c.equal().size() == columns.length) {
                return merged(sets);
            }
            return gathered(sets, limit);
        }
    }

    /** The most columns an index may have. */
    public static final int MAX_INDEX_COLUMNS = 4;

    /** An object or an array in a {@link TupleIndex} key: after every string, and equal to any other (the ORDER BY order). */
    private static final Object OTHER = new Object() {
        @Override
        public String toString() {
            return "OTHER";
        }
    };

    /**
     * A sentinel in a lookup bound of a {@link TupleIndex}, between the ranks of {@link #tupleRank}: never equal
     * to a value, so a bound never matches a key exactly.
     */
    private record Edge(int at) {}

    private static final Edge LOW = new Edge(-1);
    private static final Edge HIGH = new Edge(9);

    /** Just below every value of {@code v}'s type. */
    private static Edge before(Object v) {
        return new Edge(tupleRank(v) - 1);
    }

    /** Just above every value of {@code v}'s type. */
    private static Edge after(Object v) {
        return new Edge(tupleRank(v) + 1);
    }

    /** Twice {@link ScalarOrder#rank}, so that a sentinel's odd {@code at} falls between two ranks. */
    private static int tupleRank(Object v) {
        if (v instanceof Edge e) {
            return e.at();
        }
        return v == OTHER ? 8 : 2 * ScalarOrder.rank(v);
    }

    /**
     * Key tuples (and bounds) in the ORDER BY order, column by column. A bound is one longer than a key and ends
     * in {@link #LOW} or {@link #HIGH}: past the columns they share, it is just below or just above the key.
     */
    static int compareTuples(Object[] a, Object[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            Object x = a[i];
            Object y = b[i];
            int rx = tupleRank(x);
            int ry = tupleRank(y);
            if (rx != ry) {
                return Integer.compare(rx, ry);
            }
            if (rx == 2 || rx == 4 || rx == 6) {
                int c = ScalarOrder.order(x, y);
                if (c != 0) {
                    return c;
                }
            }
        }
        if (a.length == b.length) {
            return 0;
        }
        Object[] longer = a.length > b.length ? a : b;
        int side = LOW.equals(longer[n]) ? -1 : 1;
        return a == longer ? side : -side;
    }

    /**
     * What an index lookup finds: the values that compare to {@code value} by {@code op} ("=", "<", "<=", ">",
     * ">="), the values equal to any of {@code values}, the strings that start with {@code prefix}, or on an
     * index of several columns a {@link Composite}. Each is exact by the rules of docs/QUERY.md: null, and
     * values of no indexed type, match nothing.
     */
    public sealed interface Lookup {
        /**
         * On an index of several columns: for each of its first {@code equal.size()} columns, the values it may
         * equal (any one; the union); then, on the next column, the comparisons ({@link Compare} with "<",
         * "<=", ">", ">=" or "=") and prefixes ({@link Prefix}) that must all hold.
         */
        record Composite(List<List<Object>> equal, List<Lookup> range) implements Lookup {
            public Composite {
                List<List<Object>> copy = new ArrayList<>(equal.size());
                for (List<Object> values : equal) {
                    copy.add(java.util.Collections.unmodifiableList(new ArrayList<>(values)));
                }
                equal = java.util.Collections.unmodifiableList(copy);
                range = List.copyOf(range);
            }

            /** How many columns of the index the lookup names. */
            public int named() {
                return equal.size() + (range.isEmpty() ? 0 : 1);
            }
        }

        record Compare(String op, Object value) implements Lookup {
            public Compare {
                java.util.Objects.requireNonNull(op, "op");
            }
        }

        record AnyOf(List<Object> values) implements Lookup {
            public AnyOf {
                values = java.util.Collections.unmodifiableList(new ArrayList<>(values));
            }
        }

        record Prefix(String prefix) implements Lookup {
            public Prefix {
                java.util.Objects.requireNonNull(prefix, "prefix");
            }
        }
    }

    /**
     * The least string above every string that starts with {@code p} (UTF-16 code unit order), or null when
     * there is none ({@code p} is empty or all U+FFFF): the strings with that prefix are those in
     * [{@code p}, the result).
     */
    static String prefixEnd(String p) {
        int i = p.length() - 1;
        while (i >= 0 && p.charAt(i) == Character.MAX_VALUE) {
            i--;
        }
        return i < 0 ? null : p.substring(0, i) + (char) (p.charAt(i) + 1);
    }

    /**
     * The union of id streams that are each in id order (sorted sets, or such unions), in order and without
     * repeats, read as it is iterated (nothing is copied).
     */
    static Iterable<String> merged(java.util.Collection<? extends Iterable<String>> sets) {
        return () -> new java.util.Iterator<>() {
            private final java.util.PriorityQueue<Head> heads = new java.util.PriorityQueue<>();
            private String last;

            {
                for (Iterable<String> s : sets) {
                    java.util.Iterator<String> it = s.iterator();
                    if (it.hasNext()) {
                        heads.add(new Head(it.next(), it));
                    }
                }
                skipRepeats();
            }

            private void skipRepeats() {
                while (!heads.isEmpty() && last != null && heads.peek().id.equals(last)) {
                    advance(heads.poll());
                }
            }

            private void advance(Head h) {
                if (h.rest.hasNext()) {
                    heads.add(new Head(h.rest.next(), h.rest));
                }
            }

            @Override
            public boolean hasNext() {
                return !heads.isEmpty();
            }

            @Override
            public String next() {
                if (heads.isEmpty()) {
                    throw new java.util.NoSuchElementException();
                }
                Head h = heads.poll();
                last = h.id;
                advance(h);
                skipRepeats();
                return last;
            }
        };
    }

    private record Head(String id, java.util.Iterator<String> rest) implements Comparable<Head> {
        @Override
        public int compareTo(Head o) {
            return id.compareTo(o.id);
        }
    }

    private static final class DbState {
        final String name;
        Stamp created = Stamp.ZERO;
        Stamp dropped = Stamp.ZERO;
        final TreeMap<String, TableState> tables = new TreeMap<>();

        DbState(String name) {
            this.name = name;
        }

        boolean exists() {
            return created.after(dropped);
        }

        TableState table(String name) {
            return tables.computeIfAbsent(name, n -> new TableState(dropped));
        }
    }

    /**
     * One write to a row: its value (null for a delete), its stamp, and its causal context ({@link Op.RowOp#seen}).
     *
     * <p>Version {@code a} has been seen by version {@code b} when every entry of {@code a}'s clock is at or
     * below {@code b}'s entry for the same key. A version's clock is its context plus its own stamp under its
     * node's id and under {@code "~"}. A version with no context (from 2.0) has only {@code "~"}: such versions
     * are ordered by stamp among themselves, as 2.0 ordered every write, and any later write that saw one
     * covers it.
     */
    public record Version(Map<String, Object> value, Stamp stamp, Map<String, Stamp> seen) {
        /** The context key whose stamp covers every write at or below it, from any node. */
        static final String FLOOR = Op.FLOOR;

        public boolean deleted() {
            return value == null;
        }

        /** This version's clock entry for {@code key}, or null. */
        Stamp clock(String key) {
            if (seen == null) {
                return key.equals("~") ? stamp : null;
            }
            if (key.equals("~") || key.equals(stamp.node())) {
                return stamp; // a node's own write is newer than anything it had seen
            }
            // FLOOR: every write stamped at or below it counts as seen (entries folded in to bound the size).
            Stamp s = seen.get(key);
            Stamp floor = seen.get(FLOOR);
            return s == null ? floor : floor == null ? s : Stamp.max(s, floor);
        }

        java.util.Set<String> clockKeys() {
            if (seen == null) {
                return new java.util.TreeSet<>(java.util.Set.of("~"));
            }
            java.util.Set<String> keys = new java.util.TreeSet<>(seen.keySet());
            keys.add("~");
            keys.add(stamp.node());
            return keys;
        }

        /**
         * True if {@code other} had seen this version when it was written (or is it): this clock is at or below
         * the other's for every key either names. A key a clock does not name reads as its FLOOR, or as never.
         */
        boolean seenBy(Version other) {
            java.util.Set<String> keys = clockKeys();
            if (seen != null && seen.containsKey(FLOOR)) {
                keys.addAll(other.clockKeys()); // this floor applies to the other's keys too
            }
            for (String k : keys) {
                Stamp mine = clock(k);
                if (mine == null) {
                    continue;
                }
                Stamp theirs = other.clock(k);
                if (theirs == null || mine.after(theirs)) {
                    return false;
                }
            }
            return true;
        }

        Op.RowOp toOp(String db, String table, String id) {
            return value == null ? new Op.Delete(db, table, id, stamp, seen) : new Op.Put(db, table, id, value, stamp, seen);
        }

        static Version of(Op.RowOp op) {
            return new Version(op instanceof Op.Put p ? p.row() : null, op.stamp(), op.seen());
        }
    }

    /**
     * A row: the versions that no other version of it has seen, newest first. Normally one; more after
     * writes on nodes that had not seen each other's (a conflict). The newest is the row's value, so the
     * outcome of a conflict is last-writer-wins as in 2.0, and the others stay until a write resolves them.
     */
    private record RowState(List<Version> versions, Map<String, Object> value) {
        /** {@code value} is the current version's value (null for a delete), kept here so scans read it
         * without going through the list. */
        RowState(List<Version> versions) {
            this(versions, versions.get(0).value());
        }

        Version winner() {
            return versions.get(0);
        }

        boolean live() {
            for (Version v : versions) {
                if (v.value != null) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The row's share of its bucket sum: nothing while every version is a delete (so collecting
         * tombstones changes no sum), else the sum over all versions (so a conflicting delete still differs).
         */
        long hash(String id) {
            return live() ? versionsHash(id) : 0;
        }

        /**
         * The sum over all versions, deletes included: two replicas hold the same versions of a row exactly
         * when these agree (up to 64-bit collisions). Equal to {@link #hash} for a live row.
         */
        /** Every version is a delete stamped before {@code expiry}: it is collected, and sync leaves it out. */
        boolean expired(long expiry) {
            return !live() && winner().stamp().wall() < expiry;
        }

        /** The row's share of its bucket's full sum: {@link #versionsHash}, or nothing once expired. */
        long counted(String id, long expiry) {
            return expired(expiry) ? 0 : versionsHash(id);
        }

        long versionsHash(String id) {
            long h = 0;
            for (Version v : versions) {
                // A copy that lost its context (it went through a 2.0 node) hashes differently from the full
                // one, so the replicas that hold it fetch the full copy.
                h += rowHash(v.seen == null ? id : id + "\u0001", v.stamp);
            }
            return h;
        }

        /** This state with {@code in} merged, or this same object if {@code in} changes nothing. */
        RowState with(Version in) {
            Version replaced = null;
            for (Version v : versions) {
                if (v.stamp.equals(in.stamp)) {
                    // The same write. Keep the copy that has its context: one without it came through a 2.0
                    // node, which drops contexts, and would make later writes look concurrent with it.
                    if (v.seen != null || in.seen == null) {
                        return this;
                    }
                    replaced = v;
                } else if (in.seenBy(v)) {
                    return this;
                }
            }
            List<Version> out = new ArrayList<>(versions.size() + 1);
            for (Version v : versions) {
                if (v != replaced && !v.seenBy(in)) {
                    out.add(v);
                }
            }
            out.add(in);
            out.sort((a, b) -> b.stamp.compareTo(a.stamp));
            return new RowState(List.copyOf(out));
        }

        /** The versions stamped after {@code at}: this object if all are, null if none are. */
        RowState newerThan(Stamp at) {
            List<Version> out = new ArrayList<>(versions.size());
            for (Version v : versions) {
                if (v.stamp.after(at)) {
                    out.add(v);
                }
            }
            return out.size() == versions.size() ? this : out.isEmpty() ? null : new RowState(List.copyOf(out));
        }

        /** Estimated heap bytes of this row: its place in the table and each version. */
        long weight(String id) {
            long n = Weight.ofRow(id);
            for (Version v : versions) {
                n += Weight.ofVersion(v.value, v.seen);
            }
            return n;
        }

        /** The context for a new write over this row: the newest stamp per key over all its versions. */
        Map<String, Stamp> context() {
            Map<String, Stamp> out = new TreeMap<>();
            for (Version v : versions) {
                for (String k : v.clockKeys()) {
                    out.merge(k, v.clock(k), Stamp::max);
                }
            }
            return out;
        }
    }

    private final TreeMap<String, DbState> dbs = new TreeMap<>();
    private Stamp maxStamp = Stamp.ZERO;

    /**
     * Tombstones whose every version is stamped before this wall time are expired: left out of the full
     * bucket sums and of {@link #rowHashes}, not merged for a row not held here ({@link #withoutExpiredDeletes}),
     * and collected by {@link #collectTombstones}. Every node sets the same value at the same time ({@link #expiryAt}), so
     * nodes that have collected them and nodes that cannot yet agree on what to compare.
     */
    private long expiry = Long.MIN_VALUE;

    /** How often the expiry moves on, on wall-clock boundaries (:00, :10, ...). */
    public static final long EXPIRY_STEP_MILLIS = 10 * 60_000L;

    /** The expiry for a node whose clock reads {@code nowMillis}: the last boundary, less the TTL. */
    public static long expiryAt(long nowMillis, long ttlMillis) {
        return Math.floorDiv(nowMillis, EXPIRY_STEP_MILLIS) * EXPIRY_STEP_MILLIS - ttlMillis;
    }

    public long expiry() {
        return expiry;
    }

    /** Sets the expiry (see {@link #expiry}) and counts the full bucket sums again; O(rows) when it changes. */
    public void setExpiry(long wallMillis) {
        if (wallMillis == expiry) {
            return;
        }
        for (DbState d : dbs.values()) {
            for (TableState t : d.tables.values()) {
                t.moveExpiry(expiry, wallMillis);
            }
        }
        expiry = wallMillis;
    }

    /** A row whose versions are all deletes, keyed by its newest stamp's wall time, then its id. */
    private record Dead(long wall, String id) implements Comparable<Dead> {
        @Override
        public int compareTo(Dead o) {
            int c = Long.compare(wall, o.wall);
            return c != 0 ? c : id.compareTo(o.id);
        }
    }


    public void apply(Op op) {
        maxStamp = Stamp.max(maxStamp, op.stamp());
        DbState d = dbs.computeIfAbsent(op.db(), DbState::new);
        if (op instanceof Op.CreateDb o) {
            boolean existed = d.exists();
            d.created = Stamp.max(d.created, o.stamp());
            // Rows a cut-off peer wrote after a drop become visible again: followers must read the db again.
            if (feed != null && !existed && d.exists()) {
                feed.add("create_db", op.db(), null, null, null, o.stamp());
            }
        } else if (op instanceof Op.DropDb o) {
            boolean existed = d.exists();
            d.dropped = Stamp.max(d.dropped, o.stamp());
            for (TableState t : d.tables.values()) {
                t.drop(o.stamp(), expiry);
            }
            if (feed != null && existed && !d.exists()) {
                feed.add("drop_db", op.db(), null, null, null, o.stamp());
            }
        } else if (op instanceof Op.CreateTable o) {
            TableState t = d.table(o.table());
            boolean existed = d.exists() && t.exists();
            t.created = Stamp.max(t.created, o.stamp());
            if (feed != null && !existed && d.exists() && t.exists()) {
                feed.add("create_table", op.db(), o.table(), null, null, o.stamp());
            }
        } else if (op instanceof Op.DropTable o) {
            TableState t = d.table(o.table());
            boolean existed = d.exists() && t.exists();
            t.drop(o.stamp(), expiry);
            if (feed != null && existed && !t.exists()) {
                feed.add("drop_table", op.db(), o.table(), null, null, o.stamp());
            }
        } else if (op instanceof Op.CreateIndex o) {
            TableState t = d.table(o.table());
            t.index(o.column()).setCreated(o.stamp(), t.rows);
        } else if (op instanceof Op.DropIndex o) {
            TableState t = d.table(o.table());
            t.index(o.column()).setDropped(o.stamp(), t.rows);
        } else if (op instanceof Op.RowOp o) {
            putRow(d, o.table(), o.id(), Version.of(o));
        }
    }

    public void applyAll(List<Op> ops) {
        for (Op op : ops) {
            apply(op);
        }
    }

    private void putRow(DbState d, String table, String id, Version in) {
        TableState t = d.table(table);
        if (!in.stamp().after(t.dropped)) {
            return;
        }
        RowState r = t.rows.get(id);
        RowState next = r == null ? new RowState(List.of(in)) : r.with(in);
        if (next != r) {
            t.replace(id, r, next, expiry);
            // A version that does not change the row's value (a conflicting one that lost) is not a change.
            if (feed != null && (r == null ? null : r.value()) != next.value() && d.exists() && t.exists()) {
                feed.add("row", d.name, table, id, next.value(), next.winner().stamp());
            }
        }
    }

    private ChangeFeed feed;

    /** Starts reporting changes to {@code feed}; null stops. Set after the data is loaded, not before. */
    public void setFeed(ChangeFeed feed) {
        this.feed = feed;
    }

    /**
     * A context of at most {@link Op#MAX_SEEN} entries, which is what readers accept. Entries at or below the
     * floor are implied by it and dropped; past the limit, the oldest node entries are folded into the floor.
     * Folding claims every write at or below the new floor was seen: a write from a node that was concurrent
     * and older than all of the row's last {@code MAX_SEEN - 1} writers would be taken as superseded.
     */
    static Map<String, Stamp> bounded(Map<String, Stamp> ctx) {
        Stamp floor = ctx.get(Op.FLOOR);
        List<Map.Entry<String, Stamp>> nodes = new ArrayList<>();
        for (Map.Entry<String, Stamp> e : ctx.entrySet()) {
            if (!e.getKey().equals(Op.FLOOR) && (floor == null || e.getValue().after(floor))) {
                nodes.add(e);
            }
        }
        int room = Op.MAX_SEEN - 1; // one entry for the floor
        if (nodes.size() > (floor == null ? Op.MAX_SEEN : room)) {
            nodes.sort((a, b) -> b.getValue().compareTo(a.getValue()));
            for (Map.Entry<String, Stamp> e : nodes.subList(room, nodes.size())) {
                floor = floor == null ? e.getValue() : Stamp.max(floor, e.getValue());
            }
            nodes = new ArrayList<>(nodes.subList(0, room));
        }
        Map<String, Stamp> out = new TreeMap<>();
        for (Map.Entry<String, Stamp> e : nodes) {
            out.put(e.getKey(), e.getValue());
        }
        if (floor != null) {
            out.put(Op.FLOOR, floor);
        }
        return out;
    }

    /**
     * The batch with a causal context on every row write that has none: the clock of everything this node
     * holds for that row (and of the batch's earlier writes to it), so the write supersedes all of them.
     * Client writes go through here before they are logged; ops from peers keep the context they came with.
     */
    public List<Op> withContext(List<Op> batch) {
        List<Op> out = new ArrayList<>(batch.size());
        Map<String, Version> earlier = new java.util.HashMap<>();
        for (Op op : batch) {
            if (op instanceof Op.RowOp r && r.seen() == null) {
                String key = r.db() + "\u0000" + r.table() + "\u0000" + r.id();
                Version prev = earlier.get(key);
                Map<String, Stamp> ctx;
                if (prev != null) {
                    ctx = new RowState(List.of(prev)).context();
                } else {
                    DbState d = dbs.get(r.db());
                    TableState t = d == null ? null : d.tables.get(r.table());
                    RowState row = t == null ? null : t.rows.get(r.id());
                    ctx = row == null ? new TreeMap<>() : row.context();
                }
                ctx.remove(r.stamp().node()); // implied by the write's own stamp
                ctx.remove("~");
                ctx = bounded(ctx);
                Op.RowOp filled = r.withSeen(ctx);
                earlier.put(key, Version.of(filled));
                out.add(filled);
            } else {
                out.add(op);
            }
        }
        return out;
    }

    // ---- replica comparison ----

    /** Order-independent hash of a database's schema: its own and its tables' create and drop stamps. */
    public long schemaHash(String db) {
        // Hashes exactly what schemaOps would send, so an entry that apply() created as a placeholder
        // (a rejected stale write, say) on one replica and not on another does not count as a difference.
        long h = 0;
        for (Op op : schemaOps(db)) {
            String key = op instanceof Op.CreateTable o ? "ct\u0000" + o.table()
                    : op instanceof Op.DropTable o ? "dt\u0000" + o.table()
                    : op instanceof Op.CreateIndex o ? "ci\u0000" + o.table() + "\u0000" + o.column()
                    : op instanceof Op.DropIndex o ? "di\u0000" + o.table() + "\u0000" + o.column()
                    : op instanceof Op.CreateDb ? "cd" : "dd";
            h += rowHash(key, op.stamp());
        }
        return h;
    }

    /**
     * For each known database (dropped ones too): its schema hash, one root hash per table over the live rows
     * ({@code tables}, what 2.1 compares), and one over the full sums ({@code full}; 2.1 ignores the key).
     */
    public Map<String, Map<String, Object>> rootHashes() {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        for (Map.Entry<String, DbState> e : dbs.entrySet()) {
            Map<String, Object> tables = new TreeMap<>();
            Map<String, Object> full = new TreeMap<>();
            for (Map.Entry<String, TableState> t : e.getValue().tables.entrySet()) {
                tables.put(t.getKey(), t.getValue().root());
                full.put(t.getKey(), t.getValue().fullRoot());
            }
            Map<String, Object> m = new TreeMap<>();
            m.put("schema", schemaHash(e.getKey()));
            m.put("tables", tables);
            m.put("full", full);
            out.put(e.getKey(), m);
        }
        return out;
    }

    /** The bucket sums of a table, or all zeros if it is unknown. A copy. */
    public long[] bucketSums(String db, String table) {
        DbState d = dbs.get(db);
        TableState t = d == null ? null : d.tables.get(table);
        return t == null ? new long[BUCKETS] : t.buckets.clone();
    }

    /**
     * The full bucket sums of a table (every version of every row, tombstones included, expired ones left
     * out), or all zeros if it is unknown. A copy.
     */
    public long[] fullBucketSums(String db, String table) {
        DbState d = dbs.get(db);
        TableState t = d == null ? null : d.tables.get(table);
        return t == null ? new long[BUCKETS] : t.full.clone();
    }

    /** The database's create and drop ops, and its tables' (no rows). */
    public List<Op> schemaOps(String db) {
        List<Op> out = new ArrayList<>();
        DbState d = dbs.get(db);
        if (d == null) {
            return out;
        }
        if (d.created.after(Stamp.ZERO)) {
            out.add(new Op.CreateDb(db, d.created));
        }
        if (d.dropped.after(Stamp.ZERO)) {
            out.add(new Op.DropDb(db, d.dropped));
        }
        d.tables.forEach((name, t) -> {
            if (t.created.after(Stamp.ZERO)) {
                out.add(new Op.CreateTable(db, name, t.created));
            }
            if (t.dropped.after(d.dropped)) {
                out.add(new Op.DropTable(db, name, t.dropped));
            }
            indexOps(db, name, t, out::add);
        });
        return out;
    }

    private static void indexOps(String db, String table, TableState t, java.util.function.Consumer<Op> out) {
        t.indexes.forEach((column, ix) -> {
            if (ix.created.after(Stamp.ZERO)) {
                out.accept(new Op.CreateIndex(db, table, column, ix.created));
            }
            if (ix.dropped.after(t.dropped)) {
                out.accept(new Op.DropIndex(db, table, column, ix.dropped));
            }
        });
    }

    /** The rows (values and tombstones) of the given buckets of a table, as ops. */
    public List<Op> bucketOps(String db, String table, java.util.Set<Integer> wanted) {
        List<Op> out = new ArrayList<>();
        DbState d = dbs.get(db);
        TableState t = d == null ? null : d.tables.get(table);
        if (t == null) {
            return out;
        }
        t.rows.forEach((id, r) -> {
            if (wanted.contains(bucket(id))) {
                for (Version v : r.versions()) {
                    out.add(v.toOp(db, table, id));
                }
            }
        });
        return out;
    }

    /**
     * For each row of the given buckets of a table (tombstones included), in id order: its id and the hash
     * of all its versions. Two replicas hold the same versions of a row exactly when the hashes agree, so
     * comparing these finds the rows of a bucket that differ. A live row's hash is its share of its bucket
     * sum; a tombstone's is not 0 here, so a delete that one side lacks shows as a difference.
     */
    public Map<String, Long> rowHashes(String db, String table, java.util.Set<Integer> wanted) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        DbState d = dbs.get(db);
        TableState t = d == null ? null : d.tables.get(table);
        if (t == null) {
            return out;
        }
        t.rows.forEach((id, r) -> {
            // Expired tombstones are left out, as from the full sums: a peer that has collected them must not
            // pull them back.
            if (wanted.contains(bucket(id)) && !r.expired(expiry)) {
                out.put(id, r.versionsHash(id));
            }
        });
        return out;
    }

    /** How many rows (tombstones included) each bucket of a table holds; all zeros if it is unknown. A copy. */
    public int[] bucketRowCounts(String db, String table) {
        DbState d = dbs.get(db);
        TableState t = d == null ? null : d.tables.get(table);
        return t == null ? new int[BUCKETS] : t.count.clone();
    }

    /** The estimated weight (see Weight) of the rows each bucket of a table holds; all zeros if unknown. */
    public long[] bucketBytes(String db, String table) {
        DbState d = dbs.get(db);
        TableState t = d == null ? null : d.tables.get(table);
        return t == null ? new long[BUCKETS] : t.bytes.clone();
    }

    /** The estimated weight of each of these rows, in the order given; 0 for a row not held. */
    public long[] rowWeights(String db, String table, java.util.Collection<String> ids) {
        long[] out = new long[ids.size()];
        DbState d = dbs.get(db);
        TableState t = d == null ? null : d.tables.get(table);
        if (t != null) {
            int i = 0;
            for (String id : ids) {
                RowState r = t.rows.get(id);
                out[i++] = r == null ? 0 : r.weight(id);
            }
        }
        return out;
    }

    /** Every version (values and tombstones) of the rows with these ids, as ops; ids not held are skipped. */
    public List<Op> rowOps(String db, String table, java.util.Collection<String> ids) {
        List<Op> out = new ArrayList<>();
        DbState d = dbs.get(db);
        TableState t = d == null ? null : d.tables.get(table);
        if (t == null) {
            return out;
        }
        for (String id : new java.util.TreeSet<>(ids)) {
            RowState r = t.rows.get(id);
            if (r != null) {
                for (Version v : r.versions()) {
                    out.add(v.toOp(db, table, id));
                }
            }
        }
        return out;
    }

    /**
     * Forgets row tombstones written before {@code cutoffWallMillis}. A delete must have reached every
     * replica before its tombstone goes, or an older copy of the row could come back from a replica that
     * missed it; {@code Cluster} refuses to sync with a node that was away longer than the cutoff. Live
     * rows are not affected, nor the bucket sums, which do not count tombstones. A cluster node collects at
     * its {@link #expiry}, which leaves the full sums unchanged too.
     *
     * @return the number of tombstones removed
     */
    public int collectTombstones(long cutoffWallMillis) {
        if (!hasTombstonesBefore(cutoffWallMillis)) {
            return 0;
        }
        int n = 0;
        for (DbState d : dbs.values()) {
            for (TableState t : d.tables.values()) {
                // The rows whose versions are all deletes stamped before the cutoff, from the index.
                var it = t.dead.headSet(new Dead(cutoffWallMillis, ""), false).iterator();
                while (it.hasNext()) {
                    String id = it.next().id();
                    RowState r = t.rows.remove(id);
                    int b = bucket(id);
                    long w = r.weight(id);
                    t.full[b] -= r.counted(id, expiry);
                    t.bytes[b] -= w;
                    t.count[b]--;
                    t.weight -= w;
                    it.remove();
                    n++;
                }
            }
        }
        return n;
    }

    /** True if {@link #collectTombstones} would drop anything at {@code cutoffWallMillis}. O(tables). */
    public boolean hasTombstonesBefore(long cutoffWallMillis) {
        for (DbState d : dbs.values()) {
            for (TableState t : d.tables.values()) {
                if (!t.dead.isEmpty() && t.dead.first().wall() < cutoffWallMillis) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Estimated heap bytes of all rows and index entries held (see Weight). */
    public long weight() {
        long n = 0;
        for (DbState d : dbs.values()) {
            for (TableState t : d.tables.values()) {
                n += t.weight + t.dead.size() * Weight.DEAD_ENTRY;
                for (IndexState ix : t.indexes.values()) {
                    n += ix.weight();
                }
            }
        }
        return n;
    }

    /** Number of row tombstones held, for metrics. */
    public long tombstones() {
        long n = 0;
        for (DbState d : dbs.values()) {
            for (TableState t : d.tables.values()) {
                for (RowState r : t.rows.values()) {
                    if (!r.live()) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** True if applying {@code op} would change the state. Does not change anything itself. */
    public boolean isNew(Op op) {
        DbState d = dbs.get(op.db());
        if (op instanceof Op.CreateDb o) {
            return d == null || o.stamp().after(d.created);
        }
        if (op instanceof Op.DropDb o) {
            return d == null || o.stamp().after(d.dropped);
        }
        String table = op instanceof Op.CreateTable o ? o.table()
                : op instanceof Op.DropTable o ? o.table()
                : op instanceof Op.CreateIndex o ? o.table()
                : op instanceof Op.DropIndex o ? o.table()
                : ((Op.RowOp) op).table();
        TableState t = d == null ? null : d.tables.get(table);
        Stamp dropped = t != null ? t.dropped : d != null ? d.dropped : Stamp.ZERO;
        if (op instanceof Op.CreateIndex o) {
            IndexState ix = t == null ? null : t.indexes.get(o.column());
            return ix == null || o.stamp().after(ix.created);
        }
        if (op instanceof Op.DropIndex o) {
            IndexState ix = t == null ? null : t.indexes.get(o.column());
            return o.stamp().after(ix != null ? ix.dropped : dropped);
        }
        if (op instanceof Op.CreateTable o) {
            return t == null || o.stamp().after(t.created);
        }
        if (op instanceof Op.DropTable o) {
            return o.stamp().after(dropped);
        }
        String id = op instanceof Op.Put o ? o.id() : ((Op.Delete) op).id();
        if (!op.stamp().after(dropped)) {
            return false;
        }
        RowState r = t == null ? null : t.rows.get(id);
        return r == null || r.with(Version.of((Op.RowOp) op)) != r;
    }

    /**
     * {@code ops} (from a peer) without the deletes of rows that this node holds no state for and that the
     * batch has nothing but expired deletes for (see {@link #expiry}): such a row would be collected at once,
     * and applying it would report a delete of a row that is gone to the change feed. A row whose batch also
     * holds a value, or a newer delete, keeps all of them.
     */
    public List<Op> withoutExpiredDeletes(List<Op> ops) {
        java.util.Set<String> kept = new java.util.HashSet<>();
        boolean any = false;
        for (Op op : ops) {
            if (op instanceof Op.RowOp r) {
                if (r instanceof Op.Delete && r.stamp().wall() < expiry) {
                    any = true;
                } else {
                    kept.add(rowKey(r));
                }
            }
        }
        if (!any) {
            return ops;
        }
        List<Op> out = new ArrayList<>(ops.size());
        for (Op op : ops) {
            if (op instanceof Op.Delete del && del.stamp().wall() < expiry && !kept.contains(rowKey(del))
                    && !holds(del.db(), del.table(), del.id())) {
                continue;
            }
            out.add(op);
        }
        return out;
    }

    private static String rowKey(Op.RowOp r) {
        return r.db() + "\u0000" + r.table() + "\u0000" + r.id();
    }

    private boolean holds(String db, String table, String id) {
        DbState d = dbs.get(db);
        TableState t = d == null ? null : d.tables.get(table);
        return t != null && t.rows.containsKey(id);
    }

    /** The newest stamp applied so far; the clock must start after it. */
    public Stamp maxStamp() {
        return maxStamp;
    }

    public boolean dbExists(String db) {
        DbState d = dbs.get(db);
        return d != null && d.exists();
    }

    public boolean tableExists(String db, String table) {
        DbState d = dbs.get(db);
        if (d == null || !d.exists()) {
            return false;
        }
        TableState t = d.tables.get(table);
        return t != null && t.exists();
    }

    public List<String> databases() {
        List<String> out = new ArrayList<>();
        dbs.forEach((name, d) -> {
            if (d.exists()) {
                out.add(name);
            }
        });
        return out;
    }

    public List<String> tables(String db) {
        List<String> out = new ArrayList<>();
        DbState d = dbs.get(db);
        if (d != null && d.exists()) {
            d.tables.forEach((name, t) -> {
                if (t.exists()) {
                    out.add(name);
                }
            });
        }
        return out;
    }

    /** The row with this id, or null if absent, deleted, or its table does not exist. */
    public Map<String, Object> row(String db, String table, String id) {
        if (!tableExists(db, table)) {
            return null;
        }
        RowState r = dbs.get(db).tables.get(table).rows.get(id);
        return r == null ? null : r.value();
    }

    /** Visits the live rows of a table in id order. */
    public void forEachRow(String db, String table, BiConsumer<String, Map<String, Object>> visitor) {
        if (!tableExists(db, table)) {
            return;
        }
        dbs.get(db).tables.get(table).rows.forEach((id, r) -> {
            if (r.value() != null) {
                visitor.accept(id, r.value());
            }
        });
    }

    /** As {@link #forEachRow}, stopping at the first row for which {@code visitor} returns false. */
    public void forEachRowWhile(String db, String table, java.util.function.BiPredicate<String, Map<String, Object>> visitor) {
        if (!tableExists(db, table)) {
            return;
        }
        for (Map.Entry<String, RowState> e : dbs.get(db).tables.get(table).rows.entrySet()) {
            Map<String, Object> v = e.getValue().value();
            if (v != null && !visitor.test(e.getKey(), v)) {
                return;
            }
        }
    }

    /**
     * Visits, in id order, the rows of a table that hold more than one version: writes made on nodes that
     * had not seen each other's. The versions come newest first; the first is the row's value. Rows whose
     * versions are all deletes are left out: they agree on the outcome, and such tombstones need not spread.
     */
    public void forEachConflict(String db, String table, BiConsumer<String, List<Version>> visitor) {
        if (!tableExists(db, table)) {
            return;
        }
        dbs.get(db).tables.get(table).rows.forEach((id, r) -> {
            if (r.versions().size() > 1 && r.live()) {
                visitor.accept(id, r.versions());
            }
        });
    }

    /**
     * The indexes of a table, in name order, each by the {@code column} field of its ops: its column, or its
     * columns joined by commas ({@link Op#indexKey}).
     */
    public List<String> indexes(String db, String table) {
        List<String> out = new ArrayList<>();
        if (tableExists(db, table)) {
            dbs.get(db).tables.get(table).indexes.forEach((column, ix) -> {
                if (ix.exists()) {
                    out.add(column);
                }
            });
        }
        return out;
    }

    public boolean indexExists(String db, String table, String column) {
        return indexes(db, table).contains(column);
    }

    /**
     * The indexes of a table on several columns that lookups may use (2 to 4 distinct columns, none of them
     * {@code _id}; one a peer sent otherwise is kept but not used), in name order, each as its columns.
     */
    public List<List<String>> compositeIndexes(String db, String table) {
        List<List<String>> out = new ArrayList<>();
        if (tableExists(db, table)) {
            dbs.get(db).tables.get(table).indexes.forEach((key, ix) -> {
                if (ix.exists() && ix instanceof TupleIndex x && x.usable) {
                    out.add(List.of(x.columns));
                }
            });
        }
        return out;
    }

    /** The index of {@code column} (not {@code _id}) if it exists and answers {@code l}, else null. */
    private static IndexState answering(TableState t, String column, Lookup l) {
        IndexState ix = t.indexes.get(column);
        return ix != null && ix.exists() && ix.answers(l) ? ix : null;
    }

    /**
     * The ids, in id order, of the rows whose current value of {@code column} compares to {@code value} by
     * {@code op} ("=", "<", "<=", ">", ">="), from an index; null when the column has none (scan instead).
     * {@code _id} always has one. A caller still checks its whole condition on each row: the set is exact
     * for the one comparison, by the rules of docs/QUERY.md.
     */
    public java.util.SortedSet<String> indexLookup(String db, String table, String column, String op, Object value) {
        return indexLookup(db, table, column, new Lookup.Compare(op, value));
    }

    /** As above, for any {@link Lookup}. */
    public java.util.SortedSet<String> indexLookup(String db, String table, String column, Lookup l) {
        if (!tableExists(db, table)) {
            return null;
        }
        TableState t = dbs.get(db).tables.get(table);
        if (column.equals("_id")) {
            if (l instanceof Lookup.Composite) {
                return null;
            }
            java.util.TreeSet<String> out = new java.util.TreeSet<>();
            for (String id : idKeys(t, l)) {
                RowState r = t.rows.get(id);
                if (r != null && r.value() != null) {
                    out.add(id);
                }
            }
            return out;
        }
        IndexState ix = answering(t, column, l);
        return ix == null ? null : ix.lookup(l);
    }

    /**
     * The keys of a table's rows (deleted ones included, so a caller checks each) that may match {@code l} on
     * {@code _id}, in id order. {@code _id} is a string: other values never compare equal or ordered with it.
     */
    private static Iterable<String> idKeys(TableState t, Lookup l) {
        if (l instanceof Lookup.Compare c) {
            if (!(c.value() instanceof String v)) {
                return List.of();
            }
            java.util.NavigableMap<String, RowState> range = switch (c.op()) {
                case "=" -> t.rows.subMap(v, true, v, true);
                case "<" -> t.rows.headMap(v, false);
                case "<=" -> t.rows.headMap(v, true);
                case ">" -> t.rows.tailMap(v, false);
                case ">=" -> t.rows.tailMap(v, true);
                default -> throw new IllegalArgumentException("no index lookup for " + c.op());
            };
            return range.keySet();
        }
        if (l instanceof Lookup.AnyOf a) {
            java.util.TreeSet<String> out = new java.util.TreeSet<>();
            for (Object v : a.values()) {
                if (v instanceof String s) {
                    out.add(s);
                }
            }
            return out;
        }
        String p = ((Lookup.Prefix) l).prefix();
        String end = prefixEnd(p);
        return (end == null ? t.rows.tailMap(p, true) : t.rows.subMap(p, true, end, false)).keySet();
    }

    /**
     * A range lookup on an index that would gather more ids than this (for a union, its ranges together) is not
     * used: the caller reads another way.
     */
    public static final int MATERIALIZE_LIMIT = 100_000;

    /**
     * How many rows a lookup through an index would visit, at most; -1 when {@code column} has no index.
     * Exact for column indexes (summing the matching keys, no copying), an upper bound for {@code _id} ranges.
     */
    public long indexEstimate(String db, String table, String column, String op, Object value) {
        return indexEstimate(db, table, column, new Lookup.Compare(op, value));
    }

    /**
     * As above, for any {@link Lookup}. For {@code _id}: exact for equality; for a range or prefix the table's rows,
     * or, when {@code atMost} is below them, its ids counted up to {@code atMost}.
     */
    public long indexEstimate(String db, String table, String column, Lookup l) {
        return indexEstimate(db, table, column, l, Long.MAX_VALUE);
    }

    /** As above; once a count passes {@code atMost} it may stop there, returning a number above {@code atMost}. */
    public long indexEstimate(String db, String table, String column, Lookup l, long atMost) {
        if (!tableExists(db, table)) {
            return -1;
        }
        TableState t = dbs.get(db).tables.get(table);
        if (column.equals("_id")) {
            if (l instanceof Lookup.Composite) {
                return -1;
            }
            if (l instanceof Lookup.Compare c && c.op().equals("=")) {
                return 1;
            }
            if (l instanceof Lookup.AnyOf a) {
                return a.values().stream().filter(v -> v instanceof String).distinct().count();
            }
            // A range or prefix: the table's rows bound it. Below that, its ids are counted, up to atMost (review
            // 22g: an ordered read's budget is a share of what the read without order visits, not of the table).
            if (atMost >= t.rows.size()) {
                return t.rows.size();
            }
            long n = 0;
            for (String ignored : idKeys(t, l)) {
                if (++n > atMost) {
                    break;
                }
            }
            return n;
        }
        IndexState ix = answering(t, column, l);
        return ix == null ? -1 : ix.estimate(l, atMost);
    }

    /**
     * Visits, in id order, the live rows whose current value of {@code column} compares to {@code value} by
     * {@code op}, through an index, without copying the whole match first: {@code _id} ranges and column
     * equality stream; a column range is gathered only when it matches at most {@link #MATERIALIZE_LIMIT}
     * rows. Returns false, having visited nothing, when there is no index or the range is larger: scan instead.
     */
    public boolean forEachIndexed(String db, String table, String column, String op, Object value,
            BiConsumer<String, Map<String, Object>> visitor) {
        return forEachIndexed(db, table, column, new Lookup.Compare(op, value), visitor);
    }

    /**
     * As above, for any {@link Lookup}. The equal-value sets of {@link Lookup.AnyOf} are merged as they are
     * read; a {@link Lookup.Prefix} on a column is a range, gathered under the same limit.
     */
    public boolean forEachIndexed(String db, String table, String column, Lookup l,
            BiConsumer<String, Map<String, Object>> visitor) {
        Iterable<String> ids = indexedIds(db, table, column, l);
        if (ids == null) {
            return false;
        }
        TableState t = dbs.get(db).tables.get(table);
        for (String id : ids) {
            RowState r = t.rows.get(id);
            if (r != null && r.value() != null) {
                visitor.accept(id, r.value());
            }
        }
        return true;
    }

    /** As {@link #forEachIndexed(String, String, String, Lookup, BiConsumer)}, stopping when {@code visitor} returns false. */
    public boolean forEachIndexedWhile(String db, String table, String column, Lookup l,
            java.util.function.BiPredicate<String, Map<String, Object>> visitor) {
        Iterable<String> ids = indexedIds(db, table, column, l);
        if (ids == null) {
            return false;
        }
        TableState t = dbs.get(db).tables.get(table);
        for (String id : ids) {
            RowState r = t.rows.get(id);
            if (r != null && r.value() != null && !visitor.test(id, r.value())) {
                break;
            }
        }
        return true;
    }

    /** An ORDER BY key on a column of an index: the column's place among the index's columns, and the direction. */
    public record SortKey(int column, boolean descending) {}

    /** What {@link #forEachInOrder} did. */
    public enum Ordered {
        /** It visited rows until the visitor returned false, or every row it reads. */
        DONE,
        /** It stopped after {@code budget} steps; the caller drops what it was given and reads another way. */
        OVER_BUDGET,
        /** There is no such index, or it cannot read so; nothing was visited. */
        NO_INDEX
    }

    /**
     * Visits live rows through the index {@code index} in an ORDER BY order, without sorting: the rows whose
     * first {@code where.equal().size()} columns each equal one of their values, and whose next column satisfies
     * every lookup of {@code where.range()} (all rows when both are empty), ordered by {@code keys}, then by id
     * (descending if {@code idDescending}). Each key compares one column of the index as ORDER BY does (null and
     * missing first, then booleans, numbers, strings, objects and arrays), in its direction.
     *
     * <p>The keys on columns past the fixed ones must be those columns from the first of them on, in order and in
     * one direction; keys on fixed columns may come anywhere, in either direction. The index is read in the
     * order of the columns past the fixed ones, one stream per tuple of fixed values, and the streams are merged
     * by the keys; keys that tie (on columns the ORDER BY leaves out, or from two streams) are read as one group,
     * their ids merged.
     *
     * <p>Every row visited, every stream opened and every key of a group of several costs a step; after
     * {@code budget} steps it stops, and returns {@link Ordered#OVER_BUDGET}.
     *
     * @throws IllegalArgumentException if the keys are not as above
     */
    public Ordered forEachInOrder(String db, String table, String index, Lookup.Composite where, List<SortKey> keys,
            boolean idDescending, long budget, java.util.function.BiPredicate<String, Map<String, Object>> visitor) {
        if (!tableExists(db, table)) {
            return Ordered.NO_INDEX;
        }
        TableState t = dbs.get(db).tables.get(table);
        IndexState ix = t.indexes.get(index);
        if (ix == null || !ix.exists()) {
            return Ordered.NO_INDEX;
        }
        int fixed = where.equal().size();
        int next = fixed;
        Boolean descending = null;
        for (SortKey k : keys) {
            if (k.column() < 0 || k.column() >= ix.width()) {
                throw new IllegalArgumentException("no column " + k.column() + " in an index on " + ix.width());
            }
            if (k.column() >= fixed) {
                if (k.column() != next++ || descending != null && descending != k.descending()) {
                    throw new IllegalArgumentException("ORDER BY keys out of the index's order: " + keys);
                }
                descending = k.descending();
            }
        }
        List<java.util.Iterator<Entry>> streams = ix.streams(where, descending != null && descending);
        if (streams == null) {
            return Ordered.NO_INDEX;
        }
        Comparator<Object[]> order = (a, b) -> {
            for (SortKey k : keys) {
                int c = ScalarOrder.sortOrder(a[k.column()], b[k.column()]);
                if (c != 0) {
                    return k.descending() ? -c : c;
                }
            }
            return 0;
        };
        long steps = streams.size();
        java.util.PriorityQueue<Stream> heads = new java.util.PriorityQueue<>((x, y) -> order.compare(x.at.key(), y.at.key()));
        for (java.util.Iterator<Entry> s : streams) {
            if (s.hasNext()) {
                heads.add(new Stream(s.next(), s));
            }
        }
        List<java.util.NavigableSet<String>> group = new ArrayList<>();
        while (!heads.isEmpty()) {
            Stream first = heads.poll();
            Object[] key = first.at.key();
            group.clear();
            group.add(first.at.ids());
            first.advance(heads);
            while (!heads.isEmpty() && order.compare(heads.peek().at.key(), key) == 0) {
                if (++steps > budget) {
                    return Ordered.OVER_BUDGET;
                }
                Stream s = heads.poll();
                group.add(s.at.ids());
                s.advance(heads);
            }
            java.util.Iterator<String> ids = group.size() == 1
                    ? (idDescending ? group.get(0).descendingIterator() : group.get(0).iterator())
                    : mergedIds(group, idDescending);
            while (ids.hasNext()) {
                if (++steps > budget) {
                    return Ordered.OVER_BUDGET;
                }
                String id = ids.next();
                RowState r = t.rows.get(id);
                if (r != null && r.value() != null && !visitor.test(id, r.value())) {
                    return Ordered.DONE;
                }
            }
        }
        return Ordered.DONE;
    }

    /** One stream of an ordered read: the key it is at, and the rest. */
    private static final class Stream {
        Entry at;
        final java.util.Iterator<Entry> rest;

        Stream(Entry at, java.util.Iterator<Entry> rest) {
            this.at = at;
            this.rest = rest;
        }

        /** Moves to the next key and back into {@code heads}, unless there is none. */
        void advance(java.util.PriorityQueue<Stream> heads) {
            if (rest.hasNext()) {
                at = rest.next();
                heads.add(this);
            }
        }
    }

    /** The ids of disjoint sorted sets in one order, ascending or descending, read as they are iterated. */
    private static java.util.Iterator<String> mergedIds(List<java.util.NavigableSet<String>> sets, boolean descending) {
        Comparator<String> by = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
        java.util.PriorityQueue<Map.Entry<String, java.util.Iterator<String>>> heads =
                new java.util.PriorityQueue<>((x, y) -> by.compare(x.getKey(), y.getKey()));
        for (java.util.NavigableSet<String> s : sets) {
            java.util.Iterator<String> it = descending ? s.descendingIterator() : s.iterator();
            if (it.hasNext()) {
                heads.add(Map.entry(it.next(), it));
            }
        }
        return new java.util.Iterator<>() {
            @Override
            public boolean hasNext() {
                return !heads.isEmpty();
            }

            @Override
            public String next() {
                Map.Entry<String, java.util.Iterator<String>> h = heads.poll();
                if (h == null) {
                    throw new java.util.NoSuchElementException();
                }
                if (h.getValue().hasNext()) {
                    heads.add(Map.entry(h.getValue().next(), h.getValue()));
                }
                return h.getKey();
            }
        };
    }

    /** The keys to visit for {@code l} on {@code column}, in id order; null when the caller should scan. */
    private Iterable<String> indexedIds(String db, String table, String column, Lookup l) {
        if (!tableExists(db, table)) {
            return null;
        }
        TableState t = dbs.get(db).tables.get(table);
        if (column.equals("_id")) {
            return l instanceof Lookup.Composite ? null : idKeys(t, l);
        }
        IndexState ix = answering(t, column, l);
        return ix == null ? null : ix.ids(l, MATERIALIZE_LIMIT);
    }

    /** One read of a union ({@link #forEachUnionWhile}): what {@code lookup} finds in the index {@code column}, or in _id. */
    public record IndexRead(String column, Lookup lookup) {
        public IndexRead {
            java.util.Objects.requireNonNull(column, "column");
            java.util.Objects.requireNonNull(lookup, "lookup");
        }
    }

    /**
     * Visits, in id order and each once, the live rows that any of {@code reads} finds, until {@code visitor}
     * returns false: the reads' ids are merged as they are read, each read as {@link #forEachIndexed} reads it.
     * The ranges that must be copied to be in id order hold at most {@link #MATERIALIZE_LIMIT} ids in all, as
     * those of one read do. Returns false, having visited nothing, when a read has no index or the ranges are
     * larger: read another way.
     */
    public boolean forEachUnionWhile(String db, String table, List<IndexRead> reads,
            java.util.function.BiPredicate<String, Map<String, Object>> visitor) {
        if (!tableExists(db, table)) {
            return false;
        }
        TableState t = dbs.get(db).tables.get(table);
        List<Iterable<String>> parts = new ArrayList<>(reads.size());
        long left = MATERIALIZE_LIMIT;
        for (IndexRead read : reads) {
            Iterable<String> ids;
            if (read.column().equals("_id")) {
                if (read.lookup() instanceof Lookup.Composite) {
                    return false;
                }
                ids = idKeys(t, read.lookup());
            } else {
                IndexState ix = answering(t, read.column(), read.lookup());
                ids = ix == null ? null : ix.ids(read.lookup(), left);
                if (ids instanceof Gathered g) {
                    left -= g.size();
                }
            }
            if (ids == null) {
                return false;
            }
            parts.add(ids);
        }
        for (String id : parts.size() == 1 ? parts.get(0) : merged(parts)) {
            RowState r = t.rows.get(id);
            if (r != null && r.value() != null && !visitor.test(id, r.value())) {
                break;
            }
        }
        return true;
    }

    /** Number of rows, over every table, that hold more than one version; for metrics. */
    public long conflicts() {
        long n = 0;
        for (DbState d : dbs.values()) {
            for (TableState t : d.tables.values()) {
                for (RowState r : t.rows.values()) {
                    if (r.versions().size() > 1 && r.live()) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    public long rowCount(String db, String table) {
        long[] n = {0};
        forEachRow(db, table, (id, row) -> n[0]++);
        return n[0];
    }

    /**
     * The rows {@link #forEachRow} visits, from a count kept with every change: what {@code SELECT COUNT(*)}
     * without WHERE answers, without visiting the rows. 0 for a table that does not exist.
     */
    public long visibleRows(String db, String table) {
        if (!tableExists(db, table)) {
            return 0;
        }
        return dbs.get(db).tables.get(table).visible;
    }

    /** The whole state as ops; applying them to an empty store rebuilds it exactly. */
    public List<Op> toOps() {
        List<Op> out = new ArrayList<>();
        forEachOp(out::add);
        return out;
    }

    public List<Op> toOps(String db) {
        List<Op> out = new ArrayList<>();
        if (dbs.containsKey(db)) {
            appendOps(db, out::add);
        }
        return out;
    }

    /**
     * Hands every op of {@link #toOps()} to {@code sink}, one at a time, without building the list: a
     * checkpoint or backup of a store near its memory limit must not need as much memory again.
     */
    public void forEachOp(java.util.function.Consumer<Op> sink) {
        for (String db : dbs.keySet()) {
            appendOps(db, sink);
        }
    }

    /** The index definitions alone (what {@link Op#indexOnly} keeps of {@link #toOps()}), without the rows. */
    public void forEachIndexOp(java.util.function.Consumer<Op> sink) {
        dbs.forEach((db, d) -> d.tables.forEach((name, t) -> indexOps(db, name, t, sink)));
    }

    private void appendOps(String db, java.util.function.Consumer<Op> out) {
        DbState d = dbs.get(db);
        if (d.created.after(Stamp.ZERO)) {
            out.accept(new Op.CreateDb(db, d.created));
        }
        if (d.dropped.after(Stamp.ZERO)) {
            out.accept(new Op.DropDb(db, d.dropped));
        }
        d.tables.forEach((name, t) -> {
            if (t.created.after(Stamp.ZERO)) {
                out.accept(new Op.CreateTable(db, name, t.created));
            }
            if (t.dropped.after(d.dropped)) {
                out.accept(new Op.DropTable(db, name, t.dropped));
            }
            indexOps(db, name, t, out);
            t.rows.forEach((id, r) -> {
                for (Version v : r.versions()) {
                    out.accept(v.toOp(db, name, id));
                }
            });
        });
    }

    /**
     * Every schema op (create and drop stamps never expire, so old ones are harmless), and the row versions
     * stamped after {@code wallMillis}: what a stale node may hand on (see Cluster.pushIsolatedWrites).
     */
    public List<Op> opsSince(long wallMillis) {
        List<Op> out = new ArrayList<>();
        forEachOp(op -> {
            if (!(op instanceof Op.RowOp) || op.stamp().wall() > wallMillis) {
                out.add(op);
            }
        });
        return out;
    }

    /** All database names known, including dropped ones (their tombstones still replicate). */
    public List<String> allDatabaseNames() {
        return new ArrayList<>(dbs.keySet());
    }

    /** SHA-256 of each database's full state, for comparing replicas. */
    public Map<String, String> digests() {
        Map<String, String> out = new TreeMap<>();
        for (String db : dbs.keySet()) {
            out.put(db, digest(db));
        }
        return out;
    }

    /**
     * The database as a 2.0 node holds it: its schema without index ops, and for each row only its current
     * version (2.0 keeps one per row), without causal contexts. The 2.0 routes serve this and
     * {@link #digest} hashes it, so a 2.0 peer and this node agree once they hold the same data.
     */
    public List<Op> legacyOps(String db) {
        List<Op> out = new ArrayList<>();
        DbState d = dbs.get(db);
        if (d == null) {
            return out;
        }
        // In the order 2.0 writes them (its toOps), since the digest hashes the sequence.
        if (d.created.after(Stamp.ZERO)) {
            out.add(new Op.CreateDb(db, d.created));
        }
        if (d.dropped.after(Stamp.ZERO)) {
            out.add(new Op.DropDb(db, d.dropped));
        }
        d.tables.forEach((name, t) -> {
            if (t.created.after(Stamp.ZERO)) {
                out.add(new Op.CreateTable(db, name, t.created));
            }
            if (t.dropped.after(d.dropped)) {
                out.add(new Op.DropTable(db, name, t.dropped));
            }
            t.rows.forEach((id, r) -> {
                Version w = r.winner();
                out.add(new Version(w.value(), w.stamp(), null).toOp(db, name, id));
            });
        });
        return out;
    }

    public String digest(String db) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Op op : legacyOps(db)) {
                md.update(Json.write(Op.toLegacyJson(op)).getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
