package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

class QueryProperties {
    /** Parses, allowing only ParseException; anything else (including StackOverflowError) fails the property. */
    private static void parsesOrFailsCleanly(String text) {
        try {
            Parser.parse(text);
        } catch (ParseException expected) {
            // the only allowed failure
        }
    }

    @Property(tries = 3000)
    void arbitraryTextOnlyFailsWithParseException(@ForAll("queryish") String text) {
        parsesOrFailsCleanly(text);
    }

    @Property(tries = 2000)
    void randomBytesOnlyFailWithParseException(@ForAll byte[] bytes) {
        parsesOrFailsCleanly(new String(bytes, StandardCharsets.UTF_8));
        parsesOrFailsCleanly(new String(bytes, StandardCharsets.ISO_8859_1));
    }

    @Property(tries = 2000)
    void damagedStatementsOnlyFailWithParseException(@ForAll("statements") Statement s,
            @ForAll @IntRange(min = 0, max = 1000) int at, @ForAll @IntRange(min = 0, max = 5) int cut,
            @ForAll("fragments") String insert) {
        String text = Printer.print(s);
        int i = at % (text.length() + 1);
        int j = Math.min(text.length(), i + cut);
        parsesOrFailsCleanly(text.substring(0, i) + insert + text.substring(j));
    }

    @Property(tries = 3000)
    void printThenParseGivesTheSameStatement(@ForAll("statements") Statement s) {
        String text = Printer.print(s);
        assertEquals(s, Parser.parse(text), text);
        assertEquals(s, Parser.parse(text + " ;"));
    }

    @Property(tries = 200)
    void nestingWithinTheLimitParses(@ForAll("nestings") List<Boolean> parens) {
        StringBuilder b = new StringBuilder("SELECT * FROM d.t WHERE ");
        int open = 0;
        for (boolean p : parens) {
            if (p) {
                b.append('(');
                open++;
            } else {
                b.append("NOT ");
            }
        }
        b.append("a = 1").append(")".repeat(open));
        String text = b.toString();
        if (parens.size() <= Parser.MAX_DEPTH) {
            Parser.parse(text);
        } else {
            ParseException e = assertThrows(ParseException.class, () -> Parser.parse(text));
            assertEquals("condition nested more than " + Parser.MAX_DEPTH + " levels deep at position "
                    + e.position(), e.getMessage());
        }
    }

    @Property(tries = 30)
    void veryDeepNestingIsAParseException(@ForAll @IntRange(min = 65, max = 200_000) int depth,
            @ForAll @IntRange(min = 0, max = 2) int kind) {
        String open = kind == 0 ? "(" : kind == 1 ? "NOT " : "NOT (";
        String text = "DELETE FROM d.t WHERE " + open.repeat(depth) + "a = 1" + ")".repeat(depth);
        assertThrows(ParseException.class, () -> Parser.parse(text));
    }

    @Provide
    Arbitrary<List<Boolean>> nestings() {
        return Arbitraries.of(true, false).list().ofMinSize(0).ofMaxSize(150);
    }

    @Provide
    Arbitrary<String> fragments() {
        return Arbitraries.oneOf(tokens(), Arbitraries.strings().ofMaxLength(4));
    }

    private static Arbitrary<String> tokens() {
        return Arbitraries.of("SELECT", "*", "FROM", "d.t", "WHERE", "a", "=", "!=", "<>", "<", "<=", ">", ">=",
                "AND", "OR", "NOT", "(", ")", ",", ";", "'", "''", "'x'", "1", "-1", "1.5e3", "1e999", "1.",
                "99999999999999999999", "NULL", "IS", "TRUE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
                "DELETE", "CREATE", "DROP", "DATABASE", "TABLE", "IF", "EXISTS", "SHOW", "TABLES", "IN",
                "ORDER", "BY", "DESC", "LIMIT", "COUNT", "--", "\n", " ", ".", "!", "x".repeat(70), "é");
    }

    @Provide
    Arbitrary<String> queryish() {
        Arbitrary<String> fromTokens = tokens().list().ofMaxSize(30).map(l -> String.join(" ", l));
        Arbitrary<String> glued = tokens().list().ofMaxSize(30).map(l -> String.join("", l));
        return Arbitraries.oneOf(fromTokens, glued, Arbitraries.strings().ofMaxLength(80));
    }

    // Generators for statements.

    private static Arbitrary<String> names() {
        Arbitrary<String> common = Arbitraries.of("a", "b", "x1", "_id", "price", "name_2", "_");
        Arbitrary<String> random = Arbitraries.strings().withCharRange('a', 'z').withChars('_', '0', '9')
                .ofMinLength(1).ofMaxLength(Lexer.MAX_NAME_LENGTH);
        return Arbitraries.oneOf(common, random).filter(Lexer::isValidName);
    }

