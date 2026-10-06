package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionsGateTest {

    private static String pom(String own, String studioApi) {
        return "<project><version>" + own + "</version><properties><botmaker.studioapi.version>" + studioApi
                + "</botmaker.studioapi.version></properties></project>";
    }

    private static final Map<Module, String> CONTRACT_ON_MAIN = Map.of(Module.STUDIO_API, "0.4.3-SNAPSHOT");

    @Test
    void aPomAtItsSnapshotWithEveryPinAtItsUpstreamsMainPasses() {
        GateVerdict verdict = VersionsGate.check(Module.PLUGIN_HOST, pom("0.3.3-SNAPSHOT", "0.4.3-SNAPSHOT"),
                CONTRACT_ON_MAIN, false);
        assertEquals(GateVerdict.Status.OK, verdict.status());
        assertEquals("  plugin-host: 0.3.3-SNAPSHOT, every pin at its upstream's main — ok", verdict.line());
    }

    @Test
    void aStalePinIsRefusedNamingBothSides() {
        GateVerdict verdict = VersionsGate.check(Module.PLUGIN_HOST, pom("0.3.3-SNAPSHOT", "0.4.2-SNAPSHOT"),
                CONTRACT_ON_MAIN, false);
        assertTrue(verdict.stops());
        assertTrue(verdict.refusal().contains("botmaker.studioapi.version is 0.4.2-SNAPSHOT,"
                + " botmaker-studio-api's pom says 0.4.3-SNAPSHOT"), verdict.refusal());
    }

    @Test
    void aReleaseVersionLeftOnMainIsRefused() {
        GateVerdict verdict = VersionsGate.check(Module.PLUGIN_HOST, pom("0.3.3", "0.4.3-SNAPSHOT"),
                CONTRACT_ON_MAIN, false);
        assertTrue(verdict.refusal().contains("its own version is 0.3.3, not a -SNAPSHOT"), verdict.refusal());
    }

    @Test
    void forceOverridesAMismatch() {
        GateVerdict verdict = VersionsGate.check(Module.PLUGIN_HOST, pom("0.3.3", "0.4.3-SNAPSHOT"),
                CONTRACT_ON_MAIN, true);
        assertEquals(GateVerdict.Status.FORCED, verdict.status());
    }

    @Test
    void aPinTheReleaseDoesNotKnowIsRefusedEvenUnderForce() {
        // The SDK pinned in plugin-host's pom: no release would move it, so --force cannot make it right.
        String pom = pom("0.3.3-SNAPSHOT", "0.4.3-SNAPSHOT").replace("</properties>",
                "<botmaker.sdk.version>1.3.1-SNAPSHOT</botmaker.sdk.version></properties>");
        for (boolean force : new boolean[]{false, true}) {
            assertThrows(ReleaseRefusal.class, () -> VersionsGate.check(Module.PLUGIN_HOST, pom,
                    CONTRACT_ON_MAIN, force));
        }
    }

    @Test
    void aModulesOwnLabelAndAnUnknownKeyAreNotPins() {
        // The cli's `botmaker.cli.version` is its --version label; remote-server's `botmaker.remote.version`
        // names no module. Neither is an upstream pin.
        String cli = "<project><version>0.2.1-SNAPSHOT</version><properties>"
                + "<botmaker.cli.version>dev</botmaker.cli.version>"
                + "<botmaker.studioapi.version>0.4.3-SNAPSHOT</botmaker.studioapi.version>"
                + "<botmaker.pluginhost.version>0.3.3-SNAPSHOT</botmaker.pluginhost.version>"
                + "<botmaker.remote.version>dev</botmaker.remote.version></properties></project>";
        GateVerdict verdict = VersionsGate.check(Module.CLI, cli,
                Map.of(Module.STUDIO_API, "0.4.3-SNAPSHOT", Module.PLUGIN_HOST, "0.3.3-SNAPSHOT"), false);
        assertEquals(GateVerdict.Status.OK, verdict.status(), verdict.refusal());
    }
}
