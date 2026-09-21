package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatePlanTest {

    private static final Set<Module> ALL = EnumSet.allOf(Module.class);

    @Test
    void thePilotTakesNoGateAtAll() {
        // No CHANGELOG.md, no pom pin, no JitPack build: there is nothing for any of the three to read.
        assertFalse(GatePlan.changelog(ALL).contains(Module.PILOT));
        assertFalse(GatePlan.ciDeps(ALL).contains(Module.PILOT));
        assertFalse(GatePlan.jitpackPlugins(ALL).contains(Module.PILOT));
        assertTrue(ChangelogGate.exempt(Module.PILOT));
    }

    @Test
    void studioIsExemptFromTheJitpackGateOnly() {
        // JitPack never builds Studio — it ships installers from its own per-OS matrix — so its plugin pins
        // are bounded by its own CI. It still needs a changelog and a CI that can build it.
        assertTrue(GatePlan.changelog(ALL).contains(Module.STUDIO));
        assertTrue(GatePlan.ciDeps(ALL).contains(Module.STUDIO));
        assertFalse(GatePlan.jitpackPlugins(ALL).contains(Module.STUDIO));
    }

    @Test
    void aTemplateTakesNeitherCiGate() {
        // botmaker-gamebot has no .github/workflows at all, so a CI verdict on it is neither a pass nor a
        // failure, and "can this module's own CI build it standalone" has no subject. It is out of the
        // JitPack gate through onJitpack() and out of the changelog gate through hasChangelog(), both
        // already. What it takes is the template gate: does the project a user copies compile.
        assertFalse(GatePlan.ci(ALL).contains(Module.GAMEBOT));
        assertFalse(GatePlan.ciDeps(ALL).contains(Module.GAMEBOT));
        assertFalse(GatePlan.changelog(ALL).contains(Module.GAMEBOT));
        assertFalse(GatePlan.jitpackPlugins(ALL).contains(Module.GAMEBOT));
        // …and it is not exempt by being un-buildable: it has a pom, which is what the gate compiles.
        assertTrue(Module.GAMEBOT.mavenBuild());
    }

    @Test
    void theCountsAreTheScriptsOwnLoops() {
        // Fifteen modules: two APKs take no gate, and the template takes none of these four; Studio,
        // remote-server and the dashboard take every gate but JitPack's.
        assertEquals(14, GatePlan.ci(ALL).size());
        assertEquals(12, GatePlan.changelog(ALL).size());
        assertEquals(12, GatePlan.ciDeps(ALL).size());
        assertEquals(9, GatePlan.jitpackPlugins(ALL).size());
    }

    @Test
    void onlyWhatIsBeingReleasedIsGated() {
        Set<Module> two = Set.of(Module.CLI, Module.SHARED);
        assertEquals(List.of(Module.CLI, Module.SHARED), GatePlan.changelog(two));
        assertTrue(GatePlan.changelog(Set.of(Module.PILOT)).isEmpty());
    }

    @Test
    void theSdkGatesRunExactlyWhenTheSdkIsCut() {
        assertTrue(GatePlan.sdkGates(Set.of(Module.SDK, Module.STUDIO)));
        assertFalse(GatePlan.sdkGates(Set.of(Module.STUDIO, Module.SHARED)));
    }

    @Test
    void gatesAreListedInDecideOrderSoTheOutputReadsAsThePlanDid() {
        assertEquals(GatePlan.ciDeps(ALL),
                Order.DECIDE.stream().filter(Module::mavenBuild).filter(m -> !m.template()).toList());
    }
}
