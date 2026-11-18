package com.ataiva.serengeti;

import com.ataiva.serengeti.core.json.Json;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CSV as RFC 4180 writes it, for {@code serengeti import --csv} and {@code export --csv}. A field may be quoted
 * ({@code "a, ""b"""}), and a quoted field may hold commas, quotes and line breaks. The reader keeps whether
 * each field was quoted, which the type rule reads (docs/IMPORT.md):
 *
 * <ul>
 *   <li>an empty unquoted field leaves the column out of the row (it reads as null); {@code ""} is the empty
 *       string;
 *   <li>a quoted field is a string;
 *   <li>any other field is a string, or with {@code --csv-types}: {@code true} or {@code false} (in any case) a
 *       boolean, a JSON number a number, NaN and Infinity refused.
 * </ul>
 *
 * <p>The writer quotes every string and writes numbers and booleans bare, so a file it wrote reads back with
 * {@code --csv-types} as the same values; objects and arrays are written as their JSON text, which reads back
 * as a string.
 */
final class Csv {
    private Csv() {}

    record Field(String text, boolean quoted) {}

    /** One record and the line it starts on (1 for the first line of the input). */
    record Record(long line, List<Field> fields) {}

    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    private static final Pattern NOT_A_NUMBER = Pattern.compile("[+-]?(nan|inf|infinity)", Pattern.CASE_INSENSITIVE);

    /** Reads records from text, one at a time. Lines with nothing on them are skipped. */
    static final class Reader {
        private final Chars in;
        private final int maxChars;
        private long line = 1;

        Reader(Chars in, int maxChars) {
            this.in = in;
            this.maxChars = maxChars;
        }

        /** The next record, or null at the end of the input. */
        Record next() throws IOException, Import.BadLine {
            for (;;) {
                int c = in.peek();
                if (c < 0) {
                    return null;
                }
                if (c == '\n' || c == '\r') {
                    endOfLine();
                } else {
                    break;
                }
            }
            long start = line;
            List<Field> fields = new ArrayList<>();
            int chars = 0;
            StringBuilder b = new StringBuilder();
            for (;;) {
                b.setLength(0);
                boolean quoted = in.peek() == '"';
                if (quoted) {
                    in.read();
                    for (;;) {
                        int c = in.read();
                        if (c < 0) {
                            throw new Import.BadLine(start, "a quoted field that starts here is not closed");
                        }
                        if (c == '"') {
                            if (in.peek() != '"') {
                                break;
                            }
                            in.read();
                        } else if (c == '\r') {
                            if (in.peek() == '\n') {
                                b.append('\r');
                                c = in.read();
                            }
                            line++;
                        } else if (c == '\n') {
                            line++;
                        }
                        b.append((char) c);
                        if (++chars > maxChars) {
                            throw tooLong(start);
                        }
                    }
                    int n = in.peek();
                    if (n >= 0 && n != ',' && n != '\n' && n != '\r') {
                        throw new Import.BadLine(line, "text after the closing quote of a field (a quote inside a"
                                + " quoted field is written \"\")");
                    }
                } else {
                    for (int c; (c = in.peek()) >= 0 && c != ',' && c != '\n' && c != '\r'; ) {
                        if (c == '"') {
                            throw new Import.BadLine(line, "a quote inside an unquoted field (quote the whole field,"
                                    + " and write each quote in it as \"\")");
                        }
                        b.append((char) in.read());
                        if (++chars > maxChars) {
                            throw tooLong(start);
                        }
                    }
                }
                fields.add(new Field(b.toString(), quoted));
                int c = in.peek();
                if (c == ',') {
                    in.read();
                    continue;
                }
                if (c >= 0) {
                    endOfLine();
                }
                return new Record(start, fields);
            }
        }

        private void endOfLine() throws IOException {
            if (in.read() == '\r' && in.peek() == '\n') {
                in.read();
            }
            line++;
        }

        private Import.BadLine tooLong(long start) {
            return new Import.BadLine(start, "the record is longer than " + maxChars + " characters");
        }
    }

    /**
     * A field's value by the type rule above, or {@link #ABSENT} for an empty unquoted field. With {@code types}
     * false every field that is there is a string.
     */
    static Object value(Field f, boolean types) throws IllegalArgumentException {
        if (f.quoted()) {
            return f.text();
        }
        String s = f.text();
        if (s.isEmpty()) {
            return ABSENT;
        }
        if (!types) {
            return s;
        }
        if (s.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (s.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        if (NUMBER.matcher(s).matches()) {
            try {
                return Json.parseExact(s);
            } catch (com.ataiva.serengeti.core.json.JsonException e) {
                boolean integral = s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0;
                throw new IllegalArgumentException(Import.shorten(s) + " is "
                        + (integral ? "an integer outside the 64-bit range" : "a number beyond the range of a double"));
            }
        }
        if (NOT_A_NUMBER.matcher(s).matches()) {
            throw new IllegalArgumentException("'" + s + "' is not a number a JSON value can hold");
        }
        return s;
    }

    /** What {@link #value} gives for an empty unquoted field. */
    static final Object ABSENT = new Object();

    /** One value as a CSV field: strings quoted, numbers and booleans bare, null empty, objects and arrays as JSON text. */
    static String field(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof String s) {
            return quote(s);
        }
        if (v instanceof Map<?, ?> || v instanceof List<?>) {
            return quote(Json.write(v));
        }
        return Json.write(v);
    }

    static String quote(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
