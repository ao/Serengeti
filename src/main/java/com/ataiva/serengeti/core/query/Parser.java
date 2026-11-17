package com.ataiva.serengeti.core.query;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Recursive-descent parser for the grammar in docs/QUERY.md. Every failure is a
 * {@link ParseException} carrying the character position where it was noticed.
 */
public final class Parser {
    /** How deeply parentheses and NOT may nest in a WHERE condition. */
    public static final int MAX_DEPTH = 64;
    /**
     * Most items in one SELECT list, columns in one GROUP BY, and characters in one LIKE pattern. A list of
     * thousands of columns (a 1 MiB body holds 60,000) made one statement hold far more memory than its rows:
     * every row or group carries a slot for each, and a node ran out of memory on one read.
     */
    public static final int MAX_SELECT_ITEMS = 256;
    public static final int MAX_GROUP_BY = 32;
    public static final int MAX_LIKE_PATTERN = 1000;
    /** Most keys in one ORDER BY. */
    public static final int MAX_ORDER_BY = 32;

    private final Lexer lexer;
    private Token tok;
    private Token peeked;
    private int depth;
    /** While a HAVING condition is read: the GROUP BY columns its terms may name. Null elsewhere. */
    private List<String> havingGroups;

    private Parser(String text) {
        this.lexer = new Lexer(text);
        this.tok = lexer.next();
    }

    /** Parses one statement, with an optional trailing {@code ;}. */
    public static Statement parse(String text) {
        Parser p = new Parser(text);
        Statement s = p.statement();
        p.acceptSymbol(";");
        if (p.tok.kind() != Token.Kind.END) {
            throw p.expected("end of input");
        }
        return s;
    }

    private Statement statement() {
        if (acceptKeyword("create")) {
            return create();
        }
        if (acceptKeyword("drop")) {
            return drop();
        }
        if (acceptKeyword("show")) {
            return show();
        }
        if (acceptKeyword("insert")) {
            return insert();
        }
        if (acceptKeyword("select")) {
            return select();
        }
        if (acceptKeyword("update")) {
            return update();
        }
        if (acceptKeyword("delete")) {
            return delete();
        }
        throw expected("a statement (CREATE, DROP, SHOW, INSERT, SELECT, UPDATE or DELETE)");
    }

    private Statement create() {
        if (acceptKeyword("database")) {
            boolean ifNotExists = ifNotExists();
            return new Statement.CreateDatabase(name(), ifNotExists);
        }
        if (acceptKeyword("table")) {
            boolean ifNotExists = ifNotExists();
            String[] q = qualifiedName();
            return new Statement.CreateTable(q[0], q[1], ifNotExists);
        }
        if (acceptWord("index")) {
            boolean ifNotExists = ifNotExists();
            String[] q = indexOn();
            return new Statement.CreateIndex(q[0], q[1], indexColumns(), ifNotExists);
        }
        throw expected("DATABASE, TABLE or INDEX");
    }

    private Statement drop() {
        if (acceptKeyword("database")) {
            boolean ifExists = ifExists();
            return new Statement.DropDatabase(name(), ifExists);
        }
        if (acceptKeyword("table")) {
            boolean ifExists = ifExists();
            String[] q = qualifiedName();
            return new Statement.DropTable(q[0], q[1], ifExists);
        }
        if (acceptWord("index")) {
            boolean ifExists = ifExists();
            String[] q = indexOn();
            return new Statement.DropIndex(q[0], q[1], indexColumns(), ifExists);
        }
        throw expected("DATABASE, TABLE or INDEX");
    }

    private boolean ifNotExists() {
        if (!acceptKeyword("if")) {
            return false;
        }
        expectKeyword("not");
        expectKeyword("exists");
        return true;
    }

    private boolean ifExists() {
        if (!acceptKeyword("if")) {
            return false;
        }
        expectKeyword("exists");
        return true;
    }

