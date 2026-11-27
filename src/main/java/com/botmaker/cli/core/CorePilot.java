// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The core's Remote Pilot from the shell ({@code POST /pilot}, bearer-authenticated; not an MCP tool, so no assistant
 * reads the pairing link it answers with).
 */
public final class CorePilot {

    static final String PILOT = "/pilot";
    /** A start waits in the core for its tunnel, up to 90 s. */
    private static final Duration START = Duration.ofSeconds(100);
    private static final Duration QUICK = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private CorePilot() {}

    /** What the core refused: its sentence, shown as it is. */
    public static final class Refused extends IOException {
        public Refused(String message) {
            super(message);
        }
    }

    /**
     * Asks the pilot of the core at {@code at} to {@code act} ({@code status}, {@code start}, {@code stop} or
     * {@code reset}); {@code transport} is a start's way (an id like {@code quick-tunnel}), or null for the remembered one.
     *
     * @return the pilot's state as the core gave it
     * @throws ConnectException when no core answers
     * @throws Refused          when the core answered with why not
     */
    public static JsonNode ask(CoreFeed.At at, String act, String transport) throws IOException {
        if (!CoreFeed.answers(at)) throw new ConnectException(CoreFeed.NONE);
        ObjectNode body = JSON.createObjectNode().put("act", act);
        if (transport != null) body.put("transport", transport);
        HttpRequest request = HttpRequest.newBuilder(at.resolve(PILOT)).header("Authorization", "Bearer " + at.token())
                .timeout("start".equals(act) ? START : QUICK)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            HttpResponse<String> answer;
            try {
                answer = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException silent) {
                throw new IOException("The core did not answer the Remote Pilot in time.", silent);
            }
            if (answer.statusCode() == 409) throw new Refused(reason(answer.body()));
            if (answer.statusCode() != 200) throw new IOException("The core's Remote Pilot answered HTTP "
                    + answer.statusCode() + ".");
            return JSON.readTree(answer.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", interrupted);
        }
    }

    /** The sentence in Jetty's error page, or the body as it is. */
    private static String reason(String body) {
        String found = body.replaceAll("(?s).*<h2>HTTP ERROR 409 (.*?)</h2>.*", "$1").strip();
        return found.equals(body.strip()) || found.isBlank() ? "The core refused the Remote Pilot." : found;
    }

    /** What the pilot is, in a few lines and without its pairing link. */
    public static List<String> describe(JsonNode state) {
        List<String> lines = new ArrayList<>();
        String phase = state.path("phase").asText("OFF");
        switch (phase) {
            case "ON" -> lines.add("Remote Pilot is on, over " + kindName(state.path("kind")) + ".");
            case "STARTING" -> lines.add("Remote Pilot is starting, over " + kindName(state.path("asked")) + ".");
            default -> {
                lines.add("Remote Pilot is off.");
                if (!state.path("problem").isNull() && !state.path("problem").isMissingNode()) {
                    lines.add(state.path("problem").asText());
                    if (state.path("fix").isTextual()) lines.add("  " + state.path("fix").asText());
                }
            }
        }
        JsonNode phones = state.path("phones");
        if (phones.isArray() && !phones.isEmpty()) {
            lines.add(phones.size() + (phones.size() == 1 ? " phone connected:" : " phones connected:"));
            for (JsonNode phone : phones) {
                lines.add("  " + phone.path("id").asText() + "  " + phone.path("address").asText()
                        + (phone.path("interact").asBoolean() ? "  touching the PC" : "  watching"));
            }
        } else if ("ON".equals(phase)) {
            lines.add("No phone is connected.");
        }
        return lines;
    }

    /** The pairing link of a pilot that is on, else null. */
    public static String link(JsonNode state) {
        return state.path("url").isTextual() ? state.path("url").asText() : null;
    }

    /**
     * The pairing code as text a terminal draws: two rows of modules to a line, black on white whatever the
     * terminal's colours, its quiet border included.
     */
    public static List<String> qr(JsonNode state) {
        List<String> rows = new ArrayList<>();
        for (JsonNode row : state.path("qr")) rows.add(row.asText());
        List<String> lines = new ArrayList<>();
        for (int y = 0; y < rows.size(); y += 2) {
            String top = rows.get(y);
            String bottom = y + 1 < rows.size() ? rows.get(y + 1) : "0".repeat(top.length());
            StringBuilder line = new StringBuilder();
            for (int x = 0; x < top.length(); x++) {
                // The upper half in the foreground colour, the lower in the background's: 0 is black, 15 white.
                int up = top.charAt(x) == '1' ? 0 : 15;
                int down = x < bottom.length() && bottom.charAt(x) == '1' ? 0 : 15;
                line.append("\u001b[38;5;").append(up).append(";48;5;").append(down).append("m▀");
            }
            lines.add(line.append("\u001b[0m").toString());
        }
        return lines;
    }

    private static String kindName(JsonNode kind) {
        return switch (kind.asText("")) {
            case "TAILNET" -> "Tailscale";
            case "FUNNEL" -> "Tailscale Funnel (public)";
            case "QUICK_TUNNEL" -> "a Cloudflare quick tunnel (public)";
            case "LAN" -> "the local network";
            default -> "an unknown way";
        };
    }
}
