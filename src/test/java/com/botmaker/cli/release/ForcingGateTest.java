package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForcingGateTest {

    private static Set<Module> of(Module... modules) {
        return modules.length == 0 ? EnumSet.noneOf(Module.class) : EnumSet.copyOf(Set.of(modules));
    }

    @Test
    void theReleaseThatShippedOn2026_09_05IsRefused() {
        // `./release.sh --sdk 1.1.6` alone: the sdk->studio edge fires, nobody named --studio, and
        // MavenService.SDK_FALLBACK_VERSION never moved. Studio v1.0.37 shipped pinning 1.1.5.
        GateVerdict verdict = ForcingGate.check(of(Module.SDK), of(Module.SDK), false);

        assertTrue(verdict.stops());
        assertTrue(verdict.refusal().contains("botmaker-studio"), verdict.refusal());
        assertTrue(verdict.refusal().contains("Add --studio"), verdict.refusal());
        assertTrue(verdict.refusal().contains("SDK_FALLBACK_VERSION"), verdict.refusal());
    }

    @Test
    void namingBothFlagsPasses() {
        GateVerdict verdict = ForcingGate.check(
                of(Module.SDK, Module.STUDIO), of(Module.SDK, Module.STUDIO), false);

        assertFalse(verdict.stops());
        assertEquals(GateVerdict.Status.OK, verdict.status());
    }

    @Test
    void aRequestedModuleThatWasSkippedIsStillRequested() {
        // The whole reason the gate asks about `requested` rather than `releasing`: --studio was typed and
        // the decide pass skipped it as unchanged. No edge is being ignored — the operator was asked.
        GateVerdict verdict = ForcingGate.check(of(Module.SDK, Module.STUDIO), of(Module.SDK), false);

        assertFalse(verdict.stops());
    }

    @Test
    void forceOverridesButStillNamesTheModules() {
        GateVerdict verdict = ForcingGate.check(of(Module.SDK), of(Module.SDK), true);

        assertEquals(GateVerdict.Status.FORCED, verdict.status());
        assertTrue(verdict.line().contains("--studio"), verdict.line());
    }

    @Test
    void aContractReleaseAloneNamesEveryDownstreamItForces() {
        // studio-api forces the toolkit, the host, the example plugin, the cli, the sdk and studio — six flags,
        // one refusal.
        GateVerdict verdict = ForcingGate.check(of(Module.STUDIO_API), of(Module.STUDIO_API), false);

        assertTrue(verdict.stops());
        for (String flag : new String[]{"--plugin-toolkit", "--plugin-host", "--plugin-archetype", "--cli", "--sdk",
                "--studio"}) {
            assertTrue(verdict.refusal().contains(flag), flag + " missing from: " + verdict.refusal());
        }
    }

    @Test
    void aModuleForcedByNothingNeverAppears() {
        // The pilot has no pom pin, so no upstream release can invalidate a pin it does not have.
        GateVerdict verdict = ForcingGate.check(of(Module.STUDIO_API), of(Module.STUDIO_API), false);

        assertFalse(verdict.refusal().contains("--pilot"), verdict.refusal());
    }

    @Test
    void aRunThatCutsNothingUpstreamPasses() {
        GateVerdict verdict = ForcingGate.check(of(Module.PILOT), of(Module.PILOT), false);

        assertEquals(GateVerdict.Status.OK, verdict.status());
    }
}
