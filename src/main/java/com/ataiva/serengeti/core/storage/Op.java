package com.ataiva.serengeti.core.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One change to the data. Every op carries the stamp it was made at; applying ops is idempotent and
 * gives the same state in any order (see {@link Store}). The write-ahead log, snapshots and
 * replication all move ops.
 */
public sealed interface Op {
    Stamp stamp();

    String db();

    record CreateDb(String db, Stamp stamp) implements Op {}

    record DropDb(String db, Stamp stamp) implements Op {}

    record CreateTable(String db, String table, Stamp stamp) implements Op {}

    record DropTable(String db, String table, Stamp stamp) implements Op {}

    /**
     * Creates a secondary index on a table. Indexes are derived from the rows; only this is replicated.
     * {@code column} names the index's column, or its columns joined by commas (see {@link #indexKey}).
     */
    record CreateIndex(String db, String table, String column, Stamp stamp) implements Op {}

    record DropIndex(String db, String table, String column, Stamp stamp) implements Op {}

    /**
     * The {@code column} field of the index ops for an index on {@code columns}: the names joined by commas.
     * A name cannot hold a comma, so a 2.1 node, which reads the field as one column, holds an index on
     * several columns as one on a column no row has: empty, never used, and kept, so it comes back whole
     * after a rollback and a new upgrade.
     */
    static String indexKey(List<String> columns) {
        return String.join(",", columns);
    }

    /** The columns of the index that {@code key} (an index op's {@code column} field) names, in order. */
    static List<String> indexColumns(String key) {
        return List.of(key.split(",", -1));
    }

    /** True for ops a 2.0 node can read: everything but the index ops added in 2.1. */
    static boolean legacy(Op op) {
        return !(op instanceof CreateIndex || op instanceof DropIndex);
    }

    /** The index ops of {@code ops}. */
    static List<Op> indexOnly(List<Op> ops) {
        List<Op> out = new ArrayList<>();
        for (Op op : ops) {
            if (!legacy(op)) {
                out.add(op);
            }
        }
        return out;
    }

    /** The ops of {@code ops} that a 2.0 node can read. */
    static List<Op> legacyOnly(List<Op> ops) {
        List<Op> out = new ArrayList<>(ops.size());
        for (Op op : ops) {
            if (legacy(op)) {
                out.add(op);
            }
        }
        return out;
    }

    /**
     * A row write: {@link Put} or {@link Delete}. {@code seen} is its causal context: for each node, the newest
     * stamp of that node's writes to this row that the writer had seen, plus {@code "~"} for writes from
     * nodes that did not record one. It is null for an op that has none (written by 2.0, or a local write
     * before {@link Store#withContext} fills it in). See {@link Store} for how it decides conflicts.
     */
    sealed interface RowOp extends Op {
        String table();

        String id();

        Map<String, Stamp> seen();

        RowOp withSeen(Map<String, Stamp> seen);
    }

