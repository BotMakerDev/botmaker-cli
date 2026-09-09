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
    void studioIsTaggedSecondAndDecidedLast() {
        // The whole reason the decisions are taken up front: studio's package matrix is the longest job in
        // the release, and it depends on nothing that has to be published first.
        assertEquals(Module.PILOT, Order.TAG.get(0));
        assertEquals(Module.STUDIO, Order.TAG.get(1));
        assertEquals(Module.STUDIO, Order.DECIDE.get(Order.DECIDE.size() - 1));
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
        assertEquals(List.of(Module.STUDIO, Module.SHARED, Module.SDK), Order.toTag(picked));
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
