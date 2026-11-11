package com.botmaker.cli.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The AI command-line tools BotMaker starts, and how each is told about the MCP server.
 *
 * <p>Each one runs in a terminal, inside Studio or not, logged in however the user logged it in (a Claude
 * subscription included, which no API client can use). Each is given the MCP server and <b>denied direct file
 * edits</b> in its own vocabulary, so a change to the bot goes through the MCP tools: palette ids, values the
 * grammar reads, a compile after every edit, one undo step each ({@code 38-llm-edits.md}).
 *
 * <p>The server is Studio's HTTP endpoint ({@link Connection.Http}) or a command speaking MCP on its standard
 * streams, {@code botmaker mcp-bridge} ({@link Connection.Stdio}), which needs no Studio. <b>The token never goes
 * on a command line</b>, where {@code ps} shows it to every user: it is in a config file only the user can read,
 * in a directory deleted when the session goes, or in the tool's environment.
 */
public enum AiTool {
    CLAUDE_CODE("claude-code", "Claude Code", "claude", "npm install -g @anthropic-ai/claude-code"),
    CODEX("codex", "Codex", "codex", "npm install -g @openai/codex"),
    GEMINI("gemini", "Gemini CLI", "gemini", "npm install -g @google/gemini-cli"),
    OPENCODE("opencode", "opencode", "opencode", "curl -fsSL https://opencode.ai/install | bash"),
    UNKNOWN("unknown", "Unknown tool", "", "");

    /** The name the server is registered under in every tool, which is also Claude Code's tool prefix. */
    public static final String SERVER = "botmaker-studio";
    /** The environment variable Codex reads the bearer token from. */
    static final String TOKEN_ENV = "BOTMAKER_MCP_TOKEN";

    /** Claude Code's file-writing tools. Its Bash can write files too, so it is denied unless allowed. */
    public static final List<String> CLAUDE_EDIT_TOOLS = List.of("Edit", "Write", "NotebookEdit");

    private static final String CLAUDE_RULE = "Change the user's bot only through the " + SERVER
            + " MCP tools (read_tree, list_palette, edit_code, list_errors); direct file edits are disabled in this "
            + "session.";

    /** The work rules appended to Claude Code's prompt: Studio's screen is there behind an HTTP endpoint only. */
    static String claudePrompt(Connection connection) {
        return switch (connection) {
            case Connection.Http ignored -> "You are running inside BotMaker Studio. " + CLAUDE_RULE;
            case Connection.Stdio ignored -> "You are working on a BotMaker bot. " + CLAUDE_RULE;
        };
    }

