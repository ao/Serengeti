package com.ataiva.serengeti.core.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One parsed query. See docs/QUERY.md for the grammar. */
public sealed interface Statement {
    /** Calls the method of {@code v} for this statement's type. */
    <R> R accept(Visitor<R> v);

    /**
     * One method per statement type. Code that must decide every type (what a statement reaches, see
     * {@link Reach}) implements this, so a statement type added later does not compile until it is decided
     * there: a new record must implement {@link #accept}, which needs a method here.
     */
    interface Visitor<R> {
        R createDatabase(CreateDatabase s);

        R dropDatabase(DropDatabase s);

        R createTable(CreateTable s);

        R dropTable(DropTable s);

        R showDatabases(ShowDatabases s);

        R showTables(ShowTables s);

        R createIndex(CreateIndex s);

        R dropIndex(DropIndex s);

        R showIndexes(ShowIndexes s);

        R showConflicts(ShowConflicts s);

        R insert(Insert s);

        R select(Select s);

        R update(Update s);

        R delete(Delete s);
    }

    record CreateDatabase(String db, boolean ifNotExists) implements Statement {
        public CreateDatabase {
            Objects.requireNonNull(db, "db");
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.createDatabase(this);
        }
    }

    record DropDatabase(String db, boolean ifExists) implements Statement {
        public DropDatabase {
            Objects.requireNonNull(db, "db");
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.dropDatabase(this);
        }
    }

    record CreateTable(String db, String table, boolean ifNotExists) implements Statement {
        public CreateTable {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.createTable(this);
        }
    }

    record DropTable(String db, String table, boolean ifExists) implements Statement {
        public DropTable {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.dropTable(this);
        }
    }

