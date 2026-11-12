package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** The properties replication relies on: order does not matter, and repeats change nothing. */
class StoreProperties {
    @Property(tries = 500)
    void anyOrderGivesTheSameState(@ForAll("ops") List<Op> ops, @ForAll long seed) {
        Store inOrder = new Store();
        inOrder.applyAll(ops);
        List<Op> shuffled = new ArrayList<>(ops);
        Collections.shuffle(shuffled, new Random(seed));
        Store other = new Store();
        other.applyAll(shuffled);
        assertEquals(digests(inOrder), digests(other));
        assertEquals(inOrder.toOps(), other.toOps());
    }

    @Property(tries = 300)
    void applyingTwiceChangesNothing(@ForAll("ops") List<Op> ops) {
        Store once = new Store();
        once.applyAll(ops);
        Store twice = new Store();
        twice.applyAll(ops);
        twice.applyAll(ops);
        assertEquals(once.toOps(), twice.toOps());
    }

    @Property(tries = 300)
    void mergingStatesIsTheSameAsApplyingAllOps(@ForAll("ops") List<Op> ops) {
        List<Op> a = ops.subList(0, ops.size() / 2);
        List<Op> b = ops.subList(ops.size() / 2, ops.size());
        Store sa = new Store();
        sa.applyAll(a);
        Store sb = new Store();
        sb.applyAll(b);
        Store merged = new Store();
        merged.applyAll(sa.toOps());
        merged.applyAll(sb.toOps());
        Store all = new Store();
        all.applyAll(a);
        all.applyAll(b);
        assertEquals(all.toOps(), merged.toOps());
    }

    private static Map<String, String> digests(Store s) {
        Map<String, String> d = new java.util.TreeMap<>(s.digests());
        String empty = new Store().digest("none");
        d.values().removeIf(empty::equals);
        return d;
    }

    @Provide
    Arbitrary<List<Op>> ops() {
        Arbitrary<String> db = Arbitraries.of("a", "b");
        Arbitrary<String> table = Arbitraries.of("t", "u");
        Arbitrary<String> id = Arbitraries.of("1", "2", "3");
        Arbitrary<Stamp> stamp = Combinators.combine(Arbitraries.longs().between(1, 30),
                Arbitraries.integers().between(0, 2), Arbitraries.of("n1", "n2", "n3")).as(Stamp::new);
        Arbitrary<Map<String, Object>> row = Arbitraries.longs().between(0, 5).map(v -> Map.of("v", (Object) v));
        Arbitrary<Op> op = Arbitraries.oneOf(
                Combinators.combine(db, stamp).as(Op.CreateDb::new),
                Combinators.combine(db, stamp).as(Op.DropDb::new),
                Combinators.combine(db, table, stamp).as(Op.CreateTable::new),
                Combinators.combine(db, table, stamp).as(Op.DropTable::new),
                Combinators.combine(db, table, id, row, stamp).as(Op.Put::new),
                Combinators.combine(db, table, id, row, stamp).as(Op.Put::new),
                Combinators.combine(db, table, id, stamp).as(Op.Delete::new));
        return op.list().ofMaxSize(25).map(StoreProperties::uniqueStamps);
    }

    /** Real stamps are unique (the counter and node id make them so); give each generated op its own. */
    private static List<Op> uniqueStamps(List<Op> ops) {
        List<Op> out = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            Op o = ops.get(i);
            Stamp s = new Stamp(o.stamp().wall(), i, o.stamp().node());
            if (o instanceof Op.CreateDb c) {
                out.add(new Op.CreateDb(c.db(), s));
            } else if (o instanceof Op.DropDb c) {
                out.add(new Op.DropDb(c.db(), s));
            } else if (o instanceof Op.CreateTable c) {
                out.add(new Op.CreateTable(c.db(), c.table(), s));
            } else if (o instanceof Op.DropTable c) {
                out.add(new Op.DropTable(c.db(), c.table(), s));
            } else if (o instanceof Op.Put c) {
                out.add(new Op.Put(c.db(), c.table(), c.id(), c.row(), s));
            } else if (o instanceof Op.Delete c) {
                out.add(new Op.Delete(c.db(), c.table(), c.id(), s));
            }
        }
        return out;
    }
}
