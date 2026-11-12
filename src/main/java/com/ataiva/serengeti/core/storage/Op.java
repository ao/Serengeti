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

    /** Writes a whole row. */
    record Put(String db, String table, String id, Map<String, Object> row, Stamp stamp) implements Op {
        public Put {
            row = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(row));
        }
    }

    /** Deletes a row; kept as a tombstone so an older {@link Put} cannot bring it back. */
    record Delete(String db, String table, String id, Stamp stamp) implements Op {}

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
        return m;
    }

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
            case "put":
                Object row = m.get("row");
                if (!(row instanceof Map)) {
                    throw new IllegalArgumentException("put without a row");
                }
                return new Put(db, str(m, "table"), str(m, "id"), (Map<String, Object>) row, at);
            case "delete":
                return new Delete(db, str(m, "table"), str(m, "id"), at);
            default:
                throw new IllegalArgumentException("unknown op " + kind);
        }
    }

    static List<Object> toJson(List<Op> ops) {
        List<Object> out = new ArrayList<>(ops.size());
        for (Op op : ops) {
            out.add(toJson(op));
        }
        return out;
    }

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
            out.add(fromJson((Map<String, Object>) o));
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
