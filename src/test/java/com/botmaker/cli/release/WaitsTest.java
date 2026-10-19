package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    void aFullReleaseWaitsOnEightOfItsNineArtifacts() {
        Set<Module> all = EnumSet.allOf(Module.class);

        assertEquals(EnumSet.of(Module.STUDIO_API, Module.PLUGIN_TOOLKIT, Module.PLUGIN_HOST,
                Module.PLUGIN_ARCHETYPE, Module.CLI, Module.SHARED, Module.SESSION, Module.PLUGIN_BASICS), owed(all));
        // The one that goes: nothing tagged after the sdk resolves it from JitPack. The archetype went too until
        // the cli bundled its template (2026-10-19).
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
    void aRunThatDoesNotWaitStillStopsOnAnUpstreamJitpackAlreadyFailed() {
        // 2026-10-12: shared v0.2.4 failed on Central's 429, and the SDK, Studio and dashboard pinned it anyway.
        Map<Module, Version> releasing = new EnumMap<>(Module.class);
        releasing.put(Module.SHARED, Version.parse("0.2.4").orElseThrow());
        releasing.put(Module.SESSION, Version.parse("0.2.2").orElseThrow());
        releasing.put(Module.SDK, Version.parse("1.4.3").orElseThrow());
        List<String> asked = new ArrayList<>();
        Jitpack.Probe sharedFailed = new Jitpack.Probe() {
            @Override
            public boolean built(String pomUrl) {
                return false;
            }

            @Override
            public Optional<String> failure(String buildUrl) {
                asked.add(buildUrl.substring(buildUrl.lastIndexOf("/botmaker-") + 1));
                return buildUrl.contains("botmaker-shared/") ? Optional.of("No build artifacts found") : Optional.empty();
            }
        };

        assertEquals(Optional.of("botmaker-shared v0.2.4: No build artifacts found"),
                Waits.failedUpstream(Module.SDK, releasing, sharedFailed));
        assertEquals(Optional.empty(), Waits.failedUpstream(Module.SHARED, releasing, sharedFailed),
                "shared's own upstreams are not in this release");
        asked.clear();
        Waits.failedUpstream(Module.SESSION, Map.of(Module.SESSION, Version.parse("0.2.2").orElseThrow()),
                sharedFailed);
        assertEquals(List.of(), asked, "an upstream outside the run is PinsResolveGate's, not asked here");
    }

    @Test
    void theSkippedWaitSaysWhichTagAndWhy() {
        assertEquals("not waiting on botmaker-sdk:v1.2.0 — nothing in this release resolves it from JitPack",
                Waits.notWaiting(Module.SDK, Version.parse("1.2.0").orElseThrow()));
    }
}
