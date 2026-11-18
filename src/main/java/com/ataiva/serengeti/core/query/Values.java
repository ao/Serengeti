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
     * A value an INSERT or a SET may write: a literal, or an object (string keys) or array of such values,
     * nested at most {@link Parser#MAX_VALUE_DEPTH} deep.
     */
    static void requireStored(Object v) {
        requireStored(v, 0);
    }

    private static void requireStored(Object v, int depth) {
        if (v instanceof java.util.Map<?, ?> m) {
            requireDepth(depth);
            for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String)) {
                    throw new IllegalArgumentException("an object's keys are strings");
                }
                requireStored(e.getValue(), depth + 1);
            }
        } else if (v instanceof java.util.List<?> l) {
            requireDepth(depth);
            for (Object o : l) {
                requireStored(o, depth + 1);
            }
        } else {
            requireLiteral(v);
        }
    }

    private static void requireDepth(int depth) {
        if (depth >= Parser.MAX_VALUE_DEPTH) {
            throw new IllegalArgumentException("a value nested more than " + Parser.MAX_VALUE_DEPTH + " deep");
        }
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

    /** True for the integers of a row ({@link Long}; and the smaller boxes, which rows built in Java may hold). */
    static boolean isInteger(Object v) {
        return v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte;
    }

    static boolean isNumber(Object v) {
        return isInteger(v) || v instanceof Double || v instanceof Float;
    }

    /** What a value that is not a number is, for a message: "a string", "an array". */
    static String kind(Object v) {
        if (v instanceof String) {
            return "a string";
        }
        if (v instanceof Boolean) {
            return "a boolean";
        }
        if (v instanceof java.util.Map<?, ?>) {
            return "an object";
        }
        if (v instanceof java.util.Collection<?>) {
            return "an array";
        }
        return "not a number";
    }

    /** Integers of at most this size convert to a double exactly. */
    private static final long EXACT_IN_DOUBLE = 1L << 53;

    /**
     * {@code a + b}, or {@code a - b} with {@code minus}, for two numbers ({@link #isNumber}). Two integers give
     * a {@link Long}, exactly. With a double on either side the result is the double nearest the exact result:
     * one rounding, also for an integer beyond 2^53, which a conversion to double would round first; a zero
     * result has the sign double arithmetic gives it.
     *
     * @throws ArithmeticException {@code overflows a 64-bit integer} or {@code overflows a double}
     */
    static Object add(Object a, boolean minus, Object b) {
        if (isInteger(a) && isInteger(b)) {
            long x = ((Number) a).longValue();
            long y = ((Number) b).longValue();
            try {
                return minus ? Math.subtractExact(x, y) : Math.addExact(x, y);
            } catch (ArithmeticException overflow) {
                throw new ArithmeticException("overflows a 64-bit integer");
            }
        }
        double x = ((Number) a).doubleValue();
        double y = ((Number) b).doubleValue();
        double r = minus ? x - y : x + y;
        if ((isInteger(a) && !exactInDouble(a)) || (isInteger(b) && !exactInDouble(b))) {
            java.math.BigDecimal e = exact(a);
            e = minus ? e.subtract(exact(b)) : e.add(exact(b));
            // A zero is exact either way, and double arithmetic gives it its sign.
            if (e.signum() != 0) {
                r = e.doubleValue();
            }
        }
        if (!Double.isFinite(r)) {
            throw new ArithmeticException("overflows a double");
        }
        return r;
    }

    private static boolean exactInDouble(Object integer) {
        long v = ((Number) integer).longValue();
        return v >= -EXACT_IN_DOUBLE && v <= EXACT_IN_DOUBLE;
    }

    private static java.math.BigDecimal exact(Object n) {
        return isInteger(n) ? java.math.BigDecimal.valueOf(((Number) n).longValue())
                : new java.math.BigDecimal(((Number) n).doubleValue());
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
        if (v instanceof java.util.Map<?, ?> m) {
            StringBuilder b = new StringBuilder("{");
            for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                if (b.length() > 1) {
                    b.append(", ");
                }
                b.append(format(String.valueOf(e.getKey()))).append(": ").append(format(e.getValue()));
            }
            return b.append('}').toString();
        }
        if (v instanceof java.util.List<?> l) {
            StringBuilder b = new StringBuilder("[");
            for (Object o : l) {
                if (b.length() > 1) {
                    b.append(", ");
                }
                b.append(format(o));
            }
            return b.append(']').toString();
        }
        throw new IllegalArgumentException("not a literal: " + v.getClass().getName());
    }
}
