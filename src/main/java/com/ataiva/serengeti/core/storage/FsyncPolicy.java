package com.ataiva.serengeti.core.storage;

import java.util.Locale;

/** When the write-ahead log is forced to disk. */
public enum FsyncPolicy {
    /** Before every write is acknowledged. Survives power loss. */
    ALWAYS,
    /** Once a second. A crash of the machine (not just the process) can lose the last second. */
    INTERVAL,
    /** Never; the operating system decides. A machine crash can lose any unflushed writes. */
    OFF;

    public static FsyncPolicy parse(String s) {
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("fsync policy must be always, interval or off, not '" + s + "'");
        }
    }
}
