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

    private static int rank(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Boolean) {
            return 1;
        }
        if (v instanceof Number) {
            return 2;
        }
        if (v instanceof String) {
            return 3;
        }
        return 4;
    }

    /** Total order for ORDER BY: null, then booleans, numbers, strings, then anything else (all equal). */
    static int sortOrder(Object a, Object b) {
        int ra = rank(a);
        int rb = rank(b);
        if (ra != rb) {
            return Integer.compare(ra, rb);
        }
        if (ra == 0 || ra == 4) {
            return 0;
        }
        return order(a, b);
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
