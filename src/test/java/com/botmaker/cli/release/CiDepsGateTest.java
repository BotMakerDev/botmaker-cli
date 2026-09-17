package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiDepsGateTest {

    private static final String POM = """
            <project>
              <properties>
                <botmaker.shared.version>0.0.0-SNAPSHOT</botmaker.shared.version>
                <botmaker.studioapi.version>0.0.0-SNAPSHOT</botmaker.studioapi.version>
                <botmaker.sdk.version>1.1.6</botmaker.sdk.version>
              </properties>
            </project>
            """;

    @Test
    void onlySnapshotPinsAreUpstreamsCiHasToCheckOut() {
        // A pin at a real version resolves from JitPack and needs no checkout; only 0.0.0-SNAPSHOT means
        // "this comes from a source install".
        assertEquals(Set.of("shared", "studioapi"), CiDepsGate.keys(POM));
    }

    @Test
    void aWorkflowNamingEveryUpstreamPasses() {
        String ci = "steps:\n  - uses: actions/checkout@v4\n    with:\n      repository: "
                + "LiQiyeDev/botmaker-shared\n  - run: git clone …/botmaker-studio-api\n";
        GateVerdict verdict = CiDepsGate.check(Module.SDK, POM, Optional.of(ci), false);
        assertEquals(GateVerdict.Status.OK, verdict.status());
        assertEquals("  sdk: ci.yml checks out every 0.0.0-SNAPSHOT upstream — ok", verdict.line());
    }

    @Test
    void theRefusalNamesEveryMissingRepositoryInTheScriptsOrder() {
        GateVerdict verdict = CiDepsGate.check(Module.SDK, POM, Optional.of("steps: []"), false);
        assertTrue(verdict.stops());
        assertTrue(verdict.refusal().startsWith(
                "botmaker-sdk: pom.xml resolves botmaker-shared botmaker-studio-api at 0.0.0-SNAPSHOT"),
                verdict.refusal());
        // The reason this gate exists, in the refusal itself: the umbrella reactor hides the failure.
        assertTrue(verdict.refusal().contains("the umbrella reactor hides it"));
        assertTrue(verdict.refusal().endsWith("--force overrides."));
    }

    @Test
    void theDashboardsPinOnTheCliIsAKeyTheGateKnows() {
        // The first 0.0.0-SNAPSHOT pin on a non-plugin module (2026-09-16). Unmapped, the gate would throw
        // even under --force, which is the right answer for a key nobody classified and the wrong one here.
        String pom = "<botmaker.cli.version>0.0.0-SNAPSHOT</botmaker.cli.version>";
        assertEquals(Optional.of(Module.CLI), CiDepsGate.repositoryModule("cli"));
        GateVerdict verdict = CiDepsGate.check(Module.DASHBOARD, pom, Optional.of("steps: []"), false);
        assertTrue(verdict.refusal().startsWith("botmaker-dashboard: pom.xml resolves botmaker-cli"),
                verdict.refusal());
    }

    @Test
    void forceDowngradesARefusalToALineThatStillSaysItFailed() {
        GateVerdict verdict = CiDepsGate.check(Module.SDK, POM, Optional.of("steps: []"), true);
        assertEquals(GateVerdict.Status.FORCED, verdict.status());
        assertEquals("  sdk: ci.yml is missing botmaker-shared botmaker-studio-api — FORCED",
                verdict.line());
    }

    @Test
    void aModuleWithNoWorkflowIsSkippedRatherThanRefused() {
        GateVerdict verdict = CiDepsGate.check(Module.CLI, POM, Optional.empty(), false);
        assertEquals(GateVerdict.Status.SKIPPED, verdict.status());
        assertEquals("  cli: no ci.yml — skipped", verdict.line());
    }

    @Test
    void anUnmappedKeyIsRefusedEvenUnderForce() {
        // --force overrides a gate that FAILED, never one that could not run: an unknown
        // ${botmaker.X.version} means this check does not know what it is looking at.
        String pom = "<botmaker.pilot.version>0.0.0-SNAPSHOT</botmaker.pilot.version>";
        for (boolean force : List.of(false, true)) {
            ReleaseRefusal refused = assertThrows(ReleaseRefusal.class,
                    () -> CiDepsGate.check(Module.SDK, pom, Optional.of("steps: []"), force));
            assertTrue(refused.getMessage().startsWith(
                    "botmaker-sdk: pom.xml declares ${botmaker.pilot.version}"), refused.getMessage());
        }
    }

    @Test
    void theKeyTableIsAMappingAndNotADerivation() {
        assertEquals(Optional.of(Module.STUDIO_API), CiDepsGate.repositoryModule("studioapi"));
        assertEquals(Optional.of(Module.PLUGIN_TOOLKIT), CiDepsGate.repositoryModule("plugintoolkit"));
        assertEquals(Optional.of(Module.PLUGIN_HOST), CiDepsGate.repositoryModule("pluginhost"));
        assertTrue(CiDepsGate.repositoryModule("studio").isEmpty());
        assertTrue(CiDepsGate.repositoryModule("").isEmpty());
    }
}