    /** Writes a whole row. */
    record Put(String db, String table, String id, Map<String, Object> row, Stamp stamp, Map<String, Stamp> seen)
            implements RowOp {
        public Put {
            row = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(row));
            seen = seen == null ? null : seen.isEmpty() ? Map.of() // one shared instance: most rows have none
                    : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(seen));
        }

        public Put(String db, String table, String id, Map<String, Object> row, Stamp stamp) {
            this(db, table, id, row, stamp, null);
        }

        @Override
        public Put withSeen(Map<String, Stamp> seen) {
            return new Put(db, table, id, row, stamp, seen);
        }
    }

    /** Deletes a row; kept as a tombstone so an older {@link Put} cannot bring it back. */
    record Delete(String db, String table, String id, Stamp stamp, Map<String, Stamp> seen) implements RowOp {
        public Delete {
            seen = seen == null ? null : seen.isEmpty() ? Map.of() // one shared instance: most rows have none
                    : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(seen));
        }

        public Delete(String db, String table, String id, Stamp stamp) {
            this(db, table, id, stamp, null);
        }

        @Override
        public Delete withSeen(Map<String, Stamp> seen) {
            return new Delete(db, table, id, stamp, seen);
        }
    }

    /** The most entries a causal context may have, its floor included. */
    int MAX_SEEN = 16;

    /** The context key for a floor: every write stamped at or below it, from any node, counts as seen. */
    String FLOOR = "*";

    static Map<String, Object> toJson(Op op) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (op instanceof CreateDb o) {
            m.put("op", "create_db");
        } else if (op instanceof DropDb o) {
            m.put("op", "drop_db");
        } else if (op instanceof CreateTable o) {
            m.put("op", "create_table");
            m.put("table", o.table());
        } else if (op instanceof DropTable o) {
            m.put("op", "drop_table");
            m.put("table", o.table());
        } else if (op instanceof CreateIndex o) {
            m.put("op", "create_index");
            m.put("table", o.table());
            m.put("column", o.column());
        } else if (op instanceof DropIndex o) {
            m.put("op", "drop_index");
            m.put("table", o.table());
            m.put("column", o.column());
        } else if (op instanceof Put o) {
            m.put("op", "put");
            m.put("table", o.table());
            m.put("id", o.id());
            m.put("row", o.row());
        } else if (op instanceof Delete o) {
            m.put("op", "delete");
            m.put("table", o.table());
            m.put("id", o.id());
        }
        m.put("db", op.db());
        m.put("at", op.stamp().toString());
        if (op instanceof RowOp r && r.seen() != null) {
            Map<String, Object> seen = new java.util.TreeMap<>();
            r.seen().forEach((k, v) -> seen.put(k, v.toString()));
            m.put("seen", seen);
        }
        return m;
    }

    /** As {@link #toJson(Op)} without the causal context: what a 2.0 node would write for the same op. */
    static Map<String, Object> toLegacyJson(Op op) {
        Map<String, Object> m = toJson(op);
        m.remove("seen");
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Stamp> seen(Map<String, Object> m) {
        Object v = m.get("seen");
        if (v == null) {
            return null;
        }
        if (!(v instanceof Map<?, ?> raw) || raw.size() > MAX_SEEN) {
            throw new IllegalArgumentException("'seen' must be an object of at most " + MAX_SEEN + " stamps");
        }
        Map<String, Stamp> out = new java.util.TreeMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) raw).entrySet()) {
            if (!(e.getValue() instanceof String s)) {
                throw new IllegalArgumentException("'seen' values must be stamps");
            }
            Stamp st = Stamp.parse(s);
            String key = e.getKey().equals("~") ? "~" : e.getKey().equals(FLOOR) ? FLOOR : st.node();
            if (!key.equals(e.getKey())) {
                throw new IllegalArgumentException("'seen' entry " + e.getKey() + " names another node's stamp");
            }
            out.put(key, st); // the shared copy of the id (see Stamp.node), not the parser's

        }
        return out;
    }

    /** One op from its JSON form; null for a well-formed kind this version does not know (see listFromJson). */
    @SuppressWarnings("unchecked")
    static Op fromJson(Map<String, Object> m) {
        String kind = str(m, "op");
        String db = str(m, "db");
        Stamp at = Stamp.parse(str(m, "at"));
        switch (kind) {
            case "create_db":
                return new CreateDb(db, at);
            case "drop_db":
                return new DropDb(db, at);
            case "create_table":
                return new CreateTable(db, str(m, "table"), at);
            case "drop_table":
                return new DropTable(db, str(m, "table"), at);
            case "create_index":
                return new CreateIndex(db, str(m, "table"), str(m, "column"), at);
            case "drop_index":
                return new DropIndex(db, str(m, "table"), str(m, "column"), at);
            case "put":
                Object row = m.get("row");
                if (!(row instanceof Map)) {
                    throw new IllegalArgumentException("put without a row");
                }
                return new Put(db, str(m, "table"), str(m, "id"), (Map<String, Object>) row, at, seen(m));
            case "delete":
                return new Delete(db, str(m, "table"), str(m, "id"), at, seen(m));
            default:
                if (!kind.matches("[a-z][a-z0-9_]{0,31}")) {
                    throw new IllegalArgumentException("unknown op " + kind);
                }
                return null; // a kind added by a newer version: skipped, see listFromJson
        }
    }

    static List<Object> toJson(List<Op> ops) {
        List<Object> out = new ArrayList<>(ops.size());
        for (Op op : ops) {
            out.add(toJson(op));
        }
        return out;
    }

    /**
     * Parses a list of ops. Ops of a kind this version does not know (a well-formed lowercase name, from a
     * newer version) are left out, so a newer peer's batches, and a newer version's log after a downgrade,
     * still load: such kinds (index definitions, say) never carry row data a reader of this version needs.
     */
    @SuppressWarnings("unchecked")
    static List<Op> listFromJson(Object json) {
        if (!(json instanceof List)) {
            throw new IllegalArgumentException("expected a list of ops");
        }
        List<Op> out = new ArrayList<>();
        for (Object o : (List<Object>) json) {
            if (!(o instanceof Map)) {
                throw new IllegalArgumentException("expected an op object");
            }
            Op op = fromJson((Map<String, Object>) o);
            if (op != null) {
                out.add(op);
            }
        }
        return out;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException("missing or non-string field '" + k + "'");
        }
        return s;
    }
}
