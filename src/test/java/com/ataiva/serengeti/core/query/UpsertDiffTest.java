package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * From review 22h: INSERT ... ON CONFLICT on table x against a model on table y that holds the same rows: the tuples
 * whose _id SELECT finds get one UPDATE ... WHERE _id IN (those ids), the others one plain INSERT; DO NOTHING
 * leaves the found ones alone. Rows from three peers that had not seen each other (siblings, deletes, a delete
 * with a live sibling, rows newer and older than one another), values of every type, Long.MAX_VALUE for
 * overflow, strings for refused arithmetic. After every statement both tables hold the same rows and the same
 * versions (SHOW CONFLICTS less the stamps' times), the answer counts are the model's, a statement that fails
 * changes nothing, and the same holds after a reopen.
 */
class UpsertDiffTest {
    @TempDir
    Path dir;

    static final Object MISSING = new Object();
    static final List<String> COLUMNS = List.of("n", "s", "m");
    static final List<Object> STORED = Arrays.asList(MISSING, MISSING, null, 0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE,
            2.5, -0.0, 9007199254740993L, 1e308, "x", "", true, Map.of("k", 1L), List.of(1L));
    static final List<String> LITERALS = List.of("NULL", "0", "1", "-1", "9223372036854775807", "2.5", "-0.0", "'x'",
            "''", "TRUE");
    static final List<String> NUMBERS = List.of("1", "2.5", "9223372036854775807", "-0.0", "-3");

    private StorageEngine engine;
    private Query query;

    private void open() throws IOException {
        engine = StorageEngine.open(dir, FsyncPolicy.OFF);
        query = new Query(new Executor(engine, new Hlc("n1"), 10_000));
    }

    static <T> T pick(Random r, List<T> from) {
        return from.get(r.nextInt(from.size()));
    }

    static Map<String, Object> row(Random r) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String c : COLUMNS) {
            Object v = pick(r, STORED);
            if (v != MISSING) {
                m.put(c, v);
            }
        }
        return m;
    }

    private void load(String db, long seed, long t) throws IOException {
        for (String table : List.of("x", "y")) {
            Random data = new Random(seed);
            for (int i = 0; i < 12; i++) {
                if (data.nextInt(5) == 0) {
                    continue;
                }
                String id = "r" + i;
                List<Op> ops = new ArrayList<>();
                ops.add(new Op.Put(db, table, id, row(data), new Stamp(t + data.nextInt(20), 0, "p1"), Map.of()));
                switch (data.nextInt(4)) {
                    case 0 -> ops.add(new Op.Delete(db, table, id, new Stamp(t + data.nextInt(20), 0, "p2"), Map.of()));
                    case 1 -> ops.add(new Op.Put(db, table, id, row(data), new Stamp(t + data.nextInt(20), 0, "p2"), Map.of()));
                    default -> { }
                }
                if (data.nextInt(4) == 0) {
                    ops.add(new Op.Put(db, table, id, row(data), new Stamp(t + data.nextInt(20), 1, "p3"), Map.of()));
                }
                engine.merge(ops);
            }
        }
    }

    private Object run(String text) {
        try {
            return Json.write(query.run(text).toJson());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private String state(String db, String table) throws IOException {
        Object rows = query.run("SELECT * FROM " + db + "." + table + " ORDER BY _id").toJson().get("rows");
        Result.Rows c = (Result.Rows) query.run("SHOW CONFLICTS IN " + db + "." + table);
        List<List<Object>> versions = new ArrayList<>();
        for (List<Object> v : c.rows()) {
            List<Object> w = new ArrayList<>(v);
            w.remove(4); // the stamp's time differs between the two tables for local writes
            versions.add(w);
        }
        return Json.write(rows) + " " + Json.write(versions);
    }

    @Test
    void anUpsertWritesWhatAnInsertAndAnUpdateOfTheSameRowsWould() throws IOException {
        open();
        int runs = Integer.getInteger("upsert.diff.runs", 300);
        Map<String, Integer> kinds = new TreeMap<>();
        for (int run = 0; run < runs; run++) {
            long seed = 5000L + run;
            Random r = new Random(seed);
            String db = "u" + run;
            query.run("CREATE DATABASE " + db);
            query.run("CREATE TABLE " + db + ".x");
            query.run("CREATE TABLE " + db + ".y");
            if (r.nextBoolean()) {
                query.run("CREATE INDEX ON " + db + ".x (" + pick(r, COLUMNS) + ")");
            }
            load(db, seed, System.currentTimeMillis() + 100_000);
            for (int s = 0; s < 15; s++) {
                List<String> cols = new ArrayList<>(List.of("_id"));
                for (String c : COLUMNS) {
                    if (r.nextBoolean()) {
                        cols.add(c);
                    }
                }
                List<String> ids = new ArrayList<>();
                for (int k = 1 + r.nextInt(4); k > 0; k--) {
                    String id = "r" + r.nextInt(14);
                    if (!ids.contains(id) || r.nextInt(12) == 0) {
                        ids.add(id);
                    }
                }
                Map<String, String> tuples = new LinkedHashMap<>();
                List<String> texts = new ArrayList<>();
                for (String id : ids) {
                    List<String> v = new ArrayList<>(List.of("'" + id + "'"));
                    for (int c = 1; c < cols.size(); c++) {
                        v.add(pick(r, LITERALS));
                    }
                    String tuple = "(" + String.join(", ", v) + ")";
                    texts.add(tuple);
                    tuples.putIfAbsent(id, tuple);
                }
                boolean twice = tuples.size() < ids.size();
                int kind = r.nextInt(5);
                String clause = "";
                String sets = "";
                if (kind == 1 || kind == 2) {
                    clause = " ON CONFLICT DO NOTHING";
                } else if (kind > 2) {
                    List<String> targets = new ArrayList<>(COLUMNS);
                    Collections.shuffle(targets, r);
                    List<String> as = new ArrayList<>();
                    for (String c : targets.subList(0, 1 + r.nextInt(2))) {
                        as.add(r.nextInt(3) == 0 ? c + " = " + pick(r, LITERALS)
                                : c + " = " + pick(r, COLUMNS) + (r.nextBoolean() ? " + " : " - ") + pick(r, NUMBERS));
                    }
                    sets = String.join(", ", as);
                    clause = " ON CONFLICT DO UPDATE SET " + sets;
                }
                String head = "INSERT INTO " + db + ".%s (" + String.join(", ", cols) + ") VALUES ";
                String text = String.format(head, "x") + String.join(", ", texts) + clause;
                String before = state(db, "x");
                Object got = run(text);
                String why = "seed " + seed + ": " + text;
                if (twice) {
                    assertNull(got, why + " lists an _id twice");
                    assertEquals(before, state(db, "x"), why);
                    kinds.merge("twice", 1, Integer::sum);
                    continue;
                }
                List<String> present = new ArrayList<>();
                List<String> absent = new ArrayList<>();
                for (String id : tuples.keySet()) {
                    boolean found = !((Result.Rows) query.run("SELECT _id FROM " + db + ".y WHERE _id = '" + id + "'"))
                            .rows().isEmpty();
                    (found ? present : absent).add(id);
                }
                String inList = "(" + String.join(", ", present.stream().map(i -> "'" + i + "'").toList()) + ")";
                if (kind == 0) {
                    if (!present.isEmpty()) {
                        assertNull(got, why + " inserts a row that exists");
                        kinds.merge("insert refused", 1, Integer::sum);
                    } else {
                        // The plain INSERT's answer, byte for byte, as the same INSERT on y gives it.
                        assertEquals(run(String.format(head, "y") + String.join(", ", texts)), got, why);
                        kinds.merge("insert", 1, Integer::sum);
                    }
                } else if (kind > 2 && got == null) {
                    // Refused: the same UPDATE of the rows found must be refused too, and nothing written.
                    assertFalse(present.isEmpty(), why + " was refused with no row to update");
                    assertNull(run("UPDATE " + db + ".y SET " + sets + " WHERE _id IN " + inList), why
                            + ": refused, but UPDATE of the rows found is not");
                    assertEquals(before, state(db, "x"), why + " wrote something");
                    kinds.merge("do update refused", 1, Integer::sum);
                } else {
                    assertNotNull(got, why + " was refused");
                    List<String> written = new ArrayList<>();
                    if (kind > 2 && !present.isEmpty()) {
                        assertNotNull(run("UPDATE " + db + ".y SET " + sets + " WHERE _id IN " + inList), why);
                    }
                    if (!absent.isEmpty()) {
                        assertNotNull(run(String.format(head, "y")
                                + String.join(", ", absent.stream().map(tuples::get).toList())), why);
                    }
                    for (String id : tuples.keySet()) {
                        if (absent.contains(id) || kind > 2) {
                            written.add(id);
                        }
                    }
                    Map<String, Object> want = new LinkedHashMap<>();
                    want.put("type", "write");
                    want.put("affected", (long) written.size());
                    want.put("inserted", (long) absent.size());
                    want.put("updated", kind > 2 ? (long) present.size() : 0L);
                    want.put("skipped", kind > 2 ? 0L : (long) present.size());
                    want.put("ids", written);
                    assertEquals(Json.write(want), got, why);
                    kinds.merge(kind > 2 ? "do update" : "do nothing", 1, Integer::sum);
                }
                assertEquals(state(db, "y"), state(db, "x"), why);
            }
        }
        engine.close();
        open();
        for (int run = 0; run < runs; run++) {
            assertEquals(state("u" + run, "y"), state("u" + run, "x"), "after a reopen, run " + run);
        }
        engine.close();
        System.out.println("R22H upsert diff " + kinds);
    }
}
