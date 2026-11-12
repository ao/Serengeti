package com.ataiva.serengeti.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashMap;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class JsonProperties {
    @Property(tries = 2000)
    void arbitraryTextParsesOrFailsCleanly(@ForAll("jsonish") String s) {
        try {
            Json.parse(s);
        } catch (JsonException expected) {
            // the only allowed failure
        }
    }

    @Property(tries = 1000)
    void writeThenParseGivesTheSameValue(@ForAll("values") Object v) {
        assertEquals(v, Json.parse(Json.write(v)));
    }

    @Provide
    Arbitrary<String> jsonish() {
        Arbitrary<String> tokens = Arbitraries.of("{", "}", "[", "]", ",", ":", "\"", "\\", "\\u", "1", "-",
                ".", "e", "true", "nul", "null", " ", "\"a\"", "0", "9999999999999999999999", "\n");
        Arbitrary<String> fromTokens = tokens.list().ofMaxSize(40).map(l -> String.join("", l));
        return Arbitraries.oneOf(fromTokens, Arbitraries.strings().ofMaxLength(60));
    }

    @Provide
    Arbitrary<Object> values() {
        return value(3);
    }

    private Arbitrary<Object> value(int depth) {
        Arbitrary<Object> scalar = Arbitraries.oneOf(
                Arbitraries.strings().ofMaxLength(20).map(s -> (Object) s),
                Arbitraries.longs().map(l -> (Object) l),
                Arbitraries.doubles().filter(d -> !Double.isNaN(d) && !Double.isInfinite(d)).map(d -> (Object) d),
                Arbitraries.of(true, false).map(b -> (Object) b),
                Arbitraries.just(null));
        if (depth == 0) {
            return scalar;
        }
        Arbitrary<Object> list = value(depth - 1).list().ofMaxSize(4).map(l -> (Object) Arrays.asList(l.toArray()));
        Arbitrary<Object> map = Arbitraries.maps(Arbitraries.strings().ofMaxLength(8), value(depth - 1))
                .ofMaxSize(4).map(m -> (Object) new LinkedHashMap<>(m));
        return Arbitraries.oneOf(scalar, list, map);
    }

}
