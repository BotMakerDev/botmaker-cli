package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleTest {

    /** The fourteen flags `release.sh --help` lists, in the order it lists them. */
    private static final List<String> SCRIPT_FLAGS = List.of(
            "--studio-api", "--plugin-toolkit", "--plugin-host", "--plugin-archetype", "--plugin-basics",
            "--cli", "--shared", "--session", "--sdk", "--studio", "--pilot", "--remote-server", "--remote",
            "--dashboard");

    @Test
    void theFlagsAreTheScriptsFlags() {
        assertEquals(SCRIPT_FLAGS, java.util.Arrays.stream(Module.values()).map(Module::flag).toList());
    }

    @Test
    void theFlagIsDerivedFromTheDirectoryRatherThanTabulated() {
        assertEquals("--plugin-toolkit", Module.PLUGIN_TOOLKIT.flag());
        assertEquals("botmaker-plugin-toolkit", Module.PLUGIN_TOOLKIT.directory());
        assertEquals("plugin-toolkit", Module.PLUGIN_TOOLKIT.shortName());
    }

    @Test
    void aFlagNobodyDefinedIsNotAModule() {
        assertEquals(Optional.of(Module.SDK), Module.byFlag("--sdk"));
        // The data repositories have no artifact and no flag; the script answers `unknown arg` to one
        // invented for them, and so must this. The dashboard was on that list until 2026-09-17.
        assertTrue(Module.byFlag("--gallery").isEmpty());
        assertEquals(Optional.of(Module.DASHBOARD), Module.byFlag("--dashboard"));
        assertTrue(Module.byDirectory("botmaker-plugin-registry").isEmpty());
    }

    @Test
    void everyModuleHereIsASubmoduleOfTheUmbrella() throws Exception {
        // Reads .gitmodules from the checkout this test runs in, when there is one: the enum is this
        // package's list to keep, but a name in it that is not a real submodule would be a release that
        // cannot find its own directory.
        Path gitmodules = Path.of("..").resolve(".gitmodules").normalize();
        if (!Files.exists(gitmodules)) {
            return; // Built outside the umbrella (a standalone CI checkout) — nothing to compare against.
        }
        String text = Files.readString(gitmodules);
        for (Module module : Module.values()) {
            assertTrue(text.contains(module.directory()),
                    module.directory() + " is not a submodule of the umbrella");
        }
    }
}
