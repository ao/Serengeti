package com.ataiva.serengeti.core.storage;

import com.ataiva.serengeti.core.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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

        /** Indexes by column, dropped ones included (their stamps still replicate). */
        final TreeMap<String, IndexState> indexes = new TreeMap<>();

        IndexState index(String column) {
            return indexes.computeIfAbsent(column, c -> new IndexState(c, dropped));
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
     * A secondary index on one column: for each boolean, number and string value, the ids of the rows whose
     * current value holds it. Like a table it exists while its newest create is newer than its newest drop
     * (or its table's); its entries are rebuilt from the rows when it comes into existence, and only its
     * create and drop stamps replicate.
     */
    private static final class IndexState {
        final String column;
        Stamp created = Stamp.ZERO;
        Stamp dropped;
        final TreeMap<Boolean, java.util.TreeSet<String>> bools = new TreeMap<>();
        final TreeMap<Number, java.util.TreeSet<String>> numbers = new TreeMap<>(ScalarOrder::compareNumbers);
        final TreeMap<String, java.util.TreeSet<String>> strings = new TreeMap<>();
        /** Ids held across all keys, and distinct keys, for the weight estimate. */
        long entries;
        long keys;

        IndexState(String column, Stamp dropped) {
            this.column = column;
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
                bools.clear();
                numbers.clear();
                strings.clear();
                entries = 0;
                keys = 0;
            }
        }

        @SuppressWarnings("unchecked")
        <K> TreeMap<K, java.util.TreeSet<String>> mapFor(Object v) {
            return (TreeMap<K, java.util.TreeSet<String>>) (v instanceof Boolean ? bools
                    : v instanceof Number ? numbers : v instanceof String ? strings : null);
        }

        void add(String id, Map<String, Object> row) {
            Object v = row == null ? null : row.get(column);
            TreeMap<Object, java.util.TreeSet<String>> m = mapFor(v);
            if (m == null) {
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

        void remove(String id, Map<String, Object> row) {
            Object v = row == null ? null : row.get(column);
            TreeMap<Object, java.util.TreeSet<String>> m = mapFor(v);
            if (m == null) {
                return;
            }
            java.util.TreeSet<String> ids = m.get(v);
            if (ids != null && ids.remove(id)) {
                entries--;
                if (ids.isEmpty()) {
                    m.remove(v);
                    keys--;
                }
            }
        }

        void move(String id, Map<String, Object> was, Map<String, Object> now) {
            remove(id, was);
            add(id, now);
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

        long estimate(String op, Object v) {
            return estimate(new Lookup.Compare(op, v));
        }

        long estimate(Lookup l) {
            long n = 0;
            for (java.util.TreeSet<String> s : sets(l)) {
                n += s.size();
            }
            return n;
        }

        Iterable<String> ids(String op, Object v) {
            return ids(new Lookup.Compare(op, v));
        }

        /**
         * The ids in id order: one key's live set; the sets of several equal-value keys merged as they are
         * read; a range gathered if small enough, else null.
         */
        Iterable<String> ids(Lookup l) {
            java.util.Collection<java.util.TreeSet<String>> sets = sets(l);
            if (sets.size() <= 1) {
                return sets.isEmpty() ? List.of() : sets.iterator().next();
            }
            if (l instanceof Lookup.AnyOf) {
                return merged(sets);
            }
            if (estimate(l) > MATERIALIZE_LIMIT) {
                return null;
            }
            java.util.TreeSet<String> out = new java.util.TreeSet<>();
            for (java.util.TreeSet<String> s : sets) {
                out.addAll(s);
            }
            return out;
        }

        /** The ids whose value compares to {@code v} by {@code op} ("=", "<", "<=", ">", ">="), in id order. */
        java.util.SortedSet<String> lookup(String op, Object v) {
            java.util.TreeSet<String> out = new java.util.TreeSet<>();
            TreeMap<Object, java.util.TreeSet<String>> m = mapFor(v);
            if (m == null) {
                return out; // a comparison with null, or with a value of no indexed type, is never true
            }
            java.util.Collection<java.util.TreeSet<String>> sets = switch (op) {
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
            for (java.util.TreeSet<String> s : sets) {
                out.addAll(s);
            }
            return out;
        }

        java.util.SortedSet<String> lookup(Lookup l) {
            if (l instanceof Lookup.Compare c) {
                return lookup(c.op(), c.value());
            }
            java.util.TreeSet<String> out = new java.util.TreeSet<>();
            for (java.util.TreeSet<String> s : sets(l)) {
                out.addAll(s);
            }
            return out;
        }
    }

    /**
     * What an index lookup finds in one column: the values that compare to {@code value} by {@code op} ("=",
     * "<", "<=", ">", ">="), the values equal to any of {@code values}, or the strings that start with
     * {@code prefix}. Each is exact by the rules of docs/QUERY.md: null, and values of no indexed type, match
     * nothing.
     */
    public sealed interface Lookup {
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

    /** The union of sorted sets, in order and without repeats, read as it is iterated (nothing is copied). */
    static Iterable<String> merged(java.util.Collection<? extends java.util.SortedSet<String>> sets) {
        return () -> new java.util.Iterator<>() {
            private final java.util.PriorityQueue<Head> heads = new java.util.PriorityQueue<>();
            private String last;

            {
                for (java.util.SortedSet<String> s : sets) {
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

    /** Estimated heap bytes of all rows and index entries held (see Weight). */
    public long weight() {
        long n = 0;
        for (DbState d : dbs.values()) {
            for (TableState t : d.tables.values()) {
                n += t.weight;
                for (IndexState ix : t.indexes.values()) {
                    n += ix.entries * Weight.INDEX_ENTRY + ix.keys * Weight.INDEX_KEY;
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

    /** The columns of a table that have an index, in name order. */
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
            java.util.TreeSet<String> out = new java.util.TreeSet<>();
            for (String id : idKeys(t, l)) {
                RowState r = t.rows.get(id);
                if (r != null && r.value() != null) {
                    out.add(id);
                }
            }
            return out;
        }
        IndexState ix = t.indexes.get(column);
        return ix == null || !ix.exists() ? null : ix.lookup(l);
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

    /** A range lookup on a column index that would match more ids than this is not used: the caller scans. */
    public static final int MATERIALIZE_LIMIT = 100_000;

    /**
     * How many rows a lookup through an index would visit, at most; -1 when {@code column} has no index.
     * Exact for column indexes (summing the matching keys, no copying), an upper bound for {@code _id} ranges.
     */
    public long indexEstimate(String db, String table, String column, String op, Object value) {
        return indexEstimate(db, table, column, new Lookup.Compare(op, value));
    }

    /** As above, for any {@link Lookup}; for {@code _id}, exact for equality and an upper bound otherwise. */
    public long indexEstimate(String db, String table, String column, Lookup l) {
        if (!tableExists(db, table)) {
            return -1;
        }
        TableState t = dbs.get(db).tables.get(table);
        if (column.equals("_id")) {
            if (l instanceof Lookup.Compare c) {
                return c.op().equals("=") ? 1 : t.rows.size();
            }
            if (l instanceof Lookup.AnyOf a) {
                return a.values().stream().filter(v -> v instanceof String).distinct().count();
            }
            return t.rows.size();
        }
        IndexState ix = t.indexes.get(column);
        return ix == null || !ix.exists() ? -1 : ix.estimate(l);
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

    /** The keys to visit for {@code l} on {@code column}, in id order; null when the caller should scan. */
    private Iterable<String> indexedIds(String db, String table, String column, Lookup l) {
        if (!tableExists(db, table)) {
            return null;
        }
        TableState t = dbs.get(db).tables.get(table);
        if (column.equals("_id")) {
            return idKeys(t, l);
        }
        IndexState ix = t.indexes.get(column);
        return ix == null || !ix.exists() ? null : ix.ids(l);
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
