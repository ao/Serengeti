package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeSet;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * A lookup through an index on several columns must give exactly the rows a scan matches for it (each of the
 * first columns equal to one of its values, and every range on the next column true), whatever the mix of
 * value types, nulls and missing values, and whatever order the writes, deletes, drops and index changes
 * arrived in; and its entries count in the weight as a fresh build counts them.
 */
class CompositeIndexProperties {
    static final String TOP = String.valueOf(Character.MAX_VALUE);
    /** Stored values: every type, equal numbers of both kinds, strings around prefixes; null stands for missing. */
    static final List<Object> STORED = Arrays.asList(null, null, 1L, 2L, 1.0, 2.5, -1L, 9007199254740993L,
            9007199254740992.0, -0.0, 0L, "a", "ab", "b", "", "a" + TOP, TOP, true, false, Map.of("x", 1L), List.of(1L));
    /** Literals of lookups: as the parser makes them, with null. */
    static final List<Object> LITERALS = Arrays.asList(1L, 2L, 1.0, 2.5, 0L, -0.0, 9007199254740993L,
            9007199254740992.0, "a", "ab", "b", "", TOP, true, false, null);
    static final List<String> RANGE_OPS = List.of("<", "<=", ">", ">=", "=");
    static final List<String> PREFIXES = List.of("", "a", "b", TOP, "a" + TOP, "x");
    static final List<String> KEYS = List.of("c,d", "d,c", "c,d,e", "e,c", "c,d,e,f");

    /** The query language's {@code v op lit}: true only for comparable values, never for null. */
    static boolean holds(Object v, String op, Object lit) {
        return IndexProperties.holds(v, op, lit);
    }

