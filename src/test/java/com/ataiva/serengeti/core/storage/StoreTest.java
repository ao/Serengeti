package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StoreTest {
    static Stamp at(long t) {
        return new Stamp(t, 0, "n1");
    }

    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static Stamp at(long t, String node) {
        return new Stamp(t, 0, node);
    }

    static Store table() {
        Store s = new Store();
        s.apply(new Op.CreateDb("shop", at(1)));
        s.apply(new Op.CreateTable("shop", "items", at(2)));
        return s;
    }

    static List<Stamp> versionStamps(Store s, String id) {
        List<Stamp> out = new java.util.ArrayList<>();
        s.forEachConflict("shop", "items", (rid, vs) -> {
            if (rid.equals(id)) {
                vs.forEach(v -> out.add(v.stamp()));
            }
        });
        return out;
    }

    @Test
    void opsWithoutAContextAreOrderedByStampAsIn20() {
        Store s = table();
        s.apply(new Op.Put("shop", "items", "a", row("v", 1), at(5, "n2")));
        s.apply(new Op.Put("shop", "items", "a", row("v", 2), at(4, "n3")));
        s.apply(new Op.Delete("shop", "items", "a", at(3, "n1")));
        assertEquals(row("v", 1), s.row("shop", "items", "a"));
        assertEquals(0, s.conflicts());
    }

    @Test
    void writesThatHadNotSeenEachOtherAreBothKept() {
        Store s = table();
        s.apply(new Op.Put("shop", "items", "a", row("v", 1), at(10, "n1"), Map.of()));
        s.apply(new Op.Put("shop", "items", "a", row("v", 2), at(11, "n2"), Map.of()));
        assertEquals(row("v", 2), s.row("shop", "items", "a"), "the newer one is the value");
        assertEquals(List.of(at(11, "n2"), at(10, "n1")), versionStamps(s, "a"));
        assertEquals(1, s.conflicts());
        // A write that had seen n1's version only replaces that one: n2's is still concurrent with it.
        s.apply(new Op.Put("shop", "items", "a", row("v", 3), at(12, "n3"), Map.of("n1", at(10, "n1"))));
        assertEquals(List.of(at(12, "n3"), at(11, "n2")), versionStamps(s, "a"));
        // A write here, with the context of everything held, supersedes all of them.
        List<Op> local = s.withContext(List.of(new Op.Put("shop", "items", "a", row("v", 4), at(13, "n1"))));
        s.applyAll(local);
        assertEquals(row("v", 4), s.row("shop", "items", "a"));
        assertEquals(0, s.conflicts());
        Map<String, Stamp> ctx = ((Op.Put) local.get(0)).seen();
        assertEquals(Map.of("n2", at(11, "n2"), "n3", at(12, "n3")), ctx, "own node and ~ are implied by the stamp");
    }

    @Test
    void aDeleteConcurrentWithAnUpdateIsKeptAsAConflict() {
        Store s = table();
        s.apply(new Op.Put("shop", "items", "a", row("v", 1), at(10, "n1"), Map.of()));
        Map<String, Stamp> sawPut = Map.of("n1", at(10, "n1"));
        s.apply(new Op.Delete("shop", "items", "a", at(12, "n2"), sawPut));
        s.apply(new Op.Put("shop", "items", "a", row("v", 2), at(11, "n3"), sawPut));
        assertNull(s.row("shop", "items", "a"), "the newer version is the delete");
        assertEquals(0, s.rowCount("shop", "items"));
        assertEquals(List.of(at(12, "n2"), at(11, "n3")), versionStamps(s, "a"));
        assertEquals(0, s.collectTombstones(Long.MAX_VALUE), "a delete that is part of a conflict is not collected");
        // Inserting the id again (here, with the context) resolves it.
        s.applyAll(s.withContext(List.of(new Op.Put("shop", "items", "a", row("v", 3), at(13, "n1")))));
        assertEquals(row("v", 3), s.row("shop", "items", "a"));
        assertEquals(0, s.conflicts());
    }

    @Test
    void aLaterWriteCoversEarlierOpsWithoutAContext() {
        Store s = table();
        s.apply(new Op.Put("shop", "items", "a", row("v", 1), at(10, "n2")));
        s.applyAll(s.withContext(List.of(new Op.Put("shop", "items", "a", row("v", 2), at(11, "n1")))));
        // A 2.0 node's copy of an older write arriving late changes nothing.
        s.apply(new Op.Put("shop", "items", "a", row("v", 0), at(9, "n3")));
        assertEquals(row("v", 2), s.row("shop", "items", "a"));
        assertEquals(0, s.conflicts());
    }

    @Test
    void theSameWriteArrivingTwiceIsOneVersion() {
        Store s = table();
        Op.Put p = new Op.Put("shop", "items", "a", row("v", 1), at(10, "n1"), Map.of());
        s.apply(p);
        assertFalse(s.isNew(p));
        assertFalse(s.isNew(new Op.Put("shop", "items", "a", row("v", 1), at(10, "n1"))), "same stamp, no context");
        s.apply(new Op.Put("shop", "items", "a", row("v", 1), at(10, "n1")));
        assertEquals(0, s.conflicts());
    }

    @Test
    void contextsSurviveJsonAndBadOnesAreRefused() {
        Op.Put p = new Op.Put("shop", "items", "a", row("v", 1), at(10, "n1"), Map.of("n2", at(7, "n2"), "~", at(6, "n3")));
        assertEquals(p, Op.fromJson(Op.toJson(p)));
        assertFalse(Op.toLegacyJson(p).containsKey("seen"));
        Op.Put legacy = new Op.Put("shop", "items", "a", row("v", 1), at(10, "n1"));
        assertFalse(Op.toJson(legacy).containsKey("seen"));
        Map<String, Object> bad = new java.util.LinkedHashMap<>(Op.toJson(p));
        bad.put("seen", Map.of("n2", "7.0.n9"));
        assertTrue(assertThrowsIae(bad).contains("another node"));
        bad.put("seen", "nope");
        assertTrue(assertThrowsIae(bad).contains("'seen'"));
        Map<String, Object> many = new java.util.TreeMap<>();
        for (int i = 0; i <= Op.MAX_SEEN; i++) {
            many.put("n" + i, "1.0.n" + i);
        }
        bad.put("seen", many);
        assertTrue(assertThrowsIae(bad).contains("at most"));
    }

    private static String assertThrowsIae(Map<String, Object> json) {
        return org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Op.fromJson(json))
                .getMessage();
    }

    @Test
    void createAndRead() {
        Store s = new Store();
        s.apply(new Op.CreateDb("shop", at(1)));
        s.apply(new Op.CreateTable("shop", "items", at(2)));
        s.apply(new Op.Put("shop", "items", "a", row("name", "Lamp"), at(3)));
        assertEquals(List.of("shop"), s.databases());
        assertEquals(List.of("items"), s.tables("shop"));
        assertEquals(row("name", "Lamp"), s.row("shop", "items", "a"));
        assertEquals(1, s.rowCount("shop", "items"));
    }

    @Test
    void newerWriteWinsWhicheverArrivesFirst() {
        Store a = new Store();
        Store b = new Store();
        List<Op> base = List.of(new Op.CreateDb("d", at(1)), new Op.CreateTable("d", "t", at(2)));
        Op older = new Op.Put("d", "t", "x", row("v", 1L), at(5));
        Op newer = new Op.Put("d", "t", "x", row("v", 2L), at(6));
        a.applyAll(base);
        b.applyAll(base);
        a.apply(older);
        a.apply(newer);
        b.apply(newer);
        b.apply(older);
        assertEquals(row("v", 2L), a.row("d", "t", "x"));
        assertEquals(a.digests(), b.digests());
    }

    @Test
    void tombstoneHidesOlderPut() {
        Store s = new Store();
        s.applyAll(List.of(new Op.CreateDb("d", at(1)), new Op.CreateTable("d", "t", at(2))));
        s.apply(new Op.Delete("d", "t", "x", at(7)));
        s.apply(new Op.Put("d", "t", "x", row("v", 1L), at(6)));
        assertNull(s.row("d", "t", "x"));
        s.apply(new Op.Put("d", "t", "x", row("v", 3L), at(8)));
        assertEquals(row("v", 3L), s.row("d", "t", "x"));
    }

    @Test
    void dropRemovesOlderRowsAndRecreateStartsEmpty() {
        Store s = new Store();
        s.applyAll(List.of(new Op.CreateDb("d", at(1)), new Op.CreateTable("d", "t", at(2)),
                new Op.Put("d", "t", "x", row("v", 1L), at(3))));
        s.apply(new Op.DropTable("d", "t", at(4)));
        assertFalse(s.tableExists("d", "t"));
        s.apply(new Op.CreateTable("d", "t", at(5)));
        assertTrue(s.tableExists("d", "t"));
        assertEquals(0, s.rowCount("d", "t"));
        // a write made before the drop, arriving late, stays dropped
        s.apply(new Op.Put("d", "t", "y", row("v", 2L), at(3)));
        assertEquals(0, s.rowCount("d", "t"));
    }

    @Test
    void dropDatabaseDropsItsTablesEvenIfTheyArriveLater() {
        Store s = new Store();
        s.apply(new Op.CreateDb("d", at(1)));
        s.apply(new Op.DropDb("d", at(10)));
        s.apply(new Op.CreateTable("d", "t", at(5)));
        s.apply(new Op.Put("d", "t", "x", row("v", 1L), at(6)));
        assertFalse(s.dbExists("d"));
        s.apply(new Op.CreateDb("d", at(11)));
        assertTrue(s.dbExists("d"));
        assertFalse(s.tableExists("d", "t"));
        assertEquals(List.of(), s.tables("d"));
    }

    @Test
    void rowsForATableNotYetCreatedAppearWhenItIs() {
        Store s = new Store();
        s.apply(new Op.Put("d", "t", "x", row("v", 1L), at(3)));
        assertNull(s.row("d", "t", "x"));
        s.apply(new Op.CreateTable("d", "t", at(2)));
        s.apply(new Op.CreateDb("d", at(1)));
        assertEquals(row("v", 1L), s.row("d", "t", "x"));
    }

    @Test
    void toOpsRebuildsTheSameState() {
        Store s = new Store();
        s.applyAll(List.of(new Op.CreateDb("d", at(1)), new Op.CreateTable("d", "t", at(2)),
                new Op.Put("d", "t", "x", row("v", 1L), at(3)), new Op.Delete("d", "t", "y", at(4)),
                new Op.DropTable("d", "u", at(5)), new Op.CreateDb("e", at(6)), new Op.DropDb("e", at(7))));
        Store copy = new Store();
        copy.applyAll(s.toOps());
        assertEquals(s.digests(), copy.digests());
        assertEquals(s.toOps(), copy.toOps());
        assertEquals(s.maxStamp(), copy.maxStamp());
    }
}
