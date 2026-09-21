package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderTest {

    @Test
    void everyModuleIsDecidedAndEveryModuleButNoneIsTagged() {
        // A module missing from either list is a module a release silently never cuts, which is the failure
        // mode that would survive every other test here.
        assertEquals(EnumSet.allOf(Module.class), EnumSet.copyOf(Order.DECIDE));
        assertEquals(EnumSet.allOf(Module.class), EnumSet.copyOf(Order.TAG));
        assertEquals(Module.values().length, Order.DECIDE.size());
        assertEquals(Module.values().length, Order.TAG.size());
    }

    @Test
    void theThreeOrdersAreThreeDifferentOrders() {
        assertNotEquals(Order.DECIDE, Order.TAG);
        assertNotEquals(List.of(Module.values()), Order.TAG);
        assertNotEquals(List.of(Module.values()), Order.DECIDE);
    }

    @Test
    void thePilotIsTaggedFirstAndTheWorkedBotLast() {
        // The pilot's APK build depends on nothing of ours, so it runs while the chain is being cut. The
        // worked bot's pom pins the SDK this run cuts, so that tag must be on origin before anybody clones
        // the template — Studio's own reason, one door further out.
        assertEquals(Module.PILOT, Order.TAG.get(0));
        assertEquals(Module.GAMEBOT, Order.DECIDE.get(Order.DECIDE.size() - 1));
        assertEquals(Module.GAMEBOT, Order.TAG.get(Order.TAG.size() - 1));
        assertTrue(Order.TAG.indexOf(Module.SDK) < Order.TAG.indexOf(Module.GAMEBOT),
                "the template pins the SDK, so the SDK is tagged first");
        // The dashboard closed both lists until 2026-09-21 and is still last of the reactor's own modules.
        assertEquals(Module.DASHBOARD, Order.TAG.get(Order.TAG.size() - 2));
    }

    @Test
    void theDashboardIsStudiosCaseOverTheCli() {
        // An installable app whose package job builds its upstreams from source at the refs in .deps.env:
        // every pin must be tagged before it, and the cli's own two pins are among them because the job
        // installs the cli from source too.
        List<Module> pins = DepsEnv.upstreams(Module.DASHBOARD);
        assertEquals(List.of(Module.SHARED, Module.STUDIO_API, Module.PLUGIN_HOST, Module.CLI), pins);
        for (Module pinned : pins) {
            assertTrue(Order.TAG.indexOf(pinned) < Order.TAG.indexOf(Module.DASHBOARD),
                    "dashboard must be tagged after " + pinned);
        }
        assertTrue(Module.DASHBOARD.hasChangelog());
        assertTrue(Module.DASHBOARD.mavenBuild());
        assertFalse(Module.DASHBOARD.onJitpack());
        // Forced by the cli alone, because the Release tab calls the cli's release library in-process: an
        // installed dashboard decides by the rules it was built with.
        assertEquals(Set.of(Module.CLI), upstreamsOf(Module.DASHBOARD));
        assertTrue(Forcing.EDGES.stream().noneMatch(edge -> edge.upstream() == Module.DASHBOARD));
    }

    @Test
    void theRemoteAppIsTaggedWithThePilotAndItsServerSitsInTheChain() {
        // Both APKs depend on nothing of ours and build for minutes: both go before the JitPack chain.
        assertTrue(Order.TAG.indexOf(Module.REMOTE) < Order.TAG.indexOf(Module.STUDIO_API));
        assertFalse(Module.REMOTE.hasChangelog());
        assertFalse(Module.REMOTE.mavenBuild());
        // The server is a Maven build with a changelog that JitPack never serves and nothing pins.
        assertTrue(Module.REMOTE_SERVER.hasChangelog());
        assertTrue(Module.REMOTE_SERVER.mavenBuild());
        assertFalse(Module.REMOTE_SERVER.onJitpack());
        assertTrue(DepsEnv.upstreams(Module.REMOTE_SERVER).isEmpty());
        assertTrue(Forcing.EDGES.stream().noneMatch(edge ->
                edge.upstream() == Module.REMOTE_SERVER || edge.downstream() == Module.REMOTE_SERVER
                        || edge.upstream() == Module.REMOTE || edge.downstream() == Module.REMOTE));
    }

    @Test
    void studioIsTaggedAfterEveryTagItsPackageJobChecksOut() {
        // Derived from what Studio's .deps.env pins, not restated: Studio v1.1.0's package jobs failed on
        // 2026-09-16 fetching botmaker-shared v0.1.0, which was tagged after it. A pin added to that file
        // later is covered without touching this test.
        List<Module> pins = DepsEnv.upstreams(Module.STUDIO);
        assertFalse(pins.isEmpty());
        for (Module pinned : pins) {
            assertTrue(Order.TAG.indexOf(pinned) < Order.TAG.indexOf(Module.STUDIO),
                    "studio must be tagged after " + pinned);
        }
        // And after the SDK, whose release rewrites SDK_FALLBACK_VERSION in Studio's source.
        assertTrue(Order.TAG.indexOf(Module.SDK) < Order.TAG.indexOf(Module.STUDIO));
    }

    @Test
    void everyUpstreamIsDecidedBeforeWhatItForces() {
        // Not a restatement of the list: the forced flag is computed from the versions decided so far, so an
        // edge pointing backwards would read an answer that does not exist yet and silently never force.
        for (Forcing edge : Forcing.EDGES) {
            assertTrue(Order.DECIDE.indexOf(edge.upstream()) < Order.DECIDE.indexOf(edge.downstream()),
                    edge.upstream() + " must be decided before " + edge.downstream());
        }
    }

    @Test
    void filteringKeepsTheOrderRatherThanTheCallersOwn() {
        Set<Module> picked = Set.of(Module.SDK, Module.STUDIO, Module.SHARED);
        assertEquals(List.of(Module.SHARED, Module.SDK, Module.STUDIO), Order.toTag(picked));
        assertEquals(List.of(Module.SHARED, Module.SDK, Module.STUDIO), Order.toDecide(picked));
        assertTrue(Order.toTag(Set.of()).isEmpty());
    }

    @Test
    void theModulesForcedByNothingAreForcedByNothing() {
        Set<Module> everythingElse = EnumSet.complementOf(EnumSet.of(
                Module.PILOT, Module.PLUGIN_ARCHETYPE, Module.SHARED, Module.STUDIO_API));
        // The archetype ships text whose versions are generation-time properties; the pilot has no pom pin;
        // shared and the contract pin nothing of ours. Nothing upstream can invalidate any of them.
        assertFalse(Forcing.forced(Module.PILOT, everythingElse));
        assertFalse(Forcing.forced(Module.PLUGIN_ARCHETYPE, everythingElse));
        assertFalse(Forcing.forced(Module.SHARED, everythingElse));
        assertFalse(Forcing.forced(Module.STUDIO_API, everythingElse));
    }

    @Test
    void theForcedSetsAreTheScriptsExpressions() {
        assertEquals(Set.of(Module.STUDIO_API), upstreamsOf(Module.PLUGIN_TOOLKIT));
        assertEquals(Set.of(Module.STUDIO_API), upstreamsOf(Module.PLUGIN_HOST));
        assertEquals(Set.of(Module.STUDIO_API, Module.PLUGIN_HOST), upstreamsOf(Module.CLI));
        assertEquals(Set.of(Module.SHARED), upstreamsOf(Module.SESSION));
        assertEquals(Set.of(Module.STUDIO_API, Module.PLUGIN_TOOLKIT), upstreamsOf(Module.PLUGIN_BASICS));
        // Plugin #2 is in the SDK's set because the SDK compiles against it as an ordinary Maven
        // dependency — a plugin depending on another plugin, which PluginLoader's single classloader makes
        // possible and which costs plugin-basics its freedom to break.
        assertEquals(Set.of(Module.SHARED, Module.SESSION, Module.STUDIO_API, Module.PLUGIN_TOOLKIT,
                Module.PLUGIN_BASICS), upstreamsOf(Module.SDK));
        // The toolkit is deliberately NOT here since 2026-09-06: a generated bot's pom stopped declaring it,
        // MavenService.TOOLKIT_FALLBACK_VERSION went with the entry, and with no toolkit version written
        // anywhere in Studio's source a toolkit release changes nothing here.
        assertEquals(Set.of(Module.SHARED, Module.SESSION, Module.SDK, Module.STUDIO_API,
                Module.PLUGIN_HOST), upstreamsOf(Module.STUDIO));
    }

    @Test
    void aForcedModuleCanSayWhichReleaseDraggedItInAndWhy() {
        List<Forcing> why = Forcing.forcedBy(Module.SDK, Set.of(Module.SHARED, Module.CLI));
        assertEquals(1, why.size());
        assertEquals(Module.SHARED, why.get(0).upstream());
        assertTrue(why.get(0).sentence().startsWith("forced by botmaker-shared — "));
        // Two upstreams in one run is two reasons, not one arbitrary winner.
        assertEquals(2, Forcing.forcedBy(Module.SDK, Set.of(Module.SHARED, Module.SESSION)).size());
    }

    private static Set<Module> upstreamsOf(Module downstream) {
        return Forcing.EDGES.stream()
                .filter(edge -> edge.downstream() == downstream)
                .map(Forcing::upstream)
                .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(Module.class)));
    }
}
