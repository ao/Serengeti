package com.ataiva.serengeti;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.Test;

/**
 * SimpleFormatter prints Level.getLocalizedLevelName() (package-private, final), not Level.getLocalizedName()
 * (public, overridable). They differ for a Level subclass that overrides the latter, which LogFormat leaves to
 * SimpleFormatter (from review 22g; the node defines no such level).
 */
class LogFormatLevelTest {
    private static final class Renamed extends Level {
        private static final long serialVersionUID = 1L;

        Renamed() {
            super("NOTICE", 850);
        }

        @Override
        public String getLocalizedName() {
            return "Notice";
        }
    }

    @Test
    void aLevelThatOverridesItsLocalizedName() {
        String old = System.getProperty(LogFormat.PROPERTY);
        System.setProperty(LogFormat.PROPERTY, LogFormat.PATTERN);
        try {
            LogRecord r = new LogRecord(new Renamed(), "hello");
            r.setInstant(Instant.EPOCH);
            r.setLoggerName("x");
            assertEquals(new SimpleFormatter().format(r), new LogFormat().format(r));
        } finally {
            if (old == null) {
                System.clearProperty(LogFormat.PROPERTY);
            } else {
                System.setProperty(LogFormat.PROPERTY, old);
            }
        }
    }
}
