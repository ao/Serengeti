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

    private final Lexer lexer;
    private Token tok;
    private int depth;

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
        throw expected("DATABASE or TABLE");
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
        throw expected("DATABASE or TABLE");
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
        throw expected("DATABASES or TABLES");
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
        Statement.Projection projection;
        if (acceptSymbol("*")) {
            projection = new Statement.Projection.All();
        } else if (acceptKeyword("count")) {
            expectSymbol("(");
            expectSymbol("*");
            expectSymbol(")");
            projection = new Statement.Projection.Count();
        } else {
            List<String> names = new ArrayList<>();
            do {
                names.add(name());
            } while (acceptSymbol(","));
            projection = new Statement.Projection.Columns(names);
        }
        expectKeyword("from");
        String[] q = qualifiedName();
        Expr where = acceptKeyword("where") ? expr() : null;
        Statement.OrderBy orderBy = null;
        Long limit = null;
        boolean count = projection instanceof Statement.Projection.Count;
        if (isKeyword("order")) {
            if (count) {
                throw new ParseException("COUNT(*) cannot have ORDER BY", tok.start());
            }
            advance();
            expectKeyword("by");
            String column = name();
            boolean desc = false;
            if (acceptKeyword("desc")) {
                desc = true;
            } else {
                acceptKeyword("asc");
            }
            orderBy = new Statement.OrderBy(column, desc);
        }
        if (isKeyword("limit")) {
            if (count) {
                throw new ParseException("COUNT(*) cannot have LIMIT", tok.start());
            }
            advance();
            if (tok.kind() != Token.Kind.NUMBER || !(tok.value() instanceof Long n) || n < 0) {
                throw expected("a non-negative whole number");
            }
            limit = n;
            advance();
        }
        return new Statement.Select(q[0], q[1], projection, where, orderBy, limit);
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

    // primary := '(' expr ')' | name (cmp value | IS [NOT] NULL)
    private Expr primary() {
        if (isSymbol("(")) {
            enter();
            advance();
            Expr e = expr();
            expectSymbol(")");
            depth--;
            return e;
        }
        String column = name();
        if (acceptKeyword("is")) {
            boolean negated = acceptKeyword("not");
            expectKeyword("null");
            return new Expr.IsNull(column, negated);
        }
        Expr.Cmp op = comparison();
        return new Expr.Compare(column, op, value());
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
        throw expected("a comparison (=, !=, <>, <, <=, >, >=) or IS");
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
        tok = lexer.next();
    }

    private boolean isKeyword(String k) {
        return tok.kind() == Token.Kind.KEYWORD && tok.text().equals(k);
    }

    private boolean isSymbol(String s) {
        return tok.kind() == Token.Kind.SYMBOL && tok.text().equals(s);
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
