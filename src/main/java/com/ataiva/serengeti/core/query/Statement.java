package com.ataiva.serengeti.core.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One parsed query. See docs/QUERY.md for the grammar. */
public sealed interface Statement {
    record CreateDatabase(String db, boolean ifNotExists) implements Statement {
        public CreateDatabase {
            Objects.requireNonNull(db, "db");
        }
    }

    record DropDatabase(String db, boolean ifExists) implements Statement {
        public DropDatabase {
            Objects.requireNonNull(db, "db");
        }
    }

    record CreateTable(String db, String table, boolean ifNotExists) implements Statement {
        public CreateTable {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
        }
    }

    record DropTable(String db, String table, boolean ifExists) implements Statement {
        public DropTable {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
        }
    }

    record ShowDatabases() implements Statement {}

    record ShowTables(String db) implements Statement {
        public ShowTables {
            Objects.requireNonNull(db, "db");
        }
    }

    /** {@code CREATE INDEX [IF NOT EXISTS] ON db.table (column)}. */
    record CreateIndex(String db, String table, String column, boolean ifNotExists) implements Statement {
        public CreateIndex {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(column, "column");
        }
    }

    /** {@code DROP INDEX [IF EXISTS] ON db.table (column)}. */
    record DropIndex(String db, String table, String column, boolean ifExists) implements Statement {
        public DropIndex {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(column, "column");
        }
    }

    /** {@code SHOW INDEXES IN db.table}: the indexed columns. */
    record ShowIndexes(String db, String table) implements Statement {
        public ShowIndexes {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
        }
    }

    /** The rows of a table that hold conflicting versions, one result row per version. */
    record ShowConflicts(String db, String table) implements Statement {
        public ShowConflicts {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
        }
    }

    /** Each row in {@code rows} has one value per entry in {@code columns}. */
    record Insert(String db, String table, List<String> columns, List<List<Object>> rows) implements Statement {
        public Insert {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
            columns = List.copyOf(columns);
            if (columns.isEmpty() || rows.isEmpty()) {
                throw new IllegalArgumentException("INSERT needs columns and rows");
            }
            if (columns.stream().distinct().count() != columns.size()) {
                throw new IllegalArgumentException("INSERT columns must be distinct");
            }
            List<List<Object>> copy = new ArrayList<>(rows.size());
            for (List<Object> row : rows) {
                if (row.size() != columns.size()) {
                    throw new IllegalArgumentException("INSERT row has the wrong number of values");
                }
                row.forEach(Values::requireLiteral);
                copy.add(Collections.unmodifiableList(new ArrayList<>(row)));
            }
            rows = Collections.unmodifiableList(copy);
        }
    }

    /** One entry of a select list, and what ORDER BY sorts on: a column or an aggregate. */
    sealed interface Item {
        /** The column of the result. */
        String label();

        record Column(String name) implements Item {
            public Column {
                Objects.requireNonNull(name, "name");
            }

            @Override
            public String label() {
                return name;
            }
        }

        /** {@code COUNT(*)} when {@code column} is null (only COUNT may have that), else {@code FN(column)}. */
        record Aggregate(Fn fn, String column) implements Item {
            public static final Aggregate COUNT_ALL = new Aggregate(Fn.COUNT, null);

            public Aggregate {
                Objects.requireNonNull(fn, "fn");
                if (column == null && fn != Fn.COUNT) {
                    throw new IllegalArgumentException(fn + "(*)");
                }
            }

            /** {@code count} for COUNT(*), as in 2.1; else lowercase, as {@code sum(qty)}. */
            @Override
            public String label() {
                return column == null ? "count" : fn.name().toLowerCase(java.util.Locale.ROOT) + "(" + column + ")";
            }
        }
    }

    enum Fn { COUNT, SUM, MIN, MAX, AVG }

    /** What a SELECT returns. */
    sealed interface Projection {
        record All() implements Projection {}

        /** {@code COUNT(*)} alone. */
        record Count() implements Projection {}

        /** Columns only. */
        record Columns(List<String> names) implements Projection {
            public Columns {
                names = List.copyOf(names);
                if (names.isEmpty()) {
                    throw new IllegalArgumentException("no columns");
                }
            }
        }

