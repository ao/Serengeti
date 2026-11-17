package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * From review 22k: Store.after against a plain filter, for every kind of id source the reads pass it: TreeSets,
 * key-set views of sub-ranges of a TreeMap (subMap, headMap, tailMap, in or out of their range, empty), merged
 * streams of those, Gathered sets and List.of(), with `after` before, inside, on and past every id.
 */
class StoreAfterPropertiesTest {
    private static List<String> list(Iterable<String> it) {
        List<String> out = new ArrayList<>();
        for (String s : it) {
            out.add(s);
        }
        return out;
    }

    private static List<String> want(Iterable<String> ids, String after) {
        List<String> out = new ArrayList<>();
        for (String s : ids) {
            if (after == null || s.compareTo(after) > 0) {
                out.add(s);
            }
        }
        return out;
    }

    @Test
    void afterIsAFilterForEverySource() {
        Random r = new Random(22);
        int cases = 0;
        for (int round = 0; round < 3000; round++) {
            TreeMap<String, Integer> rows = new TreeMap<>();
            for (int i = r.nextInt(40); i > 0; i--) {
                rows.put(String.format("r%03d", r.nextInt(200)), i);
            }
            String lo = String.format("r%03d", r.nextInt(220));
            String hi = String.format("r%03d", r.nextInt(220));
            if (lo.compareTo(hi) > 0) {
                String x = lo;
                lo = hi;
                hi = x;
            }
            List<Iterable<String>> sources = new ArrayList<>();
            sources.add(rows.navigableKeySet());
            sources.add(rows.subMap(lo, r.nextBoolean(), hi, r.nextBoolean()).navigableKeySet());
            sources.add(rows.headMap(hi, r.nextBoolean()).navigableKeySet());
            sources.add(rows.tailMap(lo, r.nextBoolean()).navigableKeySet());
            sources.add(rows.subMap(lo, true, lo, true).keySet());
            sources.add(new TreeSet<>(rows.keySet()));
            Store.Gathered g = new Store.Gathered();
            g.addAll(rows.headMap(hi).keySet());
            sources.add(g);
            sources.add(List.of());
            List<java.util.SortedSet<String>> sets = new ArrayList<>();
            for (Iterable<String> s : sources) {
                if (s instanceof NavigableSet<String> n) {
                    sets.add(n);
                }
            }
            sources.add(Store.merged(sets));
            sources.add(Store.merged(List.of(rows.headMap(lo, false).navigableKeySet(),
                    rows.tailMap(hi, true).navigableKeySet())));
            List<String> afters = new ArrayList<>();
            afters.add(null);
            afters.add("");
            afters.add("r");
            afters.add("s");
            afters.add(lo);
            afters.add(hi);
            for (int i = 0; i < 4; i++) {
                afters.add(String.format("r%03d", r.nextInt(220)));
            }
            if (!rows.isEmpty()) {
                afters.add(rows.firstKey());
                afters.add(rows.lastKey());
            }
            for (Iterable<String> s : sources) {
                for (String a : afters) {
                    assertEquals(want(s, a), list(Store.after(s, a)), "after " + a + " of " + list(s));
                    // Twice: the Iterable is read again by a merge.
                    assertEquals(want(s, a), list(Store.after(s, a)), "again, after " + a);
                    cases++;
                }
            }
        }
        System.out.println("after cases " + cases);
    }
}
