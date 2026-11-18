package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.StorageEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;

/**
 * Random lists of database and table entries against random statements, decided by a model written from the
 * rules of docs/API.md ("Accounts") with no use of {@link Reach} or {@link Scope}:
 * <ul>
 *   <li>a {@code db} entry gives the database and every table of it;
 *   <li>a {@code db.table} entry gives that table: every statement that names it, CREATE and DROP TABLE included,
 *       and its database in SHOW DATABASES and SHOW TABLES, which list only what the list gives; not CREATE or DROP
 *       DATABASE;
 *   <li>anything else is refused, whether or not it exists, with the database's message if the list has nothing of
 *       that database and the table's if it has some of it;
 *   <li>the role applies first.
 * </ul>
 */
class TableLimitProperties {
    private static Path dir;
    private static StorageEngine engine;
    private static Query query;
    private static final AtomicInteger RUNS = new AtomicInteger();

    @BeforeContainer
    static void open() throws IOException {
        dir = Files.createTempDirectory("table-limit-properties");
        engine = StorageEngine.open(dir.resolve("check"), FsyncPolicy.OFF);
        query = new Query(engine, new Hlc("n1"));
    }

    @AfterContainer
    static void close() throws IOException {
        engine.close();
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** The names the lists use: often the ones {@link QueryProperties} statements use, so both sides occur. */
    private static Arbitrary<String> name() {
        return Arbitraries.oneOf(Arbitraries.of("a", "b", "x1", "_", "price"),
                Arbitraries.strings().withCharRange('a', 'c').ofMinLength(1).ofMaxLength(2))
                .filter(s -> Query.nameOf(s) != null);
    }

    /** 1 to 6 entries, databases and tables mixed. */
    @Provide
    Arbitrary<List<String>> lists() {
        Arbitrary<String> entry = Arbitraries.oneOf(name(), Combinators.combine(name(), name()).as((d, t) -> d + "." + t));
        return entry.list().uniqueElements().ofMinSize(1).ofMaxSize(6);
    }

    @Provide
    Arbitrary<Statement> statements() {
        return new QueryProperties().statements();
    }

    /**
     * The model's answer: null if an account with {@code list} (null: every database) may run {@code s}, else the
     * message of its 403.
     */
    static String model(Statement s, List<String> list, boolean mayWrite) {
        boolean reads = s instanceof Statement.Select || s instanceof Statement.ShowDatabases
                || s instanceof Statement.ShowTables || s instanceof Statement.ShowIndexes
                || s instanceof Statement.ShowConflicts;
        if (!mayWrite && !reads) {
            return "this account has the read role: it can run SELECT and SHOW only";
        }
        if (list == null || s instanceof Statement.ShowDatabases) {
            return null;
        }
        String db = DatabaseLimitTest.component(s, "db");
        String table = DatabaseLimitTest.component(s, "table");
        boolean whole = list.contains(db);
        boolean some = whole || list.stream().anyMatch(e -> e.startsWith(db + "."));
        String noDb = "this account may not use database " + db;
        if (s instanceof Statement.CreateDatabase || s instanceof Statement.DropDatabase) {
            return whole ? null : some ? noDb + " as a whole, only some of its tables" : noDb;
        }
        if (s instanceof Statement.ShowTables) {
            return some ? null : noDb;
        }
        if (table == null) {
            throw new AssertionError(s.getClass().getSimpleName() + ": the model does not know this statement");
        }
        return whole || list.contains(db + "." + table) ? null
                : some ? "this account may not use table " + db + "." + table : noDb;
    }

    @Property(tries = 4000)
    void everyStatementIsDecidedAsTheModelSays(@ForAll("statements") Statement s, @ForAll("lists") List<String> list,
            @ForAll boolean mayWrite) {
        String text = Printer.print(s);
        String why = model(s, list, mayWrite);
        if (why == null) {
            assertEquals(s, query.check(text, mayWrite, list).statement(), text + " with " + list);
        } else {
            assertEquals(why, assertThrows(ForbiddenException.class, () -> query.check(text, mayWrite, list), text)
                    .getMessage(), text + " with " + list);
        }
        if (model(s, null, mayWrite) == null) {
            query.check(text, mayWrite, null); // without a list, only the role decides
        }
    }

    /** The tables that exist, as db.table, from a, b and c with tables t, u and v. */
    @Provide
    Arbitrary<Set<String>> layouts() {
        return Combinators.combine(Arbitraries.of("a", "b", "c"), Arbitraries.of("t", "u", "v"))
                .as((d, t) -> d + "." + t).set().ofMaxSize(6);
    }

    /** Lists over the layouts' names, and d, which never exists. */
    @Provide
    Arbitrary<List<String>> smallLists() {
        Arbitrary<String> db = Arbitraries.of("a", "b", "c", "d");
        Arbitrary<String> entry = Arbitraries.oneOf(db,
                Combinators.combine(db, Arbitraries.of("t", "u", "v", "w")).as((d, t) -> d + "." + t));
        return entry.list().uniqueElements().ofMinSize(1).ofMaxSize(5);
    }

    /** Statement forms over those names; %1$s is the database and %2$s the table. */
    @Provide
    Arbitrary<String> forms() {
        Arbitrary<String> form = Arbitraries.of(TableLimitTest.FORMS).map(TableLimitTest.Form::text);
        return Combinators.combine(form, Arbitraries.of("a", "b", "c", "d"), Arbitraries.of("t", "u", "v", "w"))
                .as((f, d, t) -> String.format(f, d, t));
    }

    /**
     * On a store holding {@code layout}: SHOW DATABASES and SHOW TABLES list exactly what the model gives that
     * exists, and a statement the model refuses changes nothing and is refused with its message, while one it
     * allows is never a 403.
     */
    @Property(tries = 400)
    void listingsShowWhatTheListGivesAndRefusalsChangeNothing(@ForAll("layouts") Set<String> layout,
            @ForAll("smallLists") List<String> list, @ForAll("forms") String text, @ForAll boolean mayWrite)
            throws IOException {
        try (StorageEngine e = StorageEngine.open(dir.resolve("run" + RUNS.incrementAndGet()), FsyncPolicy.OFF, 1 << 26, 0, 100)) {
            Query admin = new Query(e, new Hlc("n1"));
            Set<String> dbs = new TreeSet<>();
            for (String t : layout) {
                dbs.add(t.substring(0, 1));
            }
            dbs.add("c"); // c exists, with or without tables
            for (String d : dbs) {
                admin.run("CREATE DATABASE " + d);
            }
            for (String t : new TreeSet<>(layout)) {
                admin.run("CREATE TABLE " + t);
                admin.run("INSERT INTO " + t + " (_id, v) VALUES ('r1', 1)");
            }
            Query q = new Query(e, new Hlc("n1"));
            Statement show = Parser.parse("SHOW DATABASES");
            List<List<Object>> shown = new ArrayList<>();
            for (String d : dbs) {
                if (list.contains(d) || list.stream().anyMatch(x -> x.startsWith(d + "."))) {
                    shown.add(List.of(d));
                }
            }
            assertEquals(model(show, list, false), null);
            assertEquals(shown, ((Result.Rows) q.run("SHOW DATABASES", false, list)).rows(), "with " + list);
            for (String d : List.of("a", "b", "c", "d")) {
                Statement st = Parser.parse("SHOW TABLES IN " + d);
                String why = model(st, list, false);
                if (why != null) {
                    assertEquals(why, assertThrows(ForbiddenException.class, () -> q.run("SHOW TABLES IN " + d, false, list))
                            .getMessage());
                } else if (!dbs.contains(d)) {
                    assertEquals("database '" + d + "' does not exist",
                            assertThrows(QueryException.class, () -> q.run("SHOW TABLES IN " + d, false, list)).getMessage());
                } else {
                    List<List<Object>> tables = new ArrayList<>();
                    for (String t : new TreeSet<>(layout)) {
                        String tn = t.substring(2);
                        if (t.startsWith(d + ".") && (list.contains(d) || list.contains(t))) {
                            tables.add(List.of(tn));
                        }
                    }
                    assertEquals(tables, ((Result.Rows) q.run("SHOW TABLES IN " + d, false, list)).rows(),
                            "SHOW TABLES IN " + d + " with " + list + " on " + layout);
                }
            }
            String why = model(Parser.parse(text), list, mayWrite);
            TableLimitTest.Unchanged before = TableLimitTest.Unchanged.of(e);
            if (why != null) {
                assertEquals(why, assertThrows(ForbiddenException.class, () -> q.run(text, mayWrite, list), text).getMessage(),
                        text + " with " + list);
                assertEquals(before, TableLimitTest.Unchanged.of(e), text + ": nothing changed");
            } else {
                try {
                    q.run(text, mayWrite, list);
                } catch (ForbiddenException x) {
                    fail(text + " with " + list + " is allowed, but: " + x.getMessage());
                } catch (QueryException x) {
                    // allowed, and refused by the statement itself
                }
            }
        }
    }
}
