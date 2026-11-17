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
                "ORDER", "BY", "DESC", "LIMIT", "COUNT", "--", "\n", " ", ".", "!", "x".repeat(70), "é",
                "LIKE", "GROUP", "SUM", "MIN", "MAX", "AVG", "'%'", "'a\\'", "'\\%_'", "%", "_", "\\", "IN (1, 2)",
                "DISTINCT", "HAVING", "COUNT(*)", "COUNT(DISTINCT a)", "ASC", "+", "-", "a + 1", "a - -1", "a-1",
                "ON", "CONFLICT", "DO", "NOTHING", "ON CONFLICT DO NOTHING", "ON CONFLICT DO UPDATE SET a = a + 1");
    }

    @Provide
    Arbitrary<String> queryish() {
        Arbitrary<String> fromTokens = tokens().list().ofMaxSize(30).map(l -> String.join(" ", l));
        Arbitrary<String> glued = tokens().list().ofMaxSize(30).map(l -> String.join("", l));
        return Arbitraries.oneOf(fromTokens, glued, Arbitraries.strings().ofMaxLength(80));
    }

    // Generators for statements.

    private static Arbitrary<String> names() {
        // distinct, having, on, conflict, do and nothing are keywords only where the grammar puts them, and names
        // everywhere else.
        Arbitrary<String> common = Arbitraries.of("a", "b", "x1", "_id", "price", "name_2", "_", "distinct", "having",
                "on", "conflict", "do", "nothing");
        Arbitrary<String> random = Arbitraries.strings().withCharRange('a', 'z').withChars('_', '0', '9')
                .ofMinLength(1).ofMaxLength(Lexer.MAX_NAME_LENGTH);
        return Arbitraries.oneOf(common, random).filter(Lexer::isValidName);
    }

    /** The columns of CREATE or DROP INDEX: 1 to 4, each once. */
    private static Arbitrary<List<String>> indexColumns() {
        return names().list().uniqueElements().ofMinSize(1).ofMaxSize(Parser.MAX_INDEX_COLUMNS);
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
        Arbitrary<Expr> in = Combinators.combine(names(), literals().list().ofMinSize(1).ofMaxSize(4),
                Arbitraries.of(true, false)).as(Expr.In::new);
        Arbitrary<String> pattern = Arbitraries.of("a", "%", "_", "\\%", "\\_", "\\\\", "'", "é", "Z", "--")
                .list().ofMaxSize(5).map(l -> String.join("", l));
        Arbitrary<Expr> like = Combinators.combine(names(), pattern, Arbitraries.of(true, false))
                .as((c, p, neg) -> new Expr.Like(c, p, neg));
        Arbitrary<Expr> leaf = Arbitraries.oneOf(compare, isNull, in, like);
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
        Arbitrary<Statement> plain = Combinators.combine(names(), names(), projection, exprs(3).injectNull(0.3), order, limit)
                .as((db, t, p, w, o, l) -> p instanceof Statement.Projection.Count
                        ? new Statement.Select(db, t, p, w, null, null)
                        : new Statement.Select(db, t, p, w, o, l));
        return Arbitraries.oneOf(plain, grouped(), newForms());
    }

    /**
     * SELECT DISTINCT, HAVING (with aggregates the list lacks), COUNT(DISTINCT) and ORDER BY on several keys,
     * built to follow the rules from generated names and literals and a seed for the choices.
     */
    private static Arbitrary<Statement> newForms() {
        return Combinators.combine(names(), names(), names().list().uniqueElements().ofMinSize(1).ofMaxSize(4),
                exprs(2).injectNull(0.4), literals().list().ofSize(6), Arbitraries.longs(),
                Arbitraries.longs().between(0, 20).injectNull(0.5)).as((db, t, cols, w, lits, seed, limit) -> {
                    java.util.Random r = new java.util.Random(seed);
                    if (r.nextInt(3) == 0) {
                        List<Statement.Item> keys = cols.stream().<Statement.Item>map(Statement.Item.Column::new).toList();
                        return new Statement.Select(db, t, new Statement.Projection.Columns(cols), true, w, List.of(), null,
                                ExecutorProperties.randomOrder(r, keys, 4), limit);
                    }
                    boolean noGroup = r.nextInt(4) == 0;
                    List<String> by = noGroup ? List.of() : cols;
                    java.util.function.Supplier<Statement.Item.Aggregate> agg = () -> {
                        int k = r.nextInt(4);
                        String c = ExecutorProperties.pick(r, cols);
                        return k == 0 ? Statement.Item.Aggregate.COUNT_ALL
                                : k == 1 ? new Statement.Item.Aggregate(Statement.Fn.COUNT, c, true)
                                : new Statement.Item.Aggregate(ExecutorProperties.pick(r, List.of(Statement.Fn.values())), c);
                    };
                    List<Statement.Item> items = new ArrayList<>();
                    by.stream().filter(c -> r.nextBoolean()).forEach(c -> items.add(new Statement.Item.Column(c)));
                    for (int i = r.nextInt(4); i > 0; i--) {
                        items.add(agg.get());
                    }
                    if (items.isEmpty()) {
                        items.add(noGroup ? agg.get() : new Statement.Item.Column(by.get(0)));
                    }
                    List<Statement.Item> lefts = new ArrayList<>();
                    by.forEach(c -> lefts.add(new Statement.Item.Column(c)));
                    items.stream().filter(i -> i instanceof Statement.Item.Aggregate).forEach(lefts::add);
                    lefts.add(agg.get());
                    Expr having = ExecutorProperties.randomHaving(r, lefts, lits, 0);
                    Statement.Projection p = ExecutorProperties.projectionOf(items);
                    if (noGroup) {
                        return new Statement.Select(db, t, p, false, w, by, having, List.of(), null);
                    }
                    List<Statement.Item> keys = new ArrayList<>();
                    by.forEach(c -> keys.add(new Statement.Item.Column(c)));
                    items.stream().filter(i -> i instanceof Statement.Item.Aggregate).forEach(keys::add);
                    return new Statement.Select(db, t, p, false, w, by, r.nextBoolean() ? having : null,
                            ExecutorProperties.randomOrder(r, keys, 4), limit);
                });
    }

    /** SELECTs with aggregates or GROUP BY that follow the rules: built so, not filtered. */
    private static Arbitrary<Statement> grouped() {
        Arbitrary<Statement.Item.Aggregate> agg = Arbitraries.oneOf(Arbitraries.just(Statement.Item.Aggregate.COUNT_ALL),
                Combinators.combine(Arbitraries.of(Statement.Fn.class), names()).as(Statement.Item.Aggregate::new));
        Arbitrary<Long> limit = Arbitraries.longs().between(0, 20).injectNull(0.5);
        Arbitrary<Object[]> parts = Combinators.combine(names(), names(),
                names().list().uniqueElements().ofMaxSize(3), agg.list().ofMaxSize(3),
                Arbitraries.integers().between(0, 1023), exprs(2).injectNull(0.4), limit)
                .as((db, t, by, aggs, pick, w, l) -> new Object[] {db, t, by, aggs, pick, w, l});
        return parts.map(a -> {
            @SuppressWarnings("unchecked")
            List<String> by = (List<String>) a[2];
            @SuppressWarnings("unchecked")
            List<Statement.Item.Aggregate> aggs = (List<Statement.Item.Aggregate>) a[3];
            int pick = (Integer) a[4];
            List<Statement.Item> items = new ArrayList<>();
            for (int i = 0; i < by.size(); i++) {
                if ((pick >> i & 1) == 1) {
                    items.add(new Statement.Item.Column(by.get(i)));
                }
            }
            List<Statement.Item.Aggregate> ordered = new ArrayList<>(aggs);
            if ((pick & 8) == 8) {
                java.util.Collections.reverse(ordered);
            }
            items.addAll(ordered);
            if (items.isEmpty()) {
                items.add(by.isEmpty() ? Statement.Item.Aggregate.COUNT_ALL : new Statement.Item.Column(by.get(0)));
            }
            Statement.Projection p = items.equals(List.of(Statement.Item.Aggregate.COUNT_ALL))
                    ? new Statement.Projection.Count()
                    : items.stream().allMatch(i -> i instanceof Statement.Item.Column)
                            ? new Statement.Projection.Columns(items.stream().map(i -> ((Statement.Item.Column) i).name()).toList())
                            : new Statement.Projection.Items(items);
            if (by.isEmpty()) {
                return new Statement.Select((String) a[0], (String) a[1], p, (Expr) a[5], List.of(), null, null);
            }
            Statement.OrderBy o = null;
            if ((pick & 16) == 16) {
                List<Statement.Item> keys = new ArrayList<>();
                by.forEach(c -> keys.add(new Statement.Item.Column(c)));
                items.stream().filter(i -> i instanceof Statement.Item.Aggregate).forEach(keys::add);
                o = new Statement.OrderBy(keys.get((pick >> 5) % keys.size()), (pick & 4) == 4);
            }
            return new Statement.Select((String) a[0], (String) a[1], p, (Expr) a[5], by, o, (Long) a[6]);
        });
    }

    /** INSERTs, half of them with ON CONFLICT (DO NOTHING, or DO UPDATE SET as UPDATE's), which lists _id. */
    private static Arbitrary<Statement> inserts() {
        Arbitrary<List<String>> columns = names().list().uniqueElements().ofMinSize(1).ofMaxSize(4);
        Arbitrary<Statement.OnConflict> onConflict = Arbitraries.oneOf(Arbitraries.just(Statement.OnConflict.DO_NOTHING),
                assignments().map(Statement.OnConflict::new));
        return Combinators.combine(names(), names(), columns.flatMap(cols -> literals().list().ofSize(cols.size())
                        .list().ofMinSize(1).ofMaxSize(3).map(rows -> new Object[] {cols, rows})), onConflict.injectNull(0.5))
                .as((db, t, pair, oc) -> {
                    @SuppressWarnings("unchecked")
                    List<String> cols = new ArrayList<>((List<String>) pair[0]);
                    @SuppressWarnings("unchecked")
                    List<List<Object>> rows = (List<List<Object>>) pair[1];
                    if (oc != null && !cols.contains(Executor.ID)) {
                        cols.set(0, Executor.ID);
                    }
                    return new Statement.Insert(db, t, cols, rows, oc);
                });
    }

    /** The numbers of {@code column + number}: any long or finite double, and the edges. */
    private static Arbitrary<Object> numbers() {
        return Arbitraries.oneOf(Arbitraries.longs().map(Object.class::cast),
                Arbitraries.longs().between(-3, 3).map(Object.class::cast),
                Arbitraries.of(Long.MAX_VALUE, Long.MIN_VALUE, (1L << 53) + 1, 0.0, -0.0, 0x1p63, 4.9e-324, 1e300)
                        .map(Object.class::cast),
                Arbitraries.longs().map(Double::longBitsToDouble).filter(Double::isFinite).map(Object.class::cast));
    }

    /** The right side of a SET: a literal, or a column plus or minus a number. */
    private static Arbitrary<Object> setValues() {
        Arbitrary<Object> arithmetic = Combinators.combine(names().filter(n -> !n.equals(Executor.ID)),
                Arbitraries.of(true, false), numbers()).as(Statement.Arithmetic::new).map(Object.class::cast);
        return Arbitraries.oneOf(literals(), arithmetic);
    }

    /** The assignments of a SET: 1 to 3 columns, each once. */
    private static Arbitrary<List<Statement.Assignment>> assignments() {
        return names().list().uniqueElements().ofMinSize(1).ofMaxSize(3)
                .flatMap(cols -> setValues().list().ofSize(cols.size()).map(vals -> {
                    List<Statement.Assignment> out = new ArrayList<>();
                    for (int i = 0; i < cols.size(); i++) {
                        out.add(new Statement.Assignment(cols.get(i), vals.get(i)));
                    }
                    return out;
                }));
    }

    private static Arbitrary<Statement> updates() {
        return Combinators.combine(names(), names(), assignments(), exprs(3).injectNull(0.3)).as(Statement.Update::new);
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
                Combinators.combine(names(), names(), indexColumns(), flag)
                        .as((db, t, cols, f) -> (Statement) new Statement.CreateIndex(db, t, cols, f)),
                Combinators.combine(names(), names(), indexColumns(), flag)
                        .as((db, t, cols, f) -> (Statement) new Statement.DropIndex(db, t, cols, f)),
                Combinators.combine(names(), names()).as(Statement.ShowIndexes::new),
                Combinators.combine(names(), names()).as(Statement.ShowConflicts::new),
                inserts(),
                selects(),
                updates(),
                Combinators.combine(names(), names(), exprs(3).injectNull(0.3)).as(Statement.Delete::new));
    }
}
