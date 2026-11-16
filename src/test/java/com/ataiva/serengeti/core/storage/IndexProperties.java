package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
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
 * An index lookup must give exactly the rows a full scan would match for that one comparison, whatever
 * the mix of value types, and whatever order the writes, deletes, drops and index changes arrived in.
 */
class IndexProperties {
    static final List<Object> VALUES = List.of(1L, 2L, 3L, 1.0, 2.5, -1L, 9007199254740993L, 9007199254740992.0,
            "a", "b", "B", "", true, false, Map.of("x", 1L), List.of(1L), "ab", "a" + Character.MAX_VALUE,
            String.valueOf(Character.MAX_VALUE));
    static final List<String> OPS = List.of("=", "<", "<=", ">", ">=");
    /** U+FFFF, the last UTF-16 code unit: a prefix that ends in it has no next string to stop at. */
    static final String TOP = String.valueOf(Character.MAX_VALUE);

    /** What the query language says for {@code v op lit}: true only for comparable, non-null values. */
    static boolean holds(Object v, String op, Object lit) {
        if (v == null || lit == null) {
            return false;
        }
        Integer c = ScalarOrder.order(v, lit);
        if (c == null) {
            return false;
        }
        return switch (op) {
            case "=" -> c == 0;
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            default -> c >= 0;
        };
    }

    static SortedSet<String> scan(Store s, String column, String op, Object lit) {
        SortedSet<String> out = new TreeSet<>();
        s.forEachRow("a", "t", (id, row) -> {
            Object v = column.equals("_id") ? id : row.get(column);
            if (holds(v, op, lit)) {
                out.add(id);
            }
        });
        return out;
    }

    static void checkEveryLookup(Store s) {
        for (String column : List.of("c", "_id")) {
            for (String op : OPS) {
                for (Object lit : column.equals("_id") ? List.<Object>of("1", "2", "0", "9", 1L) : VALUES) {
                    SortedSet<String> got = s.indexLookup("a", "t", column, op, lit);
                    List<String> streamed = new ArrayList<>();
                    boolean used = s.forEachIndexed("a", "t", column, op, lit, (id, row) -> streamed.add(id));
                    if (got == null) {
                        assertEquals(false, used, "no index, so the executor scans");
                        assertEquals(-1L, s.indexEstimate("a", "t", column, op, lit));
                        continue;
                    }
                    assertEquals(scan(s, column, op, lit), got, column + " " + op + " " + lit);
                    assertEquals(true, used);
                    assertEquals(new ArrayList<>(got), streamed, "streamed in id order: " + column + " " + op + " " + lit);
                    assertEquals(true, s.indexEstimate("a", "t", column, op, lit) >= got.size(), "estimate is an upper bound");
                }
            }
            // Any of several values: the union of the = lookups. A prefix: the strings that start with it.
            List<Store.Lookup> more = new ArrayList<>();
            more.add(new Store.Lookup.AnyOf(java.util.Arrays.asList(1L, 1.0, "a", null, true)));
            more.add(new Store.Lookup.AnyOf(List.of("1", "9", "2", "1", 3L)));
            more.add(new Store.Lookup.AnyOf(VALUES.subList(0, 8)));
            for (String p : List.of("", "a", "B", "1", "x", TOP, "a" + TOP)) {
                more.add(new Store.Lookup.Prefix(p));
            }
            for (Store.Lookup l : more) {
                SortedSet<String> got = s.indexLookup("a", "t", column, l);
                List<String> streamed = new ArrayList<>();
                boolean used = s.forEachIndexed("a", "t", column, l, (id, row) -> streamed.add(id));
                if (got == null) {
                    assertEquals(false, used);
                    continue;
                }
                SortedSet<String> want = new TreeSet<>();
                s.forEachRow("a", "t", (id, row) -> {
                    Object v = column.equals("_id") ? id : row.get(column);
                    boolean hit = l instanceof Store.Lookup.Prefix p
                            ? v instanceof String str && str.startsWith(p.prefix())
                            : ((Store.Lookup.AnyOf) l).values().stream().anyMatch(x -> holds(v, "=", x));
                    if (hit) {
                        want.add(id);
                    }
                });
                assertEquals(want, got, column + " " + l);
                assertEquals(true, used);
                assertEquals(new ArrayList<>(got), streamed, "in id order, once each: " + column + " " + l);
                assertEquals(true, s.indexEstimate("a", "t", column, l) >= got.size(), "an upper bound: " + l);
                List<String> firstTwo = new ArrayList<>();
                s.forEachIndexedWhile("a", "t", column, l, (id, row) -> firstTwo.add(id) && firstTwo.size() < 2);
                assertEquals(streamed.subList(0, Math.min(2, streamed.size())), firstTwo, "stops when told: " + l);
            }
        }
    }

    @Property(tries = 200)
    void prefixEndBoundsExactlyTheStringsWithThePrefix(@ForAll("prefixy") String p, @ForAll("prefixy") String s) {
        String end = Store.prefixEnd(p);
        boolean inRange = s.compareTo(p) >= 0 && (end == null || s.compareTo(end) < 0);
        assertEquals(s.startsWith(p), inRange, p + " / " + s + " / " + end);
    }