        /** A list with at least one aggregate, other than {@code COUNT(*)} alone (which is {@link Count}). */
        record Items(List<Item> items) implements Projection {
            public Items {
                items = List.copyOf(items);
                if (items.stream().noneMatch(i -> i instanceof Item.Aggregate)) {
                    throw new IllegalArgumentException("no aggregate: use Columns");
                }
                if (items.equals(List.of(Item.Aggregate.COUNT_ALL))) {
                    throw new IllegalArgumentException("COUNT(*) alone: use Count");
                }
            }
        }

        /** The select list as items; null for {@code *}. */
        static List<Item> itemsOf(Projection p) {
            if (p instanceof Count) {
                return List.of(Item.Aggregate.COUNT_ALL);
            }
            if (p instanceof Columns c) {
                return c.names().stream().<Item>map(Item.Column::new).toList();
            }
            if (p instanceof Items i) {
                return i.items();
            }
            return null;
        }

        /** True when the list has an aggregate. */
        static boolean aggregates(Projection p) {
            return p instanceof Count || p instanceof Items;
        }
    }

    record OrderBy(Item key, boolean descending) {
        public OrderBy {
            Objects.requireNonNull(key, "key");
        }

        public OrderBy(String column, boolean descending) {
            this(new Item.Column(column), descending);
        }

        /** The column sorted on, or null when the key is an aggregate. */
        public String column() {
            return key instanceof Item.Column c ? c.name() : null;
        }
    }

    /**
     * {@code where}, {@code orderBy} and {@code limit} may be null; {@code groupBy} is empty without GROUP BY.
     * The rules are those of docs/QUERY.md: with an aggregate and no GROUP BY, the list has only aggregates
     * and there is no ORDER BY or LIMIT; with GROUP BY, each column of the list is a GROUP BY column, and
     * ORDER BY names one, or an aggregate of the list.
     */
    record Select(String db, String table, Projection projection, Expr where, List<String> groupBy, OrderBy orderBy,
            Long limit) implements Statement {
        public Select {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(projection, "projection");
            groupBy = List.copyOf(groupBy);
            if (limit != null && limit < 0) {
                throw new IllegalArgumentException("negative LIMIT");
            }
            if (groupBy.stream().distinct().count() != groupBy.size()) {
                throw new IllegalArgumentException("GROUP BY columns must be distinct");
            }
            List<Item> items = Projection.itemsOf(projection);
            if (groupBy.isEmpty()) {
                if (Projection.aggregates(projection)) {
                    if (items.stream().anyMatch(i -> i instanceof Item.Column)) {
                        throw new IllegalArgumentException("a column with an aggregate needs GROUP BY");
                    }
                    if (orderBy != null || limit != null) {
                        throw new IllegalArgumentException("aggregates without GROUP BY take no ORDER BY or LIMIT");
                    }
                } else if (orderBy != null && orderBy.column() == null) {
                    throw new IllegalArgumentException("ORDER BY an aggregate needs GROUP BY");
                }
            } else {
                if (items == null) {
                    throw new IllegalArgumentException("SELECT * takes no GROUP BY");
                }
                for (Item i : items) {
                    if (i instanceof Item.Column c && !groupBy.contains(c.name())) {
                        throw new IllegalArgumentException("column " + c.name() + " is not in GROUP BY");
                    }
                }
                if (orderBy != null && (orderBy.column() != null ? !groupBy.contains(orderBy.column())
                        : !items.contains(orderBy.key()))) {
                    throw new IllegalArgumentException("ORDER BY takes a GROUP BY column or an aggregate of the list");
                }
            }
        }

        public Select(String db, String table, Projection projection, Expr where, OrderBy orderBy, Long limit) {
            this(db, table, projection, where, List.of(), orderBy, limit);
        }
    }

    record Assignment(String column, Object value) {
        public Assignment {
            Objects.requireNonNull(column, "column");
            Values.requireLiteral(value);
        }
    }

    /** {@code where} may be null. */
    record Update(String db, String table, List<Assignment> assignments, Expr where) implements Statement {
        public Update {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
            assignments = List.copyOf(assignments);
            if (assignments.isEmpty()) {
                throw new IllegalArgumentException("UPDATE needs at least one assignment");
            }
            if (assignments.stream().map(Assignment::column).distinct().count() != assignments.size()) {
                throw new IllegalArgumentException("UPDATE columns must be distinct");
            }
        }
    }

    /** {@code where} may be null. */
    record Delete(String db, String table, Expr where) implements Statement {
        public Delete {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
        }
    }
}