    private Statement show() {
        if (acceptKeyword("databases")) {
            return new Statement.ShowDatabases();
        }
        if (acceptKeyword("tables")) {
            expectKeyword("in");
            return new Statement.ShowTables(name());
        }
        if (acceptWord("indexes")) {
            expectKeyword("in");
            String[] q = qualifiedName();
            return new Statement.ShowIndexes(q[0], q[1]);
        }
        // CONFLICTS is not a reserved word, so tables and columns may still be called that.
        if (tok.kind() == Token.Kind.NAME && tok.text().equalsIgnoreCase("conflicts")) {
            advance();
            expectKeyword("in");
            String[] q = qualifiedName();
            return new Statement.ShowConflicts(q[0], q[1]);
        }
        throw expected("DATABASES, TABLES, INDEXES or CONFLICTS");
    }

    /** The most columns an index lists. */
    static final int MAX_INDEX_COLUMNS = 4;

    /** {@code ON db.table} after CREATE INDEX or DROP INDEX: database and table. */
    private String[] indexOn() {
        if (!acceptWord("on")) {
            throw expected("ON");
        }
        return qualifiedName();
    }

    /** {@code (column {, column})} of CREATE INDEX or DROP INDEX: 1 to 4 columns, each once. */
    private List<String> indexColumns() {
        expectSymbol("(");
        List<String> columns = new ArrayList<>();
        do {
            int at = tok.start();
            String c = name();
            if (columns.contains(c)) {
                throw new ParseException("column '" + c + "' is listed twice", at);
            }
            if (columns.size() == MAX_INDEX_COLUMNS) {
                throw new ParseException("an index has at most " + MAX_INDEX_COLUMNS + " columns", at);
            }
            columns.add(c);
        } while (acceptSymbol(","));
        expectSymbol(")");
        return columns;
    }

    /** Accepts a word that is not reserved (INDEX, ON, INDEXES), so names may still use it. */
    private boolean acceptWord(String w) {
        if (tok.kind() == Token.Kind.NAME && tok.text().equalsIgnoreCase(w)) {
            advance();
            return true;
        }
        return false;
    }

    private Statement insert() {
        expectKeyword("into");
        String[] q = qualifiedName();
        expectSymbol("(");
        List<String> columns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        do {
            int at = tok.start();
            String c = name();
            if (!seen.add(c)) {
                throw new ParseException("column '" + c + "' is listed twice", at);
            }
            columns.add(c);
        } while (acceptSymbol(","));
        expectSymbol(")");
        expectKeyword("values");
        List<List<Object>> rows = new ArrayList<>();
        do {
            int at = tok.start();
            expectSymbol("(");
            List<Object> row = new ArrayList<>();
            do {
                row.add(value());
            } while (acceptSymbol(","));
            expectSymbol(")");
            if (row.size() != columns.size()) {
                throw new ParseException("expected " + columns.size() + " values but found " + row.size(), at);
            }
            rows.add(row);
        } while (acceptSymbol(","));
        return new Statement.Insert(q[0], q[1], columns, rows);
    }

