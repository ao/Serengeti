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
        if (s instanceof Statement.ShowConflicts c) {
            return "SHOW CONFLICTS IN " + c.db() + "." + c.table();
        }
        if (s instanceof Statement.CreateIndex c) {
            return "CREATE INDEX " + (c.ifNotExists() ? "IF NOT EXISTS " : "") + "ON " + c.db() + "." + c.table()
                    + " (" + c.column() + ")";
        }
        if (s instanceof Statement.DropIndex d) {
            return "DROP INDEX " + (d.ifExists() ? "IF EXISTS " : "") + "ON " + d.db() + "." + d.table()
                    + " (" + d.column() + ")";
        }
        if (s instanceof Statement.ShowIndexes i) {
            return "SHOW INDEXES IN " + i.db() + "." + i.table();
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
            StringBuilder b = new StringBuilder(q.distinct() ? "SELECT DISTINCT " : "SELECT ");
            if (q.projection() instanceof Statement.Projection.All) {
                b.append('*');
            } else {
                b.append(Statement.Projection.itemsOf(q.projection()).stream().map(Printer::item)
                        .collect(Collectors.joining(", ")));
            }
            b.append(" FROM ").append(q.db()).append('.').append(q.table());
            where(b, q.where());
            if (!q.groupBy().isEmpty()) {
                b.append(" GROUP BY ").append(String.join(", ", q.groupBy()));
            }
            if (q.having() != null) {
                b.append(" HAVING ");
                expr(b, q.having(), true);
            }
            if (!q.orderBy().isEmpty()) {
                b.append(" ORDER BY ").append(q.orderBy().stream()
                        .map(o -> item(o.key()) + (o.descending() ? " DESC" : "")).collect(Collectors.joining(", ")));
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

    /** A WHERE condition. */
    public static String print(Expr e) {
        StringBuilder b = new StringBuilder();
        expr(b, e, false);
        return b.toString();
    }

    private static void where(StringBuilder b, Expr e) {
        if (e != null) {
            b.append(" WHERE ");
            expr(b, e, false);
        }
    }

    /** With {@code having}, each column is the label of a GROUP BY column or an aggregate, written as such. */
    private static void expr(StringBuilder b, Expr e, boolean having) {
        if (e instanceof Expr.Compare c) {
            left(b, c.column(), having).append(' ').append(c.op().symbol()).append(' ').append(Values.format(c.value()));
        } else if (e instanceof Expr.IsNull n) {
            left(b, n.column(), having).append(n.negated() ? " IS NOT NULL" : " IS NULL");
        } else if (e instanceof Expr.In in) {
            left(b, in.column(), having).append(in.negated() ? " NOT IN (" : " IN (").append(values(in.values())).append(')');
        } else if (e instanceof Expr.Like l) {
            left(b, l.column(), having).append(l.negated() ? " NOT LIKE " : " LIKE ")
                    .append(Values.format(l.pattern().text()));
        } else if (e instanceof Expr.Not n) {
            b.append("NOT ");
            expr(b, n.inner(), having);
        } else if (e instanceof Expr.And a) {
            group(b, a.terms(), " AND ", having);
        } else if (e instanceof Expr.Or o) {
            group(b, o.terms(), " OR ", having);
        } else {
            throw new IllegalArgumentException("unknown expression " + e);
        }
    }

    private static StringBuilder left(StringBuilder b, String column, boolean having) {
        return b.append(having ? item(Statement.Item.ofLabel(column)) : column);
    }

    private static void group(StringBuilder b, List<Expr> terms, String joiner, boolean having) {
        b.append('(');
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                b.append(joiner);
            }
            expr(b, terms.get(i), having);
        }
        b.append(')');
    }

    private static String item(Statement.Item i) {
        if (i instanceof Statement.Item.Aggregate a) {
            return a.fn() + "(" + (a.column() == null ? "*" : (a.distinct() ? "DISTINCT " : "") + a.column()) + ")";
        }
        return ((Statement.Item.Column) i).name();
    }

    private static String values(List<Object> row) {
        return row.stream().map(Values::format).collect(Collectors.joining(", "));
    }
}
