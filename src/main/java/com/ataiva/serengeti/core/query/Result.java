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
     * {@code _id} of each row an INSERT wrote, in the order of its tuples; it is empty for other statements.
     * {@code upsert} is null but for INSERT ... ON CONFLICT.
     */
    record Write(long affected, List<String> ids, Upsert upsert) implements Result {
        /** The tuples of an INSERT ... ON CONFLICT: inserted and updated (together {@code affected}), and skipped. */
        public record Upsert(long inserted, long updated, long skipped) {}

        public Write {
            ids = List.copyOf(ids);
            if (upsert != null && upsert.inserted() + upsert.updated() != affected) {
                throw new IllegalArgumentException("affected is not inserted + updated");
            }
        }

        public Write(long affected, List<String> ids) {
            this(affected, ids, null);
        }

        public Write(long affected) {
            this(affected, List.of());
        }

        /** {@code affected}, then with ON CONFLICT {@code inserted}, {@code updated} and {@code skipped}, then {@code ids}. */
        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "write");
            m.put("affected", affected);
            if (upsert != null) {
                m.put("inserted", upsert.inserted());
                m.put("updated", upsert.updated());
                m.put("skipped", upsert.skipped());
            }
            if (!ids.isEmpty() || upsert != null) {
                m.put("ids", ids);
            }
            return m;
        }
    }
}
