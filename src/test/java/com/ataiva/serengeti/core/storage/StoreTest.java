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
