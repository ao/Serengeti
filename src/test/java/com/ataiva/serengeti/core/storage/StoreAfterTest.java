package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Store.after: the ids of a read after a given id, for a page that goes on where another read stopped. */
class StoreAfterTest {
    private static List<String> list(Iterable<String> ids) {
        List<String> out = new ArrayList<>();
        ids.forEach(out::add);
        return out;
    }

    @Test
    void aViewOfPartOfAMapIsCutWithoutAskingItForKeysOutsideItsRange() {
        TreeMap<String, Integer> m = new TreeMap<>();
        for (String k : List.of("a", "b", "c", "d", "e", "f")) {
            m.put(k, 0);
        }
        // A range of _id as a read gives it: keys b to e, from a view that takes no key outside [b, e].
        var range = m.subMap("b", true, "e", true).navigableKeySet();
        assertEquals(List.of("b", "c", "d", "e"), list(Store.after(range, null)));
        assertEquals(List.of("b", "c", "d", "e"), list(Store.after(range, "a")), "before the range: all of it");
        assertEquals(List.of("d", "e"), list(Store.after(range, "c")));
        assertEquals(List.of("d", "e"), list(Store.after(range, "cz")));
        assertEquals(List.of(), list(Store.after(range, "e")));
        assertEquals(List.of(), list(Store.after(range, "z")), "past the range: none");
        assertEquals(List.of(), list(Store.after(m.subMap("x", true, "y", true).navigableKeySet(), "a")), "empty");
    }

    @Test
    void idsThatAreNotASortedSetAreSkippedAsTheyCome() {
        Iterable<String> merged = List.of("a", "c", "e", "g");
        assertEquals(List.of("a", "c", "e", "g"), list(Store.after(merged, "")));
        assertEquals(List.of("e", "g"), list(Store.after(merged, "c")));
        assertEquals(List.of("e", "g"), list(Store.after(merged, "d")));
        assertEquals(List.of(), list(Store.after(merged, "g")));
        assertEquals(List.of(), list(Store.after(List.of(), "a")));
    }
}