    record ShowDatabases() implements Statement {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.showDatabases(this);
        }
    }

    record ShowTables(String db) implements Statement {
        public ShowTables {
            Objects.requireNonNull(db, "db");
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.showTables(this);
        }
    }

    /** {@code CREATE INDEX [IF NOT EXISTS] ON db.table (column, ...)}: one column, or 2 to 4 in order. */
    record CreateIndex(String db, String table, List<String> columns, boolean ifNotExists) implements Statement {
        public CreateIndex {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
            columns = List.copyOf(columns);
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("an index has at least one column");
            }
        }

        public CreateIndex(String db, String table, String column, boolean ifNotExists) {
            this(db, table, List.of(column), ifNotExists);
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.createIndex(this);
        }
    }

    /** {@code DROP INDEX [IF EXISTS] ON db.table (column, ...)}: the index on exactly these columns, in this order. */
    record DropIndex(String db, String table, List<String> columns, boolean ifExists) implements Statement {
        public DropIndex {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
            columns = List.copyOf(columns);
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("an index has at least one column");
            }
        }

        public DropIndex(String db, String table, String column, boolean ifExists) {
            this(db, table, List.of(column), ifExists);
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.dropIndex(this);
        }
    }

    /** {@code SHOW INDEXES IN db.table}: the indexes, each as its columns ({@code a} or {@code a, b}). */
    record ShowIndexes(String db, String table) implements Statement {
        public ShowIndexes {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.showIndexes(this);
        }
    }

    /** The rows of a table that hold conflicting versions, one result row per version. */
    record ShowConflicts(String db, String table) implements Statement {
        public ShowConflicts {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.showConflicts(this);
        }
    }

    /**
     * Each row in {@code rows} has one value per entry in {@code columns}. {@code onConflict} is null without
     * ON CONFLICT; with it, {@code columns} lists {@code _id}.
     */
    record Insert(String db, String table, List<String> columns, List<List<Object>> rows, OnConflict onConflict)
            implements Statement {
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
            if (onConflict != null && !columns.contains(Executor.ID)) {
                throw new IllegalArgumentException("ON CONFLICT needs _id in the column list");
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

        public Insert(String db, String table, List<String> columns, List<List<Object>> rows) {
            this(db, table, columns, rows, null);
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.insert(this);
        }
    }

    /**
     * What an INSERT does with a tuple whose {@code _id} is a live row: {@code ON CONFLICT DO NOTHING} when
     * {@code assignments} is empty, else {@code ON CONFLICT DO UPDATE SET} them, as UPDATE would.
     */
    record OnConflict(List<Assignment> assignments) {
        public static final OnConflict DO_NOTHING = new OnConflict(List.of());

        public OnConflict {
            assignments = List.copyOf(assignments);
            if (assignments.stream().map(Assignment::column).distinct().count() != assignments.size()) {
                throw new IllegalArgumentException("ON CONFLICT DO UPDATE columns must be distinct");
            }
        }

        public boolean doNothing() {
            return assignments.isEmpty();
        }
    }

    /** One entry of a select list, and what ORDER BY sorts on: a column or an aggregate. */
    sealed interface Item {
        /**
         * The column of the result. A label is never a name: a column's is its name, and an aggregate's is
         * {@code count} (reserved) or has a {@code (}. So each label stands for one item ({@link #ofLabel}).
         */
        String label();

        /** The item whose {@link #label} is {@code label}. */
        static Item ofLabel(String label) {
            if (label.equals("count")) {
                return Aggregate.COUNT_ALL;
            }
            int open = label.indexOf('(');
            if (open < 0) {
                return new Column(label);
            }
            String inner = label.substring(open + 1, label.length() - 1);
            if (inner.startsWith("distinct ")) {
                return new Aggregate(Fn.COUNT, inner.substring("distinct ".length()), true);
            }
            return new Aggregate(Fn.valueOf(label.substring(0, open).toUpperCase(java.util.Locale.ROOT)), inner);
        }

        record Column(String name) implements Item {
            public Column {
                Objects.requireNonNull(name, "name");
            }

            @Override
            public String label() {
                return name;
            }
        }

        /**
         * {@code COUNT(*)} when {@code column} is null (only COUNT may have that), else {@code FN(column)}, or
         * {@code COUNT(DISTINCT column)} when {@code distinct} (only COUNT of a column may have that).
         */
        record Aggregate(Fn fn, String column, boolean distinct) implements Item {
            public static final Aggregate COUNT_ALL = new Aggregate(Fn.COUNT, null);

            public Aggregate {
                Objects.requireNonNull(fn, "fn");
                if (column == null && fn != Fn.COUNT) {
                    throw new IllegalArgumentException(fn + "(*)");
                }
                if (distinct && (fn != Fn.COUNT || column == null)) {
                    throw new IllegalArgumentException("DISTINCT is for COUNT of a column only");
                }
            }

            public Aggregate(Fn fn, String column) {
                this(fn, column, false);
            }

            /** {@code count} for COUNT(*), as in 2.1; else lowercase, as {@code sum(qty)} or {@code count(distinct qty)}. */
            @Override
            public String label() {
                if (column == null) {
                    return "count";
                }
                return fn.name().toLowerCase(java.util.Locale.ROOT) + (distinct ? "(distinct " : "(") + column + ")";
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
     * {@code where}, {@code having}, {@code limit} and {@code offset} may be null; {@code groupBy} is empty
     * without GROUP BY and {@code orderBy} without ORDER BY. The rules are those of docs/QUERY.md: with an
     * aggregate and no GROUP BY, the list has only aggregates and there is no ORDER BY; with GROUP BY, each
     * column of the list is a GROUP BY column, and each ORDER BY key names one, or an aggregate of the list.
     * {@code distinct} is SELECT DISTINCT: a list of distinct columns that act as the GROUP BY ({@link #groups}),
     * with no GROUP BY or HAVING of its own. In {@code having}, each column of a term is the {@link Item#label}
     * of a GROUP BY column or an aggregate, which need not be in the list. {@code offset} is OFFSET: how many
     * of the rows the statement gives without OFFSET and LIMIT to leave out before LIMIT counts.
     */
    record Select(String db, String table, Projection projection, boolean distinct, Expr where, List<String> groupBy,
            Expr having, List<OrderBy> orderBy, Long limit, Long offset) implements Statement {
        public Select {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(projection, "projection");
            groupBy = List.copyOf(groupBy);
            orderBy = List.copyOf(orderBy);
            if (limit != null && limit < 0) {
                throw new IllegalArgumentException("negative LIMIT");
            }
            if (offset != null && offset < 0) {
                throw new IllegalArgumentException("negative OFFSET");
            }
            if (groupBy.stream().distinct().count() != groupBy.size()) {
                throw new IllegalArgumentException("GROUP BY columns must be distinct");
            }
            if (orderBy.stream().map(OrderBy::key).distinct().count() != orderBy.size()) {
                throw new IllegalArgumentException("ORDER BY keys must be distinct");
            }
            List<String> groups = groupBy;
            if (distinct) {
                if (!(projection instanceof Projection.Columns c)) {
                    throw new IllegalArgumentException("SELECT DISTINCT takes columns only");
                }
                if (!groupBy.isEmpty() || having != null) {
                    throw new IllegalArgumentException("SELECT DISTINCT takes no GROUP BY or HAVING");
                }
                groups = c.names();
                if (groups.stream().distinct().count() != groups.size()) {
                    throw new IllegalArgumentException("SELECT DISTINCT columns must be distinct");
                }
            }
            List<Item> items = Projection.itemsOf(projection);
            if (groups.isEmpty()) {
                if (Projection.aggregates(projection)) {
                    if (items.stream().anyMatch(i -> i instanceof Item.Column)) {
                        throw new IllegalArgumentException("a column with an aggregate needs GROUP BY");
                    }
                    if (!orderBy.isEmpty()) {
                        throw new IllegalArgumentException("aggregates without GROUP BY take no ORDER BY");
                    }
                } else {
                    if (having != null) {
                        throw new IllegalArgumentException("HAVING needs GROUP BY or a list of aggregates only");
                    }
                    if (orderBy.stream().anyMatch(o -> o.column() == null)) {
                        throw new IllegalArgumentException("ORDER BY an aggregate needs GROUP BY");
                    }
                }
            } else {
                if (items == null) {
                    throw new IllegalArgumentException("SELECT * takes no GROUP BY");
                }
                for (Item i : items) {
                    if (i instanceof Item.Column c && !groups.contains(c.name())) {
                        throw new IllegalArgumentException("column " + c.name() + " is not in GROUP BY");
                    }
                }
                for (OrderBy o : orderBy) {
                    if (o.column() != null ? !groups.contains(o.column()) : !items.contains(o.key())) {
                        throw new IllegalArgumentException("ORDER BY takes a GROUP BY column or an aggregate of the list");
                    }
                }
            }
            for (Item i : havingItems(having)) {
                if (i instanceof Item.Column c && !groups.contains(c.name())) {
                    throw new IllegalArgumentException("HAVING column " + c.name() + " is not in GROUP BY");
                }
            }
        }

        /** As above, without OFFSET. */
        public Select(String db, String table, Projection projection, boolean distinct, Expr where, List<String> groupBy,
                Expr having, List<OrderBy> orderBy, Long limit) {
            this(db, table, projection, distinct, where, groupBy, having, orderBy, limit, null);
        }

        public Select(String db, String table, Projection projection, Expr where, List<String> groupBy, OrderBy orderBy,
                Long limit) {
            this(db, table, projection, false, where, groupBy, null, orderBy == null ? List.of() : List.of(orderBy), limit);
        }

        public Select(String db, String table, Projection projection, Expr where, OrderBy orderBy, Long limit) {
            this(db, table, projection, where, List.of(), orderBy, limit);
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.select(this);
        }

        /** The columns that make the groups: the list's with DISTINCT, else GROUP BY's (empty without either). */
        public List<String> groups() {
            return distinct ? ((Projection.Columns) projection).names() : groupBy;
        }

        /** The GROUP BY columns and aggregates that {@code having} tests, each once, in the order written. */
        public static List<Item> havingItems(Expr having) {
            java.util.LinkedHashSet<Item> out = new java.util.LinkedHashSet<>();
            if (having != null) {
                Expr.forEachColumn(having, label -> out.add(Item.ofLabel(label)));
            }
            return List.copyOf(out);
        }
    }

    /** {@code column = value}: {@code value} is a literal, or an {@link Arithmetic} computed from the row. */
    record Assignment(String column, Object value) {
        public Assignment {
            Objects.requireNonNull(column, "column");
            if (!(value instanceof Arithmetic)) {
                Values.requireLiteral(value);
            }
        }
    }

    /**
     * {@code column + number} or, with {@code minus}, {@code column - number}: the right side of an assignment
     * that reads a column of the row. {@code column} is not {@code _id}; {@code number} is a {@link Long} or a
     * finite {@link Double}.
     */
    record Arithmetic(String column, boolean minus, Object number) {
        public Arithmetic {
            Objects.requireNonNull(column, "column");
            if (column.equals(Executor.ID)) {
                throw new IllegalArgumentException("_id is a string: it cannot be added to");
            }
            if (!(number instanceof Long) && !(number instanceof Double d && Double.isFinite(d))) {
                throw new IllegalArgumentException("not a number: " + number);
            }
        }
    }

    /** {@code where} may be null. Every right side reads the row as it was before the statement. */
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

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.update(this);
        }
    }

    /** {@code where} may be null. */
    record Delete(String db, String table, Expr where) implements Statement {
        public Delete {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.delete(this);
        }
    }
}
