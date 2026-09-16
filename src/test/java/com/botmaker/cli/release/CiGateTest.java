package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiGateTest {

    private static final String RED = "completed\tfailure\thttps://github.com/LiQiyeDev/botmaker-plugin-host/"
            + "actions/runs/35084895266\t3f1c2a9d0b7e\n";

    @Test
    void aRedMainRefusesWithTheRunAndTheOverride() {
        // botmaker-plugin-host on 2026-09-16: red on main for ten days, and tagged anyway.
        GateVerdict verdict = CiGate.verdict(Module.PLUGIN_HOST, RED, false);

        assertTrue(verdict.stops());
        assertTrue(verdict.refusal().startsWith("botmaker-plugin-host: the newest finished CI run on main is"
                + " failure (3f1c2a9)"), verdict.refusal());
        assertTrue(verdict.refusal().contains("actions/runs/35084895266"));
        assertTrue(verdict.refusal().contains("--force overrides"));
    }

    @Test
    void forceTurnsARefusalIntoAVisibleOverride() {
        GateVerdict verdict = CiGate.verdict(Module.PLUGIN_HOST, RED, true);

        assertEquals(GateVerdict.Status.FORCED, verdict.status());
        assertTrue(verdict.line().contains("FORCED"));
    }

    @Test
    void greenPasses() {
        GateVerdict verdict = CiGate.verdict(Module.SDK, "completed\tsuccess\thttps://x\tabcdef1234\n", false);

        assertEquals(GateVerdict.Status.OK, verdict.status());
        assertEquals("  sdk: CI on main is green at abcdef1 — ok", verdict.line());
    }

    @Test
    void aRunningRunIsSkippedOverForTheNewestFinishedOne() {
        String tsv = "in_progress\t\thttps://x/2\tbbbbbbbbb\n" + RED;

        GateVerdict verdict = CiGate.verdict(Module.PLUGIN_HOST, tsv, false);

        assertTrue(verdict.stops());
        assertTrue(verdict.refusal().contains("(a newer run is still going)"));
    }

    @Test
    void nothingFinishedIsNotARefusal() {
        // A gate must not stop a release over what it cannot read.
        assertEquals(GateVerdict.Status.SKIPPED,
                CiGate.verdict(Module.SDK, "in_progress\t\thttps://x\tabc\n", false).status());
        assertEquals(GateVerdict.Status.SKIPPED, CiGate.verdict(Module.SDK, "", false).status());
    }

    @Test
    void cancelledIsRedToo() {
        assertTrue(CiGate.verdict(Module.SDK, "completed\tcancelled\thttps://x\tabc\n", false).stops());
    }
}
