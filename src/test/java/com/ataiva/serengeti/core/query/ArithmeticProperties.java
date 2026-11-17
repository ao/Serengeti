package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * {@code column + number} against a model in exact decimal arithmetic: two integers give the exact integer or
 * fail, anything with a double gives the double nearest the exact result (zero with the sign of double
 * arithmetic) or fails when that is beyond a double.
 */
class ArithmeticProperties {
    private static final BigDecimal MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal MAX = BigDecimal.valueOf(Long.MAX_VALUE);

    @Property(tries = 5000)
    void addIsTheExactResultRoundedOnce(@ForAll("numbers") Object a, @ForAll boolean minus, @ForAll("numbers") Object b) {
        BigDecimal x = exact(a);
        BigDecimal y = exact(b);
        BigDecimal e = minus ? x.subtract(y) : x.add(y);
        if (a instanceof Long && b instanceof Long) {
            if (e.compareTo(MIN) < 0 || e.compareTo(MAX) > 0) {
                assertEquals("overflows a 64-bit integer",
                        assertThrows(ArithmeticException.class, () -> Values.add(a, minus, b)).getMessage());
            } else {
                assertEquals(e.longValueExact(), Values.add(a, minus, b));
            }
            return;
        }
        double want;
        if (e.signum() == 0) {
            // Both sides are then exact doubles, and double arithmetic says which zero.
            double dx = ((Number) a).doubleValue();
            double dy = ((Number) b).doubleValue();
            want = minus ? dx - dy : dx + dy;
        } else {
            want = Double.parseDouble(e.toString());
        }
        if (Double.isInfinite(want)) {
            assertEquals("overflows a double",
                    assertThrows(ArithmeticException.class, () -> Values.add(a, minus, b)).getMessage());
        } else {
            assertEquals(want, Values.add(a, minus, b), a + (minus ? " - " : " + ") + b);
        }
    }

    private static BigDecimal exact(Object n) {
        return n instanceof Long l ? BigDecimal.valueOf(l) : new BigDecimal((Double) n);
    }

    @Provide
    Arbitrary<Object> numbers() {
        long p53 = 1L << 53;
        Arbitrary<Object> longs = Arbitraries.longs().map(Object.class::cast);
        Arbitrary<Object> small = Arbitraries.longs().between(-5, 5).map(Object.class::cast);
        Arbitrary<Object> nearEdges = Arbitraries.of(Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1,
                p53, p53 + 1, p53 - 1, p53 + 2, p53 + 3, -p53, -p53 - 1, -p53 + 1, 1L << 60, (1L << 60) + 1)
                .map(Object.class::cast);
        Arbitrary<Object> wide = Arbitraries.longs().between(-(1L << 62), 1L << 62).map(Object.class::cast);
        Arbitrary<Object> bits = Arbitraries.longs().map(Double::longBitsToDouble).filter(Double::isFinite)
                .map(Object.class::cast);
        Arbitrary<Object> doubles = Arbitraries.doubles().between(-1e20, 1e20).map(Object.class::cast);
        Arbitrary<Object> halves = Arbitraries.longs().between(-(1L << 55), 1L << 55).map(l -> l / 2.0)
                .map(Object.class::cast);
        Arbitrary<Object> special = Arbitraries.of(0.0, -0.0, 0.5, -0.5, 1.0, Double.MAX_VALUE, -Double.MAX_VALUE,
                Double.MIN_VALUE, -Double.MIN_VALUE, 0x1p53, 0x1p63, -0x1p63, 0x1p63 + 2048, 1e308, -1e308, 0.1)
                .map(Object.class::cast);
        return Arbitraries.oneOf(longs, small, nearEdges, wide, bits, doubles, halves, special);
    }
}
