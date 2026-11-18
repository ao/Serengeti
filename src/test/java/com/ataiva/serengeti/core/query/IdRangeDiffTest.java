package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * From review 22l: range terms and LIKE prefixes on _id read as one bounded read (9b8740b), against the scan (the same
 * statement with its condition as NOT (NOT (...)), which no read answers). Random ANDs of 2 to 5 terms on _id:
 * comparisons of every kind with strings near the ids (equal, one code unit before or after, prefixes, U+FFFF,
 * NUL), numbers and NULL; LIKE prefixes ending in U+FFFF or written with escapes; NOT LIKE, IN and !=; with and
 * without a term on another column, alone and as branches of ORs. Tombstones and newer versions from peers.
 * Forms: plain, LIMIT, OFFSET, ORDER BY _id DESC, ORDER BY an indexed column, COUNT(*), and UPDATE/DELETE counts
 * (which read through the same plan).
 */
class IdRangeDiffTest {
    @TempDir
    Path dir;

    @Test
    void idRangesAnswerAsTheScan() throws Exception {
        try (StorageEngine engine = StorageEngine.open(dir, FsyncPolicy.OFF)) {
            Query query = new Query(new Executor(engine, new Hlc("n1"), 10_000));
            query.run("CREATE DATABASE d");
            query.run("CREATE TABLE d.t");
            query.run("CREATE INDEX ON d.t (a)");
            query.run("CREATE INDEX ON d.t (s)");
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 3000; i++) {
                ids.add(String.format("r%05d", i * 3));
            }
            for (int i = 0; i < 1000; i++) {
                ids.add(String.format("a%04d", i));
                ids.add(String.format("b%03d", i % 700) + (i >= 700 ? "x" : ""));
            }
            ids.addAll(List.of("r", "r￿", "r￿￿", "￿", "￿￿", "￿a", "r00003\u0000", "r00003￿", "R00003",
                    "😀", "😀a", "\u0000", "a", "a￿", "r0", "r0000", "%", "_", "\\", "r%", "r_1", "r\\x"));
            Random r = new Random(9);
            long t = System.currentTimeMillis() + 1000;
            List<Op> ops = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("a", (long) r.nextInt(200));
                row.put("s", "s" + r.nextInt(100));
                row.put("n", (long) i);
                ops.add(new Op.Put("d", "t", id, row, new Stamp(t, 0, "p1"), Map.of()));
                if (i % 13 == 0) {
                    ops.add(new Op.Delete("d", "t", id, new Stamp(t + 1, 0, "p2"), Map.of()));
                } else if (i % 7 == 0) {
                    Map<String, Object> v = new LinkedHashMap<>(row);
                    v.put("a", (long) r.nextInt(200));
                    ops.add(new Op.Put("d", "t", id, v, new Stamp(t + 1, 0, "p3"), Map.of()));
                }
            }
            engine.merge(ops);

            List<String> lits = new ArrayList<>();
            for (int k = 0; k < 60; k++) {
                String id = ids.get(r.nextInt(ids.size()));
                lits.add(id);
                lits.add(id + "\u0000");
                if (!id.isEmpty()) {
                    char last = id.charAt(id.length() - 1);
                    lits.add(id.substring(0, id.length() - 1) + (char) (last - 1));
                    lits.add(id.substring(0, id.length() - 1) + (char) (last + 1));
                    lits.add(id.substring(0, Math.max(0, id.length() - 2)));
                }
            }
            lits.addAll(List.of("", "r", "r0", "r01", "r1", "r08", "r09", "s", "a", "b", "b5", "￿", "r￿", "R"));
            List<String> prefixes = List.of("r", "r0", "r00", "r01", "r1", "r08", "r089", "a", "a0", "b", "b1",
                    "a￿", "￿", "r￿", "😀", "R", "r\\%", "r\\_", "r\\\\", "\\%", "q", "r00003\u0000");
            String[] ops2 = {"<", "<=", ">", ">=", "=", "!="};

