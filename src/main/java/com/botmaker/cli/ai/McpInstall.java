package com.botmaker.cli.ai;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * BotMaker's MCP server in a tool's <b>user-level</b> config, for whoever starts the tool themselves: every session of
 * it, in any folder, gets the tools. It adds no guard; a bot's folder carries that ({@link BotGuard}), and
 * {@code botmaker ai} brings both. The server is named {@link AiTool#SERVER}, as {@code botmaker ai} and Studio's
 * Assistant window name theirs, so a session they start replaces this entry rather than doubling every tool.
 *
 * <p>Claude Code keeps its user config in a file it rewrites while it runs, so it is registered through its own
 * {@code claude mcp add} ({@link #claudeCommands}); the others' files are edited here, only the server's own entry.
 */
public final class McpInstall {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String OPENCODE_SCHEMA = "https://opencode.ai/config.json";

    /** A table header of the server's own, or of one nested in it: {@code [mcp_servers.botmaker-studio.env]}. */
    private static final Pattern CODEX_OWN_HEADER = Pattern.compile(
            "\\[\\s*mcp_servers\\s*\\.\\s*(\"" + Pattern.quote(AiTool.SERVER) + "\"|" + Pattern.quote(AiTool.SERVER)
                    + ")\\s*(\\.[^]]*)?]\\s*(#.*)?");

    private McpInstall() {}

    /** How the tool starts the server: a command and the environment it adds. */
    public record Server(List<String> command, Map<String, String> env) {
        public Server {
            if (command.isEmpty()) throw new IllegalArgumentException("no command");
            command = List.copyOf(command);
            env = Map.copyOf(env);
        }

        String executable() {
            return command.getFirst();
        }

        List<String> args() {
            return command.subList(1, command.size());
        }
    }

    public enum Result { WRITTEN, ALREADY, LEFT_ALONE }

    /** What an install did to {@code file}, and, when it was {@link Result#LEFT_ALONE}, why. */
    public record Done(Result result, Path file, String why) {}

    /**
     * The user-level config file {@code tool} reads, under {@code home} and the variables {@code env} sets; empty for
     * Claude Code, which is registered by its own command.
     */
    public static Optional<Path> configFile(AiTool tool, Path home, Map<String, String> env) {
        return switch (tool) {
            case CODEX -> Optional.of(dir(env.get("CODEX_HOME"), home.resolve(".codex")).resolve("config.toml"));
            case OPENCODE -> Optional.of(dir(env.get("XDG_CONFIG_HOME"), home.resolve(".config"))
                    .resolve("opencode").resolve("opencode.json"));
            case GEMINI -> Optional.of(home.resolve(".gemini").resolve("settings.json"));
            case CLAUDE_CODE, UNKNOWN -> Optional.empty();
        };
    }

    private static Path dir(String variable, Path fallback) {
        return variable == null || variable.isBlank() ? fallback : Path.of(variable);
    }

    /** Puts {@code server} in {@code tool}'s config {@code file}, replacing the entry an earlier install wrote. */
    public static Done install(AiTool tool, Path file, Server server) throws IOException {
        return switch (tool) {
            case CODEX -> codex(file, server);
            case OPENCODE -> opencode(file, server);
            case GEMINI -> gemini(file, server);
            case CLAUDE_CODE, UNKNOWN -> throw new IllegalArgumentException(tool + " has no config file here");
        };
    }

    /** What {@code --print} shows for {@code tool}: the entry to add to its file, or Claude Code's commands. */
    public static String preview(AiTool tool, Path claude, Server server) {
        try {
            return switch (tool) {
                case CODEX -> codexTable(server);
                case OPENCODE -> JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("mcp", Map.of(AiTool.SERVER, opencodeEntry(server)))) + "\n";
                case GEMINI -> JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("mcpServers", Map.of(AiTool.SERVER, geminiEntry(server)))) + "\n";
                case CLAUDE_CODE -> String.join(" ", quoted(claudeCommands(claude, server).getLast()));
                case UNKNOWN -> throw new IllegalArgumentException("no tool");
            };
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Claude Code's commands, run in order: the entry an earlier install made removed (it fails harmlessly when there
     * is none), then the server added in user scope. The name comes before {@code -e}, which takes several values.
     */
    public static List<List<String>> claudeCommands(Path claude, Server server) {
        String exe = claude.toString();
        List<String> add = new ArrayList<>(List.of(exe, "mcp", "add", AiTool.SERVER, "--scope", "user"));
        new TreeMap<>(server.env()).forEach((k, v) -> add.addAll(List.of("-e", k + "=" + v)));
        add.add("--");
        add.addAll(server.command());
        return List.of(List.of(exe, "mcp", "remove", AiTool.SERVER, "--scope", "user"), add);
    }

    private static List<String> quoted(List<String> command) {
        return command.stream().map(a -> a.isEmpty() || a.matches(".*[\\s\"'].*") ? '"' + a + '"' : a).toList();
    }

    // --- Codex: one TOML table, edited as lines ---

    static String codexTable(Server server) {
        StringBuilder table = new StringBuilder("[mcp_servers." + AiTool.SERVER + "]\n")
                .append("command = ").append(AiTool.toml(server.executable())).append('\n')
                .append("args = ").append(AiTool.toml(server.args())).append('\n');
        if (!server.env().isEmpty()) {
            List<String> pairs = new ArrayList<>();
            new TreeMap<>(server.env()).forEach((k, v) -> pairs.add(k + " = " + AiTool.toml(v)));
            table.append("env = { ").append(String.join(", ", pairs)).append(" }\n");
        }
        return table.toString();
    }

    /**
     * The server's table, and any nested in it, taken out of {@code file} and written at its end. A file whose shape
     * a line reader cannot be sure of is left alone: a multi-line string, or the server named another way (a dotted
     * key, an inline table).
     */
    static Done codex(Path file, Server server) throws IOException {
        String old = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        if (old.contains("\"\"\"") || old.contains("'''")) {
            return new Done(Result.LEFT_ALONE, file, "it has a multi-line string; add the entry yourself");
        }
        List<String> kept = new ArrayList<>();
        boolean inOwn = false;
        for (String line : old.split("\n", -1)) {
            String bare = line.strip();
            if (bare.startsWith("[")) inOwn = CODEX_OWN_HEADER.matcher(bare).matches();
            if (!inOwn) kept.add(line);
        }
        if (kept.stream().anyMatch(l -> l.contains(AiTool.SERVER))) {
            return new Done(Result.LEFT_ALONE, file, "it names " + AiTool.SERVER + " outside its own table");
        }
        String rest = String.join("\n", kept).stripTrailing();
        String text = (rest.isEmpty() ? "" : rest + "\n\n") + codexTable(server);
        if (text.equals(old)) return new Done(Result.ALREADY, file, "");
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return new Done(Result.WRITTEN, file, "");
    }

    // --- opencode and Gemini CLI: one key of a JSON object ---

    static Map<String, Object> opencodeEntry(Server server) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "local");
        entry.put("command", server.command());
        if (!server.env().isEmpty()) entry.put("environment", server.env());
        entry.put("enabled", true);
        return entry;
    }

    static Map<String, Object> geminiEntry(Server server) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("command", server.executable());
        entry.put("args", server.args());
        if (!server.env().isEmpty()) entry.put("env", server.env());
        return entry;
    }

    static Done opencode(Path file, Server server) throws IOException {
        Path jsonc = file.resolveSibling("opencode.jsonc");
        if (Files.notExists(file) && Files.exists(jsonc)) {
            return new Done(Result.LEFT_ALONE, jsonc, "a JSON-with-comments file would lose its comments");
        }
        return jsonKey(file, "mcp", opencodeEntry(server), true);
    }

    static Done gemini(Path file, Server server) throws IOException {
        return jsonKey(file, "mcpServers", geminiEntry(server), false);
    }

    /**
     * {@code entry} at {@code group.SERVER} of the JSON object in {@code file}, everything else kept. A file that is not
     * a plain JSON object (comments, another shape) is left alone, and so is a {@code group} that is not an object.
     */
    private static Done jsonKey(Path file, String group, Object entry, boolean schema) throws IOException {
        ObjectNode root;
        if (Files.exists(file)) {
            JsonNode read;
            try {
                read = JSON.readTree(file.toFile());
            } catch (JacksonException e) {
                return new Done(Result.LEFT_ALONE, file, "it is not plain JSON (comments?); add the entry yourself");
            }
            if (read == null || read.isMissingNode()) read = JSON.createObjectNode();
            if (!(read instanceof ObjectNode object)) {
                return new Done(Result.LEFT_ALONE, file, "it is not a JSON object");
            }
            root = object;
        } else {
            root = JSON.createObjectNode();
            if (schema) root.put("$schema", OPENCODE_SCHEMA);
        }
        JsonNode servers = root.path(group);
        if (!servers.isMissingNode() && !servers.isObject()) {
            return new Done(Result.LEFT_ALONE, file, group + " is not a JSON object");
        }
        JsonNode wanted = JSON.valueToTree(entry);
        if (wanted.equals(servers.get(AiTool.SERVER))) return new Done(Result.ALREADY, file, "");
        (servers.isObject() ? (ObjectNode) servers : root.putObject(group)).set(AiTool.SERVER, wanted);
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        return new Done(Result.WRITTEN, file, "");
    }

    // --- --dev: this checkout's build ---

    /** The umbrella {@code start} is in or under: the first folder up holding both the cli's and Studio's poms. */
    public static Optional<Path> umbrellaAbove(Path start) {
        for (Path dir = start.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            if (isUmbrella(dir)) return Optional.of(dir);
        }
        return Optional.empty();
    }

    public static boolean isUmbrella(Path dir) {
        return Files.isRegularFile(dir.resolve("botmaker-cli/pom.xml"))
                && Files.isRegularFile(dir.resolve("botmaker-studio/pom.xml"));
    }

    /** The checkout's newest built {@code -all} jar of the cli. */
    public static Optional<Path> builtJar(Path umbrella) throws IOException {
        Path target = umbrella.resolve("botmaker-cli").resolve("target");
        if (!Files.isDirectory(target)) return Optional.empty();
        try (Stream<Path> files = Files.list(target)) {
            return files.filter(f -> f.getFileName().toString().matches("botmaker-cli-.*-all\\.jar"))
                    .max(Comparator.comparingLong(f -> f.toFile().lastModified()));
        }
    }
}
