package com.ataiva.serengeti;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.XMLFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogFormatTest {
    private String oldPattern;
    private TimeZone oldZone;
    private Locale oldLocale;

    @BeforeEach
    void setUp() {
        oldPattern = System.getProperty(LogFormat.PROPERTY);
        oldZone = TimeZone.getDefault();
        oldLocale = Locale.getDefault(Locale.Category.FORMAT);
        System.setProperty(LogFormat.PROPERTY, LogFormat.PATTERN);
    }

    @AfterEach
    void tearDown() {
        if (oldPattern == null) {
            System.clearProperty(LogFormat.PROPERTY);
        } else {
            System.setProperty(LogFormat.PROPERTY, oldPattern);
        }
        TimeZone.setDefault(oldZone);
        Locale.setDefault(Locale.Category.FORMAT, oldLocale);
    }

    private static final class Notice extends Level {
        private static final long serialVersionUID = 1L;

        Notice() {
            super("NOTICE", 850);
        }
    }

    private static List<LogRecord> records() {
        Random random = new Random(11);
        List<Instant> instants = new ArrayList<>(List.of(Instant.EPOCH, Instant.ofEpochMilli(1), Instant.ofEpochSecond(0, 999_999),
                Instant.parse("1937-06-30T12:00:00.123Z"), Instant.parse("1971-12-31T23:59:59.999Z"),
                Instant.parse("2026-03-08T10:00:00.5Z"), Instant.parse("2026-11-01T08:30:00Z"),
                Instant.parse("9999-12-31T23:59:59.999Z"), Instant.parse("+10000-01-01T00:00:00Z"),
                Instant.parse("0001-01-01T00:00:00Z"), Instant.parse("-0001-06-01T00:00:00Z")));
        for (int i = 0; i < 300; i++) {
            instants.add(Instant.ofEpochSecond(Math.floorMod(random.nextLong(), 13_000_000_000L) - 2_000_000_000L,
                    random.nextInt(1_000_000_000)));
        }
        Level[] levels = {Level.SEVERE, Level.WARNING, Level.INFO, Level.CONFIG, Level.FINE, Level.FINEST, new Notice()};
        String[] names = {"com.ataiva.serengeti.core.server.HttpApi", "", null};
        String[] messages = {"request method=GET route=/health status=200 duration_ms=0.512 remote=10.0.0.1 user=-",
            "", null, "100% {0} and {1}", "line one\nline two", "café 中文 😀 %n %s"};
        List<LogRecord> out = new ArrayList<>();
        int k = 0;
        for (Instant at : instants) {
            for (String m : messages) {
                LogRecord r = new LogRecord(levels[k % levels.length], m);
                r.setInstant(at);
                r.setLoggerName(names[k % names.length]);
                if (m != null && m.contains("{0}") && k % 2 == 0) {
                    r.setParameters(new Object[] {"first", 2});
                }
                if (k % 97 == 0) {
                    r.setThrown(new IllegalStateException("broken"));
                }
                out.add(r);
                k++;
            }
        }
        return out;
    }

    private static void assertSameText(Formatter expected, Formatter actual) {
        for (LogRecord r : records()) {
            assertEquals(expected.format(r), actual.format(r), () -> "record at " + r.getInstant() + ": " + r.getMessage());
        }
    }

    @Test
    void writesWhatSimpleFormatterWritesInEveryZone() {
        for (String zone : List.of("UTC", "Asia/Kolkata", "America/St_Johns", "Pacific/Chatham", "America/Los_Angeles",
                "Europe/Amsterdam", "Africa/Monrovia", "Pacific/Kiritimati", "Etc/GMT+12")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            assertSameText(new SimpleFormatter(), new LogFormat());
        }
    }

    @Test
    void writesWhatSimpleFormatterWritesInOtherLocales() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        for (String tag : List.of("en-US", "de-DE", "fr-FR", "ar-EG", "th-TH-u-nu-thai", "hi-IN-u-nu-deva", "ja-JP")) {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(tag));
            assertSameText(new SimpleFormatter(), new LogFormat());
        }
    }

    @Test
    void replacesOnlyAPlainSimpleFormatterOnAConsoleHandler() {
        Logger logger = Logger.getAnonymousLogger();
        ConsoleHandler plain = new ConsoleHandler();
        plain.setFormatter(new SimpleFormatter());
        ConsoleHandler xml = new ConsoleHandler();
        XMLFormatter x = new XMLFormatter();
        xml.setFormatter(x);
        logger.addHandler(plain);
        logger.addHandler(xml);
        try {
            System.setProperty(LogFormat.PROPERTY, "%5$s%n");
            LogFormat.install(logger);
            assertEquals(SimpleFormatter.class, plain.getFormatter().getClass(), "another pattern: left alone");
            System.setProperty(LogFormat.PROPERTY, LogFormat.PATTERN);
            LogFormat.install(logger);
            assertTrue(plain.getFormatter() instanceof LogFormat);
            assertSame(x, xml.getFormatter());
        } finally {
            logger.removeHandler(plain);
            logger.removeHandler(xml);
            plain.close();
            xml.close();
        }
    }
}
