package com.ataiva.serengeti.core.query;

import com.ataiva.serengeti.core.storage.ScalarOrder;

/**
 * Value rules shared by the parser, printer and executor. Literals are {@code null}, {@link String},
 * {@link Long}, finite {@link Double} or {@link Boolean}. Stored values may also be other JSON types.
 */
final class Values {
    private Values() {}

    static void requireLiteral(Object v) {
        if (v == null || v instanceof String || v instanceof Long || v instanceof Boolean) {
            return;
        }
        if (v instanceof Double d && Double.isFinite(d)) {
            return;
        }
        throw new IllegalArgumentException("not a literal: " + v.getClass().getName());
    }

    /**
     * {@code a op b} in three-valued logic: null means unknown. Unknown if either side is null; if
     * the two values are of types that cannot be compared, {@code !=} is true and every other
     * operator false.
     */
    static Boolean compare(Object a, Expr.Cmp op, Object b) {
        if (a == null || b == null) {
            return null;
        }
        Integer c = order(a, b);
        if (c == null) {
            return op == Expr.Cmp.NE;
        }
        return switch (op) {
            case EQ -> c == 0;
            case NE -> c != 0;
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            case GE -> c >= 0;
        };
    }

    /** The order of two non-null values of comparable types, or null if their types differ. */
    static Integer order(Object a, Object b) {
        return ScalarOrder.order(a, b);
    }

    /**
     * A value that equals and hashes like another value's key exactly when {@code a = b} is true for two
     * booleans, numbers or strings: a number that is a whole value in the range of a long becomes that
     * {@link Long} (so 1, 1.0 and -0.0 / 0 share a key), any other number a {@link Double}. Null stays
     * null; an object or array becomes its JSON text with keys sorted, wrapped so it equals no scalar.
     */
    static Object key(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Long) {
            return v;
        }
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && d >= -0x1p63 && d < 0x1p63) {
                return (long) d;
            }
            return d;
        }
        return new Other(canonical(v));
    }

    /** An object or array as a group key: compared by its canonical JSON text. */
    record Other(String json) implements Comparable<Other> {
        @Override
        public int compareTo(Other o) {
            return json.compareTo(o.json);
        }
    }

    private static String canonical(Object v) {
        return com.ataiva.serengeti.core.json.Json.write(sortedKeys(v));
    }

    private static Object sortedKeys(Object v) {
        if (v instanceof java.util.Map<?, ?> m) {
            java.util.TreeMap<String, Object> out = new java.util.TreeMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), sortedKeys(x)));
            return out;
        }
        if (v instanceof java.util.Collection<?> c) {
            java.util.List<Object> out = new java.util.ArrayList<>(c.size());
            for (Object x : c) {
                out.add(sortedKeys(x));
            }
            return out;
        }
        return v;
    }

    /**
     * The order of two group keys made by {@link #key}: the ORDER BY order, with objects and arrays (equal
     * there) ordered by their JSON text. Two keys compare 0 only when they are equal.
     */
    static int keyOrder(Object a, Object b) {
        int c = sortOrder(a, b);
        if (c == 0 && a instanceof Other x && b instanceof Other y) {
            return x.compareTo(y);
        }
        return c;
    }

    /** Total order for ORDER BY: null, then booleans, numbers, strings, then anything else (all equal). */
    static int sortOrder(Object a, Object b) {
        return ScalarOrder.sortOrder(a, b);
    }

    /** The literal as query text. */
    static String format(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (v instanceof String s) {
            return "'" + s.replace("'", "''") + "'";
        }
        if (v instanceof Long || v instanceof Double) {
            return v.toString();
        }
        throw new IllegalArgumentException("not a literal: " + v.getClass().getName());
    }
}
