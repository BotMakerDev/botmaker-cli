package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitsTest {

    /** The modules of a release that would block on their JitPack build. */
    private static Set<Module> owed(Set<Module> releasing) {
        return releasing.stream()
                .filter(Module::onJitpack)
                .filter(module -> Waits.owed(module, releasing))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Module.class)));
    }

    @Test
    void aFullReleaseWaitsOnSevenOfItsNineArtifacts() {
        Set<Module> all = EnumSet.allOf(Module.class);

        assertEquals(EnumSet.of(Module.STUDIO_API, Module.PLUGIN_TOOLKIT, Module.PLUGIN_HOST, Module.CLI,
                Module.SHARED, Module.SESSION, Module.PLUGIN_BASICS), owed(all));
        // The two that go: nothing pins the archetype; nothing tagged after the sdk resolves it from JitPack.
        assertFalse(Waits.owed(Module.PLUGIN_ARCHETYPE, all));
        assertFalse(Waits.owed(Module.SDK, all));
    }

    @Test
    void aModuleReleasedAloneWaitsForNothing() {
        assertFalse(Waits.owed(Module.SDK, EnumSet.of(Module.SDK)));
        assertFalse(Waits.owed(Module.SHARED, EnumSet.of(Module.SHARED)));
    }

    @Test
    void anUpstreamIsOwedOnlyWhenItsConsumerIsInThisRelease() {
        assertEquals(EnumSet.of(Module.SHARED), owed(EnumSet.of(Module.SHARED, Module.SDK)));
    }

    @Test
    void aPackagedAppResolvesItsPinsFromJitpackSoItsUpstreamIsOwed() {
        // The dashboard's package job resolves the cli its tag's pom pins from JitPack (doc 43), as Studio's
        // resolves shared: tagged a minute after the upstream, it would read a build still running.
        assertEquals(EnumSet.of(Module.CLI), owed(EnumSet.of(Module.CLI, Module.DASHBOARD)));
        assertEquals(EnumSet.of(Module.SHARED), owed(EnumSet.of(Module.SHARED, Module.STUDIO)));
    }

    @Test
    void aConsumerTaggedBeforeItsUpstreamDoesNotMakeTheUpstreamOwed() {
        // Only a LATER tag can lose the race; the rule reads Order.TAG, not the set.
        assertFalse(Waits.owed(Module.SDK, EnumSet.of(Module.PLUGIN_BASICS, Module.SDK)));
        assertTrue(Waits.owed(Module.PLUGIN_BASICS, EnumSet.of(Module.PLUGIN_BASICS, Module.SDK)));
    }

    @Test
    void aModuleNotInTheReleaseIsOwedNothing() {
        assertFalse(Waits.owed(Module.SHARED, EnumSet.of(Module.SESSION)));
    }

    @Test
    void theSkippedWaitSaysWhichTagAndWhy() {
        assertEquals("not waiting on botmaker-sdk:v1.2.0 — nothing in this release resolves it from JitPack",
                Waits.notWaiting(Module.SDK, Version.parse("1.2.0").orElseThrow()));
    }
}
