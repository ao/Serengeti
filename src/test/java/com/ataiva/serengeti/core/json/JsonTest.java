package com.ataiva.serengeti.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {
    @Test
    void parsesNumbersAsLongOrDouble() {
        assertEquals(42L, Json.parse("42"));
        assertEquals(-9223372036854775808L, Json.parse("-9223372036854775808"));
        assertEquals(9.223372036854775808E18, Json.parse("9223372036854775808"));
        assertEquals(1.5, Json.parse("1.5"));
        assertEquals(1e10, Json.parse("1E10"));
        assertEquals(0.0, Json.parse("0.0"));
    }

    @Test
    void keepsKeyOrder() {
        Map<String, Object> m = Json.parseObject("{\"z\":1,\"a\":2,\"m\":3}");
        assertEquals(List.of("z", "a", "m"), new ArrayList<>(m.keySet()));
        assertEquals("{\"z\":1,\"a\":2,\"m\":3}", Json.write(m));
    }

    @Test
    void stringsAndEscapes() {
        assertEquals("a\"b\\c\n\u00e9\ud83d\ude00", Json.parse("\"a\\\"b\\\\c\\n\\u00e9\\ud83d\\ude00\""));
    }

    @Test
    void writesHtmlSensitiveCharactersEscaped() {
        String w = Json.write("</script><img onerror=x>&");
        assertEquals(-1, w.indexOf('<'));
        assertEquals(-1, w.indexOf('>'));
        assertEquals(-1, w.indexOf('&'));
        assertEquals("</script><img onerror=x>&", Json.parse(w));
    }

    @Test
    void nullAndNested() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", null);
        m.put("b", Arrays.asList(1L, null, "x", true, 2.5));
        String w = Json.write(m);
        assertEquals("{\"a\":null,\"b\":[1,null,\"x\",true,2.5]}", w);
        assertEquals(m, Json.parse(w));
        assertNull(Json.parse(" null "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[1,", "{\"a\"}", "{\"a\":1,}", "01", "1.", "-", "1e", "\"abc",
        "\"\\x\"", "\"\\u12\"", "tru", "nul", "[1]x", "{\"a\":1,\"a\":2}", "\"\u0001\"", "{1:2}",
        "1e400"})
    void rejectsMalformedInput(String s) {
        assertThrows(JsonException.class, () -> Json.parse(s));
    }

    @Test
    void rejectsDeepNesting() {
        String deep = "[".repeat(Json.MAX_DEPTH + 1) + "]".repeat(Json.MAX_DEPTH + 1);
        assertThrows(JsonException.class, () -> Json.parse(deep));
        String ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH);
        Json.parse(ok);
    }

    @Test
    void refusesNonFiniteNumbers() {
        assertThrows(JsonException.class, () -> Json.write(Double.NaN));
        assertThrows(JsonException.class, () -> Json.write(Double.POSITIVE_INFINITY));
    }
}