    private static Arbitrary<Object> literals() {
        Arbitrary<Object> strings = Arbitraries.strings().ofMaxLength(20).map(Object.class::cast);
        Arbitrary<Object> quoty = Arbitraries.of("", "'", "''", "it's", "a\nb", "--", "Mixed CASE")
                .map(Object.class::cast);
        Arbitrary<Object> longs = Arbitraries.longs().map(Object.class::cast);
        Arbitrary<Object> smallLongs = Arbitraries.longs().between(-3, 3).map(Object.class::cast);
        Arbitrary<Object> doubles = Arbitraries.doubles().map(Object.class::cast);
        Arbitrary<Object> anyDoubles = Arbitraries.longs().map(Double::longBitsToDouble)
                .filter(Double::isFinite).map(Object.class::cast);
        Arbitrary<Object> special = Arbitraries.of(0.0, -0.0, 1.0, 1e300, 4.9e-324, (double) Long.MAX_VALUE)
                .map(Object.class::cast);
        Arbitrary<Object> bools = Arbitraries.of(true, false).map(Object.class::cast);
        return Arbitraries.oneOf(strings, quoty, longs, smallLongs, doubles, anyDoubles, special, bools)
                .injectNull(0.1);
    }

    private static Arbitrary<Expr> exprs(int depth) {
        Arbitrary<Expr> compare = Combinators.combine(names(), Arbitraries.of(Expr.Cmp.class), literals())
                .as(Expr.Compare::new);
        Arbitrary<Expr> isNull = Combinators.combine(names(), Arbitraries.of(true, false)).as(Expr.IsNull::new);
        Arbitrary<Expr> leaf = Arbitraries.oneOf(compare, isNull);
        if (depth == 0) {
            return leaf;
        }
        Arbitrary<Expr> inner = Arbitraries.lazy(() -> exprs(depth - 1));
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(3, leaf),
                net.jqwik.api.Tuple.of(1, inner.map(Expr.Not::new)),
                net.jqwik.api.Tuple.of(1, inner.list().ofMinSize(2).ofMaxSize(3).map(Expr.And::new)),
                net.jqwik.api.Tuple.of(1, inner.list().ofMinSize(2).ofMaxSize(3).map(Expr.Or::new)));
    }

    private static Arbitrary<Statement> selects() {
        Arbitrary<Statement.Projection> projection = Arbitraries.oneOf(
                Arbitraries.just(new Statement.Projection.All()),
                Arbitraries.just(new Statement.Projection.Count()),
                names().list().ofMinSize(1).ofMaxSize(3).map(Statement.Projection.Columns::new));
        Arbitrary<Statement.OrderBy> order = Combinators.combine(names(), Arbitraries.of(true, false))
                .as(Statement.OrderBy::new).injectNull(0.5);
        Arbitrary<Long> limit = Arbitraries.oneOf(Arbitraries.longs().between(0, 20),
                Arbitraries.longs().between(0, Long.MAX_VALUE)).injectNull(0.5);
        return Combinators.combine(names(), names(), projection, exprs(3).injectNull(0.3), order, limit)
                .as((db, t, p, w, o, l) -> p instanceof Statement.Projection.Count
                        ? new Statement.Select(db, t, p, w, null, null)
                        : new Statement.Select(db, t, p, w, o, l));
    }

    private static Arbitrary<Statement> inserts() {
        Arbitrary<List<String>> columns = names().list().uniqueElements().ofMinSize(1).ofMaxSize(4);
        return Combinators.combine(names(), names(), columns.flatMap(cols -> literals().list().ofSize(cols.size())
                        .list().ofMinSize(1).ofMaxSize(3).map(rows -> new Object[] {cols, rows})))
                .as((db, t, pair) -> {
                    @SuppressWarnings("unchecked")
                    List<String> cols = (List<String>) pair[0];
                    @SuppressWarnings("unchecked")
                    List<List<Object>> rows = (List<List<Object>>) pair[1];
                    return new Statement.Insert(db, t, cols, rows);
                });
    }

    private static Arbitrary<Statement> updates() {
        Arbitrary<List<Statement.Assignment>> sets = names().list().uniqueElements().ofMinSize(1).ofMaxSize(3)
                .flatMap(cols -> literals().list().ofSize(cols.size()).map(vals -> {
                    List<Statement.Assignment> out = new ArrayList<>();
                    for (int i = 0; i < cols.size(); i++) {
                        out.add(new Statement.Assignment(cols.get(i), vals.get(i)));
                    }
                    return out;
                }));
        return Combinators.combine(names(), names(), sets, exprs(3).injectNull(0.3)).as(Statement.Update::new);
    }

    @Provide
    Arbitrary<Statement> statements() {
        Arbitrary<Boolean> flag = Arbitraries.of(true, false);
        return Arbitraries.oneOf(
                Combinators.combine(names(), flag).as(Statement.CreateDatabase::new),
                Combinators.combine(names(), flag).as(Statement.DropDatabase::new),
                Combinators.combine(names(), names(), flag).as(Statement.CreateTable::new),
                Combinators.combine(names(), names(), flag).as(Statement.DropTable::new),
                Arbitraries.just(new Statement.ShowDatabases()),
                names().map(Statement.ShowTables::new),
                inserts(),
                selects(),
                updates(),
                Combinators.combine(names(), names(), exprs(3).injectNull(0.3)).as(Statement.Delete::new));
    }
}