    private Statement select() {
        // DISTINCT is not reserved: right after SELECT it is the keyword unless a comma or FROM follows, so
        // "SELECT distinct FROM t" and "SELECT distinct, a FROM t" still read a column called distinct.
        boolean distinct = false;
        if (isWord("distinct") && !isSymbol(peek(), ",") && !isKeyword(peek(), "from")) {
            advance();
            distinct = true;
        }
        Statement.Projection projection;
        List<Statement.Item> items = new ArrayList<>();
        List<Integer> itemAt = new ArrayList<>();
        if (isSymbol("*")) {
            if (distinct) {
                throw new ParseException("SELECT DISTINCT takes columns, not *", tok.start());
            }
            advance();
            projection = new Statement.Projection.All();
        } else {
            Set<String> listed = new HashSet<>();
            do {
                if (items.size() == MAX_SELECT_ITEMS) {
                    throw new ParseException("a SELECT lists at most " + MAX_SELECT_ITEMS + " items", tok.start());
                }
                int at = tok.start();
                itemAt.add(at);
                Statement.Item item = item();
                if (distinct) {
                    if (item instanceof Statement.Item.Aggregate a) {
                        throw new ParseException("SELECT DISTINCT cannot list an aggregate such as " + print(a)
                                + ": use GROUP BY", at);
                    }
                    String c = ((Statement.Item.Column) item).name();
                    if (!listed.add(c)) {
                        throw new ParseException("column '" + c + "' is listed twice in SELECT DISTINCT", at);
                    }
                }
                items.add(item);
            } while (acceptSymbol(","));
            if (items.equals(List.of(Statement.Item.Aggregate.COUNT_ALL))) {
                projection = new Statement.Projection.Count();
            } else if (items.stream().allMatch(i -> i instanceof Statement.Item.Column)) {
                projection = new Statement.Projection.Columns(
                        items.stream().map(i -> ((Statement.Item.Column) i).name()).toList());
            } else {
                projection = new Statement.Projection.Items(items);
            }
        }
        boolean aggregates = Statement.Projection.aggregates(projection);
        expectKeyword("from");
        String[] q = qualifiedName();
        Expr where = acceptKeyword("where") ? expr() : null;

        List<String> groupBy = new ArrayList<>();
        java.util.Set<String> grouped = new java.util.HashSet<>();
        int groupAt = tok.start();
        if (acceptWord("group")) {
            if (projection instanceof Statement.Projection.All) {
                throw new ParseException("SELECT * cannot have GROUP BY", groupAt);
            }
            if (distinct) {
                throw new ParseException("SELECT DISTINCT cannot have GROUP BY", groupAt);
            }
            expectKeyword("by");
            do {
                int at = tok.start();
                String c = name();
                if (!grouped.add(c)) {
                    throw new ParseException("column '" + c + "' is listed twice in GROUP BY", at);
                }
                if (groupBy.size() == MAX_GROUP_BY) {
                    throw new ParseException("GROUP BY takes at most " + MAX_GROUP_BY + " columns", at);
                }
                groupBy.add(c);
            } while (acceptSymbol(","));
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof Statement.Item.Column c && (aggregates || !groupBy.isEmpty())
                    && !grouped.contains(c.name())) {
                throw new ParseException(groupBy.isEmpty()
                        ? "column '" + c.name() + "' is listed with an aggregate, so it must be in a GROUP BY"
                        : "column '" + c.name() + "' is listed but is not in GROUP BY", itemAt.get(i));
            }
        }
        // HAVING is not reserved either: it is the keyword only here, after the table, WHERE or GROUP BY.
        Expr having = null;
        int havingAt = tok.start();
        if (acceptWord("having")) {
            if (distinct) {
                throw new ParseException("SELECT DISTINCT cannot have HAVING", havingAt);
            }
            if (groupBy.isEmpty() && !aggregates) {
                throw new ParseException((projection instanceof Statement.Projection.All ? "SELECT * cannot have HAVING"
                        : "HAVING needs GROUP BY or a select list of aggregates only"), havingAt);
            }
            havingGroups = groupBy;
            having = expr();
            havingGroups = null;
        }
        // Aggregates without GROUP BY make one row, which has nothing to sort or cut: as COUNT(*) in 2.1.
        String single = aggregates && groupBy.isEmpty()
                ? projection instanceof Statement.Projection.Count ? "COUNT(*)" : "aggregates without GROUP BY"
                : null;

