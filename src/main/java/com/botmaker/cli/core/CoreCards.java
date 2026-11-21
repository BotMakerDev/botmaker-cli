// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The dashboard's eight cards as text (core dashboard plan, phase 7), in the page's order, from an {@code inside} and the
 * {@code seen} frames heard with it; and one line per {@code seen} for {@code botmaker core watch}, the page's own
 * summary. It reads the JSON by the field names the core's {@code Inside} and {@code DashboardEvents} write; what the
 * core leaves out of the feed (a picture, a pooled value) never reaches it.
 */
public final class CoreCards {

    /** The cards' headings, in the page's order. */
    public static final List<String> HEADINGS = List.of("Core", "Clients", "Project", "Runs", "Companions", "Plugins",
            "MCP calls", "Last edit");

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ZoneId zone;

    public CoreCards(ZoneId zone) {
        this.zone = zone;
    }

    /** The eight cards, one block each: its heading, then its lines indented. */
    public String cards(JsonNode inside, List<JsonNode> seen) {
        Heard heard = new Heard(seen);
        List<List<String>> bodies = List.of(core(inside), clients(inside, heard), project(inside, heard),
                runs(inside, heard), companions(inside), plugins(inside), calls(heard), lastEdit(heard));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < HEADINGS.size(); i++) {
            if (i > 0) text.append('\n');
            text.append(HEADINGS.get(i)).append('\n');
            for (String line : bodies.get(i)) text.append("  ").append(line).append('\n');
        }
        return text.toString();
    }

    /** {@code HH:mm:ss  <source>  <kind>  <summary>} for one {@code seen}. */
    public String line(JsonNode seen) {
        return time(seen.path("atMillis").asLong()) + "  " + source(seen) + "  " + kind(seen) + "  " + summary(seen);
    }

    /** What the page's feed filters by: a told event's own name ({@code runState}), else {@code edit} or {@code client}. */
    public static String kind(JsonNode seen) {
        return told(seen) ? seen.path("event").path("event").asText() : seen.path("type").asText();
    }

    private static String source(JsonNode seen) {
        return switch (seen.path("type").asText()) {
            case "told" -> seen.path("source").asText();
            case "edit" -> text(seen.path("client"), "—");
            default -> seen.path("id").asText();
        };
    }

    private static boolean told(JsonNode seen) {
        return seen.path("type").asText().equals("told");
    }

    // ---- the cards ----

    private List<String> core(JsonNode in) {
        return List.of((in.path("ending").asBoolean() ? "ending" : "up") + " " + span(in.path("uptimeMillis").asLong())
                        + " · pid " + in.path("pid").asLong() + " · v" + in.path("version").asText(),
                "heap " + mb(in.path("heapUsed").asLong()) + " / " + mb(in.path("heapMax").asLong()) + " · "
                        + in.path("threads").asInt() + " threads",
                in.path("idleLeftMillis").isNumber() ? "idle exit in " + span(in.path("idleLeftMillis").asLong())
                        : "kept up");
    }

    private List<String> clients(JsonNode in, Heard heard) {
        List<String> lines = new ArrayList<>();
        int count = in.path("clients").size();
        lines.add(count + (count == 1 ? " channel" : " channels"));
        for (JsonNode client : in.path("clients")) {
            lines.add(client.path("kind").asText() + " " + head(client.path("id").asText(), 8) + " · "
                    + client.path("requests").asLong() + " asks"
                    + (client.path("watchingCalls").asBoolean() ? " · watching calls" : ""));
        }
        if (heard.lastMcp != null) {
            lines.add("mcp · last " + heard.lastMcp.path("tool").asText() + " at "
                    + time(heard.lastMcp.path("atMillis").asLong()));
        }
        return lines;
    }

    private List<String> project(JsonNode in, Heard heard) {
        if (!in.path("project").isTextual()) return List.of("no project open");
        JsonNode history = heard.history;
        return List.of(in.path("project").asText(),
                "lock pid " + text(in.path("lockHolder"), "—") + (in.path("devMode").asBoolean() ? " · dev mode" : ""),
                history == null ? "no history heard"
                        : "undo " + (history.path("canUndo").asBoolean() ? history.path("undoLabel").asText() : "—"));
    }

    private List<String> runs(JsonNode in, Heard heard) {
        String phase = heard.runState == null ? null : heard.runState.path("phase").asText();
        if (!in.path("running").isTextual() && (phase == null || phase.equals("ENDED"))) {
            return List.of(phase == null ? "nothing runs" : "ended " + time(heard.runStateAt));
        }
        List<String> lines = new ArrayList<>();
        lines.add("running " + text(in.path("running"), "a run"));
        if (phase != null) {
            String detail = heard.runState.path("detail").asText("");
            lines.add(phase + (detail.isEmpty() ? "" : " · " + detail));
        }
        if (heard.runsStarted > 0) lines.add(heard.runsStarted + " runs");
        return lines;
    }

    private static List<String> companions(JsonNode in) {
        JsonNode list = in.path("companions");
        if (list.isEmpty()) return List.of("none running");
        List<String> lines = new ArrayList<>();
        for (JsonNode companion : list) lines.add(companion.asText());
        lines.add("with " + text(in.path("running"), "—"));
        return lines;
    }

    private static List<String> plugins(JsonNode in) {
        JsonNode list = in.path("plugins");
        if (list.isEmpty()) return List.of("none bound");
        List<String> lines = new ArrayList<>();
        for (JsonNode plugin : list) {
            lines.add(plugin.path("coordinate").asText() + " " + plugin.path("version").asText()
                    + (plugin.path("localBuild").asBoolean() ? " (local build)" : ""));
        }
        return lines;
    }

    private static List<String> calls(Heard heard) {
        long running = heard.calls.values().stream().filter(outcome -> outcome.isEmpty()).count();
        return List.of(running + " running · " + heard.calls.size() + " heard",
                heard.failed + " failed · " + heard.refused + " refused");
    }

    private List<String> lastEdit(Heard heard) {
        JsonNode edit = heard.edit;
        if (edit == null) return List.of("no edit yet");
        String methods = join(edit.path("methods"));
        String file = edit.path("file").asText("");
        return List.of((edit.path("accepted").asBoolean() ? "accepted " : "refused ") + edit.path("kind").asText()
                        + " · " + span(edit.path("tookMillis").asLong()),
                (file.isEmpty() ? "no file" : file) + (methods.isEmpty() ? "" : " · " + methods),
                text(edit.path("client"), "—") + " · " + time(edit.path("atMillis").asLong()));
    }

    /** What the cards need of the {@code seen} frames, oldest first: the latest of each, and the calls by number. */
    private static final class Heard {
        JsonNode history;
        JsonNode runState;
        long runStateAt;
        int runsStarted;
        JsonNode lastMcp;
        JsonNode edit;
        /** Each call heard starting, by its number: its outcome, empty while it runs. */
        final Map<Long, String> calls = new HashMap<>();
        int failed;
        int refused;

        Heard(List<JsonNode> seen) {
            for (JsonNode s : seen) {
                if (s.path("type").asText().equals("edit")) edit = s;
                if (!told(s)) continue;
                JsonNode e = s.path("event");
                switch (e.path("event").asText()) {
                    case "historyChanged" -> history = e;
                    case "runState" -> {
                        boolean idle = runState == null || runState.path("phase").asText().equals("ENDED");
                        // A new run, as on the page: what the last one started is not its.
                        if (idle && !e.path("phase").asText().equals("ENDED")) runsStarted = 0;
                        runState = e;
                        runStateAt = s.path("atMillis").asLong();
                    }
                    case "runsStarted" -> runsStarted = e.path("runs").size();
                    case "toolCallStarted" -> {
                        calls.put(e.path("call").asLong(), "");
                        if (s.path("source").asText().equals("mcp")) lastMcp = e;
                    }
                    case "toolCallEnded" -> {
                        long call = e.path("call").asLong();
                        // An end whose start was not heard counts nowhere, as on the page.
                        if (!calls.containsKey(call)) continue;
                        String outcome = e.path("outcome").asText();
                        calls.put(call, outcome);
                        if (outcome.equals("ERROR")) failed++;
                        if (outcome.equals("REFUSED")) refused++;
                    }
                    default -> {
                    }
                }
            }
        }
    }

    // ---- one line of the feed, as the page's summary ----

    private String summary(JsonNode s) {
        String type = s.path("type").asText();
        if (type.equals("edit")) {
            String methods = join(s.path("methods"));
            String file = s.path("file").asText("");
            String label = s.path("label").asText("");
            return (s.path("accepted").asBoolean() ? "accepted " : "refused ") + s.path("kind").asText()
                    + (file.isEmpty() ? "" : " in " + file) + (methods.isEmpty() ? "" : " · " + methods)
                    + " · " + span(s.path("tookMillis").asLong()) + (label.isEmpty() ? "" : " · " + label);
        }
        if (type.equals("client")) {
            return s.path("kind").asText() + " " + s.path("id").asText()
                    + (s.path("opened").asBoolean() ? " opened" : " closed");
        }
        JsonNode e = s.path("event");
        return switch (e.path("event").asText()) {
            case "methodsChanged" -> e.path("file").asText() + " · " + e.path("methods").size() + " changed, "
                    + e.path("removed").size() + " removed" + suffix(e.path("source"));
            case "historyChanged" -> "undo " + (e.path("canUndo").asBoolean() ? e.path("undoLabel").asText() : "—")
                    + " · redo " + (e.path("canRedo").asBoolean() ? e.path("redoLabel").asText() : "—");
            case "diagnostics" -> e.path("file").asText() + " · " + e.path("problems").size() + " problems";
            case "runState" -> e.path("phase").asText() + suffix(e.path("detail"));
            case "runOutput" -> e.path("stream").asText() + " " + e.path("text").asText();
            case "runTraced" -> e.path("level").asText() + " " + e.path("source").asText() + " " + e.path("text").asText()
                    + (e.path("count").asInt() > 1 ? " ×" + e.path("count").asInt() : "");
            case "runLogged" -> e.path("folder").asText();
            case "runFrame" -> "a frame of " + s.path("dropped").asLong() + " bytes (not shown)";
            case "runProcess" -> "pid " + e.path("pid").asLong();
            case "runsStarted" -> e.path("words").asText();
            case "debugSession" -> e.path("phase").asText();
            case "debugPaused" -> e.path("file").asText() + ":" + e.path("line").asInt();
            case "debugFollowed" -> e.path("hits").size() + " hits" + (e.path("looping").asBoolean() ? " · looping" : "");
            case "sourcesChanged" -> join(e.path("files"));
            case "inputAsked" -> e.path("kind").asText() + " · " + e.path("prompt").asText();
            case "status" -> e.path("message").asText();
            case "opened" -> String.join(" · ", texts(e.path("report")));
            case "librariesResolved" -> e.path("jars").size() + " jars"
                    + (e.path("problems").isEmpty() ? "" : " · " + e.path("problems").size() + " problems");
            case "refusal" -> (e.path("file").isTextual() && !e.path("file").asText().isEmpty()
                    ? e.path("file").asText() + " · " : "") + e.path("reason").asText();
            case "toolCallStarted" -> "#" + e.path("call").asLong() + " " + e.path("tool").asText()
                    + (e.path("plugin").asText("").isEmpty() ? "" : " (" + e.path("plugin").asText() + ")");
            case "toolCallStep" -> "#" + e.path("call").asLong() + " " + e.path("device").asText() + " "
                    + e.path("command").asText() + (e.path("ok").asBoolean() ? "" : " failed") + " · "
                    + span(e.path("millis").asLong());
            case "toolCallProgress" -> "#" + e.path("call").asLong() + " " + e.path("command").asText() + " "
                    + e.path("done").asLong() + "/" + e.path("total").asLong();
            case "toolCallEnded" -> "#" + e.path("call").asLong() + " " + e.path("outcome").asText() + " · "
                    + span(e.path("millis").asLong())
                    + (s.path("dropped").asLong() > 0 ? " · " + s.path("dropped").asLong() + " image bytes not shown" : "");
            default -> "";
        };
    }

    // ---- formats ----

    private String time(long millis) {
        return CLOCK.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    /** {@code 340 ms}, {@code 4.2 s}, {@code 3m 12s}, {@code 2h 5m}. */
    static String span(long millis) {
        if (millis < 1000) return millis + " ms";
        if (millis < 60_000) return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
        long seconds = millis / 1000;
        if (seconds < 3600) return seconds / 60 + "m " + seconds % 60 + "s";
        return seconds / 3600 + "h " + seconds % 3600 / 60 + "m";
    }

    private static String mb(long bytes) {
        return Math.round(bytes / 1048576.0) + " MB";
    }

    private static String text(JsonNode node, String none) {
        return node.isMissingNode() || node.isNull() ? none : node.asText();
    }

    private static String suffix(JsonNode detail) {
        String text = detail.asText("");
        return detail.isNull() || text.isEmpty() ? "" : " · " + text;
    }

    private static String head(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length);
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        for (JsonNode item : array) texts.add(item.asText());
        return texts;
    }

    private static String join(JsonNode array) {
        return String.join(", ", texts(array));
    }
}
