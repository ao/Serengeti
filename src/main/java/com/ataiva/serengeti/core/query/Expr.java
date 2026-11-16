package com.ataiva.serengeti.core.query;

import java.util.List;
import java.util.Objects;

/**
 * A WHERE or HAVING condition. In WHERE, columns are names as the lexer produces them; in HAVING, they are the
 * labels of GROUP BY columns and aggregates ({@link Statement.Item#label}). Values are literals.
 */
public sealed interface Expr {
    /** Gives {@code visitor} the column of each term of {@code e}, in the order written. */
    static void forEachColumn(Expr e, java.util.function.Consumer<String> visitor) {
        if (e instanceof Compare c) {
            visitor.accept(c.column());
        } else if (e instanceof In in) {
            visitor.accept(in.column());
        } else if (e instanceof Like l) {
            visitor.accept(l.column());
        } else if (e instanceof IsNull n) {
            visitor.accept(n.column());
        } else if (e instanceof Not n) {
            forEachColumn(n.inner(), visitor);
        } else if (e instanceof And a) {
            a.terms().forEach(t -> forEachColumn(t, visitor));
        } else if (e instanceof Or o) {
            o.terms().forEach(t -> forEachColumn(t, visitor));
        }
    }

    /** Comparison operators. {@code <>} parses to {@link #NE}. */
    enum Cmp {
        EQ("="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">=");

        private final String symbol;

        Cmp(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    /** {@code column op value}. */
    record Compare(String column, Cmp op, Object value) implements Expr {
        public Compare {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(op, "op");
            Values.requireLiteral(value);
        }
    }

    /**
     * {@code column IN (v1, v2, ...)}: exactly {@code column = v1 OR column = v2 ...}; with {@code negated},
     * {@code NOT (column = v1 OR ...)}. Holds 1 to {@link #MAX_VALUES} literals.
     */
    record In(String column, List<Object> values, boolean negated) implements Expr {
        public static final int MAX_VALUES = 1000;

        public In {
            Objects.requireNonNull(column, "column");
            values = new Members(values);
        }

        /** {@code column IN (...)} before {@code negated} is applied: three-valued. */
        Boolean holds(Object v) {
            return ((Members) values).holds(v);
        }
    }

    /**
     * The values of an IN list, unchangeable, with the keys of its scalars ({@link Values#key}) in a hash
     * set so that a row is tested in one lookup, which gives what comparing with each value would.
     */
    final class Members extends java.util.AbstractList<Object> implements java.util.RandomAccess {
        private final Object[] values;
        private final java.util.Set<Object> keys = new java.util.HashSet<>();
        private final boolean hasNull;

        Members(List<Object> values) {
            this.values = values.toArray();
            if (this.values.length == 0 || this.values.length > In.MAX_VALUES) {
                throw new IllegalArgumentException("IN takes 1 to " + In.MAX_VALUES + " values");
            }
            boolean nulls = false;
            for (Object v : this.values) {
                Values.requireLiteral(v);
                if (v == null) {
                    nulls = true;
                } else {
                    keys.add(Values.key(v));
                }
            }
            this.hasNull = nulls;
        }

        @Override
        public Object get(int i) {
            return values[i];
        }

        @Override
        public int size() {
            return values.length;
        }

        /** Null (unknown) for a null value; true if some list value equals it; else unknown if the list has a null. */
        Boolean holds(Object v) {
            if (v == null) {
                return null;
            }
            if ((v instanceof String || v instanceof Number || v instanceof Boolean) && keys.contains(Values.key(v))) {
                return Boolean.TRUE;
            }
            return hasNull ? null : Boolean.FALSE;
        }
    }

    /** {@code column LIKE pattern}, or {@code column NOT LIKE pattern} when {@code negated}. */
    record Like(String column, LikePattern pattern, boolean negated) implements Expr {
        public Like {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(pattern, "pattern");
        }

        public Like(String column, String pattern, boolean negated) {
            this(column, new LikePattern(pattern), negated);
        }

        /** Before {@code negated}: unknown for null, false for a value that is not a string. */
        Boolean holds(Object v) {
            if (v == null) {
                return null;
            }
            return v instanceof String s && pattern.matches(s);
        }
    }

    /** {@code column IS NULL}, or {@code column IS NOT NULL} when {@code negated}. */
    record IsNull(String column, boolean negated) implements Expr {
        public IsNull {
            Objects.requireNonNull(column, "column");
        }
    }

    /** Two or more terms joined by AND. A chain {@code a AND b AND c} is one node with three terms. */
    record And(List<Expr> terms) implements Expr {
        public And {
            terms = List.copyOf(terms);
            if (terms.size() < 2) {
                throw new IllegalArgumentException("AND needs at least two terms");
            }
        }
    }

    /** Two or more terms joined by OR. */
    record Or(List<Expr> terms) implements Expr {
        public Or {
            terms = List.copyOf(terms);
            if (terms.size() < 2) {
                throw new IllegalArgumentException("OR needs at least two terms");
            }
        }
    }

    record Not(Expr inner) implements Expr {
        public Not {
            Objects.requireNonNull(inner, "inner");
        }
    }
}
