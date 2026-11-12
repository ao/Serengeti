package com.ataiva.serengeti.core.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What a statement returned. {@link #toJson()} gives a value that core/json/Json can write. */
public sealed interface Result {
    Map<String, Object> toJson();

    /**
     * Rows from SELECT or SHOW. Each row has one value per column; a column a row does not have is
     * null. {@code truncated} is true when a SELECT without LIMIT matched more rows than the cap.
     */
    record Rows(List<String> columns, List<List<Object>> rows, boolean truncated) implements Result {
        public Rows {
            columns = List.copyOf(columns);
            List<List<Object>> copy = new ArrayList<>(rows.size());
            for (List<Object> r : rows) {
                copy.add(Collections.unmodifiableList(new ArrayList<>(r)));
            }
            rows = Collections.unmodifiableList(copy);
        }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "rows");
            m.put("columns", columns);
            m.put("rows", rows);
            m.put("count", (long) rows.size());
            m.put("truncated", truncated);
            return m;
        }
    }

    /**
     * The outcome of a write: how many databases, tables or rows it changed. {@code ids} lists the
     * {@code _id} of each inserted row, in insert order; it is empty for other statements.
     */
    record Write(long affected, List<String> ids) implements Result {
        public Write {
            ids = List.copyOf(ids);
        }

        public Write(long affected) {
            this(affected, List.of());
        }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "write");
            m.put("affected", affected);
            if (!ids.isEmpty()) {
                m.put("ids", ids);
            }
            return m;
        }
    }
}
