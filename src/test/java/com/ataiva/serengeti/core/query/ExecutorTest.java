package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.StorageEngine;
import com.ataiva.serengeti.core.storage.Store;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutorTest {
    @TempDir
    Path dir;

    private StorageEngine engine;
    private Query query;

    @BeforeEach
    void open() throws IOException {
        engine = StorageEngine.open(dir, FsyncPolicy.ALWAYS);
        query = new Query(engine, new Hlc("n1"));
    }

    @AfterEach
    void close() throws IOException {
        engine.close();
    }

    private Result run(String q) throws IOException {
        return query.run(q);
    }

    private Result.Rows rows(String q) throws IOException {
        return (Result.Rows) run(q);
    }

    private long affected(String q) throws IOException {
        return ((Result.Write) run(q)).affected();
    }

    private QueryException fails(String q) {
        return assertThrows(QueryException.class, () -> run(q));
    }

    /** One column of a SELECT, in result order. */
    private List<Object> column(String q, String name) throws IOException {
        Result.Rows r = rows(q);
        int i = r.columns().indexOf(name);
        List<Object> out = new ArrayList<>();
        for (List<Object> row : r.rows()) {
            out.add(row.get(i));
        }
        return out;
    }

    private void seed() throws IOException {
        run("CREATE DATABASE shop");
        run("CREATE TABLE shop.items");
        run("INSERT INTO shop.items (_id, name, price, stock) VALUES "
                + "('a', 'Tea', 3, 10), ('b', 'Coffee', 4.5, 0), ('c', 'Milk', 1, null), ('d', 'Cake', '2', 5)");
    }

    @Test
    void databases() throws IOException {
        assertEquals(1, affected("CREATE DATABASE shop"));
        assertTrue(fails("CREATE DATABASE shop").getMessage().contains("already exists"));
        assertEquals(0, affected("CREATE DATABASE IF NOT EXISTS shop"));
        run("CREATE DATABASE app");
        assertEquals(List.of(List.of("app"), List.of("shop")), rows("SHOW DATABASES").rows());
        assertEquals(List.of("database"), rows("SHOW DATABASES").columns());
        assertEquals(1, affected("DROP DATABASE app"));
        assertEquals("database 'app' does not exist", fails("DROP DATABASE app").getMessage());
        assertEquals(0, affected("DROP DATABASE IF EXISTS app"));
        assertEquals(List.of(List.of("shop")), rows("SHOW DATABASES").rows());
    }

    @Test
    void tables() throws IOException {
        assertEquals("database 'shop' does not exist", fails("CREATE TABLE shop.items").getMessage());
        run("CREATE DATABASE shop");
        assertEquals(1, affected("CREATE TABLE shop.items"));
        fails("CREATE TABLE shop.items");
        assertEquals(0, affected("CREATE TABLE IF NOT EXISTS shop.items"));
        run("CREATE TABLE shop.orders");
        assertEquals(List.of(List.of("items"), List.of("orders")), rows("SHOW TABLES IN shop").rows());
        assertEquals("database 'nope' does not exist", fails("SHOW TABLES IN nope").getMessage());
        assertEquals(1, affected("DROP TABLE shop.orders"));
        assertEquals("table 'shop.orders' does not exist", fails("DROP TABLE shop.orders").getMessage());
        assertEquals(0, affected("DROP TABLE IF EXISTS shop.orders"));
        assertEquals(0, affected("DROP TABLE IF EXISTS nope.orders"));
        assertEquals("database 'nope' does not exist", fails("DROP TABLE nope.orders").getMessage());
    }

    @Test
    void droppingRemovesRows() throws IOException {
        seed();
        run("DROP TABLE shop.items");
        run("CREATE TABLE shop.items");
        assertEquals(List.of(List.of(0L)), rows("SELECT COUNT(*) FROM shop.items").rows());
        run("INSERT INTO shop.items (x) VALUES (1)");
        run("DROP DATABASE shop");
        run("CREATE DATABASE shop");
        assertEquals("table 'shop.items' does not exist", fails("SELECT * FROM shop.items").getMessage());
    }

    @Test
    void insertGeneratesIdsAndRefusesDuplicates() throws IOException {
        seed();
        Result.Write w = (Result.Write) run("INSERT INTO shop.items (name) VALUES ('Jam'), ('Honey')");
        assertEquals(2, w.affected());
        assertEquals(2, w.ids().size());
        assertEquals(36, w.ids().get(0).length());
        assertEquals(List.of("Jam"), column("SELECT name FROM shop.items WHERE _id = '" + w.ids().get(0) + "'",
                "name"));

        QueryException e = fails("INSERT INTO shop.items (_id, name) VALUES ('z', 'Z'), ('a', 'again')");
        assertEquals("a row with _id 'a' already exists in 'shop.items'", e.getMessage());
        assertEquals(0, column("SELECT _id FROM shop.items WHERE _id = 'z'", "_id").size(), "nothing written");
        fails("INSERT INTO shop.items (_id) VALUES ('y'), ('y')");
        assertTrue(fails("INSERT INTO shop.items (_id) VALUES (1)").getMessage().contains("non-empty string"));
        fails("INSERT INTO shop.items (_id) VALUES ('')");
        assertEquals("table 'shop.nope' does not exist", fails("INSERT INTO shop.nope (a) VALUES (1)").getMessage());
    }

    @Test
    void deletedIdCanBeReused() throws IOException {
        seed();
        run("DELETE FROM shop.items WHERE _id = 'a'");
        assertEquals(1, affected("INSERT INTO shop.items (_id, name) VALUES ('a', 'Tea again')"));
        assertEquals(List.of("Tea again"), column("SELECT name FROM shop.items WHERE _id = 'a'", "name"));
    }

    @Test
    void selectStarListsIdFirstAndFillsMissingColumns() throws IOException {
        seed();
        run("INSERT INTO shop.items (_id, colour) VALUES ('e', 'red')");
        Result.Rows r = rows("SELECT * FROM shop.items");
        assertEquals(List.of("_id", "name", "price", "stock", "colour"), r.columns());
        assertEquals(5, r.rows().size());
        assertEquals(Arrays.asList("a", "Tea", 3L, 10L, null), r.rows().get(0));
        assertEquals(Arrays.asList("e", null, null, null, "red"), r.rows().get(4));
        assertFalse(r.truncated());
    }

    @Test
    void selectColumnsWhereOrderLimit() throws IOException {
        seed();
        assertEquals(List.of("Tea", "Coffee"), column("SELECT name FROM shop.items WHERE price >= 3", "name"));
        assertEquals(List.of("b", "a", "c"),
                column("SELECT _id FROM shop.items WHERE price > 0 ORDER BY price DESC", "_id"));
        assertEquals(List.of("c", "a", "b"), column("SELECT _id FROM shop.items ORDER BY price LIMIT 3", "_id"));
        assertEquals(List.of(), column("SELECT _id FROM shop.items LIMIT 0", "_id"));
        assertEquals(List.of("a", "b"), column("SELECT _id FROM shop.items LIMIT 2", "_id"));
        assertEquals(Arrays.asList(null, null), column("SELECT nope FROM shop.items LIMIT 2", "nope"));
        assertEquals(List.of(List.of(2L)), rows("SELECT COUNT(*) FROM shop.items WHERE stock > 0").rows());
        assertEquals(List.of("count"), rows("SELECT COUNT(*) FROM shop.items").columns());
    }

    @Test
    void orderByPutsNullsThenBooleansNumbersStrings() throws IOException {
        run("CREATE DATABASE d");
        run("CREATE TABLE d.t");
        run("INSERT INTO d.t (_id, v) VALUES ('1', 'b'), ('2', 2), ('3', true), ('4', null), ('5', 1.5), "
                + "('6', 'a'), ('7', false)");
        run("INSERT INTO d.t (_id) VALUES ('8')");
        assertEquals(List.of("4", "8", "7", "3", "5", "2", "6", "1"),
                column("SELECT _id FROM d.t ORDER BY v", "_id"));
        assertEquals(List.of("1", "6", "2", "5", "3", "7", "4", "8"),
                column("SELECT _id FROM d.t ORDER BY v DESC", "_id"));
    }

    @Test
    void comparisonRules() throws IOException {
        seed();
        // integer and double compare by value
        run("INSERT INTO shop.items (_id, price) VALUES ('f', 3.0)");
        assertEquals(List.of("a", "f"), column("SELECT _id FROM shop.items WHERE price = 3", "_id"));
        assertEquals(List.of("a", "f"), column("SELECT _id FROM shop.items WHERE price = 3.0", "_id"));
        // a string never equals a number, and is not less or greater than one
        assertEquals(List.of(), column("SELECT _id FROM shop.items WHERE price = 2", "_id"));
        assertEquals(List.of("d"), column("SELECT _id FROM shop.items WHERE price = '2'", "_id"));
        assertEquals(List.of("b", "c", "d"), column("SELECT _id FROM shop.items WHERE price != 3", "_id"));
        assertEquals(List.of(), column("SELECT _id FROM shop.items WHERE name > 1", "_id"));
        // null and missing values: comparisons are unknown, IS NULL is true
        assertEquals(List.of("a", "b", "d"), column("SELECT _id FROM shop.items WHERE stock >= 0", "_id"));
        assertEquals(List.of("a", "d"), column("SELECT _id FROM shop.items WHERE stock != 0", "_id"));
        assertEquals(List.of("a", "d"), column("SELECT _id FROM shop.items WHERE NOT stock = 0", "_id"));
        assertEquals(List.of("c", "f"), column("SELECT _id FROM shop.items WHERE stock IS NULL", "_id"));
        assertEquals(List.of(), column("SELECT _id FROM shop.items WHERE stock = null", "_id"));
        assertEquals(List.of(), column("SELECT _id FROM shop.items WHERE NOT stock = null", "_id"));
        // unknown OR true is true; unknown AND false is false, so NOT of it is true
        assertEquals(List.of("a", "b", "c", "d", "f"),
                column("SELECT _id FROM shop.items WHERE stock > 1 OR _id IS NOT NULL", "_id"));
        // f has neither stock nor name: unknown AND unknown is unknown, and so is its NOT
        assertEquals(List.of("a", "b", "c", "d"),
                column("SELECT _id FROM shop.items WHERE NOT (stock > 1 AND name = 'nothing')", "_id"));
        // values keep their case
        assertEquals(List.of(), column("SELECT _id FROM shop.items WHERE name = 'tea'", "_id"));
        assertEquals(List.of("a"), column("SELECT _id FROM shop.items WHERE NAME = 'Tea'", "_id"));
    }

    @Test
    void largeIntegersCompareExactlyWithDoubles() throws IOException {
        run("CREATE DATABASE d");
        run("CREATE TABLE d.t");
        run("INSERT INTO d.t (_id, v) VALUES ('x', 9007199254740993)");
        // 9007199254740993 is not representable as a double; the double literal is 9007199254740992.0
        assertEquals(List.of(), column("SELECT _id FROM d.t WHERE v = 9007199254740993.0", "_id"));
        assertEquals(List.of("x"), column("SELECT _id FROM d.t WHERE v > 9007199254740993.0", "_id"));
    }

    @Test
    void update() throws IOException {
        seed();
        assertEquals(2, affected("UPDATE shop.items SET stock = 0, note = 'out' WHERE price < 2 OR price = '2'"));
        assertEquals(List.of("b", "c", "d"), column("SELECT _id FROM shop.items WHERE stock = 0", "_id"));
        assertEquals(Arrays.asList(null, null, "out", "out"),
                column("SELECT note FROM shop.items", "note"));
        assertEquals(4, affected("UPDATE shop.items SET stock = NULL"));
        assertEquals(List.of(List.of(4L)), rows("SELECT COUNT(*) FROM shop.items WHERE stock IS NULL").rows());
        assertEquals(0, affected("UPDATE shop.items SET stock = 1 WHERE _id = 'zz'"));
        assertEquals("_id cannot be changed", fails("UPDATE shop.items SET _id = 'q'").getMessage());
        fails("UPDATE shop.nope SET a = 1");
    }

    @Test
    void delete() throws IOException {
        seed();
        assertEquals(1, affected("DELETE FROM shop.items WHERE name = 'Milk'"));
        assertEquals(List.of("a", "b", "d"), column("SELECT _id FROM shop.items", "_id"));
        assertEquals(3, affected("DELETE FROM shop.items"));
        assertEquals(List.of(List.of(0L)), rows("SELECT COUNT(*) FROM shop.items").rows());
        fails("DELETE FROM nope.items");
    }

    @Test
    void rowCapAndLimitCap() throws IOException {
        query = new Query(new Executor(engine, new Hlc("n1"), 3));
        seed();
        Result.Rows r = rows("SELECT * FROM shop.items");
        assertEquals(3, r.rows().size());
        assertTrue(r.truncated());
        assertFalse(rows("SELECT * FROM shop.items ORDER BY name LIMIT 3").truncated());
        assertTrue(rows("SELECT * FROM shop.items ORDER BY name").truncated());
        assertEquals(List.of("d", "b", "c"), column("SELECT _id FROM shop.items ORDER BY name", "_id"));
        assertEquals("LIMIT 4 is over the maximum of 3", fails("SELECT * FROM shop.items LIMIT 4").getMessage());
        assertEquals(List.of(List.of(4L)), rows("SELECT COUNT(*) FROM shop.items").rows());
    }

    @Test
    void dataSurvivesRestartAndNewWritesWinWithASlowClock() throws IOException {
        seed();
        run("UPDATE shop.items SET stock = 99 WHERE _id = 'a'");
        engine.close();

        engine = StorageEngine.open(dir, FsyncPolicy.ALWAYS);
        // A clock stuck at 0 would stamp writes older than the logged ones; the executor must move it on.
        query = new Query(engine, new Hlc("n1", () -> 0L));
        assertEquals(List.of(99L), column("SELECT stock FROM shop.items WHERE _id = 'a'", "stock"));
        assertEquals(List.of(List.of(4L)), rows("SELECT COUNT(*) FROM shop.items").rows());
        assertEquals(1, affected("UPDATE shop.items SET stock = 7 WHERE _id = 'a'"));
        assertEquals(List.of(7L), column("SELECT stock FROM shop.items WHERE _id = 'a'", "stock"));
        assertEquals(1, affected("DELETE FROM shop.items WHERE _id = 'b'"));
        engine.close();

        engine = StorageEngine.open(dir, FsyncPolicy.ALWAYS);
        query = new Query(engine, new Hlc("n1"));
        assertEquals(List.of("a", "c", "d"), column("SELECT _id FROM shop.items", "_id"));
        assertEquals(List.of(7L), column("SELECT stock FROM shop.items WHERE _id = 'a'", "stock"));
    }

    @Test
    void resultsSerialiseToJson() throws IOException {
        seed();
        String rows = Json.write(run("SELECT _id, stock FROM shop.items WHERE _id = 'c'").toJson());
        assertEquals("{\"type\":\"rows\",\"columns\":[\"_id\",\"stock\"],\"rows\":[[\"c\",null]],"
                + "\"count\":1,\"truncated\":false}", rows);
        assertEquals("{\"type\":\"write\",\"affected\":1,\"ids\":[\"k\"]}",
                Json.write(run("INSERT INTO shop.items (_id) VALUES ('k')").toJson()));
        assertEquals("{\"type\":\"write\",\"affected\":2}",
                Json.write(run("DELETE FROM shop.items WHERE price < 2 OR _id = 'k'").toJson()));
        Map<String, Object> parsed = Json.parseObject(Json.write(run("SELECT * FROM shop.items").toJson()));
        assertNotNull(parsed.get("rows"));
    }

    @Test
    void parseErrorsComeThroughRun() {
        ParseException e = assertThrows(ParseException.class, () -> run("SELEC * FROM shop.items"));
        assertEquals(0, e.position());
    }

    @Test
    void showConflictsListsConcurrentVersionsUntilAWriteResolvesThem() throws IOException {
        run("CREATE DATABASE shop");
        run("CREATE TABLE shop.items");
        run("INSERT INTO shop.items (_id, name, qty) VALUES ('bolt', 'bolt', 120), ('nut', 'nut', 300)");
        assertEquals(List.of(), rows("SHOW CONFLICTS IN shop.items").rows());
        // A write from a node that never saw this node's insert of bolt: the two are concurrent.
        long later = System.currentTimeMillis() + 1000;
        var other = new com.ataiva.serengeti.core.storage.Op.Put("shop", "items", "bolt",
                Map.of("name", "bolt", "qty", 90L), new com.ataiva.serengeti.core.storage.Stamp(later, 0, "n2"), Map.of());
        assertEquals(1, engine.merge(List.of(other)));
        assertEquals(List.of(90L), column("SELECT qty FROM shop.items WHERE _id = 'bolt'", "qty"),
                "the newer version is the row's value");
        Result.Rows c = rows("SHOW CONFLICTS IN shop.items");
        assertEquals(List.of("_id", "current", "deleted", "node", "at", "row"), c.columns());
        assertEquals(2, c.rows().size());
        assertEquals(Arrays.asList("bolt", true, false, "n2", later), c.rows().get(0).subList(0, 5));
        assertEquals(Map.of("name", "bolt", "qty", 90L), Json.parse(Json.write(c.rows().get(0).get(5))));
        assertEquals(Arrays.asList("bolt", false, false, "n1"), c.rows().get(1).subList(0, 4));
        assertEquals(Map.of("name", "bolt", "qty", 120L), Json.parse(Json.write(c.rows().get(1).get(5))));
        // Merging the same version again changes nothing.
        assertEquals(0, engine.merge(List.of(other)));
        // A write made here has seen both, so it resolves the conflict.
        assertEquals(1, affected("UPDATE shop.items SET qty = 210 WHERE _id = 'bolt'"));
        assertEquals(List.of(), rows("SHOW CONFLICTS IN shop.items").rows());
        assertEquals(List.of(210L), column("SELECT qty FROM shop.items WHERE _id = 'bolt'", "qty"));
        assertEquals("table 'shop.nope' does not exist", fails("SHOW CONFLICTS IN shop.nope").getMessage());
    }

    @Test
    void aWriteFromAPeerThatHadSeenThisOneReplacesItWithoutAConflict() throws IOException {
        run("CREATE DATABASE shop");
        run("CREATE TABLE shop.items");
        run("INSERT INTO shop.items (_id, qty) VALUES ('bolt', 1)");
        var mine = engine.read(s -> s.toOps("shop")).stream()
                .filter(o -> o instanceof com.ataiva.serengeti.core.storage.Op.Put).findFirst().orElseThrow();
        var seen = Map.of(mine.stamp().node(), mine.stamp());
        long later = System.currentTimeMillis() + 1000;
        engine.merge(List.of(new com.ataiva.serengeti.core.storage.Op.Put("shop", "items", "bolt", Map.of("qty", 2L),
                new com.ataiva.serengeti.core.storage.Stamp(later, 0, "n2"), seen)));
        assertEquals(List.of(2L), column("SELECT qty FROM shop.items", "qty"));
        assertEquals(List.of(), rows("SHOW CONFLICTS IN shop.items").rows());
    }

    @Test
    void indexesChangeNoResultOnlyHowRowsAreFound() throws IOException {
        run("CREATE DATABASE shop");
        run("CREATE TABLE shop.items");
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            String qty = i % 7 == 0 ? "'" + i + "'" : i % 11 == 0 ? "null" : i % 5 == 0 ? (i + 0.5) + "" : i + "";
            values.append(i == 0 ? "" : ", ").append("('r").append(1000 + i).append("', ").append(qty)
                    .append(", ").append(i % 3).append(")");
        }
        run("INSERT INTO shop.items (_id, qty, kind) VALUES " + values);
        List<String> queries = List.of(
                "SELECT _id FROM shop.items WHERE qty = 10",
                "SELECT _id FROM shop.items WHERE qty = 10.0",
                "SELECT _id FROM shop.items WHERE qty < 20",
                "SELECT _id FROM shop.items WHERE qty >= 190.5",
                "SELECT _id FROM shop.items WHERE qty > '15'",
                "SELECT _id FROM shop.items WHERE qty <= 50 AND kind = 1",
                "SELECT _id FROM shop.items WHERE kind = 2 AND qty > 100 ORDER BY qty DESC LIMIT 5",
                "SELECT COUNT(*) FROM shop.items WHERE qty > 30",
                "SELECT _id FROM shop.items WHERE qty != 10 AND qty < 13",
                "SELECT _id FROM shop.items WHERE _id = 'r1005'",
                "SELECT _id FROM shop.items WHERE _id > 'r1195'",
                "SELECT _id FROM shop.items WHERE qty = true OR qty = 3");
        List<Object> before = new ArrayList<>();
        for (String q : queries) {
            before.add(rows(q).rows());
        }
        assertEquals(1, affected("CREATE INDEX ON shop.items (qty)"));
        assertEquals(0, affected("CREATE INDEX IF NOT EXISTS ON shop.items (qty)"));
        assertEquals("'shop.items' already has an index on qty", fails("CREATE INDEX ON shop.items (qty)").getMessage());
        assertEquals(List.of(List.of("qty")), rows("SHOW INDEXES IN shop.items").rows());
        for (int i = 0; i < queries.size(); i++) {
            assertEquals(before.get(i), rows(queries.get(i)).rows(), queries.get(i));
        }
        // Writes keep the index in step.
        assertEquals(1, affected("UPDATE shop.items SET qty = 10 WHERE _id = 'r1003'"));
        assertEquals(1, affected("DELETE FROM shop.items WHERE _id = 'r1010'"));
        assertEquals(List.of(List.of("r1003")), rows("SELECT _id FROM shop.items WHERE qty = 10").rows());
        assertEquals(List.of(List.of("r1000")), rows("SELECT _id FROM shop.items WHERE qty = '0'").rows());
        assertEquals(1, affected("DROP INDEX ON shop.items (qty)"));
        assertEquals(0, affected("DROP INDEX IF EXISTS ON shop.items (qty)"));
        assertEquals(0, affected("DROP INDEX IF EXISTS ON shop.missing (qty)"), "like DROP TABLE IF EXISTS");
        assertEquals(0, affected("DROP INDEX IF EXISTS ON nodb.missing (qty)"));
        assertTrue(fails("DROP INDEX ON shop.missing (qty)").getMessage().contains("does not exist"));
        assertEquals("'shop.items' has no index on qty", fails("DROP INDEX ON shop.items (qty)").getMessage());
        assertEquals(List.of(), rows("SHOW INDEXES IN shop.items").rows());
        assertEquals(List.of(List.of("r1003")), rows("SELECT _id FROM shop.items WHERE qty = 10").rows());
        assertEquals("_id needs no index: lookups by _id never scan", fails("CREATE INDEX ON shop.items (_id)").getMessage());
        assertEquals("table 'shop.nope' does not exist", fails("CREATE INDEX ON shop.nope (qty)").getMessage());
    }

    @Test
    void anIndexOnSeveralColumnsAnswersEqualPrefixesAndARange() throws IOException {
        run("CREATE DATABASE shop");
        run("CREATE TABLE shop.items");
        StringBuilder values = new StringBuilder();
        String[] names = {"apple", "apricot", "banana", "", "b%x", "Apple"};
        for (int i = 0; i < 300; i++) {
            String kind = i % 17 == 0 ? "null" : i % 13 == 0 ? "'1'" : i % 11 == 0 ? (i % 3) + ".0" : Integer.toString(i % 3);
            String qty = i % 19 == 0 ? "null" : i % 7 == 0 ? "'" + (i % 10) + "'" : i % 5 == 0 ? (i % 20) + ".5"
                    : i % 23 == 0 ? "true" : Integer.toString(i % 20);
            values.append(i == 0 ? "" : ", ").append("('r").append(1000 + i).append("', ").append(kind).append(", ")
                    .append(qty).append(", '").append(names[i % names.length]).append(i % 4).append("')");
        }
        run("INSERT INTO shop.items (_id, kind, qty, name) VALUES " + values);
        run("INSERT INTO shop.items (_id, qty) VALUES ('nokind', 5)");
        run("INSERT INTO shop.items (_id, kind) VALUES ('noqty', 1)");
        List<String> conditions = List.of("kind = 1 AND qty = 5", "qty = 5 AND kind = 1.0", "kind = 1 AND qty > 5",
                "kind = 1 AND qty >= 5 AND qty < 12", "kind = 1 AND qty > 5 AND qty < 3", "kind = 1 AND qty > 5 AND qty < 'z'",
                "kind IN (0, 2, '1') AND qty <= 10", "kind IN (1, NULL) AND qty IN (2, 4.5, '3')", "kind = NULL AND qty = 1",
                "kind = 1 AND qty LIKE '3%'", "kind = 1 AND qty LIKE '3'", "kind = 2 AND qty = 4 AND name LIKE 'ap%'",
                "kind = 2 AND qty = 4 AND name = 'apple0'", "kind = 0 AND name = 'banana2'", "kind > 0",
                "kind = 1 AND qty IS NULL", "kind = 1 AND qty != 5", "qty = 5", "kind LIKE '1%' AND qty < 100",
                "kind = 1 AND qty = TRUE", "kind = 1 AND qty = 5 AND _id > 'r1100'", "kind = 1 AND qty > 2 AND kind = 2");
        List<Object> before = new ArrayList<>();
        for (String w : conditions) {
            before.add(List.of(rows("SELECT _id, kind, qty FROM shop.items WHERE " + w).rows(),
                    rows("SELECT COUNT(*) FROM shop.items WHERE " + w).rows(),
                    rows("SELECT _id FROM shop.items WHERE " + w + " ORDER BY _id LIMIT 3").rows()));
        }
        assertEquals(1, affected("CREATE INDEX ON shop.items (kind, qty)"));
        assertEquals(1, affected("CREATE INDEX ON shop.items (kind, qty, name)"));
        assertEquals(1, affected("CREATE INDEX ON shop.items (qty, kind)"), "another order is another index");
        assertEquals(0, affected("CREATE INDEX IF NOT EXISTS ON shop.items (kind, qty)"));
        assertEquals("'shop.items' already has an index on kind, qty", fails("CREATE INDEX ON shop.items (kind, qty)").getMessage());
        assertEquals("_id cannot be a column of an index: lookups by _id never scan",
                fails("CREATE INDEX ON shop.items (kind, _id)").getMessage());
        assertEquals(List.of(List.of("kind, qty"), List.of("kind, qty, name"), List.of("qty, kind")),
                rows("SHOW INDEXES IN shop.items").rows());
        for (int i = 0; i < conditions.size(); i++) {
            String w = conditions.get(i);
            assertEquals(before.get(i), List.of(rows("SELECT _id, kind, qty FROM shop.items WHERE " + w).rows(),
                    rows("SELECT COUNT(*) FROM shop.items WHERE " + w).rows(),
                    rows("SELECT _id FROM shop.items WHERE " + w + " ORDER BY _id LIMIT 3").rows()), w);
        }
        // The read that names the most columns is taken; of those, the one with the fewest rows.
        assertEquals(plan("kind,qty", List.of(List.of(1L)), new Store.Lookup.Compare(">", 5L)), planOf("kind = 1 AND qty > 5"));
        assertEquals(plan("kind,qty,name", List.of(List.of(2L), List.of(4L)), new Store.Lookup.Prefix("ap")),
                planOf("name LIKE 'ap%' AND qty = 4 AND kind = 2"));
        assertEquals(plan("kind,qty,name", List.of(List.of(2L), List.of(4L), List.of("apple0"))),
                planOf("kind = 2 AND qty = 4 AND name = 'apple0'"));
        assertEquals(plan("kind,qty", List.of(Arrays.asList(1L, null), List.of(2L, 4.5, "3"))),
                planOf("kind IN (1, NULL) AND qty IN (2, 4.5, '3')"), "IN lists: their product");
        assertEquals(plan("kind,qty", List.of(List.of(1L)), new Store.Lookup.Compare(">=", 5L), new Store.Lookup.Compare("<", 12L)),
                planOf("kind = 1 AND qty >= 5 AND qty < 12 AND name != 'x'"), "both ends of a range");
        assertEquals(new Executor.Plan("_id", new Store.Lookup.Compare("=", "r1003")), planOf("kind = 0 AND _id = 'r1003'"),
                "one column named either way: _id has fewer rows");
        run("CREATE INDEX ON shop.items (qty)");
        assertEquals(plan("kind,qty", List.of(List.of(1L), List.of(5L))), planOf("qty = 5 AND kind = 1"),
                "two columns over one");
        assertEquals(plan("qty,kind", List.of(List.of(5L)), new Store.Lookup.Compare(">", 0L)), planOf("qty = 5 AND kind > 0"));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            many.append(i == 0 ? "" : ", ").append(i);
        }
        Executor.Plan capped = planOf("kind IN (" + many + ") AND qty IN (" + many + ")");
        assertEquals(1, capped.named(), "40 x 40 tuples are over the 1,000 a read looks up: one column only");
        // Writes keep the index in step, and UPDATE and DELETE find their rows through it.
        assertEquals(1, affected("UPDATE shop.items SET qty = 99 WHERE kind = 1 AND qty = 1 AND _id = 'r1001'"));
        assertEquals(List.of(List.of("r1001")), rows("SELECT _id FROM shop.items WHERE kind = 1 AND qty = 99").rows());
        long n = (Long) rows("SELECT COUNT(*) FROM shop.items WHERE kind = 2 AND qty >= 10").rows().get(0).get(0);
        assertEquals(n, affected("DELETE FROM shop.items WHERE kind = 2 AND qty >= 10"));
        assertEquals(List.of(List.of(0L)), rows("SELECT COUNT(*) FROM shop.items WHERE kind = 2 AND qty >= 10").rows());
        assertEquals(1, affected("DROP INDEX ON shop.items (kind, qty)"));
        assertEquals("'shop.items' has no index on kind, qty", fails("DROP INDEX ON shop.items (kind, qty)").getMessage());
        assertEquals(0, affected("DROP INDEX IF EXISTS ON shop.items (kind, qty)"));
        assertEquals(List.of(List.of("kind, qty, name"), List.of("qty"), List.of("qty, kind")),
                rows("SHOW INDEXES IN shop.items").rows());
        // It survives a restart, from the index file, built again from the rows.
        engine.checkpoint();
        engine.close();
        engine = StorageEngine.open(dir, FsyncPolicy.ALWAYS);
        query = new Query(engine, new Hlc("n1"));
        assertEquals(List.of(List.of("kind, qty, name"), List.of("qty"), List.of("qty, kind")),
                rows("SHOW INDEXES IN shop.items").rows());
        assertEquals(List.of(List.of("r1001")), rows("SELECT _id FROM shop.items WHERE kind = 1 AND qty = 99").rows());
        assertEquals(plan("kind,qty,name", List.of(List.of(1L), List.of(99L))), planOf("kind = 1 AND qty = 99"), "a tie: the first in name order");
    }

    private static Executor.Plan plan(String index, List<List<Object>> equal, Store.Lookup... range) {
        List<Store.Lookup> r = new ArrayList<>();
        for (Store.Lookup l : range) {
            if (l != null) {
                r.add(l);
            }
        }
        return new Executor.Plan(index, new Store.Lookup.Composite(equal, r));
    }

    private Executor.Plan planOf(String where) {
        Statement.Select s = (Statement.Select) Parser.parse("SELECT * FROM shop.items WHERE " + where);
        return engine.read(st -> Executor.cheapestIndexedTerm(st, "shop", "items", s.where()));
    }

    @Test
    void anIndexSurvivesARestartAndADroppedTableTakesItsIndexes() throws IOException {
        run("CREATE DATABASE shop");
        run("CREATE TABLE shop.items");
        run("INSERT INTO shop.items (_id, qty) VALUES ('a', 1), ('b', 2)");
        run("CREATE INDEX ON shop.items (qty)");
        engine.checkpoint();
        run("INSERT INTO shop.items (_id, qty) VALUES ('c', 2)");
        engine.close();
        engine = StorageEngine.open(dir, FsyncPolicy.ALWAYS);
        query = new Query(engine, new Hlc("n1"));
        assertEquals(List.of(List.of("qty")), rows("SHOW INDEXES IN shop.items").rows());
        assertEquals(List.of(List.of("b"), List.of("c")), rows("SELECT _id FROM shop.items WHERE qty = 2").rows());
        run("DROP TABLE shop.items");
        run("CREATE TABLE shop.items");
        assertEquals(List.of(), rows("SHOW INDEXES IN shop.items").rows());
    }

    private List<String> ids(String where) throws IOException {
        List<String> out = new ArrayList<>();
        for (Object id : column("SELECT _id FROM shop.items WHERE " + where, "_id")) {
            out.add((String) id);
        }
        return out;
    }

    @Test
    void inIsAnOrOfEqualities() throws IOException {
        seed();
        run("INSERT INTO shop.items (_id, price, stock) VALUES ('e', 3.0, true), ('f', 'Tea', false)");
        assertEquals(List.of("a", "d", "e"), ids("price IN (3, '2')"));
        assertEquals(List.of("a", "e"), ids("price IN (3.0)"), "3 and 3.0 are equal, as with =");
        assertEquals(List.of("b", "c", "f"), ids("price NOT IN (3, '2')"), "a string is not a number");
        assertEquals(List.of("a", "b"), ids("_id IN ('b', 'a', 'zz', 1, 'a')"));
        assertEquals(List.of("e", "f"), ids("stock IN (TRUE, FALSE)"));
        // A row with no value, or a null in the list, makes the comparisons unknown.
        assertEquals(List.of("a", "b", "d", "e", "f"), ids("stock NOT IN (7)"), "c has a null stock");
        assertEquals(List.of("a"), ids("stock IN (10, NULL)"));
        assertEquals(List.of(), ids("stock NOT IN (10, NULL)"), "NOT IN with a null is never true");
        assertEquals(List.of("a", "c"), ids("stock IN (10, NULL) OR stock IS NULL"));
        assertEquals(List.of("b", "c", "d", "f"), ids("NOT (stock IN (10, NULL) AND price = 3)"),
                "unknown AND false is false; e is unknown AND true");
        // Each is exactly the OR it stands for.
        for (String[] pair : new String[][] {
                {"price IN (3, '2', NULL)", "price = 3 OR price = '2' OR price = NULL"},
                {"price NOT IN (3, '2', NULL)", "NOT (price = 3 OR price = '2' OR price = NULL)"},
                {"stock NOT IN (0, 10)", "NOT (stock = 0 OR stock = 10)"},
                {"name IN ('Tea', 'Milk') AND stock IN (10)", "(name = 'Tea' OR name = 'Milk') AND stock = 10"}}) {
            assertEquals(ids(pair[1]), ids(pair[0]), pair[0]);
        }
    }

    @Test
    void likeMatchesPatterns() throws IOException {
        seed();
        String emoji = new String(Character.toChars(0x1F600));
        run("INSERT INTO shop.items (_id, name) VALUES ('e', '100%'), ('f', '100x'), ('g', 'a_b'), ('h', 'axb'), "
                + "('i', 'back\\slash'), ('j', '" + emoji + "x'), ('k', ''), ('l', 'C')");
        assertEquals(List.of("b", "d", "l"), ids("name LIKE 'C%'"));
        assertEquals(List.of(), ids("name LIKE 'c%'"), "case-sensitive");
        assertEquals(List.of("b", "d"), ids("name LIKE '%e'"));
        assertEquals(List.of("a"), ids("name LIKE '_ea'"));
        assertEquals(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"), ids("name LIKE '%'"));
        assertEquals(List.of("k"), ids("name LIKE ''"));
        assertEquals(List.of("e"), ids("name LIKE '100\\%'"));
        assertEquals(List.of("e", "f"), ids("name LIKE '100_'"));
        assertEquals(List.of("g"), ids("name LIKE 'a\\_b'"));
        assertEquals(List.of("g", "h"), ids("name LIKE 'a_b'"));
        assertEquals(List.of("i"), ids("name LIKE '%\\\\%'"));
        assertEquals(List.of("j"), ids("name LIKE '__x'"), "an emoji is two UTF-16 code units");
        assertEquals(List.of(), ids("name LIKE '_x'"));
        assertEquals(List.of("a", "b", "c", "d", "l"), ids("name LIKE '%_%' AND name NOT LIKE '%\\_%'"
                + " AND name NOT LIKE '%x%' AND name NOT LIKE '%h%' AND name NOT LIKE '%0%'"));
        // A value that is not a string: LIKE is false, NOT LIKE true; null or missing: unknown.
        assertEquals(List.of("d"), ids("price LIKE '%'"));
        assertEquals(List.of("a", "b", "c"), ids("price NOT LIKE '%'"));
        assertEquals(List.of(), ids("stock LIKE '%' OR stock NOT LIKE '%' AND _id = 'c'"));
        // Without wildcards, LIKE is =.
        for (String[] pair : new String[][] {{"price LIKE '2'", "price = '2'"}, {"price NOT LIKE '2'", "price != '2'"},
                {"name LIKE '100%%'", "name LIKE '100%'"}}) {
            assertEquals(ids(pair[1]), ids(pair[0]), pair[0]);
        }
    }

    @Test
    void inAndLikeThroughAnIndexGiveTheRowsAScanDoes() throws IOException {
        run("CREATE DATABASE shop");
        run("CREATE TABLE shop.items");
        StringBuilder values = new StringBuilder();
        String top = String.valueOf(Character.MAX_VALUE); // the last UTF-16 code unit, where a prefix range ends
        String[] words = {"apple", "apricot", "banana", "b%nd", "b_x", "", "Apple", top, top + "a", "a" + top};
        for (int i = 0; i < 300; i++) {
            String v = i % 9 == 0 ? "null" : i % 4 == 0 ? Integer.toString(i % 13) : i % 7 == 0 ? (i % 13) + ".0"
                    : i % 11 == 0 ? "true" : "'" + words[i % words.length] + (i % 3 == 0 ? "" : i % 5) + "'";
            values.append(i == 0 ? "" : ", ").append("('r").append(1000 + i).append("', ").append(v)
                    .append(", ").append(i % 3).append(")");
        }
        run("INSERT INTO shop.items (_id, v, kind) VALUES " + values);
        List<String> queries = List.of(
                "v IN (1, 2.0, 'apple1', TRUE, NULL)", "v IN ('nothing')", "v NOT IN (1, 2, 'apple1')",
                "v IN (3) AND kind = 1", "kind = 2 AND v IN (1, 'banana2', 'b%nd4')", "v IN (1) OR v IN (2)",
                "v LIKE 'ap%'", "v LIKE 'ap%' AND kind = 0", "v LIKE 'b\\%%'", "v LIKE 'b\\_%'", "v LIKE 'b_%'",
                "v LIKE '%1'", "v LIKE '_pple%'", "v LIKE 'apple'", "v LIKE 'apple1'", "v NOT LIKE 'ap%'",
                "v LIKE ''", "v LIKE '" + top + "%'", "v LIKE 'a" + top + "%'", "v LIKE 'A%' OR v LIKE 'a%'",
                "_id IN ('r1001', 'r1299', 'r0', 'r1001')", "_id LIKE 'r12%'", "_id LIKE 'r12_'", "_id LIKE 'r1000'",
                "_id LIKE 'r12%' AND v LIKE 'b%'", "_id NOT IN ('r1001')");
        List<Object> before = new ArrayList<>();
        for (String w : queries) {
            before.add(List.of(rows("SELECT _id, v FROM shop.items WHERE " + w).rows(),
                    rows("SELECT COUNT(*) FROM shop.items WHERE " + w).rows()));
        }
        run("CREATE INDEX ON shop.items (v)");
        run("CREATE INDEX ON shop.items (kind)");
        for (int i = 0; i < queries.size(); i++) {
            String w = queries.get(i);
            assertEquals(before.get(i), List.of(rows("SELECT _id, v FROM shop.items WHERE " + w).rows(),
                    rows("SELECT COUNT(*) FROM shop.items WHERE " + w).rows()), w);
            assertEquals(rows("SELECT _id FROM shop.items WHERE (" + w + ") OR (" + w + ")").rows(),
                    rows("SELECT _id FROM shop.items WHERE " + w).rows(), w);
        }
        // The index is the one the term finds, and a write moves rows in and out of it.
        Statement.Select s = (Statement.Select) Parser.parse("SELECT * FROM shop.items WHERE v LIKE 'ap%' AND kind = 0");
        assertEquals(new Executor.Plan("v", new com.ataiva.serengeti.core.storage.Store.Lookup.Prefix("ap")),
                engine.read(st -> Executor.cheapestIndexedTerm(st, "shop", "items", s.where())));
        assertEquals(1, affected("UPDATE shop.items SET v = 'apex' WHERE _id = 'r1002'"));
        assertEquals(1, affected("DELETE FROM shop.items WHERE v IN ('apex') AND kind = 2"));
        assertEquals(List.of(), rows("SELECT _id FROM shop.items WHERE v LIKE 'apex%'").rows());
    }

    @Test
    void aggregatesWithoutGroupByMakeOneRow() throws IOException {
        seed();
        Result.Rows r = rows("SELECT COUNT(*), COUNT(stock), SUM(price), MIN(price), MAX(price), AVG(stock), SUM(stock) "
                + "FROM shop.items");
        assertEquals(List.of("count", "count(stock)", "sum(price)", "min(price)", "max(price)", "avg(stock)", "sum(stock)"),
                r.columns());
        // The string '2' is not added; MIN and MAX use the ORDER BY order, where numbers come before strings.
        assertEquals(List.of(Arrays.asList(4L, 3L, 8.5, 1L, "2", 5.0, 15L)), r.rows());
        assertFalse(r.truncated());
        assertEquals(List.of(Arrays.asList(0L, 0L, null, null, null, null)),
                rows("SELECT COUNT(*), COUNT(name), SUM(price), MIN(name), MAX(name), AVG(price) FROM shop.items "
                        + "WHERE price > 100").rows(), "over no rows");
        assertEquals(List.of(Arrays.asList(4L, null, null, "Cake", "Tea")),
                rows("SELECT COUNT(name), SUM(name), AVG(name), MIN(name), MAX(name) FROM shop.items").rows(),
                "no numbers: SUM and AVG are null");
        assertEquals(List.of(Arrays.asList(4L, 4L, "a", "d")),
                rows("SELECT COUNT(*), COUNT(_id), MIN(_id), MAX(_id) FROM shop.items").rows());
        assertEquals(List.of(List.of(2L, 2L)), rows("SELECT COUNT(*), COUNT(*) FROM shop.items WHERE stock > 0").rows());
        assertEquals(List.of("Tea"), column("SELECT MAX(name) FROM shop.items WHERE _id IN ('a', 'c')", "max(name)"));
        query.run("SELECT SUM(price) FROM shop.items", false); // the read role may run it
    }

    @Test
    void sumAndAvgFollowTheNumberRules() throws IOException {
        run("CREATE DATABASE d");
        run("CREATE TABLE d.t");
        run("INSERT INTO d.t (_id, g, n) VALUES ('1', 'max', 9223372036854775807), ('2', 'max', 1), ('3', 'max', -1), "
                + "('4', 'over', 9223372036854775807), ('5', 'over', 1), ('6', 'dbl', 9223372036854775807), "
                + "('7', 'dbl', 9223372036854775807), ('8', 'dbl', 0.5), ('9', 'neg', -0.0), ('a', 'neg', -0.0), "
                + "('b', 'big', 1e308), ('c', 'big', 1e308), ('d', 'mix', 1), ('e', 'mix', 2.5), ('f', 'mix', 'x'), "
                + "('g', 'mix', TRUE), ('h', 'mix', NULL), ('i', 'min', -9223372036854775808), ('j', 'min', -1), "
                + "('k', 'min', 1)");
        String sum = "SELECT SUM(n), AVG(n) FROM d.t WHERE g = ";
        // Integers are added exactly: an overflow on the way that comes back is not an error.
        assertEquals(List.of(List.of(Long.MAX_VALUE, (double) Long.MAX_VALUE / 3)), rows(sum + "'max'").rows());
        assertEquals("SUM overflows a 64-bit integer", fails(sum.replace(", AVG(n)", "") + "'over'").getMessage());
        assertEquals(List.of(List.of(4.611686018427388E18)), rows("SELECT AVG(n) FROM d.t WHERE g = 'over'").rows(),
                "AVG is a double, so it does not overflow there");
        assertEquals(List.of(List.of(1.8446744073709552E19)), rows("SELECT SUM(n) FROM d.t WHERE g = 'dbl'").rows(),
                "once a double is added the sum is a double");
        Result.Rows neg = rows(sum + "'neg'");
        assertEquals("-0.0", neg.rows().get(0).get(0).toString());
        assertEquals("SUM overflows a double", fails(sum.replace(", AVG(n)", "") + "'big'").getMessage());
        assertEquals("AVG overflows a double", fails("SELECT AVG(n) FROM d.t WHERE g = 'big'").getMessage());
        // Only numbers are added: 'x', TRUE and NULL are skipped, though COUNT(n) counts all but the null.
        assertEquals(List.of(List.of(3.5, 1.75, 4L, 5L)),
                rows("SELECT SUM(n), AVG(n), COUNT(n), COUNT(*) FROM d.t WHERE g = 'mix'").rows());
        assertEquals(List.of(List.of(Long.MIN_VALUE)), rows("SELECT SUM(n) FROM d.t WHERE g = 'min'").rows());
        assertEquals(List.of(List.of(Long.MAX_VALUE)),
                rows("SELECT SUM(n) FROM d.t WHERE g IN ('min', 'over') OR _id = '1'").rows(),
                "MAX + MAX + 1 + MIN - 1 + 1 fits, so it is not an error");
        // A group that overflows fails the statement; groups are summed in GROUP BY order, so 'big' comes first.
        assertEquals("SUM overflows a 64-bit integer",
                fails("SELECT g, SUM(n) FROM d.t WHERE g != 'big' GROUP BY g").getMessage());
        assertEquals("SUM overflows a double", fails("SELECT g, SUM(n) FROM d.t GROUP BY g ORDER BY g DESC").getMessage());
    }

    @Test
    void groupByMakesOneRowPerValue() throws IOException {
        run("CREATE DATABASE d");
        run("CREATE TABLE d.t");
        run("INSERT INTO d.t (_id, k, n, s) VALUES ('01', 'b', 1, 'x'), ('02', 'a', 2, 'y'), ('03', 'b', 3, NULL), "
                + "('04', 1, 4, 'x'), ('05', 1.0, 5.5, 'x'), ('06', NULL, 6, 'z'), ('07', TRUE, 7, 'x'), "
                + "('08', 'a', NULL, 'y'), ('09', FALSE, 'nine', 'x')");
        run("INSERT INTO d.t (_id, n) VALUES ('10', 10)");
        Result.Rows r = rows("SELECT k, COUNT(*), COUNT(n), SUM(n), MIN(n), MAX(s) FROM d.t GROUP BY k");
        assertEquals(List.of("k", "count", "count(n)", "sum(n)", "min(n)", "max(s)"), r.columns());
        // null (with missing) first, then false, true, numbers (1 and 1.0 are one group, shown as first seen), strings.
        assertEquals(List.of(
                Arrays.asList(null, 2L, 2L, 16L, 6L, "z"),
                Arrays.asList(false, 1L, 1L, null, "nine", "x"),
                Arrays.asList(true, 1L, 1L, 7L, 7L, "x"),
                Arrays.asList(1L, 2L, 2L, 9.5, 4L, "x"),
                Arrays.asList("a", 2L, 1L, 2L, 2L, "y"),
                Arrays.asList("b", 2L, 2L, 4L, 1L, "x")), r.rows());
        assertEquals(List.of("s", "count"), rows("SELECT s, COUNT(*) FROM d.t GROUP BY s").columns());
        assertEquals(List.of(Arrays.asList(null, 2L), List.of("x", 5L), List.of("y", 2L), List.of("z", 1L)),
                rows("SELECT s, COUNT(*) FROM d.t GROUP BY s").rows());
        // ORDER BY an aggregate of the list, ties by the GROUP BY values; LIMIT counts groups.
        assertEquals(List.of(List.of("x", 5L), Arrays.asList(null, 2L)),
                rows("SELECT s, COUNT(*) FROM d.t GROUP BY s ORDER BY count(*) DESC LIMIT 2").rows());
        assertEquals(List.of(List.of("z", 1L), Arrays.asList(null, 2L), List.of("y", 2L), List.of("x", 5L)),
                rows("SELECT s, COUNT(*) FROM d.t GROUP BY s ORDER BY COUNT(*)").rows());
        assertEquals(List.of(List.of("z"), List.of("y"), List.of("x"), Arrays.asList((Object) null)),
                rows("SELECT s FROM d.t GROUP BY s ORDER BY s DESC").rows(), "GROUP BY alone lists the distinct values");
        // Two columns; a GROUP BY column need not be listed, and ORDER BY may name it.
        assertEquals(List.of(List.of(1L), List.of(1L), List.of(2L)),
                rows("SELECT COUNT(*) FROM d.t WHERE k IN ('a', 'b') GROUP BY k, s ORDER BY k DESC").rows());
        assertEquals(List.of(Arrays.asList("a", "y", 2L), Arrays.asList("b", null, 1L), Arrays.asList("b", "x", 1L)),
                rows("SELECT k, s, COUNT(*) FROM d.t WHERE k IN ('a', 'b') GROUP BY k, s").rows());
        assertEquals(List.of(Arrays.asList("b", null, 1L), Arrays.asList("b", "x", 1L), Arrays.asList("a", "y", 2L)),
                rows("SELECT k, s, COUNT(*) FROM d.t WHERE k IN ('a', 'b') GROUP BY s, k ORDER BY k DESC").rows());
        assertEquals(List.of(), rows("SELECT k, COUNT(*) FROM d.t WHERE n > 100 GROUP BY k").rows(), "no rows, no groups");
        assertEquals(List.of(List.of(2L, 4L)), rows("SELECT COUNT(*), SUM(n) FROM d.t WHERE k = 'b' GROUP BY k").rows());
    }

    @Test
    void groupsOfObjectsAndArrays() throws IOException {
        run("CREATE DATABASE d");
        run("CREATE TABLE d.t");
        Map<String, Object> ab = new java.util.LinkedHashMap<>();
        ab.put("a", 1L);
        ab.put("b", 2L);
        Map<String, Object> ba = new java.util.LinkedHashMap<>();
        ba.put("b", 2L);
        ba.put("a", 1L);
        var at = new com.ataiva.serengeti.core.storage.Stamp(System.currentTimeMillis() + 1000, 0, "n1");
        engine.apply(List.of(
                new com.ataiva.serengeti.core.storage.Op.Put("d", "t", "1", Map.of("v", ab, "n", 1L), at),
                new com.ataiva.serengeti.core.storage.Op.Put("d", "t", "2", Map.of("v", ba, "n", 2L), at),
                new com.ataiva.serengeti.core.storage.Op.Put("d", "t", "3", Map.of("v", List.of(1L, 2L), "n", 3L), at),
                new com.ataiva.serengeti.core.storage.Op.Put("d", "t", "4", Map.of("v", 5L, "n", 4L), at)));
        Result.Rows r = rows("SELECT v, COUNT(*), SUM(n) FROM d.t GROUP BY v");
        // Objects and arrays come after numbers (the ORDER BY order), by their JSON text with keys sorted, so
        // [1,2] before {"a":1,"b":2}; the two objects are one group, shown as the first one seen.
        assertEquals(List.of(List.of(5L, 1L, 4L), List.of(List.of(1L, 2L), 1L, 3L), List.of(ab, 2L, 3L)), r.rows());
        assertEquals("{\"a\":1,\"b\":2}", com.ataiva.serengeti.core.json.Json.write(r.rows().get(2).get(0)));
        assertEquals(List.of(List.of(5L, ab)), rows("SELECT MIN(v), MAX(v) FROM d.t").rows(),
                "MAX is an object, the first of the equal ones in _id order");
        assertEquals(List.of(List.of(4L)), rows("SELECT COUNT(v) FROM d.t").rows());
        assertEquals(List.of(List.of(5L), List.of(List.of(1L, 2L)), List.of(ab)), rows("SELECT DISTINCT v FROM d.t").rows());
        assertEquals(List.of(List.of(3L, 4L)), rows("SELECT COUNT(DISTINCT v), COUNT(v) FROM d.t").rows(),
                "the two equal objects count once");
    }

    @Test
    void selectDistinctIsItsGroupByForm() throws IOException {
        run("CREATE DATABASE d");
        run("CREATE TABLE d.t");
        run("INSERT INTO d.t (_id, k, n, s) VALUES ('01', 'b', 1, 'x'), ('02', 'a', 2, 'y'), ('03', 'b', 3, NULL), "
                + "('04', 1, 4, 'x'), ('05', 1.0, 5.5, 'x'), ('06', NULL, 6, 'z'), ('07', TRUE, 7, 'x'), "
                + "('08', 'a', NULL, 'y'), ('09', FALSE, 'nine', 'x')");
        run("INSERT INTO d.t (_id, n) VALUES ('10', 10)");
        Result.Rows r = rows("SELECT DISTINCT k FROM d.t");
        assertEquals(List.of("k"), r.columns());
        // null with missing, false, true, numbers (1 and 1.0 are one value, shown as the lowest _id's), strings.
        assertEquals(List.of(Arrays.asList((Object) null), List.of(false), List.of(true), List.of(1L), List.of("a"),
                List.of("b")), r.rows());
        assertFalse(r.truncated());
        assertEquals(List.of(Arrays.asList(null, null), Arrays.asList(null, "z"), List.of(false, "x"), List.of(true, "x"),
                List.of(1L, "x"), List.of("a", "y"), Arrays.asList("b", null), List.of("b", "x")),
                rows("SELECT DISTINCT k, s FROM d.t").rows());
        assertEquals(List.of(Arrays.asList(null, "b"), List.of("x", 1L), List.of("x", true)),
                rows("SELECT DISTINCT s, k FROM d.t WHERE n > 2 ORDER BY k DESC, s LIMIT 3").rows());
        for (String[] pair : new String[][] {
                {"SELECT DISTINCT k, s FROM d.t", "SELECT k, s FROM d.t GROUP BY k, s"},
                {"SELECT DISTINCT s, k FROM d.t WHERE n > 2 ORDER BY k DESC, s LIMIT 3",
                        "SELECT s, k FROM d.t WHERE n > 2 GROUP BY s, k ORDER BY k DESC, s LIMIT 3"},
                {"SELECT DISTINCT s FROM d.t ORDER BY s DESC", "SELECT s FROM d.t GROUP BY s ORDER BY s DESC"},
                {"SELECT DISTINCT _id FROM d.t WHERE k IS NULL", "SELECT _id FROM d.t WHERE k IS NULL GROUP BY _id"}}) {
            assertEquals(rows(pair[1]), rows(pair[0]), pair[0]);
        }
        assertEquals(List.of(Arrays.asList((Object) null)), rows("SELECT DISTINCT nope FROM d.t").rows());
        assertEquals(List.of(), rows("SELECT DISTINCT k FROM d.t WHERE n > 100").rows());
        run("CREATE INDEX ON d.t (k)");
        assertEquals(List.of(List.of("a"), List.of("b")), rows("SELECT DISTINCT k FROM d.t WHERE k IN ('a', 'b')").rows());
        run("INSERT INTO d.t (_id, distinct) VALUES ('11', 'd'), ('12', 'd')");
        assertEquals(List.of(Arrays.asList((Object) null), List.of("d")), rows("SELECT DISTINCT distinct FROM d.t").rows());
        assertEquals(List.of(List.of("d"), List.of("d")), rows("SELECT distinct FROM d.t WHERE distinct IS NOT NULL").rows());
        query.run("SELECT DISTINCT k FROM d.t", false); // the read role may run it
    }

    @Test
    void countDistinctCountsEachValueOnce() throws IOException {
        run("CREATE DATABASE d");
        run("CREATE TABLE d.t");
        run("INSERT INTO d.t (_id, g, v) VALUES ('01', 'a', 1), ('02', 'a', 1.0), ('03', 'a', 2), ('04', 'a', NULL), "
                + "('05', 'a', '1'), ('06', 'b', TRUE), ('07', 'b', TRUE), ('08', 'b', 'x'), ('09', 'c', NULL)");
        run("INSERT INTO d.t (_id, g) VALUES ('10', 'c')");
        Result.Rows r = rows("SELECT COUNT(DISTINCT v), COUNT(v), COUNT(*) FROM d.t");
        assertEquals(List.of("count(distinct v)", "count(v)", "count"), r.columns());
        // 1 and 1.0 are one value, and the string '1' another; null and missing are not counted.
        assertEquals(List.of(List.of(5L, 7L, 10L)), r.rows());
        assertEquals(List.of(List.of("a", 3L, 4L), List.of("b", 2L, 3L), List.of("c", 0L, 0L)),
                rows("SELECT g, COUNT(DISTINCT v), COUNT(v) FROM d.t GROUP BY g").rows());
        assertEquals(List.of(List.of("c", 0L), List.of("b", 2L), List.of("a", 3L)),
                rows("SELECT g, COUNT(DISTINCT v) FROM d.t GROUP BY g ORDER BY COUNT(DISTINCT v)").rows());
        assertEquals(List.of(List.of(0L)), rows("SELECT COUNT(DISTINCT v) FROM d.t WHERE g = 'z'").rows(), "over no rows");
        assertEquals(List.of(List.of(10L, 3L)), rows("SELECT COUNT(DISTINCT _id), COUNT(DISTINCT g) FROM d.t").rows());
        assertEquals(List.of(List.of("a"), List.of("b")),
                rows("SELECT g FROM d.t GROUP BY g HAVING COUNT(DISTINCT v) >= 2").rows());
    }

    @Test
    void countDistinctHoldsAtMostItsBoundOverAllGroups() throws IOException {
        query = new Query(new Executor(engine, new Hlc("n1"), 10, 100, 5));
        run("CREATE DATABASE d");
        run("CREATE TABLE d.t");
        run("INSERT INTO d.t (_id, g, v) VALUES ('1', 1, 'a'), ('2', 1, 'b'), ('3', 1, 'c'), ('4', 2, 'a'), "
                + "('5', 2, 'b'), ('6', 2, 'c')");
        String over = "COUNT(DISTINCT) would hold more than 5 values";
        assertEquals(List.of(List.of(3L)), rows("SELECT COUNT(DISTINCT v) FROM d.t").rows());
        assertEquals(over, fails("SELECT g, COUNT(DISTINCT v) FROM d.t GROUP BY g").getMessage(), "3 values in each of 2 groups");
        assertEquals(List.of(List.of(1L, 3L)), rows("SELECT g, COUNT(DISTINCT v) FROM d.t WHERE g = 1 GROUP BY g").rows());
        assertEquals(List.of(List.of(3L, 2L)), rows("SELECT COUNT(DISTINCT v), COUNT(DISTINCT g) FROM d.t").rows(),
                "3 + 2 values: at the bound, not over it");
        assertEquals(over, fails("SELECT COUNT(DISTINCT v), COUNT(DISTINCT _id) FROM d.t").getMessage());
        assertEquals(List.of(List.of(3L, 3L)), rows("SELECT COUNT(DISTINCT v), COUNT(DISTINCT v) FROM d.t").rows(),
                "an aggregate listed twice is held once");
        assertEquals(over, fails("SELECT g FROM d.t GROUP BY g HAVING COUNT(DISTINCT v) > 1").getMessage(),
                "an aggregate of HAVING counts too");
        assertEquals(over, fails("SELECT COUNT(DISTINCT v) FROM d.t GROUP BY g LIMIT 1").getMessage(), "LIMIT does not change it");
    }

    @Test
    void havingFiltersGroupsByTheirValuesAndAggregates() throws IOException {
        run("CREATE DATABASE d");
        run("CREATE TABLE d.t");
        run("INSERT INTO d.t (_id, k, n, s) VALUES ('01', 'a', 1, 'x'), ('02', 'a', 2, 'y'), ('03', 'a', 3, 'x'), "
                + "('04', 'b', 10, 'x'), ('05', 'b', NULL, 'y'), ('06', 'c', 's', 'z'), ('07', NULL, 4, 'x')");
        // Groups: null (COUNT 1, SUM 4), a (3, 6), b (2, 10), c (1, null: 's' is no number).
        assertEquals(List.of(List.of("a", 3L), List.of("b", 2L)),
                rows("SELECT k, COUNT(*) FROM d.t GROUP BY k HAVING COUNT(*) > 1").rows());
        Result.Rows r = rows("SELECT k FROM d.t GROUP BY k HAVING SUM(n) >= 4");
        assertEquals(List.of("k"), r.columns(), "an aggregate of HAVING need not be listed");
        assertEquals(List.of(Arrays.asList((Object) null), List.of("a"), List.of("b")), r.rows());
        assertEquals(List.of(Arrays.asList(null, 1L), List.of("a", 3L), List.of("c", 1L)),
                rows("SELECT k, COUNT(*) FROM d.t GROUP BY k HAVING k IN ('a', 'c') OR k IS NULL").rows());
        assertEquals(List.of(List.of("a"), List.of("b")), rows("SELECT k FROM d.t GROUP BY k HAVING MAX(s) LIKE 'y%'").rows());
        // Values as the list shows them: AVG is a double, SUM of integers an integer; = compares numbers by value.
        assertEquals(List.of(List.of("a", 2.0)), rows("SELECT k, AVG(n) FROM d.t GROUP BY k HAVING AVG(n) = 2").rows());
        assertEquals(List.of(List.of("a", 6L)), rows("SELECT k, SUM(n) FROM d.t GROUP BY k HAVING SUM(n) = 6.0").rows());
        assertEquals(4, rows("SELECT k FROM d.t GROUP BY k HAVING MAX(s) != 1").rows().size(), "a string is never 1");
        // Three-valued: c's SUM is null, so a comparison with it is unknown, and so is its NOT.
        assertEquals(List.of(Arrays.asList((Object) null), List.of("a"), List.of("b")),
                rows("SELECT k FROM d.t GROUP BY k HAVING SUM(n) > 0").rows());
        assertEquals(List.of(), rows("SELECT k FROM d.t GROUP BY k HAVING NOT SUM(n) > 0").rows());
        assertEquals(List.of(List.of("c")), rows("SELECT k FROM d.t GROUP BY k HAVING SUM(n) IS NULL").rows());
        assertEquals(List.of(Arrays.asList((Object) null), List.of("c")),
                rows("SELECT k FROM d.t GROUP BY k HAVING SUM(n) > 100 OR COUNT(*) = 1").rows(), "unknown OR true is true");
        assertEquals(List.of(), rows("SELECT k FROM d.t GROUP BY k HAVING SUM(n) > 100 AND COUNT(*) = 1").rows());
        assertEquals(List.of(Arrays.asList((Object) null), List.of("a"), List.of("b")),
                rows("SELECT k FROM d.t GROUP BY k HAVING NOT (SUM(n) > 100 AND COUNT(*) = 1)").rows(),
                "NOT (unknown AND true) is unknown");
        // HAVING comes before ORDER BY and LIMIT.
        assertEquals(List.of(List.of("b", 2L), List.of("c", 1L)),
                rows("SELECT k, COUNT(*) FROM d.t GROUP BY k HAVING COUNT(*) < 3 ORDER BY count(*) DESC, k DESC LIMIT 2").rows());
        assertEquals(List.of(), rows("SELECT k FROM d.t WHERE n > 100 GROUP BY k HAVING COUNT(*) = 0").rows(),
                "with GROUP BY, no rows make no groups");
        assertEquals(List.of(List.of("a", "x", 2L)),
                rows("SELECT k, s, COUNT(*) FROM d.t GROUP BY k, s HAVING s = 'x' AND COUNT(*) > 1").rows());
        assertEquals(List.of(List.of("06"), List.of("07")), rows("SELECT _id FROM d.t GROUP BY _id HAVING _id > '05'").rows());
        query.run("SELECT k FROM d.t GROUP BY k HAVING COUNT(*) > 1", false); // the read role may run it
    }

    @Test
    void havingWithoutGroupByTestsTheOneGroupEvenOverNoRows() throws IOException {
        run("CREATE DATABASE d");
        run("CREATE TABLE d.t");
        Result.Rows r = rows("SELECT COUNT(*) FROM d.t HAVING COUNT(*) = 0");
        assertEquals(List.of("count"), r.columns());
        assertEquals(List.of(List.of(0L)), r.rows());
        r = rows("SELECT COUNT(*) FROM d.t HAVING COUNT(*) > 0");
        assertEquals(List.of("count"), r.columns());
        assertEquals(List.of(), r.rows());
        assertFalse(r.truncated());
        assertEquals(List.of(Arrays.asList(null, null)), rows("SELECT SUM(n), MAX(n) FROM d.t HAVING SUM(n) IS NULL").rows());
        assertEquals(List.of(Arrays.asList((Object) null)), rows("SELECT MAX(n) FROM d.t HAVING COUNT(*) >= 0").rows());
        run("INSERT INTO d.t (_id, n) VALUES ('1', 1), ('2', 2)");
        assertEquals(List.of(List.of(2L)), rows("SELECT COUNT(*) FROM d.t HAVING COUNT(*) > 1").rows());
        assertEquals(List.of(Arrays.asList(0L, null)), rows("SELECT COUNT(*), SUM(n) FROM d.t WHERE n > 100 HAVING COUNT(*) = 0").rows());
        assertEquals(List.of(), rows("SELECT MAX(n) FROM d.t HAVING COUNT(*) > 2").rows());
        assertEquals(List.of(List.of(1.5)), rows("SELECT AVG(n) FROM d.t HAVING AVG(n) = 1.5").rows());
    }

    @Test
    void orderByKeysSortInTurnAndTiesKeepIdOrGroupOrder() throws IOException {
        run("CREATE DATABASE d");
        run("CREATE TABLE d.t");
        run("INSERT INTO d.t (_id, a, b) VALUES ('1', 2, 'x'), ('2', 1, 'y'), ('3', 2, 'y'), ('4', 1, 'x'), ('5', 2, 'x'), "
                + "('6', NULL, 'x'), ('7', 'z', NULL), ('8', 1, 'y')");
        java.util.function.Function<String, List<Object>> ids = q -> {
            try {
                return column("SELECT _id FROM d.t " + q, "_id");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        assertEquals(List.of("6", "2", "4", "8", "1", "3", "5", "7"), ids.apply("ORDER BY a"), "one key, as before");
        assertEquals(List.of("7", "1", "3", "5", "2", "4", "8", "6"), ids.apply("ORDER BY a DESC"));
        assertEquals(List.of("6", "2", "8", "4", "3", "1", "5", "7"), ids.apply("ORDER BY a, b DESC"));
        assertEquals(List.of("7", "1", "5", "3", "4", "2", "8", "6"), ids.apply("ORDER BY a DESC, b"));
        assertEquals(List.of("7", "6", "4", "1", "5", "2", "8", "3"), ids.apply("ORDER BY b, a"));
        assertEquals(List.of("7", "3", "1", "5", "2", "8", "4", "6"), ids.apply("ORDER BY a DESC, b DESC"),
                "rows equal on every key keep _id order, ascending, in any direction");
        assertEquals(List.of("6", "8", "4", "2", "5", "3", "1", "7"), ids.apply("ORDER BY a, _id DESC"));
        assertEquals(List.of("1", "2", "3"), ids.apply("ORDER BY _id, a DESC LIMIT 3"));
        assertEquals(List.of("8", "7", "6"), ids.apply("ORDER BY _id DESC, a LIMIT 3"));
        assertEquals(List.of("6", "2", "8"), ids.apply("ORDER BY a, b DESC LIMIT 3"));
        assertEquals(List.of("4", "8"), ids.apply("WHERE a = 1 ORDER BY b, _id DESC LIMIT 2"));
        // Groups equal on every key keep their GROUP BY order.
        assertEquals(List.of(List.of(2L, "y", 1L), List.of(1L, "y", 2L), Arrays.asList(null, "x", 1L), List.of(1L, "x", 1L),
                List.of(2L, "x", 2L), Arrays.asList("z", null, 1L)),
                rows("SELECT a, b, COUNT(*) FROM d.t GROUP BY a, b ORDER BY b DESC, count(*)").rows());
        assertEquals(List.of(List.of("y", 1L), List.of("x", 1L), List.of("x", 1L), Arrays.asList(null, 1L), List.of("y", 2L),
                List.of("x", 2L)), rows("SELECT b, COUNT(*) FROM d.t GROUP BY a, b ORDER BY count(*), b DESC").rows());
        // At most 32 keys.
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < Parser.MAX_ORDER_BY - 1; i++) {
            keys.append("c").append(i).append(i % 3 == 0 ? " DESC, " : ", ");
        }
        assertEquals(ids.apply("ORDER BY a"), ids.apply("ORDER BY " + keys + "a"), "31 keys no row has, then a");
        assertTrue(fails("SELECT _id FROM d.t ORDER BY " + keys + "a, b").getMessage().startsWith("ORDER BY takes at most 32 keys"));
    }

    @Test
    void groupCaps() throws IOException {
        query = new Query(new Executor(engine, new Hlc("n1"), 3, 5));
        run("CREATE DATABASE d");
        run("CREATE TABLE d.t");
        run("INSERT INTO d.t (_id, g) VALUES ('1', 1), ('2', 2), ('3', 3), ('4', 4), ('5', 5), ('6', 5)");
        Result.Rows r = rows("SELECT g, COUNT(*) FROM d.t GROUP BY g");
        assertEquals(List.of(List.of(1L, 1L), List.of(2L, 1L), List.of(3L, 1L)), r.rows());
        assertTrue(r.truncated(), "5 groups, 3 returned");
        r = rows("SELECT g, COUNT(*) FROM d.t GROUP BY g ORDER BY count(*) DESC LIMIT 3");
        assertEquals(List.of(List.of(5L, 2L), List.of(1L, 1L), List.of(2L, 1L)), r.rows());
        assertFalse(r.truncated());
        assertEquals("LIMIT 4 is over the maximum of 3",
                fails("SELECT g, COUNT(*) FROM d.t GROUP BY g LIMIT 4").getMessage());
        r = rows("SELECT DISTINCT g FROM d.t");
        assertEquals(List.of(List.of(1L), List.of(2L), List.of(3L)), r.rows());
        assertTrue(r.truncated(), "5 distinct values, 3 returned");
        assertEquals("LIMIT 4 is over the maximum of 3", fails("SELECT DISTINCT g FROM d.t LIMIT 4").getMessage());
        // The cap on rows returned counts the groups HAVING keeps.
        assertTrue(rows("SELECT g FROM d.t GROUP BY g HAVING COUNT(*) = 1").truncated(), "4 groups kept, 3 returned");
        r = rows("SELECT g FROM d.t GROUP BY g HAVING g > 2");
        assertEquals(List.of(List.of(3L), List.of(4L), List.of(5L)), r.rows());
        assertFalse(r.truncated());
        run("INSERT INTO d.t (_id, g) VALUES ('7', 7)");
        assertEquals("GROUP BY would make more than 5 groups", fails("SELECT g FROM d.t GROUP BY g").getMessage());
        assertEquals("GROUP BY would make more than 5 groups",
                fails("SELECT COUNT(*) FROM d.t GROUP BY g LIMIT 1").getMessage(), "LIMIT does not change it");
        assertEquals("GROUP BY would make more than 5 groups",
                fails("SELECT g FROM d.t GROUP BY g HAVING g = 7").getMessage(), "the cap counts groups before HAVING");
        assertEquals("SELECT DISTINCT would hold more than 5 rows", fails("SELECT DISTINCT g FROM d.t").getMessage());
        assertEquals("SELECT DISTINCT would hold more than 5 rows", fails("SELECT DISTINCT g FROM d.t LIMIT 1").getMessage());
        assertEquals(List.of(List.of(5L), List.of(7L)), rows("SELECT DISTINCT g FROM d.t WHERE g > 4").rows());
        assertEquals(List.of(List.of(1L)), rows("SELECT COUNT(*) FROM d.t WHERE g = 7 GROUP BY g").rows());
        assertEquals(List.of(List.of(7L)), rows("SELECT COUNT(*) FROM d.t").rows());
    }

    @Test
    void theDefaultGroupAndDistinctCapsAre100000() throws IOException {
        run("CREATE DATABASE d");
        run("CREATE TABLE d.t");
        StringBuilder b = new StringBuilder("INSERT INTO d.t (_id, g) VALUES ");
        for (int i = 0; i <= Executor.DEFAULT_MAX_GROUPS; i++) {
            b.append(i == 0 ? "" : ", ").append("('r").append(i).append("', ").append(i % 10_001).append(')');
        }
        run(b.toString());
        assertEquals("GROUP BY would make more than 100,000 groups",
                fails("SELECT _id, COUNT(*) FROM d.t GROUP BY _id").getMessage());
        assertEquals("SELECT DISTINCT would hold more than 100,000 rows", fails("SELECT DISTINCT _id FROM d.t").getMessage());
        assertEquals(100_000, Executor.DEFAULT_MAX_DISTINCT);
        assertEquals("COUNT(DISTINCT) would hold more than 100,000 values",
                fails("SELECT COUNT(DISTINCT _id) FROM d.t").getMessage());
        assertEquals(List.of(List.of(100_000L)), rows("SELECT COUNT(DISTINCT _id) FROM d.t WHERE _id != 'r7'").rows(),
                "100,000 values are not over the cap");
        assertEquals(List.of(List.of(10_001L)), rows("SELECT COUNT(DISTINCT g) FROM d.t").rows());
        Result.Rows r = rows("SELECT g, COUNT(*) FROM d.t GROUP BY g");
        assertEquals(10_000, r.rows().size());
        assertTrue(r.truncated(), "10,001 groups, capped at 10,000 rows");
        assertEquals(List.of(0L, 10L), r.rows().get(0));
        assertEquals(List.of(List.of(10_000L, 9L)), rows("SELECT g, COUNT(*) FROM d.t GROUP BY g ORDER BY g DESC LIMIT 1").rows());
    }

    @Test
    void orderByIdReadsInIdOrderAndKeysetPagesCoverTheTable() throws IOException {
        query = new Query(new Executor(engine, new Hlc("n1"), 4));
        run("CREATE DATABASE d");
        run("CREATE TABLE d.t");
        StringBuilder b = new StringBuilder("INSERT INTO d.t (_id, n) VALUES ");
        for (int i = 0; i < 25; i++) {
            b.append(i == 0 ? "" : ", ").append("('k").append((char) ('a' + (i * 7) % 25)).append("', ").append(i).append(')');
        }
        run(b.toString());
        Result.Rows r = rows("SELECT _id FROM d.t ORDER BY _id");
        assertEquals(List.of(List.of("ka"), List.of("kb"), List.of("kc"), List.of("kd")), r.rows());
        assertTrue(r.truncated());
        assertEquals(rows("SELECT _id FROM d.t WHERE n >= 3").rows(), rows("SELECT _id FROM d.t WHERE n >= 3 ORDER BY _id ASC").rows());
        assertEquals(List.of(List.of("ky"), List.of("kx")), rows("SELECT _id FROM d.t ORDER BY _id DESC LIMIT 2").rows());
        List<String> seen = new ArrayList<>();
        String last = "";
        while (true) {
            List<List<Object>> page = rows("SELECT _id, n FROM d.t WHERE _id > '" + last + "' ORDER BY _id LIMIT 4").rows();
            page.forEach(row -> seen.add((String) row.get(0)));
            if (page.size() < 4) {
                break;
            }
            last = (String) page.get(page.size() - 1).get(0);
        }
        List<String> all = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            all.add("k" + (char) ('a' + i));
        }
        assertEquals(all, seen);
        // These read rows in _id order and stop after the page; any other order sorts every match.
        for (String q : List.of("SELECT * FROM d.t", "SELECT * FROM d.t WHERE _id > 'x' ORDER BY _id LIMIT 4",
                "SELECT n FROM d.t ORDER BY _id ASC, n DESC LIMIT 4")) {
            assertTrue(Executor.readsInIdOrder((Statement.Select) Parser.parse(q)), q);
        }
        for (String q : List.of("SELECT * FROM d.t ORDER BY _id DESC", "SELECT * FROM d.t ORDER BY n, _id",
                "SELECT * FROM d.t ORDER BY n")) {
            assertFalse(Executor.readsInIdOrder((Statement.Select) Parser.parse(q)), q);
        }
        assertEquals(rows("SELECT _id, n FROM d.t WHERE _id > 'kc' ORDER BY _id LIMIT 4"),
                rows("SELECT _id, n FROM d.t WHERE _id > 'kc' ORDER BY _id, n DESC LIMIT 4"));
    }

    @Test
    void aReadOnlyCallerRunsSelectAndShowOnly() throws IOException {
        run("CREATE DATABASE shop");
        run("CREATE TABLE shop.items");
        run("INSERT INTO shop.items (_id, n) VALUES ('a', 1)");
        for (String q : List.of("SELECT * FROM shop.items", "SELECT COUNT(*) FROM shop.items", "SHOW DATABASES",
                "SHOW TABLES IN shop", "SHOW INDEXES IN shop.items", "SHOW CONFLICTS IN shop.items")) {
            query.run(q, false);
        }
        for (String q : List.of("INSERT INTO shop.items (n) VALUES (2)", "UPDATE shop.items SET n = 3",
                "DELETE FROM shop.items", "CREATE TABLE shop.more", "DROP TABLE shop.items", "CREATE DATABASE x",
                "DROP DATABASE shop", "CREATE INDEX ON shop.items (n)", "DROP INDEX IF EXISTS ON shop.items (n)")) {
            ForbiddenException e = assertThrows(ForbiddenException.class, () -> query.run(q, false), q);
            assertTrue(e.getMessage().contains("read role"), e.getMessage());
        }
        assertEquals(List.of(List.of("a", 1L)), rows("SELECT _id, n FROM shop.items").rows(), "nothing changed");
        assertThrows(ParseException.class, () -> query.run("SELEC x", false), "parse errors stay parse errors");
    }
}
