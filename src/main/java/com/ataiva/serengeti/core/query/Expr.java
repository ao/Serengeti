package com.ataiva.serengeti.core.query;

import java.util.List;
import java.util.Objects;

/** A WHERE condition. Columns are names as the lexer produces them; values are literals. */
public sealed interface Expr {
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
