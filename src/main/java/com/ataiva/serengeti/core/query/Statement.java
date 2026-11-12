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

    /** What a SELECT returns. */
    sealed interface Projection {
        record All() implements Projection {}

        record Count() implements Projection {}

        record Columns(List<String> names) implements Projection {
            public Columns {
                names = List.copyOf(names);
                if (names.isEmpty()) {
                    throw new IllegalArgumentException("no columns");
                }
            }
        }
    }

    record OrderBy(String column, boolean descending) {
        public OrderBy {
            Objects.requireNonNull(column, "column");
        }
    }

    /**
     * {@code where}, {@code orderBy} and {@code limit} may be null. A COUNT(*) select has no order or
     * limit.
     */
    record Select(String db, String table, Projection projection, Expr where, OrderBy orderBy, Long limit)
            implements Statement {
        public Select {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(projection, "projection");
            if (limit != null && limit < 0) {
                throw new IllegalArgumentException("negative LIMIT");
            }
            if (projection instanceof Projection.Count && (orderBy != null || limit != null)) {
                throw new IllegalArgumentException("COUNT(*) takes no ORDER BY or LIMIT");
            }
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
