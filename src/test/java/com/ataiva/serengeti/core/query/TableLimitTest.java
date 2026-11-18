package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.StorageEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Accounts limited to some tables ({@link Query#check} with {@code db.table} entries): every statement form of
 * docs/QUERY.md against lists of database entries, of table entries, of both, and no list, for the read and the
 * write role, on databases and tables that exist and that do not. The decision comes from {@link #expected}, a
 * model written from the rules in docs/API.md ("Accounts"), not from {@link Reach} or {@link Scope}. A refused
 * statement writes nothing (the store, the log and the change feed are as they were) and reads nothing (it is
 * answered while the store is locked for writing).
 */
class TableLimitTest {
    @TempDir
    Path dir;

    /** What a form reaches, for the model. */
    enum Kind { DATABASE, TABLE, TABLES, LISTING }

    /** A statement form: %1$s is the database, %2$s the table; whether it only reads; what it reaches. */
    record Form(String text, boolean reads, Kind kind) {
        String on(String db, String table) {
            return String.format(text, db, table);
        }
    }

    static final List<Form> FORMS = List.of(
            new Form("CREATE DATABASE %1$s", false, Kind.DATABASE),
            new Form("CREATE DATABASE IF NOT EXISTS %1$s", false, Kind.DATABASE),
            new Form("DROP DATABASE %1$s", false, Kind.DATABASE),
            new Form("DROP DATABASE IF EXISTS %1$s", false, Kind.DATABASE),
            new Form("CREATE TABLE %1$s.%2$s", false, Kind.TABLE),
            new Form("CREATE TABLE IF NOT EXISTS %1$s.%2$s", false, Kind.TABLE),
            new Form("DROP TABLE %1$s.%2$s", false, Kind.TABLE),
            new Form("DROP TABLE IF EXISTS %1$s.%2$s", false, Kind.TABLE),
            new Form("CREATE INDEX ON %1$s.%2$s (w)", false, Kind.TABLE),
            new Form("CREATE INDEX IF NOT EXISTS ON %1$s.%2$s (v, w)", false, Kind.TABLE),
            new Form("DROP INDEX ON %1$s.%2$s (v)", false, Kind.TABLE),
            new Form("DROP INDEX IF EXISTS ON %1$s.%2$s (w)", false, Kind.TABLE),
            new Form("SHOW DATABASES", true, Kind.LISTING),
            new Form("SHOW TABLES IN %1$s", true, Kind.TABLES),
            new Form("SHOW INDEXES IN %1$s.%2$s", true, Kind.TABLE),
            new Form("SHOW CONFLICTS IN %1$s.%2$s", true, Kind.TABLE),
            new Form("INSERT INTO %1$s.%2$s (_id, v) VALUES ('r2', 2), ('r3', 3)", false, Kind.TABLE),
            new Form("INSERT INTO %1$s.%2$s (v) VALUES (4)", false, Kind.TABLE),
            new Form("INSERT INTO %1$s.%2$s (_id, v) VALUES ('r1', 9) ON CONFLICT DO UPDATE SET v = v + 1", false,
                    Kind.TABLE),
            new Form("INSERT INTO %1$s.%2$s (_id, v) VALUES ('r1', 9) ON CONFLICT DO NOTHING", false, Kind.TABLE),
            new Form("SELECT * FROM %1$s.%2$s", true, Kind.TABLE),
            new Form("SELECT _id, v FROM %1$s.%2$s WHERE v IN (1, 2) OR _id LIKE 'r%%' ORDER BY v DESC LIMIT 5 OFFSET 0",
                    true, Kind.TABLE),
            new Form("SELECT COUNT(*) FROM %1$s.%2$s WHERE v > 0", true, Kind.TABLE),
            new Form("SELECT v, COUNT(*), SUM(v) FROM %1$s.%2$s GROUP BY v HAVING COUNT(*) > 0", true, Kind.TABLE),
            new Form("SELECT DISTINCT v FROM %1$s.%2$s", true, Kind.TABLE),
            new Form("UPDATE %1$s.%2$s SET v = v + 1 WHERE _id = 'r1'", false, Kind.TABLE),
            new Form("UPDATE %1$s.%2$s SET v = 5", false, Kind.TABLE),
            new Form("DELETE FROM %1$s.%2$s WHERE _id = 'r1'", false, Kind.TABLE),
            new Form("DELETE FROM %1$s.%2$s", false, Kind.TABLE));

    /**
     * The lists: database entries only, table entries only (and one table that does not exist, a.zz), both (a
     * database, and a table of another), and none (null: every database).
     */
    static final List<List<String>> LISTS = Arrays.asList(List.of("a"), List.of("a.t", "a.zz", "b.t"),
            List.of("a", "b.t"), null);

    /**
     * The targets: a database and table that exist, one that does not in a database that does, one in a
     * database that does not exist (zz).
     */
    static final String[][] TARGETS = {{"a", "t"}, {"a", "u"}, {"a", "zz"}, {"b", "t"}, {"b", "u"}, {"zz", "t"}};

    /**
     * The model: null if an account with {@code list} (null: every database) and the write role or not may run
     * {@code f} on {@code db}.{@code table}, else the 403 it gets. Written from the rules: a database entry gives
     * the database and all its tables; a table entry gives that table only, and its database in SHOW DATABASES
     * and SHOW TABLES, but not CREATE or DROP DATABASE.
     */
    static String expected(Form f, List<String> list, boolean mayWrite, String db, String table) {
        if (!mayWrite && !f.reads()) {
            return "this account has the read role: it can run SELECT and SHOW only";
        }
        if (list == null || f.kind() == Kind.LISTING) {
            return null;
        }
        boolean whole = list.contains(db);
        boolean some = whole || list.stream().anyMatch(e -> e.startsWith(db + "."));
        String noDb = "this account may not use database " + db;
        switch (f.kind()) {
            case DATABASE:
                return whole ? null : some ? noDb + " as a whole, only some of its tables" : noDb;
            case TABLES:
                return some ? null : noDb;
            default:
                return whole || list.contains(db + "." + table) ? null
                        : some ? "this account may not use table " + db + "." + table : noDb;
        }
    }

    /** A node with databases a (tables t and u) and b (table t), each table with a row and an index on v. */
    static StorageEngine open(Path d) throws IOException {
        StorageEngine engine = StorageEngine.open(d, FsyncPolicy.OFF, 1 << 26, 0, 1000);
        Query admin = new Query(engine, new Hlc("n1"));
        for (String t : List.of("a.t", "a.u", "b.t")) {
            String db = t.substring(0, 1);
            admin.run("CREATE DATABASE IF NOT EXISTS " + db);
            admin.run("CREATE TABLE " + t);
            admin.run("INSERT INTO " + t + " (_id, v) VALUES ('r1', 1)");
            admin.run("CREATE INDEX ON " + t + " (v)");
        }
        return engine;
    }

    /** Counts the writes that reach the storage engine. */
    static AtomicInteger countWrites(StorageEngine engine) {
        AtomicInteger n = new AtomicInteger();
        engine.setWriteHooks(new StorageEngine.WriteHooks() {
            @Override
            public void before() {
                n.incrementAndGet();
            }

            @Override
            public void after(List<Op> committed) {}
        });
        return n;
    }

    /** What a refusal must leave as it was: the state, the log and the change feed. */
    record Unchanged(List<Op> ops, long log, String feed) {
        static Unchanged of(StorageEngine e) {
            return new Unchanged(e.snapshotOps(), e.walBytes(), e.changes().now());
        }
    }

    @Test
    void everyFormAgainstEveryKindOfListIsAllowedOrRefusedAsTheRulesSayWithNothingWritten() throws IOException {
        int stores = 0;
        int refused = 0;
        int allowed = 0;
        for (Form f : FORMS) {
            for (List<String> list : LISTS) {
                for (boolean mayWrite : new boolean[] {false, true}) {
                    try (StorageEngine engine = open(dir.resolve("n" + stores++))) {
                        Query q = new Query(engine, new Hlc("n1"));
                        AtomicInteger writes = countWrites(engine);
                        Unchanged before = Unchanged.of(engine);
                        List<String[]> mayRun = new ArrayList<>();
                        for (String[] t : TARGETS) {
                            String text = f.on(t[0], t[1]);
                            String why = expected(f, list, mayWrite, t[0], t[1]);
                            String label = text + " as " + (mayWrite ? "write" : "read") + " with " + list;
                            if (why == null) {
                                mayRun.add(t);
                                continue;
                            }
                            ForbiddenException e = assertThrows(ForbiddenException.class, () -> q.run(text, mayWrite, list),
                                    label);
                            assertEquals(why, e.getMessage(), label);
                            // check() alone, which reads nothing, refuses it the same way.
                            assertEquals(why, assertThrows(ForbiddenException.class, () -> q.check(text, mayWrite, list))
                                    .getMessage(), label);
                            refused++;
                        }
                        assertEquals(0, writes.get(), f.text() + " with " + list + ": no write reached the storage");
                        assertEquals(before, Unchanged.of(engine), f.text() + " with " + list + ": nothing changed");
                        for (String[] t : mayRun) {
                            String text = f.on(t[0], t[1]);
                            try {
                                q.run(text, mayWrite, list);
                            } catch (ForbiddenException e) {
                                fail(text + " with " + list + " is allowed, but: " + e.getMessage());
                            } catch (QueryException e) {
                                // Allowed, and refused by the statement itself (no such table, a row already
                                // there): a 400, never a 403.
                            }
                            allowed++;
                        }
                    }
                }
            }
        }
        assertTrue(refused > 300 && allowed > 300, refused + " refused, " + allowed + " allowed");
    }

    @Test
    void aTableEntryGivesThatTablesStatementsAndNotItsDatabase() throws IOException {
        try (StorageEngine engine = open(dir)) {
            Query q = new Query(engine, new Hlc("n1"));
            List<String> list = List.of("a.t", "a.new");
            q.run("INSERT INTO a.t (_id, v) VALUES ('r2', 2)", true, list);
            q.run("UPDATE a.t SET v = 3 WHERE _id = 'r2'", true, list);
            assertEquals(List.of(List.of("r1", 1L), List.of("r2", 3L)),
                    ((Result.Rows) q.run("SELECT _id, v FROM a.t ORDER BY _id", false, list)).rows());
            q.run("CREATE INDEX ON a.t (v, w)", true, list);
            assertEquals(List.of(List.of("v"), List.of("v, w")),
                    ((Result.Rows) q.run("SHOW INDEXES IN a.t", false, list)).rows());
            q.run("DROP INDEX ON a.t (v, w)", true, list);
            q.run("DELETE FROM a.t WHERE _id = 'r2'", true, list);
            q.run("SHOW CONFLICTS IN a.t", false, list);
            // CREATE and DROP TABLE of a listed table, also one that did not exist yet.
            q.run("CREATE TABLE a.new", true, list);
            q.run("INSERT INTO a.new (_id) VALUES ('n')", true, list);
            assertEquals(List.of(List.of("new"), List.of("t")), ((Result.Rows) q.run("SHOW TABLES IN a", false, list)).rows());
            q.run("DROP TABLE a.new", true, list);
            assertEquals(List.of(List.of("t")), ((Result.Rows) q.run("SHOW TABLES IN a", false, list)).rows());
            // Not the database itself, and not another table of it.
            for (String text : List.of("CREATE DATABASE IF NOT EXISTS a", "DROP DATABASE a", "DROP DATABASE IF EXISTS a")) {
                assertEquals("this account may not use database a as a whole, only some of its tables",
                        assertThrows(ForbiddenException.class, () -> q.run(text, true, list)).getMessage(), text);
            }
            String[][] others = {{"SELECT * FROM a.u", "a.u"}, {"CREATE TABLE a.v", "a.v"}, {"DROP TABLE a.u", "a.u"},
                {"SHOW INDEXES IN a.u", "a.u"}, {"INSERT INTO a.zz (_id) VALUES ('x')", "a.zz"}};
            for (String[] o : others) {
                assertEquals("this account may not use table " + o[1],
                        assertThrows(ForbiddenException.class, () -> q.run(o[0], true, list)).getMessage(), o[0]);
            }
            // Names are folded as the parser folds them.
            q.run("SELECT * FROM A.T", false, list);
            assertThrows(ForbiddenException.class, () -> q.run("SELECT * FROM A.U", false, list));
            assertTrue(((Result.Rows) new Query(engine, new Hlc("n1")).run("SHOW TABLES IN a")).rows().contains(List.of("u")),
                    "a.u is still there");
        }
    }

    @Test
    void showDatabasesAndShowTablesListOnlyWhatTheListGives() throws IOException {
        try (StorageEngine engine = open(dir)) {
            Query q = new Query(engine, new Hlc("n1"));
            new Query(engine, new Hlc("n1")).run("CREATE DATABASE c");
            Object[][] cases = {
                // list, SHOW DATABASES, SHOW TABLES IN a, IN b, IN c
                {List.of("a"), List.of("a"), List.of("t", "u"), null, null},
                {List.of("a.t", "b.t"), List.of("a", "b"), List.of("t"), List.of("t"), null},
                {List.of("a.zz"), List.of("a"), List.of(), null, null},
                {List.of("a", "b.t"), List.of("a", "b"), List.of("t", "u"), List.of("t"), null},
                {List.of("a.t", "a"), List.of("a"), List.of("t", "u"), null, null},
                {List.of("c.t", "x.t"), List.of("c"), null, null, List.of()},
                {List.of("x", "y.t"), List.of(), null, null, null},
            };
            for (Object[] c : cases) {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) c[0];
                assertEquals(rows(c[1]), ((Result.Rows) q.run("SHOW DATABASES", false, list)).rows(), "SHOW DATABASES with " + list);
                String[] dbs = {"a", "b", "c"};
                for (int i = 0; i < 3; i++) {
                    String text = "SHOW TABLES IN " + dbs[i];
                    if (c[i + 2] == null) {
                        assertEquals("this account may not use database " + dbs[i],
                                assertThrows(ForbiddenException.class, () -> q.run(text, false, list)).getMessage(), text);
                    } else {
                        assertEquals(rows(c[i + 2]), ((Result.Rows) q.run(text, false, list)).rows(), text + " with " + list);
                    }
                }
            }
            // A listed database or table in a database that does not exist: what an unlimited account gets.
            QueryException e = assertThrows(QueryException.class, () -> q.run("SHOW TABLES IN x", false, List.of("x.t")));
            assertFalse(e instanceof ForbiddenException);
            assertEquals("database 'x' does not exist", e.getMessage());
        }
    }

    private static List<List<Object>> rows(Object names) {
        List<List<Object>> out = new ArrayList<>();
        for (Object n : (List<?>) names) {
            out.add(List.of(n));
        }
        return out;
    }

    /**
     * A refusal is decided from the list and the statement alone: it is answered while another thread holds the
     * store's write lock, which every read waits for. An allowed SELECT, the control, waits.
     */
    @Test
    void aRefusalReadsNothing() throws Exception {
        try (StorageEngine engine = open(dir)) {
            Query q = new Query(engine, new Hlc("n1"));
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
                try {
                    engine.update(store -> {
                        locked.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return new StorageEngine.Planned<>(List.of(), null);
                    });
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            try {
                List<String> list = List.of("a.t");
                assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                    int n = 0;
                    for (Form f : FORMS) {
                        for (String[] t : TARGETS) {
                            if (expected(f, list, true, t[0], t[1]) != null) {
                                String text = f.on(t[0], t[1]);
                                assertThrows(ForbiddenException.class, () -> q.run(text, true, list), text);
                                n++;
                            }
                        }
                    }
                    assertTrue(n > 100, n + " refusals");
                });
                CompletableFuture<Result> select = CompletableFuture.supplyAsync(() -> {
                    try {
                        return q.run("SELECT * FROM a.t", false, list);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                Thread.sleep(300);
                assertFalse(select.isDone(), "an allowed read waits for the lock");
                release.countDown();
                assertEquals(1, ((Result.Rows) select.get(10, TimeUnit.SECONDS)).rows().size());
            } finally {
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void entriesAreReadAsTheParserReadsNames() {
        assertEquals("shop", Scope.entry("Shop"));
        assertEquals("shop.orders", Scope.entry("Shop.Orders"));
        assertEquals("_x.y1", Scope.entry("_X.Y1"));
        for (String bad : Arrays.asList("shop.", ".orders", ".", "shop..orders", "shop.orders.x", "shop.select",
                "select.t", "shop.1x", "shop.a-b", "shop .t", "shop. t", "", "x".repeat(64) + ".t", "s.café", null)) {
            assertEquals(null, Scope.entry(bad), String.valueOf(bad));
        }
        assertEquals("a." + "t".repeat(63), Scope.entry("A." + "T".repeat(63)));
        // Stored lists are checked for shape only, so a word reserved later does not make them unreadable.
        assertTrue(Scope.stored("select.t"));
        assertFalse(Scope.stored("Shop.t"));
        assertFalse(Scope.stored("shop.t.x"));
        assertFalse(Scope.stored("shop."));
        assertThrows(IllegalArgumentException.class, () -> Scope.of(List.of("shop", "Shop.t")),
                "a list not in the stored form is refused, never read as something else");
    }

    @Test
    void theScopeDecidesFromTheListAlone() {
        Scope s = Scope.of(List.of("a", "b.t"));
        assertTrue(s.database("a"));
        assertFalse(s.database("b"));
        assertTrue(s.table("a", "anything"));
        assertTrue(s.table("b", "t"));
        assertFalse(s.table("b", "u"));
        assertFalse(s.table("c", "t"));
        assertTrue(s.touches("b"));
        assertFalse(s.touches("c"));
        assertEquals(null, s.refusal(new Reach.Listing()));
        assertEquals(null, s.refusal(new Reach.Tables("b")));
        assertEquals("this account may not use table b.u", s.refusal(new Reach.Table("b", "u")));
        // From the change feed's parameters, which are not names: matched exactly, and shown cut short.
        assertEquals("this account may not use table b.T", s.refusal(new Reach.Table("b", "T")));
        assertEquals("this account may not use table b.", s.refusal(new Reach.Table("b", "")));
        assertEquals("this account may not use database " + "x".repeat(64) + "...",
                s.refusal(new Reach.Database("x".repeat(100))));
        assertEquals("this account may not use table b." + "y".repeat(64) + "...",
                s.refusal(new Reach.Table("b", "y".repeat(100))));
        // A name the parser takes is at most 63 characters, and shown whole.
        assertEquals("this account may not use table b." + "y".repeat(63), s.refusal(new Reach.Table("b", "y".repeat(63))));
    }
}
