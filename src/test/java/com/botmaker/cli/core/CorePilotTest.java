// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the shell shows of the core's pilot state: never the pairing link in the description. */
class CorePilotTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void anOnStateIsDescribedWithoutItsLink() throws Exception {
        var state = JSON.readTree("""
                {"phase":"ON","kind":"LAN","asked":"LAN","url":"http://10.0.0.5:4000/?token=SECRET",
                 "qr":["10","01"],"phones":[{"id":"ab12","address":"10.0.0.9","interact":true}]}""");
        List<String> lines = CorePilot.describe(state);
        assertEquals("Remote Pilot is on, over the local network.", lines.getFirst());
        assertTrue(lines.stream().noneMatch(line -> line.contains("SECRET")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("ab12") && line.contains("touching")));
        assertEquals("http://10.0.0.5:4000/?token=SECRET", CorePilot.link(state));
    }

    @Test
    void anOffStateSaysWhy() throws Exception {
        var state = JSON.readTree("{\"phase\":\"OFF\",\"problem\":\"Tailscale is off\",\"fix\":\"Turn it on\",\"url\":null}");
        assertEquals(List.of("Remote Pilot is off.", "Tailscale is off", "  Turn it on"), CorePilot.describe(state));
        assertNull(CorePilot.link(state));
    }

    @Test
    void theCodeIsTwoRowsToALineBlackOnWhite() throws Exception {
        var state = JSON.readTree("{\"qr\":[\"10\",\"01\",\"11\"]}");
        List<String> lines = CorePilot.qr(state);
        assertEquals(2, lines.size());
        assertTrue(lines.getFirst().startsWith("\u001b[38;5;0;48;5;15m▀"));
    }
}
