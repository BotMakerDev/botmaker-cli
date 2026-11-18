// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One operation's parameters as command-line options (web discovery plan, phase 5), built from its JSON schema with
 * picocli's programmatic model, so its usage text is the same options that parse: {@code --<field> <value>}, a nested
 * record's fields as {@code --<field>.<inner>}, a list repeated or split at commas, a map as {@code key=value}, a list
 * of records as one JSON object per value, an enum's choices offered. What parses is put back as the arguments the
 * schema takes, for the core to check in its own words.
 */
public final class OperationOptions {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CommandSpec spec;
    /** Each option's dotted path, by its longest name. */
    private final Map<String, Kind> kinds = new LinkedHashMap<>();

    /** How a parsed value is put back. */
    private enum Kind {
        TEXT, INTEGER, NUMBER, BOOLEAN, LIST, MAP, RECORD_LIST
    }

    /** {@code operation}'s options; {@code invocation} ({@code botmaker tool sdk keep-exploring}) heads the usage. */
    public OperationOptions(String invocation, Operations.Operation operation) {
        spec = CommandSpec.create().name(invocation);
        spec.usageMessage().description(operation.description().isBlank()
                ? (operation.kind() == Operations.Kind.RUN ? "A run: it starts as its toolbar button does." : "")
                : operation.description());
        spec.mixinStandardHelpOptions(false);
        add("", operation.schema(), true);
    }

    private void add(String prefix, JsonNode schema, boolean required) {
        Set<String> needed = new HashSet<>();
        schema.path("required").forEach(n -> needed.add(n.asText()));
        Iterator<Map.Entry<String, JsonNode>> fields = schema.path("properties").fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String path = prefix + field.getKey();
            JsonNode property = field.getValue();
            boolean must = required && needed.contains(field.getKey());
            String type = property.path("type").asText("string");
            if (type.equals("object") && property.has("properties")) {
                add(path + ".", property, must);
                continue;
            }
            OptionSpec.Builder option = OptionSpec.builder("--" + path).required(must)
                    .description(property.path("description").asText(""));
            List<String> choices = choices(property);
            Kind kind;
            switch (type) {
                case "integer" -> {
                    kind = Kind.INTEGER;
                    option.type(Long.class).paramLabel("<integer>");
                }
                case "number" -> {
                    kind = Kind.NUMBER;
                    option.type(Double.class).paramLabel("<number>");
                }
                case "boolean" -> {
                    kind = Kind.BOOLEAN;
                    option.type(Boolean.class).arity("0..1").fallbackValue("true");
                }
                case "array" -> {
                    JsonNode items = property.path("items");
                    boolean records = items.path("type").asText().equals("object");
                    kind = records ? Kind.RECORD_LIST : Kind.LIST;
                    choices = choices(items);
                    option.type(List.class).auxiliaryTypes(records ? String.class : scalar(items))
                            .paramLabel(records ? "<json>" : choices.isEmpty() ? label(items) : "<choice>");
                    if (!records) option.splitRegex(",");
                }
                case "object" -> {
                    kind = Kind.MAP;
                    option.type(Map.class).auxiliaryTypes(String.class, scalar(property.path("additionalProperties")))
                            .paramLabel("<key=value>");
                }
                default -> {
                    kind = Kind.TEXT;
                    option.type(String.class).paramLabel(choices.isEmpty() ? "<text>" : "<choice>");
                }
            }
            if (!choices.isEmpty()) {
                option.completionCandidates(choices);
                option.description(property.path("description").asText("") + " One of: "
                        + String.join(", ", choices) + ".");
            }
            spec.addOption(option.build());
            kinds.put("--" + path, kind);
        }
    }

    /** The Java type a list's item or a map's value parses to, so it reaches the core as its schema's type. */
    private static Class<?> scalar(JsonNode schema) {
        return switch (schema.path("type").asText("string")) {
            case "integer" -> Long.class;
            case "number" -> Double.class;
            case "boolean" -> Boolean.class;
            default -> String.class;
        };
    }

    private static String label(JsonNode schema) {
        return switch (schema.path("type").asText("string")) {
            case "integer" -> "<integer>";
            case "number" -> "<number>";
            case "boolean" -> "<boolean>";
            default -> "<text>";
        };
    }

    private static List<String> choices(JsonNode property) {
        List<String> out = new ArrayList<>();
        property.path("enum").forEach(n -> out.add(n.asText()));
        return out;
    }

    /** Every option's name, for the shell's completion. */
    public List<String> names() {
        return spec.options().stream().map(OptionSpec::longestName).toList();
    }

    /** The choices {@code option} takes, for the shell's completion; none when it is no option of choices. */
    public List<String> choices(String option) {
        OptionSpec found = spec.findOption(option);
        if (found == null || found.completionCandidates() == null) return List.of();
        List<String> out = new ArrayList<>();
        found.completionCandidates().forEach(out::add);
        return out;
    }

    /** The usage text: each option, its kind and what it is for. */
    public String usage() {
        return new CommandLine(spec).getUsageMessage();
    }

    /** One line of the options, for a list of operations: {@code --minutes <integer> [--maxScreens <integer>]}. */
    public String synopsis() {
        List<String> out = new ArrayList<>();
        for (OptionSpec option : spec.options()) {
            String one = option.longestName() + (option.arity().max() == 0 || kinds.get(option.longestName())
                    == Kind.BOOLEAN ? "" : " " + option.paramLabel());
            out.add(option.required() ? one : "[" + one + "]");
        }
        return String.join(" ", out);
    }

    /**
     * {@code words} parsed as the arguments the schema takes: nested records rebuilt from their dotted options, an
     * option not given left out.
     *
     * @throws CommandLine.ParameterException with picocli's sentence when the words do not parse
     * @throws IllegalArgumentException       when a list of records is given a value that is no JSON object
     */
    public Map<String, Object> arguments(List<String> words) {
        ParseResult parsed = new CommandLine(spec).parseArgs(words.toArray(String[]::new));
        Map<String, Object> out = new LinkedHashMap<>();
        for (OptionSpec option : parsed.matchedOptions()) {
            String name = option.longestName();
            Object value = parsed.matchedOptionValue(name, null);
            if (value == null) continue;
            put(out, name.substring(2).split("\\."), switch (kinds.get(name)) {
                case RECORD_LIST -> records(name, value);
                default -> value;
            });
        }
        return out;
    }

    private static List<Object> records(String option, Object value) {
        List<Object> out = new ArrayList<>();
        for (Object one : (List<?>) value) {
            try {
                JsonNode node = JSON.readTree(String.valueOf(one));
                if (node == null || !node.isObject()) throw new IOException("not an object");
                out.add(JSON.convertValue(node, Map.class));
            } catch (IOException e) {
                throw new IllegalArgumentException(option + " takes a JSON object, {\"name\": …}: " + one);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> into, String[] path, Object value) {
        Map<String, Object> at = into;
        for (int i = 0; i < path.length - 1; i++) {
            at = (Map<String, Object>) at.computeIfAbsent(path[i], k -> new LinkedHashMap<String, Object>());
        }
        at.put(path[path.length - 1], value);
    }
}
