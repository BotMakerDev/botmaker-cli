package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanRoomTest {

    @Test
    void aJarModuleIsProbedAsOneDependency() {
        String pom = CleanRoom.pom(Module.SDK, new Version(1, 8, 0));

        assertTrue(pom.contains("<artifactId>botmaker-sdk</artifactId><version>v1.8.0</version>"), pom);
        assertFalse(pom.contains("<parent>"), pom);
    }

    /** The plugin parent ships no jar: asking for one failed v0.1.0's check on 2026-10-28 though JitPack had it. */
    @Test
    void theParentIsProbedAsAPluginInheritsIt() {
        String pom = CleanRoom.pom(Module.PLUGIN_PARENT, new Version(0, 1, 0));

        assertTrue(pom.contains("<parent>"), pom);
        assertTrue(pom.contains("<artifactId>botmaker-plugin-parent</artifactId><version>v0.1.0</version>"), pom);
        assertTrue(pom.contains("<artifactId>botmaker-studio-api</artifactId></dependency>"), pom);
        assertTrue(pom.contains("<artifactId>botmaker-plugin-toolkit</artifactId></dependency>"), pom);
    }
}
