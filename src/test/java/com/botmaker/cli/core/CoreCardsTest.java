// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreCardsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final CoreCards cards = new CoreCards(ZoneOffset.UTC);

    /** An inside as the core's {@code Inside} writes it, an empty Optional as null. */
    static final String INSIDE = """
            {"atMillis":3600000,"uptimeMillis":192000,"pid":4242,"version":"1.3.0","ending":false,
             "heapUsed":125829120,"heapMax":4294967296,"threads":41,"threadsByPrefix":{"main":1},"rebuiltJars":[],
             "project":"NunuSite","lockHolder":4242,"devMode":true,
             "clients":[{"id":"0123456789abcdef","kind":"studio","openedMillis":1000,"watchingCalls":true,
                         "requests":12,"lastRequest":"Edit","openFile":"Bot.java"}],
             "running":"Bot.main","companions":["com.example.Explorer"],
             "plugins":[{"coordinate":"com.github.BotMakerDev:botmaker-sdk","version":"2.1.0","localBuild":true,
                         "said":""}],
             "idleLeftMillis":null}
            """;

    @Test
    void theEightCardsInThePagesOrder() throws Exception {
        String text = cards.cards(JSON.readTree(INSIDE), seen());
        int at = -1;
        for (String heading : CoreCards.HEADINGS) {
            int next = text.indexOf("\n" + heading + "\n");
            if (heading.equals("Core")) next = text.startsWith("Core\n") ? 0 : -1;
            assertTrue(next > at, heading + " after the one before it:\n" + text);
            at = next;
        }
        assertEquals(8, CoreCards.HEADINGS.size());
        assertTrue(text.contains("  up 3m 12s · pid 4242 · v1.3.0\n"), text);
        assertTrue(text.contains("  heap 120 MB / 4096 MB · 41 threads\n"), text);
        assertTrue(text.contains("  kept up\n"), text);
        assertTrue(text.contains("  studio 01234567 · 12 asks · watching calls\n"), text);
        assertTrue(text.contains("  mcp · last core_status at 00:59:58\n"), text);
        assertTrue(text.contains("  NunuSite\n  lock pid 4242 · dev mode\n  undo Insert a call\n"), text);
        assertTrue(text.contains("  running Bot.main\n  RUNNING · Bot.main\n  2 runs\n"), text);
        assertTrue(text.contains("  com.example.Explorer\n  with Bot.main\n"), text);
        assertTrue(text.contains("  com.github.BotMakerDev:botmaker-sdk 2.1.0 (local build)\n"), text);
        assertTrue(text.contains("  1 running · 2 heard\n  1 failed · 0 refused\n"), text);
        assertTrue(text.contains("  accepted Insert · 340 ms\n  Bot.java · main\n"), text);
        assertFalse(text.contains("gold=999"), "a run's own lines stay out of the cards");
    }

    @Test
    void nothingOpenNothingRuns() throws Exception {
        String text = cards.cards(JSON.readTree("""
                {"atMillis":0,"uptimeMillis":500,"pid":1,"version":"1.0","ending":false,"heapUsed":0,"heapMax":0,
                 "threads":1,"threadsByPrefix":{},"rebuiltJars":[],"project":null,"lockHolder":null,"devMode":false,
                 "clients":[],"running":null,"companions":[],"plugins":[],"idleLeftMillis":540000}
                """), List.of());
        for (String line : List.of("idle exit in 9m 0s", "0 channels", "no project open", "nothing runs",
                "none running", "none bound", "0 running · 0 heard", "no edit yet")) {
            assertTrue(text.contains("  " + line + "\n"), line + " in\n" + text);
        }
    }

    @Test
    void oneLinePerSeenAsThePageSummarisesIt() throws Exception {
        List<JsonNode> seen = seen();
        assertEquals("00:59:50  project  runState  RUNNING · Bot.main", cards.line(seen.get(1)));
        assertEquals("00:59:58  mcp  toolCallStarted  #8 core_status", cards.line(seen.get(4)));
        assertEquals("00:59:59  0123456789abcdef  edit  accepted Insert in Bot.java · main · 340 ms",
                cards.line(seen.get(seen.size() - 1)));
        assertEquals("edit", CoreCards.kind(seen.get(seen.size() - 1)));
        assertEquals("runState", CoreCards.kind(seen.get(1)));
    }

    @Test
    void spans() {
        assertEquals("340 ms", CoreCards.span(340));
        assertEquals("4.2 s", CoreCards.span(4200));
        assertEquals("3m 12s", CoreCards.span(192_000));
        assertEquals("2h 5m", CoreCards.span(7_500_000));
    }

    static List<JsonNode> seen() throws Exception {
        List<JsonNode> seen = new ArrayList<>();
        for (String frame : List.of(
                told(3_588_000, "project", "{\"event\":\"historyChanged\",\"canUndo\":true,\"undoLabel\":\"Insert a call\","
                        + "\"canRedo\":false,\"redoLabel\":\"\"}"),
                told(3_590_000, "project", "{\"event\":\"runState\",\"phase\":\"RUNNING\",\"detail\":\"Bot.main\"}"),
                told(3_591_000, "project", "{\"event\":\"runsStarted\",\"words\":\"2 of 2 started\",\"runs\":"
                        + "[{\"row\":0,\"runId\":\"a\"},{\"row\":1,\"runId\":\"b\"}]}"),
                told(3_592_000, "project", "{\"event\":\"runOutput\",\"stream\":\"OUT\",\"text\":\"gold=999\"}"),
                // The core writes no plugin as "": the line says none.
                told(3_598_000, "mcp", "{\"event\":\"toolCallStarted\",\"call\":8,\"tool\":\"core_status\","
                        + "\"plugin\":\"\",\"atMillis\":3598000}"),
                told(3_598_100, "project", "{\"event\":\"toolCallStarted\",\"call\":7,\"tool\":\"run_bot\","
                        + "\"plugin\":null,\"atMillis\":3598100}"),
                told(3_598_500, "project", "{\"event\":\"toolCallEnded\",\"call\":7,\"outcome\":\"ERROR\",\"millis\":400}"),
                told(3_598_600, "project", "{\"event\":\"toolCallEnded\",\"call\":3,\"outcome\":\"REFUSED\",\"millis\":4}"),
                "{\"type\":\"edit\",\"atMillis\":3599000,\"client\":\"0123456789abcdef\",\"kind\":\"Insert\","
                        + "\"accepted\":true,\"file\":\"Bot.java\",\"methods\":[\"main\"],\"tookMillis\":340,\"label\":\"\"}")) {
            seen.add(JSON.readTree(frame));
        }
        return seen;
    }

    private static String told(long at, String source, String event) {
        return "{\"type\":\"told\",\"atMillis\":" + at + ",\"source\":\"" + source + "\",\"dropped\":0,\"event\":" + event
                + "}";
    }
}
