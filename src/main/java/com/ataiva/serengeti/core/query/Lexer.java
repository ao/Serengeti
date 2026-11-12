package com.ataiva.serengeti.core.query;

import java.util.Locale;
import java.util.Set;

/** Splits query text into tokens, one at a time. */
final class Lexer {
    static final int MAX_NAME_LENGTH = 63;

    private static final Set<String> KEYWORDS = Set.of(
            "and", "asc", "by", "count", "create", "database", "databases", "delete", "desc",
            "drop", "exists", "false", "from", "if", "in", "insert", "into", "is", "limit", "not",
            "null", "or", "order", "select", "set", "show", "table", "tables", "true", "update",
            "values", "where");

    private final String src;
    private int pos;

    Lexer(String src) {
        this.src = src;
    }

    static boolean isKeyword(String lowercase) {
        return KEYWORDS.contains(lowercase);
    }

    /** True if {@code s} is a name as the parser would produce it: lowercase, not a keyword. */
    static boolean isValidName(String s) {
        if (s.isEmpty() || s.length() > MAX_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || c == '_' || (i > 0 && c >= '0' && c <= '9');
            if (!ok) {
                return false;
            }
        }
        return !KEYWORDS.contains(s);
    }

    /** The source text of a token. */
    String text(Token t) {
        return src.substring(t.start(), t.end());
    }

    Token next() {
        skipBlanks();
        int start = pos;
        if (start >= src.length()) {
            return new Token(Token.Kind.END, "", null, start, start);
        }
        char c = src.charAt(start);
        if (isNameStart(c)) {
            return name(start);
        }
        if (isDigit(c) || (c == '-' && isDigit(charAt(start + 1)))) {
            return number(start);
        }
        if (c == '\'') {
            return string(start);
        }
        return symbol(start, c);
    }

    private void skipBlanks() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                pos++;
            } else if (c == '-' && charAt(pos + 1) == '-') {
                pos += 2;
                while (pos < src.length() && src.charAt(pos) != '\n' && src.charAt(pos) != '\r') {
                    pos++;
                }
            } else {
                return;
            }
        }
    }

    private Token name(int start) {
        int p = start + 1;
        while (isNamePart(charAt(p))) {
            p++;
        }
        int length = p - start;
        if (length > MAX_NAME_LENGTH) {
            throw new ParseException("expected a name of at most " + MAX_NAME_LENGTH
                    + " characters but found one of " + length, start);
        }
        pos = p;
        String lower = src.substring(start, p).toLowerCase(Locale.ROOT);
        Token.Kind kind = KEYWORDS.contains(lower) ? Token.Kind.KEYWORD : Token.Kind.NAME;
        return new Token(kind, lower, null, start, p);
    }

    private Token number(int start) {
        int p = start;
        if (src.charAt(p) == '-') {
            p++;
        }
        p = digits(p);
        boolean integral = true;
        if (charAt(p) == '.') {
            if (!isDigit(charAt(p + 1))) {
                throw malformedNumber(start);
            }
            p = digits(p + 1);
            integral = false;
        }
        if (charAt(p) == 'e' || charAt(p) == 'E') {
            int q = p + 1;
            if (charAt(q) == '+' || charAt(q) == '-') {
                q++;
            }
            if (!isDigit(charAt(q))) {
                throw malformedNumber(start);
            }
            p = digits(q);
            integral = false;
        }
        if (isNamePart(charAt(p)) || charAt(p) == '.') {
            throw malformedNumber(start);
        }
        String text = src.substring(start, p);
        Object value = null;
        if (integral) {
            try {
                value = Long.parseLong(text);
            } catch (NumberFormatException tooBig) {
                // falls through to a double
            }
        }
        if (value == null) {
            double d = Double.parseDouble(text);
            if (!Double.isFinite(d)) {
                throw new ParseException("number out of range '" + shorten(text) + "'", start);
            }
            value = d;
        }
        pos = p;
        return new Token(Token.Kind.NUMBER, text, value, start, p);
    }

    private ParseException malformedNumber(int start) {
        int p = start + 1;
        while (isNamePart(charAt(p)) || charAt(p) == '.' || charAt(p) == '-' || charAt(p) == '+') {
            p++;
        }
        return new ParseException("malformed number '" + shorten(src.substring(start, p)) + "'", start);
    }

    private Token string(int start) {
        StringBuilder value = new StringBuilder();
        int p = start + 1;
        while (true) {
            int q = src.indexOf('\'', p);
            if (q < 0) {
                throw new ParseException("unterminated string", start);
            }
            value.append(src, p, q);
            if (charAt(q + 1) == '\'') {
                value.append('\'');
                p = q + 2;
            } else {
                pos = q + 1;
                return new Token(Token.Kind.STRING, null, value.toString(), start, pos);
            }
        }
    }

    private Token symbol(int start, char c) {
        char n = charAt(start + 1);
        String text = switch (c) {
            case '(', ')', ',', '.', ';', '*', '=' -> String.valueOf(c);
            case '<' -> n == '=' ? "<=" : n == '>' ? "<>" : "<";
            case '>' -> n == '=' ? ">=" : ">";
            case '!' -> {
                if (n == '=') {
                    yield "!=";
                }
                throw unexpected(start);
            }
            default -> throw unexpected(start);
        };
        pos = start + text.length();
        return new Token(Token.Kind.SYMBOL, text, null, start, pos);
    }

    private ParseException unexpected(int start) {
        int cp = src.codePointAt(start);
        String shown = cp > ' ' && cp < 0x7f ? "'" + (char) cp + "'" : String.format("U+%04X", cp);
        return new ParseException("unexpected character " + shown, start);
    }

    private int digits(int p) {
        while (isDigit(charAt(p))) {
            p++;
        }
        return p;
    }

    private char charAt(int i) {
        return i < src.length() ? src.charAt(i) : '\0';
    }

    static String shorten(String s) {
        return s.length() <= 32 ? s : s.substring(0, 32) + "...";
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isNameStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isNamePart(char c) {
        return isNameStart(c) || isDigit(c);
    }
}
