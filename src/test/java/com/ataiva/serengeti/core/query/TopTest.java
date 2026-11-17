package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * From review 22k: Executor.Top gives the first keep rows of a full sort, for inputs in order, in reverse, random,
 * sawtooth, organ-pipe, blocks reversed, with many equal keys (ties broken by _id), keep from 1 to past the input,
 * and an order on a key, DESC, then _id, as the executor builds it.
 */
class TopTest {
    private static Executor.Match match(int id, long key) {
        return new Executor.Match(String.format("r%05d", id), Map.of("k", key));
    }

    @Test
    void topIsThePrefixOfTheSort() {
        Comparator<Executor.Match> byKeyDesc = Comparator.<Executor.Match, Long>comparing(m -> (Long) m.row().get("k"))
                .reversed().thenComparing(Executor.Match::id);
        Comparator<Executor.Match> byKey = Comparator.<Executor.Match, Long>comparing(m -> (Long) m.row().get("k"))
                .thenComparing(Executor.Match::id);
        Random r = new Random(5);
        int cases = 0;
        for (int round = 0; round < 4000; round++) {
            int n = r.nextInt(r.nextInt(4) == 0 ? 3000 : 120);
            int distinct = 1 + r.nextInt(r.nextBoolean() ? 5 : 100_000);
            List<Executor.Match> in = new ArrayList<>(n);
            int shape = r.nextInt(7);
            for (int i = 0; i < n; i++) {
                long key = switch (shape) {
                    case 0 -> i;
                    case 1 -> n - i;
                    case 2 -> r.nextInt(distinct);
                    case 3 -> i % 37;
                    case 4 -> i < n / 2 ? i : n - i;
                    case 5 -> (i / 50) * 50 + (49 - i % 50);
                    default -> (n - i) / 3;
                };
                in.add(match(shape == 2 ? r.nextInt(1_000_000) : i, key % Math.max(1, distinct)));
            }
            // _id must be unique, as rows' are.
            java.util.Set<String> seen = new java.util.HashSet<>();
            in.removeIf(m -> !seen.add(m.id()));
            Comparator<Executor.Match> order = r.nextBoolean() ? byKey : byKeyDesc;
            long keep = 1 + r.nextInt(r.nextBoolean() ? 20 : Math.max(1, n + 5));
            Executor.Top top = new Executor.Top(order, keep);
            for (Executor.Match m : in) {
                top.add(m);
            }
            List<Executor.Match> all = new ArrayList<>(in);
            all.sort(order);
            List<Executor.Match> want = all.subList(0, (int) Math.min(keep, all.size()));
            assertEquals(want, top.sorted(), "shape " + shape + ", n " + in.size() + ", keep " + keep);
            cases++;
        }
        System.out.println("top cases " + cases);
    }
}
