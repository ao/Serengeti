package com.ataiva.serengeti.core.query;

import java.util.Objects;

/**
 * A LIKE pattern: {@code %} matches any run of characters (also none), {@code _} exactly one UTF-16 code unit,
 * and {@code \%}, {@code \_} and {@code \\} match those characters. Matching is case-sensitive. Two patterns
 * are equal when they are written the same.
 */
public final class LikePattern {
    private static final int ANY_RUN = -1;
    private static final int ANY_ONE = -2;

    private final String text;
    /** One entry per pattern element: a code unit (0 to 0xFFFF), {@link #ANY_RUN} or {@link #ANY_ONE}. */
    private final int[] units;
    private final String prefix;
    private final boolean exact;

    /**
     * @throws IllegalArgumentException if a {@code \} is not followed by {@code %}, {@code _} or {@code \}
     */
    public LikePattern(String text) {
        this.text = Objects.requireNonNull(text, "text");
        int bad = badEscape(text);
        if (bad >= 0) {
            throw new IllegalArgumentException("bad escape at " + bad + " in LIKE pattern");
        }
        int[] u = new int[text.length()];
        int n = 0;
        int literal = -1; // length of the literal prefix, once a wildcard is seen
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                c = text.charAt(++i);
                u[n++] = c;
                if (literal < 0) {
                    p.append(c);
                }
            } else if (c == '%' || c == '_') {
                u[n++] = c == '%' ? ANY_RUN : ANY_ONE;
                if (literal < 0) {
                    literal = p.length();
                }
            } else {
                u[n++] = c;
                if (literal < 0) {
                    p.append(c);
                }
            }
        }
        this.units = java.util.Arrays.copyOf(u, n);
        this.prefix = p.toString();
        this.exact = literal < 0;
    }

    /** The offset of the first {@code \} that does not start {@code \%}, {@code \_} or {@code \\}, or -1. */
    public static int badEscape(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\\') {
                char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
                if (next != '%' && next != '_' && next != '\\') {
                    return i;
                }
                i++;
            }
        }
        return -1;
    }

    /** The pattern as written, escapes included. */
    public String text() {
        return text;
    }

    /** The characters before the first wildcard, escapes resolved; all of them when {@link #exact()}. */
    public String prefix() {
        return prefix;
    }

    /** True when the pattern has no wildcard, so it matches {@link #prefix()} only. */
    public boolean exact() {
        return exact;
    }

    public boolean matches(String s) {
        int[] u = units;
        int p = 0;
        int i = 0;
        int star = -1; // the last % seen, and where its run started in s
        int mark = 0;
        while (i < s.length()) {
            if (p < u.length && (u[p] == ANY_ONE || u[p] == s.charAt(i))) {
                p++;
                i++;
            } else if (p < u.length && u[p] == ANY_RUN) {
                star = p++;
                mark = i;
            } else if (star >= 0) {
                p = star + 1;
                i = ++mark;
            } else {
                return false;
            }
        }
        while (p < u.length && u[p] == ANY_RUN) {
            p++;
        }
        return p == u.length;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LikePattern other && other.text.equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