    /**
     * Where a package manager puts a tool on macOS, which an app opened from Finder lacks on its {@code PATH}
     * (launchd's has no Homebrew). The cli has no {@code botmaker-shared}, whose {@code Executables} keeps the same list.
     */
    static final List<String> MAC_EXTRA_DIRS = List.of("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private final String id;
    private final String displayName;
    private final String executable;
    private final String installHint;

    AiTool(String id, String displayName, String executable, String installHint) {
        this.id = id;
        this.displayName = displayName;
        this.executable = executable;
        this.installHint = installHint;
    }

    /** The MCP server a tool is pointed at, and a private directory this session's config files may go in. */
    public sealed interface Connection {
        Path configDir();

        /** Studio's endpoint at {@code url}, which takes {@code token} as a bearer token. */
        record Http(String url, String token, Path configDir) implements Connection {}

        /** A server the tool starts itself and speaks MCP to on its standard streams; the executable first. */
        record Stdio(List<String> command, Path configDir) implements Connection {
            public Stdio {
                if (command.isEmpty()) throw new IllegalArgumentException("no command to start the server");
                command = List.copyOf(command);
            }

            String executable() {
                return command.getFirst();
            }

            List<String> args() {
                return command.subList(1, command.size());
            }
        }
    }

    /** The program's arguments (the executable first) and what to add to its environment. */
    public record Launch(List<String> command, Map<String, String> env) {
        public Launch {
            command = List.copyOf(command);
            env = Map.copyOf(env);
        }
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String executable() {
        return executable;
    }

    public String installHint() {
        return installHint;
    }

    /** The tools a user can pick, in menu order. */
    public static List<AiTool> offered() {
        return List.of(CLAUDE_CODE, CODEX, GEMINI, OPENCODE);
    }

    /** The tool {@code id} names, or {@link #UNKNOWN}. */
    public static AiTool fromId(String id) {
        for (AiTool tool : values()) {
            if (tool.id.equals(id)) return tool;
        }
        return UNKNOWN;
    }

    /** The offered tool a person names by its id or its executable ({@code claude}), or {@link #UNKNOWN}. */
    public static AiTool named(String name) {
        for (AiTool tool : offered()) {
            if (tool.id.equals(name) || tool.executable.equals(name)) return tool;
        }
        return UNKNOWN;
    }

    /** One line saying how this tool is kept from writing files, for the session's status. */
    public String guard(boolean allowShell) {
        return switch (this) {
            case CLAUDE_CODE -> allowShell ? "Edit and Write denied; shell commands allowed"
                    : "Edit, Write and shell commands denied";
            case CODEX -> "read-only sandbox: it can read and run commands, not write files";
            case GEMINI -> allowShell ? "write_file and replace excluded; shell commands allowed"
                    : "write_file, replace and shell commands excluded";
            case OPENCODE -> allowShell ? "edits denied; shell commands ask first" : "edits and shell commands denied";
            case UNKNOWN -> "";
        };
    }

    /**
     * How to start this tool at {@code executable} against {@code connection}. Writes the tool's config file
     * into {@code connection.configDir()}, readable by the user only, when the tool takes one.
     */
    public Launch launch(Path executable, Connection connection, boolean allowShell) throws IOException {
        String exe = executable.toString();
        Path dir = connection.configDir();
        return switch (this) {
            case CLAUDE_CODE -> {
                Path config = writeClaudeConfig(dir.resolve("claude-mcp.json"), connection);
                List<String> command = new ArrayList<>(List.of(exe));
                command.addAll(claudeArguments(config, connection, allowShell ? List.of() : List.of("Bash")));
                yield new Launch(command, Map.of());
            }
            case CODEX -> {
                List<String> command = new ArrayList<>(List.of(exe, "--sandbox", "read-only"));
                String key = "mcp_servers." + SERVER;
                yield switch (connection) {
                    case Connection.Http http -> {
                        command.addAll(List.of("-c", key + ".url=" + toml(http.url()),
                                "-c", key + ".bearer_token_env_var=" + toml(TOKEN_ENV)));
                        yield new Launch(command, Map.of(TOKEN_ENV, http.token()));
                    }
                    case Connection.Stdio stdio -> {
                        command.addAll(List.of("-c", key + ".command=" + toml(stdio.executable()),
                                "-c", key + ".args=" + toml(stdio.args())));
                        yield new Launch(command, Map.of());
                    }
                };
            }
            case GEMINI -> {
                List<String> excluded = new ArrayList<>(List.of("write_file", "replace"));
                if (!allowShell) excluded.add("run_shell_command");
                Map<String, Object> server = new LinkedHashMap<>();
                switch (connection) {
                    case Connection.Http http -> {
                        server.put("httpUrl", http.url());
                        server.put("headers", Map.of("Authorization", "Bearer " + http.token()));
                    }
                    case Connection.Stdio stdio -> {
                        server.put("command", stdio.executable());
                        server.put("args", stdio.args());
                    }
                }
                server.put("trust", true);
                Path settings = writePrivate(dir.resolve("gemini-settings.json"), Map.of(
                        "mcpServers", Map.of(SERVER, server), "tools", Map.of("exclude", excluded)));
                yield new Launch(List.of(exe), Map.of("GEMINI_CLI_SYSTEM_SETTINGS_PATH", settings.toString()));
            }
            case OPENCODE -> {
                Map<String, Object> server = switch (connection) {
                    case Connection.Http http -> Map.of("type", "remote", "url", http.url(),
                            "headers", Map.of("Authorization", "Bearer " + http.token()), "enabled", true);
                    case Connection.Stdio stdio -> Map.of("type", "local", "command", stdio.command(),
                            "enabled", true);
                };
                Map<String, Object> config = new LinkedHashMap<>();
                config.put("$schema", "https://opencode.ai/config.json");
                config.put("mcp", Map.of(SERVER, server));
                config.put("permission", Map.of("edit", "deny", "bash", allowShell ? "ask" : "deny"));
                Path file = writePrivate(dir.resolve("opencode.json"), config);
                yield new Launch(List.of(exe), Map.of("OPENCODE_CONFIG", file.toString()));
            }
            case UNKNOWN -> throw new IllegalStateException("no tool to launch");
        };
    }

    /**
     * Where this tool is installed: {@code path} (a {@code PATH} value) first, then the usual per-user install
     * directories, which a desktop-launched Studio's {@code PATH} often lacks.
     */
    public Optional<Path> locate(String path, Path home) {
        if (this == UNKNOWN) return Optional.empty();
        List<Path> dirs = new ArrayList<>();
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (!dir.isBlank()) dirs.add(Path.of(dir));
            }
        }
        for (String extra : List.of(".local/bin", ".opencode/bin", ".npm-global/bin", ".bun/bin", "bin")) {
            dirs.add(home.resolve(extra));
        }
        if (isMac()) {
            for (String dir : MAC_EXTRA_DIRS) dirs.add(Path.of(dir));
        }
        List<String> names = isWindows() ? List.of(executable + ".exe", executable + ".cmd") : List.of(executable);
        for (Path dir : dirs) {
            for (String name : names) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Asks the user's login shell where this tool is, for a tool installed through a version manager that only
     * a login shell puts on {@code PATH}. Blocks for up to three seconds; never call it on the FX thread.
     */
    public Optional<Path> locateByLoginShell(String shell) {
        if (this == UNKNOWN) return Optional.empty();
        try {
            Process probe = new ProcessBuilder(posixShell(shell), "-lc", "command -v " + executable)
                    .redirectErrorStream(true).start();
            if (!probe.waitFor(3, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return Optional.empty();
            }
            String out = new String(probe.getInputStream().readAllBytes()).strip();
            if (probe.exitValue() != 0 || out.isEmpty()) return Optional.empty();
            Path found = Path.of(out.lines().reduce((a, b) -> b).orElse(out));
            return Files.isExecutable(found) ? Optional.of(found) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * {@code command} run by the user's login shell, which puts on {@code PATH} what a tool installed through
     * {@code nvm}, {@code mise} or similar needs to start ({@code node}, mostly) and a desktop-launched Studio
     * lacks. The shell {@code exec}s the tool, so the terminal's process is the tool. Unchanged on Windows.
     */
    public static List<String> viaLoginShell(String shell, List<String> command) {
        if (isWindows()) return command;
        List<String> wrapped = new ArrayList<>(List.of(posixShell(shell), "-lc", "exec \"$0\" \"$@\""));
        wrapped.addAll(command);
        return wrapped;
    }

    /**
     * {@code shell} when it speaks POSIX {@code sh}; fish, nu and an unset {@code $SHELL} hand over to bash, or on
     * a Mac to zsh, whose {@code ~/.zprofile} is where {@code brew shellenv} puts Homebrew on {@code PATH}.
     */
    static String posixShell(String shell) {
        String name = shell == null || shell.isBlank() ? "" : Path.of(shell).getFileName().toString();
        if (List.of("bash", "zsh", "sh", "ksh", "dash").contains(name)) return shell;
        return isMac() ? "/bin/zsh" : "/bin/bash";
    }

    /**
     * Claude Code's MCP config for the server {@code connection} names, written to {@code file} readable by the
     * user only: the token stays out of the command line, where shell history and every process listing would
     * show it.
     */
    public static Path writeClaudeConfig(Path file, Connection connection) throws IOException {
        Map<String, Object> server = switch (connection) {
            case Connection.Http http -> Map.of("type", "http", "url", http.url(),
                    "headers", Map.of("Authorization", "Bearer " + http.token()));
            case Connection.Stdio stdio -> Map.of("type", "stdio", "command", stdio.executable(),
                    "args", stdio.args());
        };
        return writePrivate(file, Map.of("mcpServers", Map.of(SERVER, server)));
    }

    /**
     * Claude Code's arguments after its executable: the config at {@code config}, the MCP tools allowed without a
     * prompt, the edit tools and {@code alsoDenied} denied, and the work rules for {@code connection} appended to its
     * prompt.
     */
    public static List<String> claudeArguments(Path config, Connection connection, List<String> alsoDenied) {
        List<String> args = new ArrayList<>(List.of("--mcp-config", config.toString(),
                "--allowedTools", "mcp__" + SERVER, "--disallowedTools"));
        args.addAll(CLAUDE_EDIT_TOOLS);
        args.addAll(alsoDenied);
        args.addAll(List.of("--append-system-prompt", claudePrompt(connection)));
        return args;
    }

    /**
     * {@code value}, a string or a list of them, as a TOML value for Codex's {@code -c key=value}. A literal string
     * ({@code 'C:\bin'}) where it can be one: it needs no escape, and a double quote would not survive Windows'
     * command line, which Java does not escape. Otherwise a basic string, whose JSON form is also TOML.
     */
    static String toml(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(AiTool::toml).collect(Collectors.joining(",", "[", "]"));
        }
        String text = value.toString();
        if (text.indexOf('\'') < 0 && text.chars().noneMatch(c -> c < 0x20 || c == 0x7f)) return "'" + text + "'";
        try {
            return JSON.writeValueAsString(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static boolean isWindows() {
        return OS.startsWith("windows");
    }

    static boolean isMac() {
        return OS.startsWith("mac");
    }

    /** Writes {@code value} as JSON to {@code file}, readable and writable by the user only where the OS says so. */
    private static Path writePrivate(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.deleteIfExists(file);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException e) {
            Files.createFile(file);
        }
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        return file;
    }
}