    static boolean matches(Map<String, Object> row, List<String> columns, Store.Lookup.Composite l) {
        for (int i = 0; i < l.equal().size(); i++) {
            Object v = row.get(columns.get(i));
            if (l.equal().get(i).stream().noneMatch(x -> holds(v, "=", x))) {
                return false;
            }
        }
        Object v = l.range().isEmpty() ? null : row.get(columns.get(l.equal().size()));
        for (Store.Lookup r : l.range()) {
            boolean hit = r instanceof Store.Lookup.Prefix p ? v instanceof String s && s.startsWith(p.prefix())
                    : holds(v, ((Store.Lookup.Compare) r).op(), ((Store.Lookup.Compare) r).value());
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    /** Lookups for an index on {@code n} columns: a fixed spread, and some drawn from {@code rnd}. */
    static List<Store.Lookup.Composite> lookups(int n, Random rnd) {
        List<Store.Lookup.Composite> out = new ArrayList<>();
        for (Object a : LITERALS) {
            out.add(new Store.Lookup.Composite(List.of(Collections.singletonList(a)), List.of()));
        }
        for (String op : RANGE_OPS) {
            for (Object b : LITERALS) {
                out.add(new Store.Lookup.Composite(List.of(List.of(1L)), List.of(new Store.Lookup.Compare(op, b))));
                out.add(new Store.Lookup.Composite(List.of(), List.of(new Store.Lookup.Compare(op, b))));
            }
        }
        for (String p : PREFIXES) {
            out.add(new Store.Lookup.Composite(List.of(List.of("a")), List.of(new Store.Lookup.Prefix(p))));
            out.add(new Store.Lookup.Composite(List.of(), List.of(new Store.Lookup.Prefix(p))));
        }
        for (int i = 0; i < 60; i++) {
            int k = rnd.nextInt(n + 1);
            List<List<Object>> equal = new ArrayList<>();
            for (int j = 0; j < k; j++) {
                List<Object> values = new ArrayList<>();
                for (int m = 1 + rnd.nextInt(3); m > 0; m--) {
                    values.add(LITERALS.get(rnd.nextInt(LITERALS.size())));
                }
                equal.add(values);
            }
            List<Store.Lookup> range = new ArrayList<>();
            if (k < n) {
                for (int m = rnd.nextInt(3); m > 0; m--) {
                    range.add(rnd.nextInt(4) == 0 ? new Store.Lookup.Prefix(PREFIXES.get(rnd.nextInt(PREFIXES.size())))
                            : new Store.Lookup.Compare(RANGE_OPS.get(rnd.nextInt(RANGE_OPS.size())),
                                    LITERALS.get(rnd.nextInt(LITERALS.size()))));
                }
            }
            if (k + (range.isEmpty() ? 0 : 1) > 0) {
                out.add(new Store.Lookup.Composite(equal, range));
            }
        }
        return out;
    }

    static void checkEveryLookup(Store s, long seed) {
        Random rnd = new Random(seed);
        List<List<String>> composite = s.compositeIndexes("a", "t");
        for (String key : KEYS) {
            List<String> columns = List.of(key.split(","));
            boolean exists = s.indexes("a", "t").contains(key);
            assertEquals(exists, composite.contains(columns), key);
            for (Store.Lookup.Composite l : lookups(columns.size(), rnd)) {
                SortedSet<String> got = s.indexLookup("a", "t", key, l);
                List<String> streamed = new ArrayList<>();
                boolean used = s.forEachIndexed("a", "t", key, l, (id, row) -> streamed.add(id));
                if (!exists) {
                    assertEquals(null, got, key);
                    assertEquals(false, used, "no index, so the executor scans");
                    assertEquals(-1L, s.indexEstimate("a", "t", key, l));
                    continue;
                }
                SortedSet<String> want = new TreeSet<>();
                s.forEachRow("a", "t", (id, row) -> {
                    if (matches(row, columns, l)) {
                        want.add(id);
                    }
                });
                assertEquals(want, got, key + " " + l);
                assertEquals(true, used);
                assertEquals(new ArrayList<>(got), streamed, "streamed in id order, once each: " + key + " " + l);
                assertEquals(got.size(), s.indexEstimate("a", "t", key, l), "the estimate is exact: " + l);
                List<String> firstTwo = new ArrayList<>();
                s.forEachIndexedWhile("a", "t", key, l, (id, row) -> firstTwo.add(id) && firstTwo.size() < 2);
                assertEquals(streamed.subList(0, Math.min(2, streamed.size())), firstTwo, "stops when told: " + l);
            }
            // A lookup that names more columns than the index has, or none, is not one it answers.
            List<List<Object>> tooMany = new ArrayList<>();
            for (int i = 0; i <= columns.size(); i++) {
                tooMany.add(List.of(1L));
            }
            assertEquals(-1L, s.indexEstimate("a", "t", key, new Store.Lookup.Composite(tooMany, List.of())));
            assertEquals(-1L, s.indexEstimate("a", "t", key, new Store.Lookup.Composite(List.of(), List.of())));
            assertEquals(-1L, s.indexEstimate("a", "t", key, new Store.Lookup.Compare("=", 1L)), "not a column's lookup");
        }
        assertEquals(-1L, s.indexEstimate("a", "t", "_id", new Store.Lookup.Composite(List.of(List.of("1")), List.of())));
    }

    @Property(tries = 400)
    void aLookupGivesExactlyWhatAScanMatches(@ForAll("ops") List<Op> ops, @ForAll long seed) {
        Store s = new Store();
        s.applyAll(ops);
        checkEveryLookup(s, seed);
    }

    @Property(tries = 300)
    void lookupsAreTheSameInAnyOrder(@ForAll("ops") List<Op> ops, @ForAll long seed) {
        Store a = new Store();
        a.applyAll(ops);
        List<Op> shuffled = new ArrayList<>(ops);
        Collections.shuffle(shuffled, new Random(seed));
        Store b = new Store();
        b.applyAll(shuffled);
        assertEquals(a.toOps(), b.toOps());
        assertEquals(a.indexes("a", "t"), b.indexes("a", "t"));
        assertEquals(a.schemaHash("a"), b.schemaHash("a"));
        assertEquals(a.weight(), b.weight());
        checkEveryLookup(b, seed);
        for (String key : a.indexes("a", "t")) {
            if (key.contains(",")) {
                for (Store.Lookup.Composite l : lookups(key.split(",").length, new Random(seed))) {
                    assertEquals(a.indexLookup("a", "t", key, l), b.indexLookup("a", "t", key, l));
                }
            }
        }
    }

    @Property(tries = 300)
    void aRebuiltStoreHasTheSameIndexesAndWeight(@ForAll("ops") List<Op> ops, @ForAll long seed) {
        Store a = new Store();
        a.applyAll(ops);
        Store b = new Store();
        b.applyAll(a.toOps());
        assertEquals(a.indexes("a", "t"), b.indexes("a", "t"));
        assertEquals(a.schemaHash("a"), b.schemaHash("a"));
        assertEquals(a.weight(), b.weight(), "the weight estimate never drifts from a fresh count");
        // The weight is that of the rows plus each index's entries and keys, counted from the rows.
        Store bare = new Store();
        for (Op op : a.toOps()) {
            if (Op.legacy(op)) {
                bare.apply(op);
            }
        }
        long want = bare.weight();
        for (String key : a.indexes("a", "t")) {
            List<String> columns = List.of(key.split(",", -1));
            java.util.Map<List<Object>, Integer> keys = new java.util.HashMap<>();
            long[] entries = {0};
            // Every live row is an entry (an index on one column keeps those without a value apart, keyless).
            a.forEachRow("a", "t", (id, row) -> {
                entries[0]++;
                if (columns.size() == 1) {
                    Object v = row.get(columns.get(0));
                    if (ScalarOrder.scalar(v)) {
                        keys.merge(Arrays.asList(normal(v)), 1, Integer::sum);
                    }
                } else {
                    List<Object> k = new ArrayList<>();
                    for (String c : columns) {
                        k.add(normal(row.get(c)));
                    }
                    keys.merge(k, 1, Integer::sum);
                }
            });
            want += entries[0] * Weight.INDEX_ENTRY
                    + keys.size() * (columns.size() == 1 ? Weight.INDEX_KEY : Weight.ofTupleKey(columns.size()));
        }
        if (a.tableExists("a", "t")) { // else indexes on rows written after the drop are held but not listed
            assertEquals(want, a.weight(), "every index entry and key is counted");
        }
        a.collectTombstones(Long.MAX_VALUE);
        Store c = new Store();
        c.applyAll(a.toOps());
        assertEquals(c.weight(), a.weight(), "nor after tombstones are collected");
        checkEveryLookup(b, seed);
    }

    private static final Object OTHER = new Object();

    /** A value as one key of the ORDER BY order: numbers by exact value (1 and 1.0 alike), objects and arrays all one. */
    private static Object normal(Object v) {
        if (v instanceof Number n) {
            java.math.BigDecimal d = n instanceof Long l ? java.math.BigDecimal.valueOf(l)
                    : new java.math.BigDecimal(n.doubleValue());
            return d.signum() == 0 ? java.math.BigDecimal.ZERO : d.stripTrailingZeros();
        }
        return v == null || ScalarOrder.scalar(v) ? v : OTHER;
    }

    @Provide
    Arbitrary<List<Op>> ops() {
        Arbitrary<Stamp> stamp = Combinators.combine(Arbitraries.longs().between(3, 60),
                Arbitraries.of("n1", "n2", "n3")).as((w, n) -> new Stamp(w, 0, n));
        Arbitrary<String> id = Arbitraries.of("1", "2", "3", "4", "5", "6", "7", "8");
        Arbitrary<Object> value = Arbitraries.of(STORED).injectNull(0.1);
        Arbitrary<Map<String, Object>> row = Combinators.combine(value, value, value, value).as((c, d, e, f) -> {
            Map<String, Object> m = new java.util.HashMap<>();
            Object[] vs = {c, d, e, f};
            String[] names = {"c", "d", "e", "f"};
            for (int i = 0; i < 4; i++) {
                if (vs[i] != null) {
                    m.put(names[i], vs[i]);
                } else if (i % 2 == 0) {
                    m.put(names[i], null); // an explicit null, which reads as missing
                }
            }
            return m;
        });
        Arbitrary<String> key = Arbitraries.of(KEYS);
        Arbitrary<Op> op = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(8, Combinators.combine(id, row, stamp).as((i, r, s) -> (Op) new Op.Put("a", "t", i, r, s))),
                net.jqwik.api.Tuple.of(2, Combinators.combine(id, stamp).as((i, s) -> (Op) new Op.Delete("a", "t", i, s))),
                net.jqwik.api.Tuple.of(3, Combinators.combine(key, stamp).as((k, s) -> (Op) new Op.CreateIndex("a", "t", k, s))),
                net.jqwik.api.Tuple.of(1, stamp.map(s -> (Op) new Op.CreateIndex("a", "t", "c", s))),
                net.jqwik.api.Tuple.of(1, Combinators.combine(key, stamp).as((k, s) -> (Op) new Op.DropIndex("a", "t", k, s))),
                net.jqwik.api.Tuple.of(1, stamp.map(s -> (Op) new Op.DropTable("a", "t", s))),
                net.jqwik.api.Tuple.of(1, stamp.map(s -> (Op) new Op.CreateTable("a", "t", s))));
        return op.list().ofMaxSize(40).map(list -> {
            List<Op> out = new ArrayList<>();
            out.add(new Op.CreateDb("a", new Stamp(1, 0, "n1")));
            out.add(new Op.CreateTable("a", "t", new Stamp(2, 0, "n1")));
            for (int i = 0; i < list.size(); i++) {
                out.add(withCounter(list.get(i), i + 1)); // real stamps are unique
            }
            return out;
        });
    }

    private static Op withCounter(Op o, int counter) {
        Stamp s = new Stamp(o.stamp().wall(), counter, o.stamp().node());
        if (o instanceof Op.Put p) {
            return new Op.Put(p.db(), p.table(), p.id(), p.row(), s);
        }
        if (o instanceof Op.Delete d) {
            return new Op.Delete(d.db(), d.table(), d.id(), s);
        }
        if (o instanceof Op.CreateIndex c) {
            return new Op.CreateIndex(c.db(), c.table(), c.column(), s);
        }
        if (o instanceof Op.DropIndex d) {
            return new Op.DropIndex(d.db(), d.table(), d.column(), s);
        }
        if (o instanceof Op.DropTable d) {
            return new Op.DropTable(d.db(), d.table(), s);
        }
        return new Op.CreateTable(o.db(), ((Op.CreateTable) o).table(), s);
    }
}
