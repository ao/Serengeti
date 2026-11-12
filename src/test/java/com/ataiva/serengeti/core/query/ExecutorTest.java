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
}
