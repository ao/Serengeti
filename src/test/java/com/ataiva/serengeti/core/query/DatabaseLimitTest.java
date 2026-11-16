package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.StorageEngine;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Accounts limited to some databases ({@link Query#check}): every statement form of docs/QUERY.md, run by an
 * account limited to {@code a} and {@code c} on a node with databases {@code a} and {@code b} ({@code c} does
 * not exist yet). Each form runs in its database, and is refused for {@code b} and for {@code zz}, which does
 * not exist, with nothing written.
 */
class DatabaseLimitTest {
    @TempDir
    Path dir;

    private static final List<String> LIMIT = List.of("a", "c");

    /** Each form with %s for its database, and the database of the list it runs in. */
    private static final String[][] FORMS = {
        {"CREATE DATABASE %s", "c"},
        {"CREATE DATABASE IF NOT EXISTS %s", "a"},
        {"DROP DATABASE %s", "a"},
        {"DROP DATABASE IF EXISTS %s", "c"},
        {"CREATE TABLE %s.u", "a"},
        {"CREATE TABLE IF NOT EXISTS %s.t", "a"},
        {"DROP TABLE %s.t", "a"},
        {"DROP TABLE IF EXISTS %s.u", "a"},
        {"CREATE INDEX ON %s.t (w)", "a"},
        {"CREATE INDEX IF NOT EXISTS ON %s.t (v)", "a"},
        {"DROP INDEX ON %s.t (v)", "a"},
        {"DROP INDEX IF EXISTS ON %s.t (w)", "a"},
        {"SHOW TABLES IN %s", "a"},
        {"SHOW INDEXES IN %s.t", "a"},
        {"SHOW CONFLICTS IN %s.t", "a"},
        {"INSERT INTO %s.t (_id, v) VALUES ('r2', 2), ('r3', 3)", "a"},
        {"INSERT INTO %s.t (v) VALUES (4)", "a"},
        {"SELECT * FROM %s.t", "a"},
        {"SELECT _id, v FROM %s.t WHERE v IN (1, 2) OR _id LIKE 'r%%' ORDER BY v DESC, _id LIMIT 5", "a"},
        {"SELECT COUNT(*) FROM %s.t WHERE v > 0", "a"},
        {"SELECT v, COUNT(*), SUM(v) FROM %s.t GROUP BY v HAVING COUNT(*) > 0 ORDER BY v", "a"},
        {"SELECT DISTINCT v FROM %s.t", "a"},
        {"SELECT COUNT(DISTINCT v) FROM %s.t", "a"},
        {"UPDATE %s.t SET v = 3 WHERE _id = 'r1'", "a"},
        {"UPDATE %s.t SET v = 5", "a"},
        {"DELETE FROM %s.t WHERE _id = 'r1'", "a"},
        {"DELETE FROM %s.t", "a"},
    };

    /** A node with databases a and b, each with a table t that has a row and an index on v. */
    private StorageEngine open(Path d) throws IOException {
        StorageEngine engine = StorageEngine.open(d, FsyncPolicy.OFF);
        Query admin = new Query(engine, new Hlc("n1"));
        for (String db : List.of("a", "b")) {
            admin.run("CREATE DATABASE " + db);
            admin.run("CREATE TABLE " + db + ".t");
            admin.run("INSERT INTO " + db + ".t (_id, v) VALUES ('r1', 1)");
            admin.run("CREATE INDEX ON " + db + ".t (v)");
        }
        return engine;
    }

    /** Counts the writes that reach the storage engine. */
    private static AtomicInteger countWrites(StorageEngine engine) {
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

    @Test
    void everyFormRunsInAListedDatabaseAndIsRefusedOutsideItWithNothingWritten() throws IOException {
        int i = 0;
        for (String[] f : FORMS) {
            StorageEngine engine = open(dir.resolve("n" + i++));
            try {
                Query q = new Query(engine, new Hlc("n1"));
                AtomicInteger writes = countWrites(engine);
                List<Op> before = engine.snapshotOps();
                long log = engine.walBytes();
                for (String outside : List.of("b", "zz")) {
                    String text = String.format(f[0], outside);
                    ForbiddenException e = assertThrows(ForbiddenException.class, () -> q.run(text, true, LIMIT), text);
                    assertEquals("this account may not use database " + outside, e.getMessage(), text);
                    // check() alone, which reads nothing, refuses it the same way: the refusal does not depend on
                    // what is stored, so it cannot tell whether the database exists.
                    assertEquals(e.getMessage(), assertThrows(ForbiddenException.class,
                            () -> q.check(text, true, LIMIT)).getMessage());
                }
                assertEquals(0, writes.get(), f[0] + ": no write reached the storage");
                assertEquals(before, engine.snapshotOps(), f[0] + ": nothing changed");
                assertEquals(log, engine.walBytes(), f[0] + ": nothing was logged");
                String inside = String.format(f[0], f[1]);
                q.run(inside, true, LIMIT); // and runs where the account may go
            } finally {
                engine.close();
            }
        }
    }

    @Test
    void theRoleStillAppliesOnTop() throws IOException {
        try (StorageEngine engine = open(dir)) {
            Query q = new Query(engine, new Hlc("n1"));
            AtomicInteger writes = countWrites(engine);
            for (String[] f : FORMS) {
                String text = String.format(f[0], "a");
                if (Query.reads(Parser.parse(text))) {
                    q.run(text, false, List.of("a"));
                } else {
                    ForbiddenException e = assertThrows(ForbiddenException.class, () -> q.run(text, false, List.of("a")), text);
                    assertTrue(e.getMessage().contains("read role"), e.getMessage());
                }
                String outside = String.format(f[0], "b");
                assertThrows(ForbiddenException.class, () -> q.run(outside, false, List.of("a")), outside);
            }
            assertEquals(0, writes.get());
        }
    }

    @Test
    void showDatabasesListsOnlyTheAccountsDatabasesThatExist() throws IOException {
        try (StorageEngine engine = open(dir)) {
            Query q = new Query(engine, new Hlc("n1"));
            assertEquals(List.of(List.of("a")), ((Result.Rows) q.run("SHOW DATABASES", false, LIMIT)).rows(),
                    "c is listed but does not exist");
            assertEquals(List.of(List.of("b")), ((Result.Rows) q.run("SHOW DATABASES", false, List.of("b", "x"))).rows());
            assertEquals(List.of(), ((Result.Rows) q.run("SHOW DATABASES", false, List.of("x"))).rows());
            assertEquals(List.of(List.of("a"), List.of("b")), ((Result.Rows) q.run("SHOW DATABASES", false, null)).rows(),
                    "without a list, every database");
            // Once c exists, it is shown.
            q.run("CREATE DATABASE c", true, LIMIT);
            assertEquals(List.of(List.of("a"), List.of("c")), ((Result.Rows) q.run("show databases;", false, LIMIT)).rows());
        }
    }

    @Test
    void namesAreComparedAsTheParserFoldsThem() throws IOException {
        try (StorageEngine engine = open(dir)) {
            Query q = new Query(engine, new Hlc("n1"));
            q.run("SELECT * FROM A.T", false, List.of("a"));
            assertThrows(ForbiddenException.class, () -> q.run("SELECT * FROM B.t", false, List.of("a")));
            assertThrows(ParseException.class, () -> q.run("SELECT * FROM", false, List.of("a")),
                    "a parse error stays a parse error");
        }
    }

    /**
     * Every Statement type the parser can produce is among the forms above, and what it reaches is its {@code db}
     * component (read here by reflection, not through {@link Reach}), or for SHOW DATABASES none. A statement
     * type added later fails this test until it has a form here.
     */
    @Test
    void everyStatementTypeIsCoveredAndReachesTheDatabaseItNames() {
        Set<Class<?>> seen = new HashSet<>();
        List<String> texts = new ArrayList<>();
        for (String[] f : FORMS) {
            texts.add(String.format(f[0], "a"));
        }
        texts.add("SHOW DATABASES");
        for (String text : texts) {
            Statement s = Parser.parse(text);
            seen.add(s.getClass());
            assertEquals(expectedReach(s), Reach.of(s), text);
        }
        Set<Class<?>> all = new HashSet<>(List.of(Statement.class.getPermittedSubclasses()));
        assertEquals(all, seen, "every statement type has a form in FORMS");
        assertEquals(14, all.size());
    }

    /** {@code new Reach.Database(db)} for a statement with a {@code db} component; SHOW DATABASES lists. */
    static Reach expectedReach(Statement s) {
        for (RecordComponent c : s.getClass().getRecordComponents()) {
            if (c.getName().equals("db")) {
                try {
                    return new Reach.Database((String) c.getAccessor().invoke(s));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }
        }
        if (s instanceof Statement.ShowDatabases) {
            return new Reach.Listing();
        }
        throw new AssertionError(s.getClass().getSimpleName() + " names no database: decide what it reaches");
    }

    @Test
    void nameOfTakesWhatTheParserTakes() {
        assertEquals("shop", Query.nameOf("Shop"));
        assertEquals("_x1", Query.nameOf("_X1"));
        assertEquals(null, Query.nameOf("1x"));
        assertEquals(null, Query.nameOf("a-b"));
        assertEquals(null, Query.nameOf("select"), "a keyword");
        assertEquals(null, Query.nameOf(""));
        assertEquals(null, Query.nameOf("x".repeat(64)));
        assertEquals("x".repeat(63), Query.nameOf("X".repeat(63)));
        assertEquals(null, Query.nameOf("\u212Aeep"), "the Kelvin sign folds to k, but the lexer refuses it");
        assertEquals(null, Query.nameOf("café"));
        assertEquals("sum", Query.nameOf("sum"), "not reserved");
    }
}
