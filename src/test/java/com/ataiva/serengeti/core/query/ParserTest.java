package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.query.Expr.Cmp;
import com.ataiva.serengeti.core.query.Statement.Projection;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParserTest {
    private static Statement parse(String s) {
        return Parser.parse(s);
    }

    private static ParseException fails(String s) {
        return assertThrows(ParseException.class, () -> Parser.parse(s));
    }

    private static void roundTrips(String s) {
        Statement st = parse(s);
        assertEquals(st, parse(Printer.print(st)), Printer.print(st));
    }

    @Test
    void createAndDropDatabase() {
        assertEquals(new Statement.CreateDatabase("shop", false), parse("CREATE DATABASE shop"));
        assertEquals(new Statement.CreateDatabase("shop", true), parse("create database if not exists Shop;"));
        assertEquals(new Statement.DropDatabase("shop", false), parse("DROP DATABASE shop"));
        assertEquals(new Statement.DropDatabase("shop", true), parse("DROP DATABASE IF EXISTS shop"));
        roundTrips("CREATE DATABASE IF NOT EXISTS shop");
        roundTrips("DROP DATABASE IF EXISTS shop");
    }

    @Test
    void createAndDropTable() {
        assertEquals(new Statement.CreateTable("shop", "items", false), parse("CREATE TABLE shop.items"));
        assertEquals(new Statement.CreateTable("shop", "items", true),
                parse("CREATE TABLE IF NOT EXISTS shop . items"));
        assertEquals(new Statement.DropTable("shop", "items", true), parse("DROP TABLE IF EXISTS shop.items;"));
        assertTrue(fails("CREATE TABLE items").getMessage().contains("db.table"));
        fails("DROP TABLE IF shop.items");
        roundTrips("DROP TABLE shop.items");
    }

    @Test
    void show() {
        assertEquals(new Statement.ShowDatabases(), parse("SHOW DATABASES"));
        assertEquals(new Statement.ShowTables("shop"), parse("show tables in SHOP"));
        fails("SHOW TABLES");
        fails("SHOW TABLE IN shop");
    }

    @Test
    void insert() {
        Statement s = parse("INSERT INTO shop.items (name, price, ok, note) VALUES ('Tea', 3, true, null), "
                + "('It''s', -1.5e2, FALSE, 'x')");
        assertEquals(new Statement.Insert("shop", "items", List.of("name", "price", "ok", "note"), List.of(
                Arrays.asList("Tea", 3L, true, null),
                Arrays.asList("It's", -150.0, false, "x"))), s);
        roundTrips("INSERT INTO a.b (_id, x) VALUES ('k', 1), ('j', 2.5)");
    }

    @Test
    void insertErrors() {
        ParseException e = fails("INSERT INTO a.b (x, y) VALUES (1)");
        assertEquals(30, e.position());
        assertTrue(e.getMessage().contains("expected 2 values but found 1"));
        e = fails("INSERT INTO a.b (x, x) VALUES (1, 2)");
        assertEquals(20, e.position());
        fails("INSERT INTO a.b () VALUES ()");
        fails("INSERT INTO a.b (x) VALUES");
        fails("INSERT INTO a.b (x) VALUES (y)");
    }

    @Test
    void select() {
        assertEquals(new Statement.Select("d", "t", new Projection.All(), null, null, null),
                parse("SELECT * FROM d.t"));
        assertEquals(new Statement.Select("d", "t", new Projection.Count(),
                new Expr.Compare("a", Cmp.GT, 1L), null, null), parse("SELECT COUNT(*) FROM d.t WHERE a > 1"));
        assertEquals(new Statement.Select("d", "t", new Projection.Columns(List.of("a", "b")),
                new Expr.IsNull("c", true), new Statement.OrderBy("a", true), 10L),
                parse("SELECT a, B FROM d.t WHERE c IS NOT NULL ORDER BY a DESC LIMIT 10;"));
        assertEquals(new Statement.OrderBy("a", false),
                ((Statement.Select) parse("SELECT * FROM d.t ORDER BY a ASC")).orderBy());
        roundTrips("SELECT a, b FROM d.t WHERE a = 'X' OR b < 2 ORDER BY b LIMIT 0");
    }

    @Test
    void selectErrors() {
        fails("SELECT FROM d.t");
        fails("SELECT * FROM d.t LIMIT -1");
        fails("SELECT * FROM d.t LIMIT 1.5");
        fails("SELECT * FROM d.t ORDER a");
        assertTrue(fails("SELECT COUNT(*) FROM d.t LIMIT 1").getMessage().contains("COUNT(*)"));
        fails("SELECT COUNT(*) FROM d.t ORDER BY a");
        fails("SELECT * FROM d.t WHERE a");
        fails("SELECT * FROM d.t WHERE a = b");
        fails("SELECT * FROM d.t extra");
    }

    @Test
    void update() {
        assertEquals(new Statement.Update("d", "t",
                List.of(new Statement.Assignment("a", 1L), new Statement.Assignment("b", null)),
                new Expr.Compare("_id", Cmp.EQ, "k")), parse("UPDATE d.t SET a = 1, b = NULL WHERE _id = 'k'"));
        fails("UPDATE d.t SET a = 1, a = 2");
        fails("UPDATE d.t SET");
        fails("UPDATE d.t SET a 1");
        roundTrips("UPDATE d.t SET a = 'q', b = FALSE");
    }

    @Test
    void delete() {
        assertEquals(new Statement.Delete("d", "t", null), parse("DELETE FROM d.t"));
        assertEquals(new Statement.Delete("d", "t", new Expr.Not(new Expr.Compare("a", Cmp.NE, 2L))),
                parse("DELETE FROM d.t WHERE NOT a <> 2"));
        fails("DELETE d.t");
    }

    @Test
    void conditionsFollowPrecedence() {
        Expr a = new Expr.Compare("a", Cmp.EQ, 1L);
        Expr b = new Expr.Compare("b", Cmp.LE, 2L);
        Expr c = new Expr.Compare("c", Cmp.GE, 3L);
        assertEquals(new Expr.Or(List.of(a, new Expr.And(List.of(b, c)))),
                where("a = 1 OR b <= 2 AND c >= 3"));
        assertEquals(new Expr.And(List.of(new Expr.Or(List.of(a, b)), c)),
                where("(a = 1 OR b <= 2) AND c >= 3"));
        assertEquals(new Expr.And(List.of(new Expr.Not(a), b, c)), where("NOT a = 1 AND b <= 2 AND c >= 3"));
        assertEquals(new Expr.Not(new Expr.Not(a)), where("not not ((a = 1))"));
        assertEquals(new Expr.Compare("a", Cmp.LT, "Mixed Case"), where("A < 'Mixed Case'"));
    }

    private static Expr where(String cond) {
        return ((Statement.Select) parse("SELECT * FROM d.t WHERE " + cond)).where();
    }

    @Test
    void errorsGivePositions() {
        ParseException e = fails("SELECT * FORM d.t");
        assertEquals(9, e.position());
        assertEquals("expected FROM but found name 'form' at position 9", e.getMessage());
        assertEquals(0, fails("").position());
        assertEquals(0, fails(";").position());
        assertEquals(18, fails("SELECT * FROM d.t;;").position());
        assertTrue(fails("SELECT * FROM d.select").getMessage().contains("keyword SELECT"));
        assertEquals(28, fails("SELECT * FROM d.t WHERE a = 'open").position());
    }

    @Test
    void commentsAndWhitespaceAreSkipped() {
        assertEquals(new Statement.ShowDatabases(), parse("  -- list them\n\tSHOW\r\nDATABASES -- done"));
    }

    @Test
    void nestingIsLimited() {
        int ok = Parser.MAX_DEPTH;
        where("(".repeat(ok) + "a = 1" + ")".repeat(ok));
        where("NOT ".repeat(ok) + "a = 1");
        ParseException e = fails("SELECT * FROM d.t WHERE " + "(".repeat(ok + 1) + "a = 1" + ")".repeat(ok + 1));
        assertTrue(e.getMessage().contains("nested"));
        fails("SELECT * FROM d.t WHERE " + "NOT ".repeat(100_000) + "a = 1");
        fails("SELECT * FROM d.t WHERE " + "(".repeat(100_000));
    }

    @Test
    void longChainsDoNotNest() {
        String cond = String.join(" AND ", java.util.Collections.nCopies(20_000, "a = 1"));
        Expr e = where(cond);
        assertEquals(20_000, ((Expr.And) e).terms().size());
    }
}
