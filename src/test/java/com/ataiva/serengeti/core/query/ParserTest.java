package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void selectListsGroupByAndLikePatternsHaveLimits() {
        java.util.function.IntFunction<String> cols = n -> java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> "c" + i).collect(java.util.stream.Collectors.joining(", "));
        Parser.parse("SELECT " + cols.apply(Parser.MAX_SELECT_ITEMS) + " FROM d.t");
        assertTrue(fails("SELECT " + cols.apply(Parser.MAX_SELECT_ITEMS + 1) + " FROM d.t").getMessage()
                .contains("at most 256 items"));
        Parser.parse("SELECT COUNT(*) FROM d.t GROUP BY " + cols.apply(Parser.MAX_GROUP_BY));
        assertTrue(fails("SELECT COUNT(*) FROM d.t GROUP BY " + cols.apply(Parser.MAX_GROUP_BY + 1)).getMessage()
                .contains("at most 32 columns"));
        Parser.parse("SELECT * FROM d.t WHERE s LIKE '" + "a".repeat(Parser.MAX_LIKE_PATTERN) + "'");
        assertTrue(fails("SELECT * FROM d.t WHERE s LIKE '" + "a".repeat(Parser.MAX_LIKE_PATTERN + 1) + "'")
                .getMessage().contains("at most 1000 characters"));
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
    void showConflicts() {
        assertEquals(new Statement.ShowConflicts("shop", "items"), parse("SHOW CONFLICTS IN shop.items"));
        assertEquals(new Statement.ShowConflicts("shop", "items"), parse("show Conflicts in SHOP.items;"));
        fails("SHOW CONFLICTS shop.items");
        fails("SHOW CONFLICTS IN shop");
        // Not a reserved word: a table or column may still be called conflicts.
        assertEquals(new Statement.CreateTable("shop", "conflicts", false), parse("CREATE TABLE shop.conflicts"));
    }

    @Test
    void indexStatements() {
        assertEquals(new Statement.CreateIndex("shop", "items", "qty", false), parse("CREATE INDEX ON shop.items (qty)"));
        assertEquals(new Statement.CreateIndex("shop", "items", "qty", true),
                parse("create index if not exists on Shop.Items (QTY);"));
        assertEquals(new Statement.DropIndex("shop", "items", "qty", false), parse("DROP INDEX ON shop.items (qty)"));
        assertEquals(new Statement.DropIndex("shop", "items", "qty", true), parse("DROP INDEX IF EXISTS ON shop.items (qty)"));
        assertEquals(new Statement.ShowIndexes("shop", "items"), parse("SHOW INDEXES IN shop.items"));
        fails("CREATE INDEX shop.items (qty)");
        fails("CREATE INDEX ON shop.items qty");
        fails("SHOW INDEXES IN shop");
        // Several columns: 2 to 4, in order, each once.
        assertEquals(new Statement.CreateIndex("shop", "items", List.of("kind", "qty"), false),
                parse("CREATE INDEX ON shop.items (kind, qty)"));
        assertEquals(new Statement.CreateIndex("shop", "items", List.of("a", "b", "c", "d"), true),
                parse("create index if not exists on Shop.Items (A,b , C,d)"));
        assertEquals(new Statement.DropIndex("shop", "items", List.of("qty", "kind"), true),
                parse("DROP INDEX IF EXISTS ON shop.items (qty, kind)"));
        roundTrips("CREATE INDEX ON shop.items (kind, qty)");
        roundTrips("DROP INDEX IF EXISTS ON shop.items (a, b, c)");
        assertEquals("an index has at most 4 columns", failsAt("CREATE INDEX ON d.t (a, b, c, d, e)", "e)"));
        assertEquals("column 'a' is listed twice", failsAt("CREATE INDEX ON d.t (a, b, A)", "A)"));
        assertEquals("column 'b' is listed twice", failsAt("DROP INDEX ON d.t (b, b)", "b)"));
        fails("CREATE INDEX ON d.t (a, )");
        fails("CREATE INDEX ON d.t ()");
        fails("CREATE INDEX ON d.t (a b)");
        // None of index, indexes or on is reserved.
        assertEquals(new Statement.CreateTable("index", "on", false), parse("CREATE TABLE index.on"));
        assertEquals(new Statement.CreateIndex("on", "index", "indexes", false), parse("CREATE INDEX ON on.index (indexes)"));
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
    void insertOnConflict() {
        List<List<Object>> rows = List.of(List.of("a", 1L), List.of("b", 2L));
        assertEquals(new Statement.Insert("d", "t", List.of("_id", "n"), rows, Statement.OnConflict.DO_NOTHING),
                parse("INSERT INTO d.t (_id, n) VALUES ('a', 1), ('b', 2) ON CONFLICT DO NOTHING"));
        assertEquals(new Statement.Insert("d", "t", List.of("_id", "n"), rows, new Statement.OnConflict(List.of(
                set("n", "n", false, 1L), new Statement.Assignment("seen", true), set("m", "n", true, 0.5)))),
                parse("insert into d.t (_id, n) values ('a', 1), ('b', 2) on conflict do update set n = n + 1, seen = TRUE,"
                        + " m = n-0.5;"));
        assertEquals(null, ((Statement.Insert) parse("INSERT INTO d.t (_id) VALUES ('a')")).onConflict());
        for (String s : List.of("INSERT INTO d.t (_id, n) VALUES ('a', 1) ON CONFLICT DO UPDATE SET n = n + 1",
                "INSERT INTO d.t (n, _id) VALUES (1, 'a'), (-0.0, 'b') ON CONFLICT DO NOTHING",
                "INSERT INTO d.t (_id, n) VALUES ('a', 1) ON CONFLICT DO UPDATE SET n = n - -1, m = NULL, k = 'x', j = j + 1.0E300",
                "INSERT INTO d.t (_id) VALUES ('a') ON CONFLICT DO UPDATE SET _id = 'b'")) {
            roundTrips(s);
        }
        assertEquals("INSERT INTO d.t (_id, n) VALUES ('a', 1) ON CONFLICT DO UPDATE SET n = n + 1, m = m - 1",
                Printer.print(parse("INSERT INTO d.t (_id,n) VALUES ('a',1) On Conflict Do Update Set n=n+1,m=m-1")));
        // ON, CONFLICT, DO and NOTHING are not reserved: they are names everywhere else.
        assertEquals(new Statement.Insert("on", "conflict", List.of("_id", "do", "nothing", "on", "conflict"),
                List.of(List.of("a", 1L, 2L, 3L, 4L)), new Statement.OnConflict(List.of(
                set("nothing", "do", false, 1L), set("do", "nothing", true, 1L), set("conflict", "on", false, 0L)))),
                parse("INSERT INTO on.conflict (_id, do, nothing, on, conflict) VALUES ('a', 1, 2, 3, 4)"
                        + " ON CONFLICT DO UPDATE SET nothing = do + 1, do = nothing - 1, conflict = on + 0"));
        roundTrips("SELECT do, nothing FROM on.conflict WHERE conflict = 1 AND on IS NULL ORDER BY do");
        roundTrips("UPDATE on.conflict SET do = do + 1, nothing = 2 WHERE on = 1");
    }

    @Test
    void insertOnConflictErrors() {
        assertEquals("ON CONFLICT needs _id in the column list: a row without _id gets a new one, so it cannot conflict",
                failsAt("INSERT INTO d.t (n) VALUES (1) ON CONFLICT DO NOTHING", "ON"));
        assertTrue(failsAt("INSERT INTO d.t (n, id) VALUES (1, 'a') ON CONFLICT DO UPDATE SET n = n + 1", "ON")
                .startsWith("ON CONFLICT needs _id"));
        assertEquals("expected CONFLICT but found name 'conflicts'",
                failsAt("INSERT INTO d.t (_id) VALUES ('a') ON CONFLICTS DO NOTHING", "CONFLICTS"));
        assertEquals("expected CONFLICT but found end of input", failsAtEnd("INSERT INTO d.t (_id) VALUES ('a') ON"));
        assertEquals("expected DO but found end of input", failsAtEnd("INSERT INTO d.t (_id) VALUES ('a') ON CONFLICT"));
        assertEquals("expected DO but found name 'nothing'",
                failsAt("INSERT INTO d.t (_id) VALUES ('a') ON CONFLICT NOTHING", "NOTHING"));
        assertEquals("expected NOTHING or UPDATE but found end of input",
                failsAtEnd("INSERT INTO d.t (_id) VALUES ('a') ON CONFLICT DO"));
        assertEquals("expected NOTHING or UPDATE but found SET",
                failsAt("INSERT INTO d.t (_id) VALUES ('a') ON CONFLICT DO SET n = 1", "SET"));
        assertEquals("expected SET but found name 'n'",
                failsAt("INSERT INTO d.t (_id) VALUES ('a') ON CONFLICT DO UPDATE n = 1", "n = 1"));
        assertEquals("expected a name but found end of input",
                failsAtEnd("INSERT INTO d.t (_id) VALUES ('a') ON CONFLICT DO UPDATE SET"));
        assertTrue(failsAtEnd("INSERT INTO d.t (_id) VALUES ('a') ON CONFLICT DO UPDATE SET n = n")
                .startsWith("expected '+' or '-'"));
        assertEquals("column 'n' is set twice",
                failsAt("INSERT INTO d.t (_id) VALUES ('a') ON CONFLICT DO UPDATE SET n = 1, N = 2", "N = 2"));
        assertEquals("_id is a string: it cannot be added to",
                failsAt("INSERT INTO d.t (_id) VALUES ('a') ON CONFLICT DO UPDATE SET n = _id + 1", "_id + 1"));
        // Nothing follows: no WHERE, no second clause, and ON CONFLICT comes after every tuple.
        assertEquals("expected end of input but found WHERE",
                failsAt("INSERT INTO d.t (_id) VALUES ('a') ON CONFLICT DO UPDATE SET n = 1 WHERE n > 0", "WHERE"));
        assertEquals("expected end of input but found name 'on'",
                failsAt("INSERT INTO d.t (_id) VALUES ('a') ON CONFLICT DO NOTHING on conflict do nothing", "on conflict"));
        assertEquals("expected end of input but found ','",
                failsAt("INSERT INTO d.t (_id) VALUES ('a') ON CONFLICT DO NOTHING, ('b')", ", ('b')"));
        assertEquals("expected end of input but found name 'do'",
                failsAt("INSERT INTO d.t (_id) VALUES ('a') DO NOTHING", "DO"));
        assertEquals("expected end of input but found name 'conflict'",
                failsAt("INSERT INTO d.t (_id) VALUES ('a') CONFLICT DO NOTHING", "CONFLICT"));
        assertThrows(IllegalArgumentException.class, () -> new Statement.Insert("d", "t", List.of("n"),
                List.of(List.of(1L)), Statement.OnConflict.DO_NOTHING));
        assertThrows(IllegalArgumentException.class, () -> new Statement.OnConflict(List.of(
                new Statement.Assignment("n", 1L), new Statement.Assignment("n", 2L))));
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
        assertEquals(List.of(new Statement.OrderBy("a", false)),
                ((Statement.Select) parse("SELECT * FROM d.t ORDER BY a ASC")).orderBy());
        roundTrips("SELECT a, b FROM d.t WHERE a = 'X' OR b < 2 ORDER BY b LIMIT 0");
    }

    @Test
    void selectErrors() {
        fails("SELECT FROM d.t");
        fails("SELECT * FROM d.t LIMIT -1");
        fails("SELECT * FROM d.t LIMIT 1.5");
        fails("SELECT * FROM d.t ORDER a");
        assertTrue(fails("SELECT COUNT(*) FROM d.t ORDER BY a").getMessage().contains("COUNT(*)"));
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

    private static Statement.Assignment set(String column, String from, boolean minus, Object number) {
        return new Statement.Assignment(column, new Statement.Arithmetic(from, minus, number));
    }

    /** The one assignment of {@code UPDATE d.t SET <text>}. */
    private static Statement.Assignment setOf(String text) {
        return ((Statement.Update) parse("UPDATE d.t SET " + text)).assignments().get(0);
    }

    @Test
    void updateSetsAColumnPlusOrMinusANumber() {
        assertEquals(new Statement.Update("d", "t", List.of(set("stock", "stock", true, 1L)),
                new Expr.And(List.of(new Expr.Compare("_id", Cmp.EQ, "a"), new Expr.Compare("stock", Cmp.GT, 0L)))),
                parse("UPDATE d.t SET stock = stock - 1 WHERE _id = 'a' AND stock > 0"));
        assertEquals(new Statement.Update("d", "t", List.of(set("total", "price", false, 5L),
                new Statement.Assignment("note", "x"), set("n", "n", false, 0.5)), null),
                parse("UPDATE d.t SET total = price + 5, note = 'x', n = n + 0.5"));
        // A number keeps its sign; a minus glued to the number is the operator, as in SQL.
        assertEquals(set("n", "n", false, -1L), setOf("n = n + -1"));
        assertEquals(set("n", "n", true, -1L), setOf("n = n - -1"));
        assertEquals(set("n", "n", true, 1L), setOf("n = n -1"));
        assertEquals(set("n", "n", true, 1L), setOf("n = n-1"));
        assertEquals(set("n", "m", true, 2.5), setOf("n=m-2.5"));
        assertEquals(set("n", "n", true, 0L), setOf("n = n -0"));
        assertEquals(set("n", "n", true, 0.0), setOf("n = n -0.0"), "the sign goes with the operator");
        assertEquals(set("n", "n", false, -0.0), setOf("n = n + -0.0"));
        assertEquals(set("n", "n", false, Long.MIN_VALUE), setOf("n = n + -9223372036854775808"));
        // As n - 9223372036854775808, whose number does not fit in a long, so it is a double.
        assertEquals(set("n", "n", true, 0x1p63), setOf("n = n -9223372036854775808"));
        assertEquals(set("n", "n", true, 0x1p63), setOf("n = n - 9223372036854775808"));
        assertEquals(set("n", "n", false, 1e300), setOf("n = n + 1E300"));
        // Column names that are words elsewhere, and a comment after the operator.
        assertEquals(set("sum", "distinct", false, 1L), setOf("sum = distinct + -- one\n1"));
        assertEquals(set("a", "a", false, 1L), setOf("A = A + 1"));
        for (String s : List.of("UPDATE d.t SET stock = stock - 1 WHERE _id = 'a' AND stock > 0",
                "UPDATE d.t SET a = a + -1, b = b - -2.5E-7, c = 'x', d = d - 0, e = e + -0.0, f = f - 1.0E300",
                "UPDATE d.t SET n = n + 9223372036854775807, m = m - -9223372036854775808",
                "UPDATE d.t SET n = n -1", "UPDATE d.t SET n = n - 9223372036854775808",
                "UPDATE d.t SET like = like + 1 WHERE like > 2")) {
            roundTrips(s);
        }
        assertEquals("UPDATE d.t SET n = n - -1, m = m + 1, k = k - 1",
                Printer.print(parse("UPDATE d.t SET n = n - -1, m = m+1, k = k-1")));
    }

    @Test
    void updateArithmeticTakesOnlyAColumnPlusOrMinusANumber() {
        assertEquals("_id is a string: it cannot be added to", failsAt("UPDATE d.t SET a = _id + 1", "_id"));
        assertEquals("expected '+' or '-' and a number (SET takes a value, or a column plus or minus a number) but"
                + " found end of input", failsAtEnd("UPDATE d.t SET a = b"));
        assertTrue(failsAt("UPDATE d.t SET a = b WHERE c = 1", "WHERE").startsWith("expected '+' or '-'"));
        assertTrue(failsAt("UPDATE d.t SET a = b, c = 1", ",").startsWith("expected '+' or '-'"));
        assertTrue(failsAt("UPDATE d.t SET a = b * 2", "*").startsWith("expected '+' or '-'"));
        for (String s : List.of("a = b / 2", "a = b % 2", "a = b || 'x'", "a = b ^ 2")) {
            assertTrue(fails("UPDATE d.t SET " + s).getMessage().startsWith("unexpected character"), s);
        }
        assertEquals("expected a number but found name 'c'", failsAt("UPDATE d.t SET a = b + c", "c"));
        assertEquals("expected a number but found string 'x'", failsAt("UPDATE d.t SET a = b + 'x'", "'x'"));
        assertEquals("expected a number but found NULL", failsAt("UPDATE d.t SET a = b + NULL", "NULL"));
        assertEquals("expected a number but found TRUE", failsAt("UPDATE d.t SET a = b - TRUE", "TRUE"));
        assertEquals("expected a number but found '('", failsAt("UPDATE d.t SET a = b + (1)", "("));
        assertEquals("expected a number but found '+'", failsAt("UPDATE d.t SET a = b + +1", "+1"));
        assertEquals("expected a number but found end of input", failsAtEnd("UPDATE d.t SET a = b -"));
        assertEquals("expected end of input but found '+'", failsAt("UPDATE d.t SET a = b + 1 + 2", "+ 2"));
        assertEquals("expected end of input but found number -2", failsAt("UPDATE d.t SET a = b + 1 -2", "-2"));
        // The number comes after the column, and only in SET.
        assertTrue(failsAt("UPDATE d.t SET a = 1 + b", "+").startsWith("expected end of input but found '+'"));
        assertTrue(failsAt("UPDATE d.t SET a = +1", "+").startsWith("expected a value"));
        assertTrue(failsAt("UPDATE d.t SET a = -b", "-").startsWith("expected a value"));
        assertTrue(failsAt("UPDATE d.t SET a = count + 1", "count").startsWith("expected a value"));
        assertTrue(failsAt("UPDATE d.t SET a = (b + 1)", "(").startsWith("expected a value"));
        assertTrue(failsAt("SELECT * FROM d.t WHERE a = b + 1", "b").startsWith("expected a value"));
        assertTrue(failsAt("SELECT * FROM d.t WHERE a + 1 = 2", "+").startsWith("expected a comparison"));
        assertTrue(failsAt("INSERT INTO d.t (a) VALUES (a + 1)", "a + 1").startsWith("expected a value"));
        assertEquals("number out of range '1e999'", failsAt("UPDATE d.t SET a = a + 1e999", "1e999"));
        // "--" starts a comment, so a = a--1 is a = a and an error, as in SQL.
        assertTrue(failsAtEnd("UPDATE d.t SET a = a--1").startsWith("expected '+' or '-'"));
        assertThrows(IllegalArgumentException.class, () -> new Statement.Arithmetic("_id", false, 1L));
        assertThrows(IllegalArgumentException.class, () -> new Statement.Arithmetic("a", false, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Statement.Arithmetic("a", false, "1"));
        assertThrows(IllegalArgumentException.class, () -> new Statement.Arithmetic("a", false, null));
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

    @Test
    void inLists() {
        assertEquals(new Expr.In("a", Arrays.asList(1L, "x", null, true, 2.5), false), where("a IN (1, 'x', NULL, TRUE, 2.5)"));
        assertEquals(new Expr.In("a", List.of(1L), true), where("A not in (1)"));
        assertEquals(new Expr.Not(new Expr.In("a", List.of(1L), false)), where("NOT a IN (1)"));
        assertEquals(new Expr.And(List.of(new Expr.In("a", List.of(1L, 2L), false), new Expr.Compare("b", Cmp.EQ, 3L))),
                where("a IN (1,2) AND b = 3"));
        roundTrips("SELECT * FROM d.t WHERE a IN (1, 'x', NULL) OR b NOT IN (-0.0, FALSE)");
        String thousand = String.join(", ", java.util.Collections.nCopies(Expr.In.MAX_VALUES, "7"));
        assertEquals(1000, ((Expr.In) where("a IN (" + thousand + ")")).values().size());
        String text = "SELECT * FROM d.t WHERE a IN (" + thousand + ", 8)";
        ParseException e = fails(text);
        assertEquals("IN takes at most 1000 values at position " + text.lastIndexOf('8'), e.getMessage());
        fails("SELECT * FROM d.t WHERE a IN ()");
        fails("SELECT * FROM d.t WHERE a IN 1");
        fails("SELECT * FROM d.t WHERE a IN (b)");
        fails("SELECT * FROM d.t WHERE a IN (1,)");
        fails("SELECT * FROM d.t WHERE a IN (1");
        fails("SELECT * FROM d.t WHERE a NOT = 1");
        assertTrue(fails("SELECT * FROM d.t WHERE a NOT 1").getMessage().startsWith("expected IN or LIKE after NOT"));
        assertTrue(fails("SELECT * FROM d.t WHERE a ~ 1").getMessage().contains("unexpected character"));
        assertTrue(fails("SELECT * FROM d.t WHERE a b").getMessage().contains("IS, IN or LIKE"));
    }

    @Test
    void likePatterns() {
        Expr.Like l = (Expr.Like) where("a LIKE 'ab%'");
        assertEquals(new Expr.Like("a", "ab%", false), l);
        assertEquals("ab", l.pattern().prefix());
        assertEquals(new Expr.Like("a", "_", true), where("a NOT LIKE '_'"));
        assertEquals(new Expr.Like("like", "like", false), where("like LIKE 'like'"));
        assertEquals(new Expr.Compare("like", Cmp.EQ, 1L), where("LIKE = 1"));
        LikePattern p = ((Expr.Like) where("a LIKE '50\\%\\_\\\\'")).pattern();
        assertEquals("50%_\\", p.prefix());
        assertTrue(p.exact());
        roundTrips("SELECT * FROM d.t WHERE a LIKE 'it''s\\%%' AND b NOT LIKE ''");

        String head = "SELECT * FROM d.t WHERE a LIKE '";
        ParseException e = fails(head + "a\\b'");
        assertEquals("expected \\%, \\_ or \\\\ in a LIKE pattern but found '\\b' at position " + (head.length() + 1),
                e.getMessage());
        e = fails(head + "it''s\\x'");
        assertEquals(head.length() + 5, e.position(), "counts the doubled quote as two characters of the text");
        e = fails(head + "ab\\'");
        assertTrue(e.getMessage().contains("a \\ at the end"), e.getMessage());
        assertEquals(head.length() + 2, e.position());
        fails(head + "\\'' AND b = 1");
        fails("SELECT * FROM d.t WHERE a LIKE 5");
        fails("SELECT * FROM d.t WHERE a LIKE NULL");
        fails("SELECT * FROM d.t WHERE a LIKE b");
        fails("SELECT * FROM d.t WHERE a LIKE");
    }

    @Test
    void newWordsAreStillNames() {
        assertEquals(new Statement.Select("d", "t", new Projection.Columns(List.of("sum", "min", "max", "avg", "group", "like")),
                new Expr.Compare("group", Cmp.EQ, 1L), new Statement.OrderBy("sum", false), null),
                parse("SELECT sum, min, max, avg, group, like FROM d.t WHERE group = 1 ORDER BY sum"));
        assertEquals(new Statement.CreateTable("group", "like", false), parse("CREATE TABLE group.like"));
        assertEquals(new Statement.CreateIndex("sum", "avg", "group", false), parse("CREATE INDEX ON sum.avg (group)"));
        roundTrips("INSERT INTO d.t (sum, min, group, like) VALUES (1, 2, 3, 4)");
        roundTrips("UPDATE d.t SET like = 1, max = 2 WHERE avg IS NULL AND group LIKE 'x%'");
        assertEquals(new Projection.Items(List.of(new Statement.Item.Aggregate(Statement.Fn.SUM, "sum"),
                new Statement.Item.Aggregate(Statement.Fn.MAX, "group"))),
                ((Statement.Select) parse("SELECT sum(sum), MAX(group) FROM d.t")).projection());
        assertEquals(List.of("group"), ((Statement.Select) parse("SELECT group FROM d.t GROUP BY group")).groupBy());
        // COUNT stays reserved, as in 2.1.
        assertTrue(fails("SELECT count FROM d.t").getMessage().contains("keyword COUNT"));
        assertEquals(7, fails("SELECT count FROM d.t").position());
        fails("CREATE TABLE d.count");
    }

    @Test
    void aggregates() {
        Statement.Item.Aggregate countAll = Statement.Item.Aggregate.COUNT_ALL;
        List<Statement.Item> all = List.of(countAll, new Statement.Item.Aggregate(Statement.Fn.COUNT, "a"),
                new Statement.Item.Aggregate(Statement.Fn.SUM, "a"), new Statement.Item.Aggregate(Statement.Fn.MIN, "b"),
                new Statement.Item.Aggregate(Statement.Fn.MAX, "_id"), new Statement.Item.Aggregate(Statement.Fn.AVG, "c"));
        assertEquals(new Statement.Select("d", "t", new Projection.Items(all), new Expr.IsNull("a", true), null, null),
                parse("SELECT count(*), COUNT(a), sum(A), Min(b), MAX(_id), avg(c) FROM d.t WHERE a IS NOT NULL"));
        assertEquals(List.of("count", "count(a)", "sum(a)", "min(b)", "max(_id)", "avg(c)"),
                all.stream().map(Statement.Item::label).toList());
        // COUNT(*) alone keeps its 2.1 form, with or without GROUP BY.
        assertEquals(new Statement.Select("d", "t", new Projection.Count(), null, List.of("a"), null, null),
                parse("SELECT COUNT(*) FROM d.t GROUP BY a"));
        assertEquals(new Statement.Select("d", "t", new Projection.Columns(List.of("a")), null, List.of("a", "b"),
                new Statement.OrderBy("b", true), 3L), parse("SELECT a FROM d.t GROUP BY a, b ORDER BY b DESC LIMIT 3"));
        Statement.Select s = (Statement.Select) parse(
                "SELECT a, COUNT(*), SUM(n) FROM d.t WHERE n > 0 GROUP BY a, b ORDER BY sum(N) DESC LIMIT 5");
        assertEquals(List.of(new Statement.OrderBy(new Statement.Item.Aggregate(Statement.Fn.SUM, "n"), true)), s.orderBy());
        assertEquals(List.of("a", "b"), s.groupBy());
        assertEquals(List.of(new Statement.OrderBy(countAll, false)),
                ((Statement.Select) parse("SELECT a, COUNT(*) FROM d.t GROUP BY a ORDER BY count(*) ASC")).orderBy());
        roundTrips("SELECT a, COUNT(*), AVG(n), MIN(_id) FROM d.t WHERE n IN (1, 2) GROUP BY a ORDER BY AVG(n) LIMIT 0");
        roundTrips("SELECT COUNT(*), COUNT(*) FROM d.t");
        roundTrips("SELECT COUNT(a) FROM d.t GROUP BY a ORDER BY a DESC");
    }

    @Test
    void aggregateErrors() {
        ParseException e = fails("SELECT a, COUNT(*) FROM d.t");
        assertEquals("column 'a' is listed with an aggregate, so it must be in a GROUP BY at position 7", e.getMessage());
        e = fails("SELECT b, a, SUM(n) FROM d.t GROUP BY b");
        assertEquals("column 'a' is listed but is not in GROUP BY at position 10", e.getMessage());
        assertEquals(7, fails("SELECT a FROM d.t GROUP BY b").position());
        assertEquals("SELECT * cannot have GROUP BY at position 18", fails("SELECT * FROM d.t GROUP BY a").getMessage());
        assertTrue(fails("SELECT a FROM d.t GROUP BY a, A").getMessage().contains("listed twice in GROUP BY"));
        assertEquals("SUM(*) is not an aggregate: only COUNT takes * at position 11", fails("SELECT SUM(*) FROM d.t").getMessage());
        assertTrue(fails("SELECT SUM(a) FROM d.t ORDER BY a").getMessage()
                .startsWith("aggregates without GROUP BY cannot have ORDER BY"));
        assertTrue(fails("SELECT MIN(a), MAX(a) FROM d.t LIMIT 1 ORDER BY a").getMessage().startsWith("expected end"));
        assertTrue(fails("SELECT COUNT(*) FROM d.t ORDER BY a").getMessage().startsWith("COUNT(*) cannot have ORDER BY"));
        e = fails("SELECT a, COUNT(*) FROM d.t GROUP BY a ORDER BY b");
        assertEquals("ORDER BY 'b' must name a GROUP BY column or an aggregate of the select list at position 48",
                e.getMessage());
        assertEquals("ORDER BY SUM(n) must be in the select list at position 48",
                fails("SELECT a, COUNT(*) FROM d.t GROUP BY a ORDER BY sum(n)").getMessage());
        assertEquals("ORDER BY COUNT(*) needs GROUP BY at position 27",
                fails("SELECT a FROM d.t ORDER BY COUNT(*)").getMessage());
        fails("SELECT a FROM d.t GROUP a");
        fails("SELECT a FROM d.t GROUP BY");
        fails("SELECT a FROM d.t GROUP BY a,");
        fails("SELECT a FROM d.t GROUP BY SUM(a)");
        fails("SELECT a FROM d.t ORDER BY a GROUP BY a");
        fails("SELECT COUNT(a FROM d.t");
        fails("SELECT COUNT() FROM d.t");
        fails("SELECT SUM(a, b) FROM d.t");
        fails("SELECT SUM(a) b FROM d.t");
        fails("SELECT *, COUNT(*) FROM d.t");
        fails("SELECT a, COUNT(*) FROM d.t GROUP BY a ORDER BY count");
    }

    @Test
    void insertAndSetWriteObjectsAndArrays() {
        Statement.Insert i = (Statement.Insert) parse(
                "INSERT INTO d.t (_id, tags, doc) VALUES ('a', ['x', 2, -0.0, TRUE, NULL, []], {'k': {'it''s': [1]}, '': {}})");
        java.util.Map<String, Object> doc = new java.util.LinkedHashMap<>();
        doc.put("k", java.util.Map.of("it's", List.of(1L)));
        doc.put("", java.util.Map.of());
        assertEquals(List.of("a", Arrays.asList("x", 2L, -0.0, true, null, List.of()), doc), i.rows().get(0));
        assertTrue(Double.doubleToRawLongBits((Double) ((List<?>) i.rows().get(0).get(1)).get(2)) < 0, "-0.0 keeps its sign");
        roundTrips("INSERT INTO d.t (_id, tags, doc) VALUES ('a', ['x', 2, -0.0, TRUE, NULL, []], {'k': {'it''s': [1]}, '': {}})");
        assertEquals("INSERT INTO d.t (_id, v) VALUES ('a', {'k': [1, 2.5, 'é😀'], 'n': NULL})",
                Printer.print(parse("insert into d.t (_id, v) values ('a', { 'k' : [ 1 , 2.5, 'é😀' ] , 'n':null })")));
        assertEquals(List.of(new Statement.Assignment("v", List.of(1L)), new Statement.Assignment("w", java.util.Map.of("a", true))),
                ((Statement.Update) parse("UPDATE d.t SET v = [1], w = {'a': TRUE}")).assignments());
        roundTrips("INSERT INTO d.t (_id, v) VALUES ('a', [1]) ON CONFLICT DO UPDATE SET v = {'b': [2, [3]]}");
        // The values are the statement's own: nothing can change them after parsing.
        List<?> tags = (List<?>) i.rows().get(0).get(1);
        assertThrows(UnsupportedOperationException.class, () -> tags.clear());

        assertEquals("key 'k' is listed twice", failsAt("INSERT INTO d.t (v) VALUES ({'k': 1, 'k': 2})", "'k': 2"));
        assertTrue(failsAt("INSERT INTO d.t (v) VALUES ({k: 1})", "k:").startsWith("expected a key (a string)"));
        assertTrue(failsAt("INSERT INTO d.t (v) VALUES ({'k' 1})", "1}").startsWith("expected ':'"));
        assertTrue(failsAt("INSERT INTO d.t (v) VALUES ([1, ])", "])").startsWith("expected a value"));
        assertTrue(failsAtEnd("INSERT INTO d.t (v) VALUES ([1, 2").startsWith("expected ']'"));
        assertTrue(failsAt("INSERT INTO d.t (v) VALUES ([b])", "b])").startsWith("expected a value"));
        // Only values written hold them: a condition compares with a literal.
        assertTrue(failsAt("SELECT * FROM d.t WHERE v = [1]", "[").startsWith("expected a value"));
        assertTrue(failsAt("SELECT * FROM d.t WHERE v IN ({'a': 1})", "{").startsWith("expected a value"));
        assertTrue(failsAt("UPDATE d.t SET v = 1 WHERE w = {'a': 1}", "{").startsWith("expected a value"));

        String deep = "[".repeat(Parser.MAX_VALUE_DEPTH) + "]".repeat(Parser.MAX_VALUE_DEPTH);
        Statement.Insert d = (Statement.Insert) parse("INSERT INTO d.t (v) VALUES (" + deep + ")");
        assertEquals(deep, Printer.value(d.rows().get(0).get(0)));
        String tooDeep = "{'a': " + deep + "}";
        assertEquals("objects and arrays nest at most 64 deep",
                failsAt("INSERT INTO d.t (v) VALUES (" + tooDeep + ")", "[]"));
        assertThrows(IllegalArgumentException.class, () -> new Statement.Assignment("v",
                com.ataiva.serengeti.core.json.Json.parse("[".repeat(Parser.MAX_VALUE_DEPTH + 1) + "]".repeat(Parser.MAX_VALUE_DEPTH + 1))));
    }

    private static Statement.Select select(String s) {
        return (Statement.Select) parse(s);
    }

    /** The message of the parse error, which must be at the first {@code at} in {@code text}. */
    private static String failsAt(String text, String at) {
        ParseException e = fails(text);
        assertEquals(text.indexOf(at), e.position(), e.getMessage());
        return e.getMessage().replaceFirst(" at position \\d+$", "");
    }

    /** As {@link #failsAt}, for a failure at the end of the text. */
    private static String failsAtEnd(String text) {
        ParseException e = fails(text);
        assertEquals(text.length(), e.position(), e.getMessage());
        return e.getMessage().replaceFirst(" at position \\d+$", "");
    }

    @Test
    void selectDistinct() {
        assertEquals(new Statement.Select("d", "t", new Projection.Columns(List.of("a", "b")), true,
                new Expr.Compare("a", Cmp.GT, 1L), List.of(), null,
                List.of(new Statement.OrderBy("b", true), new Statement.OrderBy("a", false)), 3L),
                parse("SELECT DISTINCT a, B FROM d.t WHERE a > 1 ORDER BY b DESC, a LIMIT 3"));
        assertEquals(List.of("a", "b"), select("select distinct a, b from d.t").groups());
        assertEquals(List.of(), select("SELECT a, b FROM d.t").groups());
        roundTrips("SELECT DISTINCT a FROM d.t");
        roundTrips("SELECT DISTINCT _id, a FROM d.t WHERE a IN (1, 2) ORDER BY _id DESC LIMIT 0");
        // DISTINCT is the keyword only right after SELECT and before a column: distinct is a name everywhere else.
        Statement.Select plain = select("SELECT distinct FROM d.t");
        assertFalse(plain.distinct());
        assertEquals(new Projection.Columns(List.of("distinct")), plain.projection());
        assertEquals(new Projection.Columns(List.of("distinct", "a")), select("SELECT distinct, a FROM d.t").projection());
        assertFalse(select("SELECT distinct, a FROM d.t").distinct());
        Statement.Select both = select("SELECT DISTINCT distinct, a FROM distinct.distinct WHERE distinct = 1 ORDER BY distinct");
        assertTrue(both.distinct());
        assertEquals(new Projection.Columns(List.of("distinct", "a")), both.projection());
        assertEquals(new Expr.Compare("distinct", Cmp.EQ, 1L), both.where());
        roundTrips("SELECT DISTINCT distinct FROM d.t");
        roundTrips("SELECT distinct FROM d.t");
        roundTrips("SELECT distinct, COUNT(*) FROM d.t GROUP BY distinct ORDER BY distinct");
        roundTrips("INSERT INTO d.t (distinct) VALUES (1)");
        roundTrips("UPDATE d.t SET distinct = 2 WHERE distinct IS NULL");
        assertEquals(new Statement.CreateTable("distinct", "distinct", false), parse("CREATE TABLE distinct.distinct"));
    }

    @Test
    void selectDistinctErrors() {
        assertEquals("SELECT DISTINCT takes columns, not *", failsAt("SELECT DISTINCT * FROM d.t", "*"));
        assertEquals("SELECT DISTINCT cannot list an aggregate such as COUNT(*): use GROUP BY",
                failsAt("SELECT DISTINCT a, COUNT(*) FROM d.t", "COUNT"));
        assertEquals("SELECT DISTINCT cannot list an aggregate such as SUM(a): use GROUP BY",
                failsAt("SELECT DISTINCT sum(a) FROM d.t", "sum"));
        assertEquals("SELECT DISTINCT cannot have GROUP BY", failsAt("SELECT DISTINCT a FROM d.t GROUP BY a", "GROUP"));
        assertEquals("SELECT DISTINCT cannot have HAVING", failsAt("SELECT DISTINCT a FROM d.t HAVING a > 1", "HAVING"));
        assertEquals("column 'a' is listed twice in SELECT DISTINCT", failsAt("SELECT DISTINCT a, b, A FROM d.t", "A FROM"));
        assertEquals("ORDER BY 'b' must name a column of the SELECT DISTINCT list",
                failsAt("SELECT DISTINCT a FROM d.t ORDER BY a, b", "b"));
        assertEquals("ORDER BY COUNT(*) must name a column of the SELECT DISTINCT list",
                failsAt("SELECT DISTINCT a FROM d.t ORDER BY COUNT(*)", "COUNT"));
        assertTrue(failsAt("SELECT DISTINCT order FROM d.t", "order").contains("keyword ORDER"));
        fails("SELECT DISTINCT a b FROM d.t");
        fails("SELECT DISTINCT DISTINCT a FROM d.t");
        fails("SELECT DISTINCT");
        fails("SELECT DISTINCT a,");
    }

    @Test
    void countDistinct() {
        Statement.Item.Aggregate ca = new Statement.Item.Aggregate(Statement.Fn.COUNT, "a", true);
        Statement.Select s = select("SELECT COUNT(DISTINCT a), count(distinct B), COUNT(a) FROM d.t");
        List<Statement.Item> items = List.of(ca, new Statement.Item.Aggregate(Statement.Fn.COUNT, "b", true),
                new Statement.Item.Aggregate(Statement.Fn.COUNT, "a"));
        assertEquals(new Projection.Items(items), s.projection());
        assertEquals(List.of("count(distinct a)", "count(distinct b)", "count(a)"),
                items.stream().map(Statement.Item::label).toList());
        items.forEach(i -> assertEquals(i, Statement.Item.ofLabel(i.label())));
        // COUNT(distinct) counts a column called distinct; COUNT(DISTINCT distinct) its distinct values.
        assertEquals(new Projection.Items(List.of(new Statement.Item.Aggregate(Statement.Fn.COUNT, "distinct"),
                new Statement.Item.Aggregate(Statement.Fn.COUNT, "distinct", true),
                new Statement.Item.Aggregate(Statement.Fn.SUM, "distinct"))),
                select("SELECT COUNT(distinct), COUNT(DISTINCT distinct), SUM(distinct) FROM d.t").projection());
        assertEquals("count(distinct)", new Statement.Item.Aggregate(Statement.Fn.COUNT, "distinct").label());
        assertEquals(new Statement.Item.Aggregate(Statement.Fn.COUNT, "distinct"), Statement.Item.ofLabel("count(distinct)"));
        roundTrips("SELECT a, COUNT(DISTINCT b) FROM d.t GROUP BY a ORDER BY COUNT(DISTINCT b) DESC");
        roundTrips("SELECT COUNT(distinct), COUNT(DISTINCT distinct) FROM d.t");
        assertEquals("SUM(DISTINCT) is not an aggregate: only COUNT takes DISTINCT",
                failsAt("SELECT SUM(DISTINCT a) FROM d.t", "DISTINCT"));
        assertEquals("AVG(DISTINCT) is not an aggregate: only COUNT takes DISTINCT",
                failsAt("SELECT AVG(distinct a) FROM d.t", "distinct"));
        assertEquals("COUNT(DISTINCT *) is not an aggregate: COUNT(DISTINCT) takes a column",
                failsAt("SELECT COUNT(DISTINCT *) FROM d.t", "*"));
        assertEquals("ORDER BY COUNT(DISTINCT b) must be in the select list",
                failsAt("SELECT a, COUNT(b) FROM d.t GROUP BY a ORDER BY COUNT(DISTINCT b)", "COUNT(DISTINCT"));
        fails("SELECT COUNT(DISTINCT a, b) FROM d.t");
        fails("SELECT COUNT(DISTINCT a b) FROM d.t");
        fails("SELECT COUNT(DISTINCT 1) FROM d.t");
    }

    @Test
    void having() {
        Statement.Select s = select("SELECT a, COUNT(*) FROM d.t GROUP BY a HAVING COUNT(*) > 1 AND sum(N) IS NOT NULL "
                + "ORDER BY a");
        assertEquals(new Expr.And(List.of(new Expr.Compare("count", Cmp.GT, 1L), new Expr.IsNull("sum(n)", true))),
                s.having());
        assertEquals(List.of(Statement.Item.Aggregate.COUNT_ALL, new Statement.Item.Aggregate(Statement.Fn.SUM, "n")),
                Statement.Select.havingItems(s.having()));
        assertEquals(List.of(new Statement.OrderBy("a", false)), s.orderBy());
        assertEquals(new Expr.Or(List.of(new Expr.In("a", List.of(1L, 2L), true), new Expr.Like("max(b)", "x%", false),
                new Expr.Not(new Expr.Compare("count(distinct c)", Cmp.LE, 2.5)))),
                select("SELECT COUNT(*) FROM d.t GROUP BY a HAVING a NOT IN (1, 2) OR MAX(b) LIKE 'x%' "
                        + "OR NOT COUNT(DISTINCT c) <= 2.5").having());
        roundTrips("SELECT a, COUNT(*) FROM d.t WHERE b = 1 GROUP BY a, b HAVING (COUNT(*) > 1 OR b IS NULL) "
                + "AND NOT AVG(n) IN (1, 2.5) ORDER BY count(*) DESC, a LIMIT 3");
        roundTrips("SELECT COUNT(*) FROM d.t HAVING COUNT(*) > 1");
        roundTrips("SELECT SUM(n) FROM d.t WHERE n > 0 HAVING NOT (MAX(n) IN (1, 2) OR min(s) LIKE 'a%')");
        roundTrips("SELECT a FROM d.t GROUP BY a, b HAVING b IS NULL OR COUNT(DISTINCT c) >= 2");
        // HAVING is the keyword only after the table, WHERE or GROUP BY: having is a name everywhere else.
        assertEquals(new Statement.Select("having", "having", new Projection.Columns(List.of("having")),
                new Expr.Compare("having", Cmp.EQ, 1L), new Statement.OrderBy("having", false), null),
                parse("SELECT having FROM having.having WHERE having = 1 ORDER BY having"));
        Statement.Select h = select("SELECT having, COUNT(having) FROM d.t GROUP BY having HAVING having > 1");
        assertEquals(List.of("having"), h.groupBy());
        assertEquals(new Expr.Compare("having", Cmp.GT, 1L), h.having());
        assertEquals(new Expr.IsNull("count", false), select("SELECT COUNT(*) FROM d.having HAVING COUNT(*) IS NULL").having());
        roundTrips("SELECT having FROM d.t GROUP BY having HAVING having > 1");
        roundTrips("INSERT INTO d.t (having, distinct) VALUES (1, 2)");
        assertEquals(new Statement.CreateIndex("having", "having", "having", false), parse("CREATE INDEX ON having.having (having)"));
    }

    @Test
    void havingErrors() {
        assertEquals("HAVING 'b' must name a GROUP BY column or an aggregate",
                failsAt("SELECT a FROM d.t GROUP BY a HAVING a = 1 AND b > 1", "b >"));
        assertEquals("HAVING 'a' must name a GROUP BY column or an aggregate",
                failsAt("SELECT COUNT(*) FROM d.t HAVING a > 1", "a >"));
        assertEquals("HAVING needs GROUP BY or a select list of aggregates only",
                failsAt("SELECT a FROM d.t HAVING a > 1", "HAVING"));
        assertEquals("SELECT * cannot have HAVING", failsAt("SELECT * FROM d.t HAVING COUNT(*) > 1", "HAVING"));
        assertEquals("column 'a' is listed with an aggregate, so it must be in a GROUP BY",
                failsAt("SELECT a, COUNT(*) FROM d.t HAVING COUNT(*) > 1", "a"));
        assertEquals("COUNT(*) cannot have ORDER BY", failsAt("SELECT COUNT(*) FROM d.t HAVING COUNT(*) > 1 ORDER BY a", "ORDER"));
        assertEquals("aggregates without GROUP BY cannot have ORDER BY",
                failsAt("SELECT SUM(a) FROM d.t HAVING SUM(a) > 1 ORDER BY a LIMIT 1", "ORDER"));
        assertTrue(fails("SELECT a FROM d.t GROUP BY a HAVING 1 = COUNT(*)").getMessage().startsWith("expected a name"));
        fails("SELECT a FROM d.t GROUP BY a HAVING");
        fails("SELECT a FROM d.t GROUP BY a HAVING COUNT(*)");
        fails("SELECT a FROM d.t GROUP BY a HAVING COUNT(*) > b");
        fails("SELECT a FROM d.t GROUP BY a HAVING COUNT(*) NOT 1");
        fails("SELECT a FROM d.t GROUP BY a HAVING SUM(*) > 1");
        fails("SELECT a FROM d.t GROUP BY a ORDER BY a HAVING a = 1");
        fails("SELECT COUNT(*) FROM d.t HAVING COUNT(*) > 1 GROUP BY a");
        fails("SELECT a FROM d.t GROUP BY a HAVING a = 1 HAVING a = 2");
        assertTrue(fails("SELECT a FROM d.t GROUP BY a HAVING " + "(".repeat(Parser.MAX_DEPTH + 1) + "a = 1"
                + ")".repeat(Parser.MAX_DEPTH + 1)).getMessage().contains("nested"));
    }

    @Test
    void limitAndOffset() {
        Statement.Select s = select("SELECT * FROM d.t ORDER BY a LIMIT 10 OFFSET 20");
        assertEquals(10L, s.limit());
        assertEquals(20L, s.offset());
        assertEquals(new Statement.Select("d", "t", new Projection.All(), false, null, List.of(), null,
                List.of(new Statement.OrderBy("a", false)), 10L, 20L), s);
        s = select("select a from d.t offset 3");
        assertEquals(null, s.limit());
        assertEquals(3L, s.offset());
        assertEquals(null, select("SELECT * FROM d.t LIMIT 5").offset());
        assertEquals(0L, select("SELECT * FROM d.t OFFSET 0").offset());
        assertFalse(select("SELECT * FROM d.t OFFSET 0").equals(select("SELECT * FROM d.t")), "OFFSET 0 is written");
        // Every form takes them: plain, ordered, grouped with HAVING, DISTINCT, and aggregates without GROUP BY.
        for (String q : List.of("SELECT * FROM d.t LIMIT 0 OFFSET 0", "SELECT a, b FROM d.t WHERE a > 1 OFFSET 7",
                "SELECT * FROM d.t ORDER BY a DESC, _id LIMIT 3 OFFSET 2", "SELECT _id FROM d.t ORDER BY _id OFFSET 9",
                "SELECT a, COUNT(*) FROM d.t GROUP BY a HAVING COUNT(*) > 1 ORDER BY COUNT(*) DESC LIMIT 2 OFFSET 1",
                "SELECT DISTINCT a FROM d.t ORDER BY a LIMIT 1 OFFSET 1", "SELECT COUNT(*) FROM d.t OFFSET 1",
                "SELECT COUNT(*) FROM d.t WHERE a = 1 LIMIT 0", "SELECT MIN(a), SUM(b) FROM d.t HAVING MIN(a) > 0 LIMIT 1 OFFSET 0",
                "SELECT * FROM d.t WHERE (a IN (1, 2) OR b = 3) LIMIT 9223372036854775807 OFFSET 9223372036854775807")) {
            roundTrips(q);
            assertEquals(q, Printer.print(parse(q)));
        }
        // OFFSET is not reserved: a column, a table or a database may be called offset.
        assertEquals(new Projection.Columns(List.of("offset")), select("SELECT offset FROM d.t").projection());
        s = select("SELECT offset FROM offset.offset WHERE offset = 1 GROUP BY offset ORDER BY offset LIMIT 1 OFFSET 2");
        assertEquals(List.of("offset"), s.groupBy());
        assertEquals(2L, s.offset());
        roundTrips("SELECT offset FROM offset.offset WHERE offset = 1 ORDER BY offset DESC OFFSET 2");
        assertEquals(new Statement.Insert("d", "offset", List.of("offset"), List.of(List.of(1L))),
                parse("INSERT INTO d.offset (offset) VALUES (1)"));
        // A non-negative whole number, once, after LIMIT.
        for (String n : List.of("-1", "1.5", "1e2", "'5'", "NULL", "9223372036854775808", "a")) {
            assertEquals("expected a non-negative whole number",
                    failsAt("SELECT * FROM d.t OFFSET " + n, n).replaceFirst(" but found .*", ""), n);
        }
        assertEquals("expected a non-negative whole number but found end of input",
                failsAtEnd("SELECT * FROM d.t LIMIT 1 OFFSET"));
        assertEquals("LIMIT comes before OFFSET", failsAt("SELECT * FROM d.t OFFSET 1 LIMIT 2", "LIMIT"));
        assertTrue(failsAt("SELECT * FROM d.t LIMIT 1 OFFSET 2 OFFSET 3", "OFFSET 3").startsWith("expected end of input"));
        assertTrue(failsAt("SELECT * FROM d.t OFFSET 2 ORDER BY a", "ORDER").startsWith("expected end of input"));
        assertTrue(failsAt("SELECT * FROM d.t OFFSET 2 WHERE a = 1", "WHERE").startsWith("expected end of input"));
        assertTrue(failsAt("SELECT COUNT(*) FROM d.t OFFSET 1 ORDER BY a", "ORDER").startsWith("expected end of input"));
        fails("UPDATE d.t SET a = 1 OFFSET 1");
        fails("DELETE FROM d.t OFFSET 1");
        fails("SELECT * FROM d.t LIMIT OFFSET 1");
    }

    @Test
    void orderByKeys() {
        assertEquals(List.of(new Statement.OrderBy("a", true), new Statement.OrderBy("b", false), new Statement.OrderBy("_id", false)),
                select("SELECT * FROM d.t ORDER BY a DESC, b, _id ASC LIMIT 2").orderBy());
        assertEquals(List.of(), select("SELECT * FROM d.t").orderBy());
        roundTrips("SELECT a, b FROM d.t WHERE a = 1 ORDER BY b DESC, a, nope DESC LIMIT 5");
        roundTrips("SELECT a, COUNT(*), SUM(n) FROM d.t GROUP BY a, b ORDER BY count(*) DESC, b, SUM(n), a DESC");
        java.util.function.IntFunction<String> keys = n -> java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> "c" + i + (i % 2 == 0 ? " DESC" : "")).collect(java.util.stream.Collectors.joining(", "));
        assertEquals(Parser.MAX_ORDER_BY, select("SELECT * FROM d.t ORDER BY " + keys.apply(Parser.MAX_ORDER_BY)).orderBy().size());
        String over = "SELECT * FROM d.t ORDER BY " + keys.apply(Parser.MAX_ORDER_BY + 1);
        assertEquals("ORDER BY takes at most 32 keys", failsAt(over, "c32"));
        assertEquals("column 'a' is listed twice in ORDER BY", failsAt("SELECT * FROM d.t ORDER BY a, b, A DESC", "A DESC"));
        assertEquals("COUNT(*) is listed twice in ORDER BY",
                failsAt("SELECT a, COUNT(*) FROM d.t GROUP BY a ORDER BY COUNT(*), a, count(*) DESC", "count"));
        assertEquals("ORDER BY 'b' must name a GROUP BY column or an aggregate of the select list",
                failsAt("SELECT a FROM d.t GROUP BY a ORDER BY a, b", "b"));
        assertEquals("ORDER BY COUNT(*) needs GROUP BY", failsAt("SELECT * FROM d.t ORDER BY a, COUNT(*)", "COUNT"));
        fails("SELECT * FROM d.t ORDER BY a,");
        fails("SELECT * FROM d.t ORDER BY a DESC DESC");
        fails("SELECT * FROM d.t ORDER BY a b");
        fails("SELECT * FROM d.t ORDER BY , a");
    }
}
