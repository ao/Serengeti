package com.ataiva.serengeti.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The build version, read from a resource that Maven fills in at build time. */
public final class Version {
    private static final String CURRENT = load();

    private Version() {}

    public static String current() {
        return CURRENT;
    }

    private static String load() {
        try (InputStream in = Version.class.getResourceAsStream("/serengeti-version.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version", "unknown");
            return v.startsWith("${") ? "unknown" : v;
        } catch (IOException e) {
            return "unknown";
        }
    }
}
