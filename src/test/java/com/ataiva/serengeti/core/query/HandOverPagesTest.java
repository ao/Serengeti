package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * From review 22k: pages that stream, then hand over to a gathered read after the last _id streamed, against the scan.
 * 40,000 rows whose ids do not follow their numbers: id = "r" + a permutation, so a condition on n puts matches
 * anywhere in _id order, and some rows are named with ids that sort before and after the rest ("a...", "z...").
 * Unions whose parts are _id ranges (key-set views) before, around and past the hand-over point, empty parts,
 * IN lists, ranges and prefixes; tombstones and newer versions from peers; LIMIT 1 to 2,000 and none; keyset
 * forms; OFFSET.
 */
class HandOverPagesTest {
    @TempDir
    Path dir;

    @Test
    void handOverPagesAnswerAsTheScan() throws IOException {
        try (StorageEngine engine = StorageEngine.open(dir, FsyncPolicy.OFF)) {
            Query query = new Query(new Executor(engine, new Hlc("n1"), 10_000));
            query.run("CREATE DATABASE d");
            query.run("CREATE TABLE d.t");
            for (String c : List.of("a", "b", "s", "kind")) {
                query.run("CREATE INDEX ON d.t (" + c + ")");
            }
            int rows = 40_000;
            List<Integer> perm = new ArrayList<>();
            for (int i = 0; i < rows; i++) {
                perm.add(i);
            }
            java.util.Collections.shuffle(perm, new Random(3));
            Random r = new Random(8);
            long t = System.currentTimeMillis() + 1000;
            List<Op> ops = new ArrayList<>();
            for (int i = 0; i < rows; i++) {
                String id = (i % 97 == 0 ? "a" : i % 89 == 0 ? "z" : "r") + String.format("%06d", perm.get(i));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("a", (long) r.nextInt(400));
                row.put("b", (long) r.nextInt(400));
                row.put("s", "s" + r.nextInt(300));
                row.put("kind", (long) (i % 7));
                row.put("n", (long) i);
                ops.add(new Op.Put("d", "t", id, row, new Stamp(t, 0, "p1"), Map.of()));
                if (i % 11 == 0) {
                    ops.add(new Op.Delete("d", "t", id, new Stamp(t + 1, 0, "p2"), Map.of()));
                } else if (i % 5 == 0) {
                    Map<String, Object> v = new LinkedHashMap<>(row);
                    v.put("a", (long) r.nextInt(400));
                    ops.add(new Op.Put("d", "t", id, v, new Stamp(t + 1, 0, "p3"), Map.of()));
                }
                if (ops.size() >= 4000) {
                    engine.merge(ops);
                    ops.clear();
                }
            }
            engine.merge(ops);
            List<String> conditions = List.of(
                    "a < 10 OR b < 10",
                    "(a < 10 OR b < 10) AND n >= 39000",
                    "(a < 10 OR b < 10) AND n < 50",
                    "a < 12 OR _id < 'r000500'",
                    "a < 12 OR _id > 'r039500'",
                    "a < 12 OR _id >= 'z'",
                    "a < 12 OR _id < 'a000400'",
                    "_id < 'a000100' OR (a < 12 AND n > 30000)",
                    "a < 12 OR _id IN ('r000001', 'z039000', 'a000000', 'r020000')",
                    "a < 12 OR b = 999",
                    "a < 12 OR _id LIKE 'r0123%'",
                    "(a < 12 OR b < 12) AND _id > 'r020000'",
                    "(a < 12 OR b < 12) AND _id < 'r001000'",
                    "kind = 3 AND (a < 10 OR b < 10) AND n >= 30000",
                    "kind = 3 AND a < 30",
                    "a < 25 AND n > 39900",
                    "s LIKE 's1%' AND n > 39000",
                    "a IN (1, 2, 3) OR b IN (4, 5, 6)",
                    "(a IN (1, 2) OR s LIKE 's2%') AND n > 20000");
            int n = 0;
            for (String w : conditions) {
                for (String limit : List.of(" LIMIT 1", " LIMIT 10", " LIMIT 100", " LIMIT 2000", "", " LIMIT 10 OFFSET 50",
                        " OFFSET 7")) {
                    for (String form : List.of("SELECT _id, n FROM d.t WHERE %s",
                            "SELECT _id FROM d.t WHERE (%s) AND _id > 'r019999' ORDER BY _id")) {
                        String s = String.format(form, w) + limit;
                        String twin = String.format(form, "NOT (NOT (" + w + "))") + limit;
                        assertEquals(Json.write(query.run(twin).toJson()), Json.write(query.run(s).toJson()), s);
                        n++;
                    }
                }
            }
            System.out.println("hand-over pages compared " + n);
        }
    }
}
