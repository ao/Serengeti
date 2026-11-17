package com.ataiva.serengeti;

import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * The node's log line, {@link #PATTERN}, exactly as {@link SimpleFormatter} writes it, without parsing the
 * pattern again for every record. The console handler formats inside its lock, which every request's log
 * line passes through, so the time spent there bounds the rate of requests; parsing the pattern was 30% of
 * it. A record with a stack trace, or a default locale whose digits are not ASCII, goes to SimpleFormatter.
 */
final class LogFormat extends SimpleFormatter {
    /** Time with milliseconds and zone offset, level, logger, message, and the stack trace if there is one. */
    static final String PATTERN = "%1$tY-%1$tm-%1$tdT%1$tH:%1$tM:%1$tS.%1$tL%1$tz %4$s %3$s: %5$s%6$s%n";
    static final String PROPERTY = "java.util.logging.SimpleFormatter.format";

    private record Digits(Locale locale, boolean ascii) {}

    private volatile Digits digits = new Digits(Locale.ROOT, true);

    /**
     * Gives the console handlers of {@code logger} that use a plain SimpleFormatter this format instead, when
     * the SimpleFormatter pattern is {@link #PATTERN}; a formatter configured otherwise is left alone.
     */
    static void install(Logger logger) {
        if (!PATTERN.equals(System.getProperty(PROPERTY))) {
            return;
        }
        for (Handler h : logger.getHandlers()) {
            if (h instanceof ConsoleHandler && h.getFormatter() != null && h.getFormatter().getClass() == SimpleFormatter.class) {
                h.setFormatter(new LogFormat());
            }
        }
    }

    @Override
    public String format(LogRecord r) {
        Locale locale = Locale.getDefault(Locale.Category.FORMAT);
        Digits d = digits;
        if (!d.locale().equals(locale)) {
            d = new Digits(locale, DecimalFormatSymbols.getInstance(locale).getZeroDigit() == '0');
            digits = d;
        }
        if (!d.ascii() || r.getThrown() != null) {
            return super.format(r);
        }
        ZonedDateTime t = ZonedDateTime.ofInstant(r.getInstant(), ZoneId.systemDefault());
        String message = String.valueOf(formatMessage(r));
        StringBuilder b = new StringBuilder(64 + message.length());
        digits(b, t.get(ChronoField.YEAR_OF_ERA), 4).append('-');
        digits(b, t.getMonthValue(), 2).append('-');
        digits(b, t.getDayOfMonth(), 2).append('T');
        digits(b, t.getHour(), 2).append(':');
        digits(b, t.getMinute(), 2).append(':');
        digits(b, t.getSecond(), 2).append('.');
        digits(b, t.get(ChronoField.MILLI_OF_SECOND), 3);
        // %tz: the offset as +HHMM; seconds of an offset are dropped.
        int offset = t.getOffset().getTotalSeconds();
        b.append(offset < 0 ? '-' : '+');
        int minutes = Math.abs(offset) / 60;
        digits(b, minutes / 60 * 100 + minutes % 60, 4);
        b.append(' ').append(r.getLevel().getLocalizedName()).append(' ').append(r.getLoggerName())
                .append(": ").append(message).append(System.lineSeparator());
        return b.toString();
    }

    /** {@code v} (not negative) with zeros in front to {@code width} digits. */
    private static StringBuilder digits(StringBuilder b, int v, int width) {
        String s = Integer.toString(v);
        for (int i = s.length(); i < width; i++) {
            b.append('0');
        }
        return b.append(s);
    }
}
