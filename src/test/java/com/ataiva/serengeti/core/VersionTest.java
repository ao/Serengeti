package com.ataiva.serengeti.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionTest {
    @Test
    void versionComesFromTheBuild() {
        String v = Version.current();
        assertTrue(v.matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?"), "unexpected version: " + v);
    }
}
