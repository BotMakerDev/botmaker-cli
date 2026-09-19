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
    void aFullReleaseWaitsOnSixOfItsNineArtifacts() {
        Set<Module> all = EnumSet.allOf(Module.class);

        assertEquals(EnumSet.of(Module.STUDIO_API, Module.PLUGIN_TOOLKIT, Module.PLUGIN_HOST, Module.SHARED,
                Module.SESSION, Module.PLUGIN_BASICS), owed(all));
        // The three that go, each for its own reason: nothing pins the archetype; only the dashboard pins the
        // cli and it builds the cli from source; nothing tagged after the sdk resolves it from JitPack.
        assertFalse(Waits.owed(Module.PLUGIN_ARCHETYPE, all));
        assertFalse(Waits.owed(Module.CLI, all));
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
    void aConsumerThatBuildsFromSourceIsOwedNoWait() {
        // The dashboard pins the cli, and its package job checks the cli out at that tag and installs it:
        // it needs the tag on origin, which the push already guarantees, not a JitPack build.
        assertTrue(owed(EnumSet.of(Module.CLI, Module.DASHBOARD)).isEmpty());
        // Studio the same over shared.
        assertTrue(owed(EnumSet.of(Module.SHARED, Module.STUDIO)).isEmpty());
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