    @Provide
    Arbitrary<String> prefixy() {
        return Arbitraries.of("a", "b", TOP, String.valueOf((char) (Character.MAX_VALUE - 1)), "", "z").list()
                .ofMaxSize(4).map(l -> String.join("", l));
    }

    @Property(tries = 300)
    void mergedSetsGiveTheirSortedUnion(@ForAll("idSets") List<List<String>> sets) {
        List<SortedSet<String>> sorted = new ArrayList<>();
        SortedSet<String> union = new TreeSet<>();
        for (List<String> l : sets) {
            sorted.add(new TreeSet<>(l));
            union.addAll(l);
        }
        List<String> got = new ArrayList<>();
        Store.merged(sorted).forEach(got::add);
        assertEquals(new ArrayList<>(union), got);
    }

    @Provide
    Arbitrary<List<List<String>>> idSets() {
        return Arbitraries.of("a", "b", "c", "d", "e", "aa", "").list().ofMaxSize(5).list().ofMaxSize(6);
    }

    @Property(tries = 1000)
    void aLookupGivesExactlyWhatAScanMatches(@ForAll("ops") List<Op> ops) {
        Store s = new Store();
        s.applyAll(ops);
        checkEveryLookup(s);
    }

    @Property(tries = 500)
    void lookupsAreTheSameInAnyOrder(@ForAll("ops") List<Op> ops, @ForAll long seed) {
        Store a = new Store();
        a.applyAll(ops);
        List<Op> shuffled = new ArrayList<>(ops);
        Collections.shuffle(shuffled, new Random(seed));
        Store b = new Store();
        b.applyAll(shuffled);
        assertEquals(a.toOps(), b.toOps());
        assertEquals(a.indexes("a", "t"), b.indexes("a", "t"));
        checkEveryLookup(b);
        for (String op : OPS) {
            for (Object lit : VALUES) {
                assertEquals(a.indexLookup("a", "t", "c", op, lit), b.indexLookup("a", "t", "c", op, lit));
            }
        }
    }

    @Property(tries = 300)
    void aRebuiltStoreHasTheSameIndexes(@ForAll("ops") List<Op> ops) {
        Store a = new Store();
        a.applyAll(ops);
        Store b = new Store();
        b.applyAll(a.toOps());
        assertEquals(a.indexes("a", "t"), b.indexes("a", "t"));
        assertEquals(a.schemaHash("a"), b.schemaHash("a"));
        assertEquals(a.weight(), b.weight(), "the weight estimate never drifts from a fresh count");
        a.collectTombstones(Long.MAX_VALUE);
        Store c = new Store();
        c.applyAll(a.toOps());
        assertEquals(c.weight(), a.weight(), "nor after tombstones are collected");
        checkEveryLookup(b);
    }

    @Property(tries = 300)
    void isNewIsTrueExactlyWhenApplyingChangesTheState(@ForAll("ops") List<Op> state, @ForAll("ops") List<Op> more) {
        Store s = new Store();
        s.applyAll(state);
        for (Op op : more) {
            List<Op> before = s.toOps();
            boolean predicted = s.isNew(op);
            s.apply(op);
            assertEquals(!before.equals(s.toOps()), predicted, () -> "op " + op + " on " + before);
        }
    }

    @Provide
    Arbitrary<List<Op>> ops() {
        Arbitrary<Stamp> stamp = Combinators.combine(Arbitraries.longs().between(3, 60),
                Arbitraries.of("n1", "n2", "n3")).as((w, n) -> new Stamp(w, 0, n));
        Arbitrary<String> id = Arbitraries.of("1", "2", "3", "4", "5");
        Arbitrary<Map<String, Object>> row = Arbitraries.of(VALUES).injectNull(0.15).map(v -> {
            Map<String, Object> m = new java.util.HashMap<>();
            if (v != null) {
                m.put("c", v);
            }
            m.put("other", 1L);
            return m;
        });
        Arbitrary<Op> op = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(6, Combinators.combine(id, row, stamp).as((i, r, s) -> (Op) new Op.Put("a", "t", i, r, s))),
                net.jqwik.api.Tuple.of(2, Combinators.combine(id, stamp).as((i, s) -> (Op) new Op.Delete("a", "t", i, s))),
                net.jqwik.api.Tuple.of(2, stamp.map(s -> (Op) new Op.CreateIndex("a", "t", "c", s))),
                net.jqwik.api.Tuple.of(1, stamp.map(s -> (Op) new Op.DropIndex("a", "t", "c", s))),
                net.jqwik.api.Tuple.of(1, stamp.map(s -> (Op) new Op.DropTable("a", "t", s))),
                net.jqwik.api.Tuple.of(1, stamp.map(s -> (Op) new Op.CreateTable("a", "t", s))));
        Arbitrary<List<Op>> body = op.list().ofMaxSize(30);
        return body.map(list -> {
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