            List<String> wrong = new ArrayList<>();
            int n = 0;
            for (int c = 0; c < 2500; c++) {
                String w = condition(r, lits, prefixes, ops2);
                if (r.nextInt(4) == 0) {
                    w = "(" + w + ") OR (" + (r.nextBoolean() ? condition(r, lits, prefixes, ops2)
                            : "a = " + r.nextInt(200)) + ")";
                } else if (r.nextInt(5) == 0) {
                    w = "s = 's" + r.nextInt(100) + "' OR (" + w + ")";
                }
                String twin = "NOT (NOT (" + w + "))";
                List<String> forms = List.of(
                        "SELECT _id, n FROM d.t WHERE %s",
                        "SELECT _id FROM d.t WHERE %s LIMIT " + (1 + r.nextInt(30)),
                        "SELECT _id FROM d.t WHERE %s LIMIT 5 OFFSET " + r.nextInt(40),
                        "SELECT _id FROM d.t WHERE %s ORDER BY _id DESC LIMIT " + (1 + r.nextInt(50)),
                        "SELECT _id, a FROM d.t WHERE %s ORDER BY a, _id LIMIT " + (1 + r.nextInt(20)),
                        "SELECT COUNT(*) FROM d.t WHERE %s",
                        "SELECT a, COUNT(*) FROM d.t WHERE %s GROUP BY a ORDER BY a LIMIT 3");
                for (String f : forms) {
                    String s = String.format(f, w);
                    String a;
                    String b;
                    try {
                        a = Json.write(query.run(s).toJson());
                    } catch (RuntimeException e) {
                        a = "error " + e.getMessage();
                    }
                    try {
                        b = Json.write(query.run(String.format(f, twin)).toJson());
                    } catch (RuntimeException e) {
                        b = "error " + e.getMessage();
                    }
                    n++;
                    if (!a.equals(b)) {
                        wrong.add(s + "\n   read: " + shorten(a) + "\n   scan: " + shorten(b));
                    }
                }
            }
            // UPDATE and DELETE read through the same plans: their counts against the scan's.
            for (int c = 0; c < 200; c++) {
                String w = condition(r, lits, prefixes, ops2);
                long want = (Long) ((List<?>) ((List<?>) query.run("SELECT COUNT(*) FROM d.t WHERE NOT (NOT (" + w + "))")
                        .toJson().get("rows")).get(0)).get(0);
                Object got = query.run("UPDATE d.t SET touched = " + c + " WHERE " + w).toJson().get("affected");
                long touched = (Long) ((List<?>) ((List<?>) query.run("SELECT COUNT(*) FROM d.t WHERE touched = " + c)
                        .toJson().get("rows")).get(0)).get(0);
                n++;
                if (!Long.valueOf(want).equals(got) || touched != want) {
                    wrong.add("UPDATE WHERE " + w + ": " + got + " affected, " + touched + " touched, scan " + want);
                }
            }
            System.out.println("_id ranges: " + n + " statements compared, " + wrong.size() + " wrong");
            assertEquals(List.of(), wrong, String.join("\n", wrong.subList(0, Math.min(15, wrong.size()))));
        }
    }

    private static String shorten(String s) {
        return s.length() < 400 ? s : s.substring(0, 400) + "...";
    }

    private static String condition(Random r, List<String> lits, List<String> prefixes, String[] ops) {
        int terms = 2 + r.nextInt(4);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < terms; i++) {
            int k = r.nextInt(20);
            if (k < 11) {
                out.add("_id " + ops[r.nextInt(4)] + " " + Values.format(lits.get(r.nextInt(lits.size()))));
            } else if (k < 15) {
                out.add("_id LIKE " + Values.format(prefixes.get(r.nextInt(prefixes.size())) + (r.nextInt(4) == 0 ? "_%" : "%")));
            } else if (k == 15) {
                out.add("_id " + ops[r.nextInt(ops.length)] + " " + Values.format(lits.get(r.nextInt(lits.size()))));
            } else if (k == 16) {
                out.add("_id " + ops[r.nextInt(4)] + " " + (r.nextBoolean() ? "5" : "NULL"));
            } else if (k == 17) {
                out.add("_id NOT LIKE " + Values.format(prefixes.get(r.nextInt(prefixes.size())) + "%"));
            } else if (k == 18) {
                out.add("_id IN (" + Values.format(lits.get(r.nextInt(lits.size()))) + ", "
                        + Values.format(lits.get(r.nextInt(lits.size()))) + ")");
            } else {
                out.add(r.nextBoolean() ? "a < " + r.nextInt(200) : "n >= " + r.nextInt(5000));
            }
        }
        return String.join(" AND ", out);
    }
}