        List<String> distinctColumns = distinct ? ((Statement.Projection.Columns) projection).names() : null;
        List<Statement.OrderBy> orderBy = new ArrayList<>();
        Long limit = null;
        if (isKeyword("order")) {
            if (single != null) {
                throw new ParseException(single + " cannot have ORDER BY", tok.start());
            }
            advance();
            expectKeyword("by");
            do {
                int at = tok.start();
                if (orderBy.size() == MAX_ORDER_BY) {
                    throw new ParseException("ORDER BY takes at most " + MAX_ORDER_BY + " keys", at);
                }
                Statement.Item key = item();
                if (distinct) {
                    if (!(key instanceof Statement.Item.Column c) || !distinctColumns.contains(c.name())) {
                        throw new ParseException("ORDER BY " + quoted(key) + " must name a column of the SELECT DISTINCT"
                                + " list", at);
                    }
                } else if (key instanceof Statement.Item.Column c) {
                    if (!groupBy.isEmpty() && !groupBy.contains(c.name())) {
                        throw new ParseException("ORDER BY '" + c.name() + "' must name a GROUP BY column or an aggregate"
                                + " of the select list", at);
                    }
                } else if (groupBy.isEmpty() || !items.contains(key)) {
                    throw new ParseException("ORDER BY " + print(key) + (groupBy.isEmpty() ? " needs GROUP BY"
                            : " must be in the select list"), at);
                }
                for (Statement.OrderBy o : orderBy) {
                    if (o.key().equals(key)) {
                        throw new ParseException((key instanceof Statement.Item.Column ? "column " : "") + quoted(key)
                                + " is listed twice in ORDER BY", at);
                    }
                }
                boolean desc = false;
                if (acceptKeyword("desc")) {
                    desc = true;
                } else {
                    acceptKeyword("asc");
                }
                orderBy.add(new Statement.OrderBy(key, desc));
            } while (acceptSymbol(","));
        }
        if (isKeyword("limit")) {
            if (single != null) {
                throw new ParseException(single + " cannot have LIMIT", tok.start());
            }
            advance();
            if (tok.kind() != Token.Kind.NUMBER || !(tok.value() instanceof Long n) || n < 0) {
                throw expected("a non-negative whole number");
            }
            limit = n;
            advance();
        }
        return new Statement.Select(q[0], q[1], projection, distinct, where, groupBy, having, orderBy, limit);
    }

    private static String print(Statement.Item i) {
        return i instanceof Statement.Item.Aggregate a
                ? a.fn() + "(" + (a.column() == null ? "*" : (a.distinct() ? "DISTINCT " : "") + a.column()) + ")"
                : ((Statement.Item.Column) i).name();
    }

    /** A column in quotes, as {@code 'a'}; an aggregate as {@link #print}. */
    private static String quoted(Statement.Item i) {
        return i instanceof Statement.Item.Column c ? "'" + c.name() + "'" : print(i);
    }

    /** The left side of a HAVING term: a GROUP BY column or an aggregate, as its label. */
    private String havingOperand() {
        int at = tok.start();
        Statement.Item i = item();
        if (i instanceof Statement.Item.Column c && !havingGroups.contains(c.name())) {
            throw new ParseException("HAVING '" + c.name() + "' must name a GROUP BY column or an aggregate", at);
        }
        return i.label();
    }

    /**
     * A column, or an aggregate: {@code COUNT(*)}, {@code COUNT(DISTINCT column)}, or COUNT, SUM, MIN, MAX or
     * AVG of a column. Only COUNT is reserved; {@code sum} and the others are aggregates only when a {@code (}
     * follows, and DISTINCT is the keyword only before a column, so columns may still have those names.
     */
    private Statement.Item item() {
        Statement.Fn fn;
        if (isKeyword("count")) {
            int at = tok.start();
            advance();
            if (!isSymbol("(")) {
                throw new ParseException("expected a name but found the keyword COUNT, which cannot be used as a name", at);
            }
            advance();
            fn = Statement.Fn.COUNT;
            if (acceptSymbol("*")) {
                expectSymbol(")");
                return Statement.Item.Aggregate.COUNT_ALL;
            }
        } else {
            String n = name();
            fn = switch (n) {
                case "sum" -> Statement.Fn.SUM;
                case "min" -> Statement.Fn.MIN;
                case "max" -> Statement.Fn.MAX;
                case "avg" -> Statement.Fn.AVG;
                default -> null;
            };
            if (fn == null || !acceptSymbol("(")) {
                return new Statement.Item.Column(n);
            }
        }
        if (isSymbol("*")) {
            throw new ParseException(fn + "(*) is not an aggregate: only COUNT takes *", tok.start());
        }
        // COUNT(distinct) counts a column called distinct; COUNT(DISTINCT distinct) its distinct values.
        boolean distinct = false;
        if (isWord("distinct") && !isSymbol(peek(), ")")) {
            if (fn != Statement.Fn.COUNT) {
                throw new ParseException(fn + "(DISTINCT) is not an aggregate: only COUNT takes DISTINCT", tok.start());
            }
            advance();
            distinct = true;
            if (isSymbol("*")) {
                throw new ParseException("COUNT(DISTINCT *) is not an aggregate: COUNT(DISTINCT) takes a column",
                        tok.start());
            }
        }
        String column = name();
        expectSymbol(")");
        return new Statement.Item.Aggregate(fn, column, distinct);
    }

    private Statement update() {
        String[] q = qualifiedName();
        expectKeyword("set");
        List<Statement.Assignment> sets = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        do {
            int at = tok.start();
            String c = name();
            if (!seen.add(c)) {
                throw new ParseException("column '" + c + "' is set twice", at);
            }
            expectSymbol("=");
            sets.add(new Statement.Assignment(c, value()));
        } while (acceptSymbol(","));
        Expr where = acceptKeyword("where") ? expr() : null;
        return new Statement.Update(q[0], q[1], sets, where);
    }

    private Statement delete() {
        expectKeyword("from");
        String[] q = qualifiedName();
        Expr where = acceptKeyword("where") ? expr() : null;
        return new Statement.Delete(q[0], q[1], where);
    }

    // expr := and {OR and}
    private Expr expr() {
        Expr first = and();
        if (!isKeyword("or")) {
            return first;
        }
        List<Expr> terms = new ArrayList<>();
        terms.add(first);
        while (acceptKeyword("or")) {
            terms.add(and());
        }
        return new Expr.Or(terms);
    }

    // and := not {AND not}
    private Expr and() {
        Expr first = not();
        if (!isKeyword("and")) {
            return first;
        }
        List<Expr> terms = new ArrayList<>();
        terms.add(first);
        while (acceptKeyword("and")) {
            terms.add(not());
        }
        return new Expr.And(terms);
    }

    // not := NOT not | primary
    private Expr not() {
        if (isKeyword("not")) {
            enter();
            advance();
            Expr inner = not();
            depth--;
            return new Expr.Not(inner);
        }
        return primary();
    }

    // primary := '(' expr ')' | name (cmp value | IS [NOT] NULL | [NOT] IN '(' value {',' value} ')' | [NOT] LIKE string)
    private Expr primary() {
        if (isSymbol("(")) {
            enter();
            advance();
            Expr e = expr();
            expectSymbol(")");
            depth--;
            return e;
        }
        String column = havingGroups != null ? havingOperand() : name();
        if (acceptKeyword("is")) {
            boolean negated = acceptKeyword("not");
            expectKeyword("null");
            return new Expr.IsNull(column, negated);
        }
        boolean negated = acceptKeyword("not");
        if (acceptKeyword("in")) {
            return new Expr.In(column, inList(), negated);
        }
        // LIKE is not a reserved word: a column may be called like, and "like LIKE 'x%'" reads as it should.
        if (tok.kind() == Token.Kind.NAME && tok.text().equals("like")) {
            advance();
            return new Expr.Like(column, likePattern(), negated);
        }
        if (negated) {
            throw expected("IN or LIKE after NOT");
        }
        Expr.Cmp op = comparison();
        return new Expr.Compare(column, op, value());
    }

    private List<Object> inList() {
        expectSymbol("(");
        List<Object> values = new ArrayList<>();
        do {
            if (values.size() == Expr.In.MAX_VALUES) {
                throw new ParseException("IN takes at most " + Expr.In.MAX_VALUES + " values", tok.start());
            }
            values.add(value());
        } while (acceptSymbol(","));
        expectSymbol(")");
        return values;
    }

    private LikePattern likePattern() {
        if (tok.kind() != Token.Kind.STRING) {
            throw expected("a string (the LIKE pattern)");
        }
        String p = (String) tok.value();
        if (p.length() > MAX_LIKE_PATTERN) {
            throw new ParseException("a LIKE pattern is at most " + MAX_LIKE_PATTERN + " characters", tok.start());
        }
        int bad = LikePattern.badEscape(p);
        if (bad >= 0) {
            // The position in the query text: past the opening quote, and past the second ' of each '' before it.
            int quotes = 0;
            for (int i = 0; i < bad; i++) {
                if (p.charAt(i) == '\'') {
                    quotes++;
                }
            }
            String found = bad + 1 < p.length() ? "'\\" + p.charAt(bad + 1) + "'" : "a \\ at the end";
            throw new ParseException("expected \\%, \\_ or \\\\ in a LIKE pattern but found " + found,
                    tok.start() + 1 + bad + quotes);
        }
        advance();
        return new LikePattern(p);
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw new ParseException("condition nested more than " + MAX_DEPTH + " levels deep", tok.start());
        }
    }

    private Expr.Cmp comparison() {
        if (tok.kind() == Token.Kind.SYMBOL) {
            Expr.Cmp op = switch (tok.text()) {
                case "=" -> Expr.Cmp.EQ;
                case "!=", "<>" -> Expr.Cmp.NE;
                case "<" -> Expr.Cmp.LT;
                case "<=" -> Expr.Cmp.LE;
                case ">" -> Expr.Cmp.GT;
                case ">=" -> Expr.Cmp.GE;
                default -> null;
            };
            if (op != null) {
                advance();
                return op;
            }
        }
        throw expected("a comparison (=, !=, <>, <, <=, >, >=), IS, IN or LIKE");
    }

    private Object value() {
        Token t = tok;
        switch (t.kind()) {
            case STRING:
            case NUMBER:
                advance();
                return t.value();
            case KEYWORD:
                switch (t.text()) {
                    case "true":
                        advance();
                        return Boolean.TRUE;
                    case "false":
                        advance();
                        return Boolean.FALSE;
                    case "null":
                        advance();
                        return null;
                    default:
                        break;
                }
                break;
            default:
                break;
        }
        throw expected("a value (string, number, TRUE, FALSE or NULL)");
    }

    private String[] qualifiedName() {
        String db = name();
        if (!isSymbol(".")) {
            throw expected("'.' and a table name (tables are written db.table)");
        }
        advance();
        return new String[] {db, name()};
    }

    private String name() {
        if (tok.kind() != Token.Kind.NAME) {
            if (tok.kind() == Token.Kind.KEYWORD) {
                throw new ParseException("expected a name but found the keyword " + describe(tok)
                        + ", which cannot be used as a name", tok.start());
            }
            throw expected("a name");
        }
        String n = tok.text();
        advance();
        return n;
    }

    private void advance() {
        if (peeked != null) {
            tok = peeked;
            peeked = null;
        } else {
            tok = lexer.next();
        }
    }

    /** The token after the current one, read ahead once. */
    private Token peek() {
        if (peeked == null) {
            peeked = lexer.next();
        }
        return peeked;
    }

    private boolean isKeyword(String k) {
        return isKeyword(tok, k);
    }

    private static boolean isKeyword(Token t, String k) {
        return t.kind() == Token.Kind.KEYWORD && t.text().equals(k);
    }

    private boolean isSymbol(String s) {
        return isSymbol(tok, s);
    }

    private static boolean isSymbol(Token t, String s) {
        return t.kind() == Token.Kind.SYMBOL && t.text().equals(s);
    }

    /** True if the current token is the unreserved word {@code w} (a name to the lexer). */
    private boolean isWord(String w) {
        return tok.kind() == Token.Kind.NAME && tok.text().equals(w);
    }

    private boolean acceptKeyword(String k) {
        if (isKeyword(k)) {
            advance();
            return true;
        }
        return false;
    }

    private boolean acceptSymbol(String s) {
        if (isSymbol(s)) {
            advance();
            return true;
        }
        return false;
    }

    private void expectKeyword(String k) {
        if (!acceptKeyword(k)) {
            throw expected(k.toUpperCase(java.util.Locale.ROOT));
        }
    }

    private void expectSymbol(String s) {
        if (!acceptSymbol(s)) {
            throw expected("'" + s + "'");
        }
    }

    private ParseException expected(String what) {
        return new ParseException("expected " + what + " but found " + describe(tok), tok.start());
    }

    private String describe(Token t) {
        return switch (t.kind()) {
            case END -> "end of input";
            case KEYWORD -> t.text().toUpperCase(java.util.Locale.ROOT);
            case NAME -> "name '" + t.text() + "'";
            case STRING -> "string '" + Lexer.shorten((String) t.value()) + "'";
            case NUMBER -> "number " + Lexer.shorten(t.text());
            case SYMBOL -> "'" + t.text() + "'";
        };
    }
}
