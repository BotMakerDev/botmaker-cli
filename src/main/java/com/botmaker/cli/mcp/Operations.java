// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What the core's {@code operations} tool answers (web discovery plan, phase 5): the open project, and each bound
 * plugin with its operations — its assistant tools and its declared runs — each with its parameters' JSON schema.
 * The keys are the core's {@code assist.Operations}: a change to one changes both.
 *
 * @param project the folder the core has open, empty for none
 */
public record Operations(Optional<Path> project, List<Plugin> plugins) {

    /** The tool that answers it. */
    public static final String TOOL = "operations";

    private static final ObjectMapper JSON = new ObjectMapper();
    /** The prefix a BotMaker plugin's artifactId carries, which its short name drops. */
    private static final String PREFIX = "botmaker-";

    public Operations {
        plugins = List.copyOf(plugins);
    }

    /**
     * An assistant tool, called by its MCP name, or a declared run, started by {@code run_bot}; {@link #UNKNOWN} a kind
     * a newer core lists, which this cli cannot call.
     */
    public enum Kind {
        TOOL("tool", "tool"), RUN("run", "run"), UNKNOWN("", "unknown");

        private final String id;
        private final String displayName;

        Kind(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        /** As the core writes it. */
        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /** The kind the core wrote; {@link #UNKNOWN} for one this cli does not know. */
        public static Kind fromId(String id) {
            for (Kind kind : values()) {
                if (kind != UNKNOWN && kind.id.equals(id)) return kind;
            }
            return UNKNOWN;
        }
    }

    /** One bound plugin and what it offers. */
    public record Plugin(String groupId, String artifactId, String name, String version, List<Operation> operations) {

        public Plugin {
            operations = List.copyOf(operations);
        }

        /** How the shell names it: its artifactId without {@code botmaker-}, lowercase ({@code sdk}). */
        public String shortName() {
            String id = artifactId.toLowerCase(Locale.ROOT);
            return id.startsWith(PREFIX) && id.length() > PREFIX.length() ? id.substring(PREFIX.length()) : id;
        }

        /** {@code groupId:artifactId}, the name no other plugin shares. */
        public String coordinate() {
            return groupId + ":" + artifactId;
        }

        /**
         * The operation {@code wanted} names, as {@link Operation#command()} writes it or as the plugin titles it.
         *
         * @throws IllegalArgumentException when none is, listing the ones that are, or when a tool and a run share the
         *                                  name
         */
        public Operation operation(String wanted) {
            String key = Operation.commandOf(wanted);
            List<Operation> found = operations.stream().filter(o -> o.command().equals(key)).toList();
            if (found.size() > 1) {
                throw new IllegalArgumentException(shortName() + " has " + found.size() + " operations named '" + key
                        + "' (" + found.stream().map(o -> o.kind().displayName() + " " + o.name())
                        .collect(Collectors.joining(", ")) + "): the shell cannot tell them apart.");
            }
            return found.stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(shortName() + " has no operation '" + wanted
                            + "'. It has: " + (operations.isEmpty() ? "none"
                            : operations.stream().map(Operation::command).collect(Collectors.joining(", "))) + "."));
        }
    }

    /**
     * One operation.
     *
     * @param call   the MCP name a tool is called by; empty for a run
     * @param schema its parameters, a JSON schema object
     */
    public record Operation(Kind kind, String name, String call, String description, JsonNode schema) {

        /** How the shell names it: lowercase, words joined by {@code -} ({@code keep-exploring}, {@code crop-picture}). */
        public String command() {
            return commandOf(name);
        }

        static String commandOf(String name) {
            return name.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        }
    }

    /**
     * {@code text} as the core wrote it.
     *
     * @throws IOException when it is not the answer of a core that has the tool
     */
    public static Operations read(String text) throws IOException {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (IOException e) {
            throw new IOException("BotMaker's core answered no list of operations: " + text.strip());
        }
        if (root == null || !root.path("plugins").isArray()) {
            throw new IOException("BotMaker's core answered no list of operations: " + text.strip());
        }
        List<Plugin> plugins = new ArrayList<>();
        for (JsonNode plugin : root.path("plugins")) {
            List<Operation> operations = new ArrayList<>();
            for (JsonNode op : plugin.path("operations")) {
                operations.add(new Operation(Kind.fromId(op.path("kind").asText()), op.path("name").asText(), op.path("call").asText(""),
                        op.path("description").asText(""), op.path("schema")));
            }
            plugins.add(new Plugin(plugin.path("groupId").asText(), plugin.path("artifactId").asText(),
                    plugin.path("name").asText(), plugin.path("version").asText(""), operations));
        }
        JsonNode project = root.path("project");
        return new Operations(project.isTextual() ? Optional.of(Path.of(project.asText())) : Optional.empty(),
                plugins);
    }

    /**
     * The plugin {@code wanted} names: its short name, its artifactId or its {@code groupId:artifactId}.
     *
     * @throws IllegalArgumentException when none is, listing the ones that are, or when two share the short name,
     *                                  listing both
     */
    public Plugin plugin(String wanted) {
        String key = wanted.strip().toLowerCase(Locale.ROOT);
        List<Plugin> found = plugins.stream().filter(p -> key.contains(":")
                ? p.coordinate().toLowerCase(Locale.ROOT).equals(key)
                : p.shortName().equals(key) || p.artifactId().toLowerCase(Locale.ROOT).equals(key)).toList();
        if (found.size() > 1) {
            throw new IllegalArgumentException("Several plugins are named '" + wanted.strip() + "': "
                    + found.stream().map(Plugin::coordinate).collect(Collectors.joining(", "))
                    + ". Name one by groupId:artifactId.");
        }
        return found.stream().findFirst().orElseThrow(() -> new IllegalArgumentException("No plugin '"
                + wanted.strip() + "' has operations here. " + (plugins.isEmpty() ? "None has."
                : "These have: " + plugins.stream().map(Plugin::shortName).collect(Collectors.joining(", ")) + ".")));
    }
}
