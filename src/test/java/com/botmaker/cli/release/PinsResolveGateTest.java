package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A tag must not pin a release JitPack does not serve (2026-10-12: shared v0.2.4, pinned by three tags). */
class PinsResolveGateTest {

    private static final Version BROKEN = new Version(0, 2, 4);

    private static final List<PinsResolveGate.Pin> PINS = List.of(
            new PinsResolveGate.Pin(Module.SDK, Module.SHARED, BROKEN),
            new PinsResolveGate.Pin(Module.DASHBOARD, Module.SHARED, BROKEN),
            new PinsResolveGate.Pin(Module.DASHBOARD, Module.CLI, new Version(0, 3, 3)));

    @Test
    void aPinJitpackDoesNotServeIsRefusedNamingTheFlagThatRepairsIt() {
        GateVerdict verdict = PinsResolveGate.verdict(PINS, pin -> pin.upstream() != Module.SHARED, () -> true, false);
        assertEquals(GateVerdict.Status.REFUSED, verdict.status());
        assertTrue(verdict.refusal().contains("botmaker-sdk would pin botmaker-shared v0.2.4"), verdict.refusal());
        assertTrue(verdict.refusal().contains("botmaker-dashboard would pin botmaker-shared v0.2.4"));
        assertTrue(verdict.refusal().contains("add --shared."), "the flag once, however many tags pin it");
    }

    @Test
    void everyPinServedPasses() {
        assertEquals(GateVerdict.Status.OK, PinsResolveGate.verdict(PINS, pin -> true, () -> true, false).status());
        assertEquals(GateVerdict.Status.OK, PinsResolveGate.verdict(List.of(), pin -> false, () -> false, false)
                .status(), "a release whose upstreams are all in it pins nothing outside it");
    }

    @Test
    void anUnreachableJitpackIsNotARefusalAndForceIsSaid() {
        assertEquals(GateVerdict.Status.SKIPPED,
                PinsResolveGate.verdict(PINS, pin -> false, () -> false, false).status());
        GateVerdict forced = PinsResolveGate.verdict(PINS, pin -> pin.upstream() != Module.SHARED, () -> true, true);
        assertEquals(GateVerdict.Status.FORCED, forced.status());
        assertTrue(forced.line().contains("sdk → shared v0.2.4"), forced.line());
    }
}
