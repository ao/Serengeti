package com.ataiva.serengeti.core.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer. Objects become {@link LinkedHashMap} (key order kept), arrays
 * {@link ArrayList}, integers that fit in a long {@link Long}, other numbers {@link Double}, and
 * {@code null} stays {@code null}.
 */
public final class Json {
    /** Maximum nesting of arrays and objects accepted by {@link #parse}. */
    public static final int MAX_DEPTH = 128;

    private final String s;
    private int pos;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != text.length()) {
            throw new JsonException("unexpected data after the JSON value", p.pos);
        }
        return v;
    }

    /** Parses text that must be a JSON object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("expected a JSON object", 0);
        }
        return (Map<String, Object>) v;
    }

    private Object value(int depth) {
        if (pos >= s.length()) {
            throw new JsonException("unexpected end of input", pos);
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{':
                return object(depth + 1);
            case '[':
                return array(depth + 1);
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw new JsonException("unexpected character '" + c + "'", pos);
        }
    }

    private Map<String, Object> object(int depth) {
        if (depth > MAX_DEPTH) {
            throw new JsonException("nesting deeper than " + MAX_DEPTH, pos);
        }
        pos++;
        Map<String, Object> m = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            pos++;
            return m;
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw new JsonException("expected a string key", pos);
            }
            int keyPos = pos;
            String k = string();
            if (m.containsKey(k)) {
                throw new JsonException("duplicate key \"" + k + "\"", keyPos);
            }
            skipWs();
            expect(':');
            skipWs();
            m.put(k, value(depth));
            skipWs();
            char c = peek();
            pos++;
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw new JsonException("expected ',' or '}'", pos - 1);
            }
        }
    }

    private List<Object> array(int depth) {
        if (depth > MAX_DEPTH) {
            throw new JsonException("nesting deeper than " + MAX_DEPTH, pos);
        }
        pos++;
        List<Object> a = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            pos++;
            return a;
        }
        while (true) {
            skipWs();
            a.add(value(depth));
            skipWs();
            char c = peek();
            pos++;
            if (c == ']') {
                return a;
            }
            if (c != ',') {
                throw new JsonException("expected ',' or ']'", pos - 1);
            }
        }
    }

    private String string() {
        int start = pos;
        pos++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw new JsonException("unterminated string", start);
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return b.toString();
            }
            if (c < 0x20) {
                throw new JsonException("control character in string", pos - 1);
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (pos >= s.length()) {
                throw new JsonException("unterminated string", start);
            }
            char e = s.charAt(pos++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (pos + 4 > s.length()) {
                        throw new JsonException("bad \\u escape", pos - 2);
                    }
                    int cp = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = Character.digit(s.charAt(pos + i), 16);
                        if (d < 0) {
                            throw new JsonException("bad \\u escape", pos - 2);
                        }
                        cp = cp * 16 + d;
                    }
                    pos += 4;
                    b.append((char) cp);
                    break;
                default:
                    throw new JsonException("bad escape '\\" + e + "'", pos - 2);
            }
        }
    }

    private Object number() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++;
        } else if (isDigit(peek())) {
            while (isDigit(peek())) {
                pos++;
            }
        } else {
            throw new JsonException("bad number", start);
        }
        boolean integral = true;
        if (peek() == '.') {
            integral = false;
            pos++;
            if (!isDigit(peek())) {
                throw new JsonException("bad number", start);
            }
            while (isDigit(peek())) {
                pos++;
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            integral = false;
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            if (!isDigit(peek())) {
                throw new JsonException("bad number", start);
            }
            while (isDigit(peek())) {
                pos++;
            }
        }
        String t = s.substring(start, pos);
        if (integral) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException tooBig) {
                // falls through to double
            }
        }
        double d = Double.parseDouble(t);
        if (Double.isInfinite(d)) {
            throw new JsonException("number out of range", start);
        }
        return d;
    }

    private void literal(String word) {
        if (!s.startsWith(word, pos)) {
            throw new JsonException("unexpected token", pos);
        }
        pos += word.length();
    }

    private void expect(char c) {
        if (peek() != c) {
            throw new JsonException("expected '" + c + "'", pos);
        }
        pos++;
    }

    private char peek() {
        return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private void skipWs() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(v, b);
        return b.toString();
    }

    public static void write(Object v, StringBuilder b) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String str) {
            writeString(str, b);
        } else if (v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            b.append(((Number) v).longValue());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new JsonException("cannot write " + d + " as JSON", -1);
            }
            b.append(Double.toString(d));
        } else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                writeString(String.valueOf(e.getKey()), b);
                b.append(':');
                write(e.getValue(), b);
            }
            b.append('}');
        } else if (v instanceof Collection<?> c) {
            b.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                write(o, b);
            }
            b.append(']');
        } else {
            throw new JsonException("cannot write " + v.getClass().getName() + " as JSON", -1);
        }
    }

    private static void writeString(String str, StringBuilder b) {
        b.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '<': b.append("\\u003c"); break;
                case '>': b.append("\\u003e"); break;
                case '&': b.append("\\u0026"); break;
                case '\u2028': b.append("\\u2028"); break;
                case '\u2029': b.append("\\u2029"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }
}
