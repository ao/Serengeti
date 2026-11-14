package com.ataiva.serengeti.core.storage;

import java.math.BigDecimal;

/**
 * How the query language orders values (docs/QUERY.md, "Comparisons"), shared by the query layer and the
 * indexes so the two cannot disagree. Numbers compare by exact numeric value across integers and doubles,
 * strings by UTF-16 code unit, booleans false before true; values of different types are not comparable.
 */
public final class ScalarOrder {
    private ScalarOrder() {}

    /** The order of two non-null values of comparable types (-1, 0 or 1), or null if their types differ. */
    public static Integer order(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return compareNumbers(x, y);
        }
        if (a instanceof String x && b instanceof String y) {
            return Integer.signum(x.compareTo(y));
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            return Boolean.compare(x, y);
        }
        return null;
    }

    /** True for the values an index holds and a comparison can match: booleans, numbers and strings. */
    public static boolean scalar(Object v) {
        return v instanceof Boolean || v instanceof Number || v instanceof String;
    }

    static boolean integral(Number n) {
        return n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte;
    }

    public static int compareNumbers(Number x, Number y) {
        if (integral(x) && integral(y)) {
            return Long.compare(x.longValue(), y.longValue());
        }
        double dx = x.doubleValue();
        double dy = y.doubleValue();
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) {
            return Double.compare(dx, dy);
        }
        if (!integral(x) && !integral(y)) {
            return dx < dy ? -1 : dx > dy ? 1 : 0;
        }
        // One long and one double: compare exactly, not after rounding the long to a double.
        BigDecimal bx = integral(x) ? BigDecimal.valueOf(x.longValue()) : new BigDecimal(dx);
        BigDecimal by = integral(y) ? BigDecimal.valueOf(y.longValue()) : new BigDecimal(dy);
        return bx.compareTo(by);
    }
}
