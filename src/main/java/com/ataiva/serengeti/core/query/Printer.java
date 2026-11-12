package com.ataiva.serengeti.core.query;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Writes a statement back as query text. {@code Parser.parse(Printer.print(s))} equals {@code s}.
 * AND and OR groups are always parenthesised, so the output does not rely on precedence.
 */
public final class Printer {
    private Printer() {}

    public static String print(Statement s) {
        if (s instanceof Statement.CreateDatabase c) {
            return "CREATE DATABASE " + (c.ifNotExists() ? "IF NOT EXISTS " : "") + c.db();
        }
        if (s instanceof Statement.DropDatabase d) {
            return "DROP DATABASE " + (d.ifExists() ? "IF EXISTS " : "") + d.db();
        }
        if (s instanceof Statement.CreateTable c) {
            return "CREATE TABLE " + (c.ifNotExists() ? "IF NOT EXISTS " : "") + c.db() + "." + c.table();
        }
        if (s instanceof Statement.DropTable d) {
            return "DROP TABLE " + (d.ifExists() ? "IF EXISTS " : "") + d.db() + "." + d.table();
        }
        if (s instanceof Statement.ShowDatabases) {
            return "SHOW DATABASES";
        }
        if (s instanceof Statement.ShowTables t) {
            return "SHOW TABLES IN " + t.db();
        }
        if (s instanceof Statement.Insert i) {
            StringBuilder b = new StringBuilder("INSERT INTO ").append(i.db()).append('.').append(i.table())
                    .append(" (").append(String.join(", ", i.columns())).append(") VALUES ");
            for (int r = 0; r < i.rows().size(); r++) {
                if (r > 0) {
                    b.append(", ");
                }
                b.append('(').append(values(i.rows().get(r))).append(')');
            }
            return b.toString();
        }
        if (s instanceof Statement.Select q) {
            StringBuilder b = new StringBuilder("SELECT ");
            if (q.projection() instanceof Statement.Projection.All) {
                b.append('*');
            } else if (q.projection() instanceof Statement.Projection.Count) {
                b.append("COUNT(*)");
            } else {
                b.append(String.join(", ", ((Statement.Projection.Columns) q.projection()).names()));
            }
            b.append(" FROM ").append(q.db()).append('.').append(q.table());
            where(b, q.where());
            if (q.orderBy() != null) {
                b.append(" ORDER BY ").append(q.orderBy().column());
                if (q.orderBy().descending()) {
                    b.append(" DESC");
                }
            }
            if (q.limit() != null) {
                b.append(" LIMIT ").append(q.limit());
            }
            return b.toString();
        }
        if (s instanceof Statement.Update u) {
            StringBuilder b = new StringBuilder("UPDATE ").append(u.db()).append('.').append(u.table())
                    .append(" SET ");
            b.append(u.assignments().stream().map(a -> a.column() + " = " + Values.format(a.value()))
                    .collect(Collectors.joining(", ")));
            where(b, u.where());
            return b.toString();
        }
        if (s instanceof Statement.Delete d) {
            StringBuilder b = new StringBuilder("DELETE FROM ").append(d.db()).append('.').append(d.table());
            where(b, d.where());
            return b.toString();
        }
        throw new IllegalArgumentException("unknown statement " + s);
    }

    public static String print(Expr e) {
        StringBuilder b = new StringBuilder();
        expr(b, e);
        return b.toString();
    }

    private static void where(StringBuilder b, Expr e) {
        if (e != null) {
            b.append(" WHERE ");
            expr(b, e);
        }
    }

    private static void expr(StringBuilder b, Expr e) {
        if (e instanceof Expr.Compare c) {
            b.append(c.column()).append(' ').append(c.op().symbol()).append(' ').append(Values.format(c.value()));
        } else if (e instanceof Expr.IsNull n) {
            b.append(n.column()).append(n.negated() ? " IS NOT NULL" : " IS NULL");
        } else if (e instanceof Expr.Not n) {
            b.append("NOT ");
            expr(b, n.inner());
        } else if (e instanceof Expr.And a) {
            group(b, a.terms(), " AND ");
        } else if (e instanceof Expr.Or o) {
            group(b, o.terms(), " OR ");
        } else {
            throw new IllegalArgumentException("unknown expression " + e);
        }
    }

    private static void group(StringBuilder b, List<Expr> terms, String joiner) {
        b.append('(');
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                b.append(joiner);
            }
            expr(b, terms.get(i));
        }
        b.append(')');
    }

    private static String values(List<Object> row) {
        return row.stream().map(Values::format).collect(Collectors.joining(", "));
    }
}
