package com.botmaker.cli.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps an AI tool started by hand in a bot's folder from writing the bot's Java (2026-10-12, every client
 * 2026-11-11): the bot changes through the MCP tools, where every edit compiles before it lands. Each client reads
 * its own project file:
 * <ul>
 *   <li>Claude Code, {@code .claude/settings.json}: the file-editing tools denied on {@code src/**}, and the shell
 *   commands that rewrite a file in place;</li>
 *   <li>opencode, {@code opencode.json}: {@code permission.edit} denied, {@code bash} asked;</li>
 *   <li>Codex, {@code .codex/config.toml}: the {@code read-only} sandbox, read once the user trusts the folder;</li>
 *   <li>every client that reads it (Codex, opencode, Cursor…), {@code AGENTS.md}: how to work on the bot.</li>
 * </ul>
 * The sessions BotMaker starts itself are denied the same on their command line or in a config that outranks these
 * ({@link AiTool}). None of these files names the MCP server: its command is this machine's.
 *
 * <p>Written as a project opens and by {@code botmaker ai}, only adding what is missing and removing none but the
 * {@link #OBSOLETE} rules an earlier guard wrote: the user's own rules and settings stay as they are (a setting the
 * user gave another value leaves that client unguarded, which {@link Guard#result} says), and a file that is not what
 * its client reads, or a link, is left alone. The three files after Claude Code's are this machine's, like
 * {@code .botmaker/}: a line of {@code .git/info/exclude} each ({@link #EXCLUDED}), so a bot's history and Publish never
 * see them unless its author adds one. A guard against slips, not a sandbox: a shell command can always be spelled
 * another way.
 */
public final class BotGuard {

    /** The file, relative to the bot's folder, that Claude Code reads a project's shared settings from. */
    public static final Path FILE = Path.of(".claude", "settings.json");
    /** opencode's project config, at the bot's root. */
    public static final Path OPENCODE = Path.of("opencode.json");
    /** Codex's project config, read only in a folder the user trusts. */
    public static final Path CODEX = Path.of(".codex", "config.toml");
    /** How to work on the bot, for every client that reads it; written when missing, never rewritten. */
    public static final Path AGENTS = Path.of("AGENTS.md");
    /** The {@code .git/info/exclude} lines that keep the files the guard adds out of the bot's versions. */
    public static final List<String> EXCLUDED = List.of("/AGENTS.md", "/opencode.json", "/.codex/");

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private BotGuard() {}

    /**
     * The shell commands denied as rules: each rewrites a file in place. Prefix rules, so another spelling
     * ({@code sed -E -i}, a redirect) passes; they catch the usual slip, and reading, git and builds still work.
     */
    public static final List<String> SHELL = List.of("Bash(sed -i *)", "Bash(sed --in-place *)", "Bash(perl -i *)",
            "Bash(perl -pi *)");

    /** Every rule the guard keeps in {@code permissions.deny}: an {@code Edit(path)} rule covers every file-editing tool. */
    static final List<String> DENY = denied();

    /**
     * Rules an earlier guard wrote, which Claude Code now warns about on every start ({@code MultiEdit} is no tool,
     * and a path rule names {@code Edit} only); removed when found, whoever wrote them, since {@code Edit(./src/**)}
     * denies all they meant to.
     */
    static final List<String> OBSOLETE = List.of("MultiEdit(./src/**)", "Write(./src/**)", "NotebookEdit(./src/**)");

    /** A {@code sandbox_mode} key, bare or quoted, and what follows its {@code =}. */
    private static final Pattern SANDBOX = Pattern.compile("^\\s*([\"']?)sandbox_mode\\1\\s*=\\s*(.*)$");

    static final String CODEX_CONFIG = """
            # Written by BotMaker: this bot changes through BotMaker's MCP tools only (AGENTS.md says how).
            # Codex reads this file once you trust this folder.
            sandbox_mode = "read-only"
            """;

    static final String AGENTS_TEXT = """
            # Working on this bot

            This is a BotMaker bot: Java that BotMaker's block editor reads and writes.

            - Change the bot only through BotMaker's MCP tools. Every edit is compiled before it lands, and the
              user can undo it. Start with `plugin_guide`: it says where each kind of work goes.
            - Read any file freely.
            - Never edit `src/` or `pom.xml` yourself, by hand or with a shell command.
            - No BotMaker tools in this session? Start it from this folder with `botmaker ai <tool>`
              (claude, codex, gemini or opencode): the tools come with it, and Studio need not be open.

            BotMaker writes this file only when it is missing; change it as you like.
            """;

    private static List<String> denied() {
        List<String> rules = new ArrayList<>(List.of("Edit(./src/**)"));
        rules.addAll(SHELL);
        return List.copyOf(rules);
    }

    /** What {@link #ensure} did to one file. */
    public enum Result {
        WRITTEN, ALREADY,
        /** Not the file its client reads, a link to a file elsewhere (a dotfile), or a setting the user chose. */
        LEFT_ALONE,
        FAILED;

        /** Whether the file now guards its client. */
        public boolean guarded() {
            return this == WRITTEN || this == ALREADY;
        }
    }

    /** One client's file and what {@link #ensure} did to it; {@code tool} is {@link AiTool#UNKNOWN} for every client's. */
    public record Guard(AiTool tool, Path file, Result result) {

        /** Why the client is not guarded, for a person, or empty when it is. */
        public Optional<String> warning(Path botFolder) {
            if (result.guarded()) return Optional.empty();
            String who = tool == AiTool.UNKNOWN ? "AI tool" : tool.displayName();
            return Optional.of(who + " sessions in " + botFolder + " are not guarded: " + file + " was "
                    + (result == Result.LEFT_ALONE
                    ? "left as it is (not what the tool reads, a link, or a setting of yours)" : "not written"));
        }
    }

    /**
     * Guards every client in {@code botFolder}, writing a file only when something is missing, and keeps the files
     * that are this machine's out of the bot's versions. Prints nothing: {@link Guard#warning} says what to.
     */
    public static List<Guard> ensure(Path botFolder) {
        List<Guard> guards = List.of(
                new Guard(AiTool.CLAUDE_CODE, FILE, claude(botFolder, FILE)),
                new Guard(AiTool.OPENCODE, OPENCODE, opencode(botFolder, OPENCODE)),
                new Guard(AiTool.CODEX, CODEX, codex(botFolder, CODEX)),
                new Guard(AiTool.UNKNOWN, AGENTS, agents(botFolder, AGENTS)));
        exclude(botFolder);
        return guards;
    }

    static Result claude(Path bot, Path file) {
        return json(bot, file, (root, created) -> {
            JsonNode permissions = root.path("permissions");
            if (!permissions.isMissingNode() && !permissions.isObject()) return Result.LEFT_ALONE;
            ObjectNode perms = permissions.isObject() ? (ObjectNode) permissions : root.putObject("permissions");
            JsonNode deny = perms.path("deny");
            if (!deny.isMissingNode() && !deny.isArray()) return Result.LEFT_ALONE;
            ArrayNode rules = deny.isArray() ? (ArrayNode) deny : perms.putArray("deny");
            boolean changed = false;
            for (int i = rules.size() - 1; i >= 0; i--) {
                if (OBSOLETE.contains(rules.get(i).asText())) {
                    rules.remove(i);
                    changed = true;
                }
            }
            List<String> present = new ArrayList<>();
            rules.forEach(r -> present.add(r.asText()));
            for (String rule : DENY) {
                if (present.contains(rule)) continue;
                rules.add(rule);
                changed = true;
            }
            return changed ? Result.WRITTEN : Result.ALREADY;
        });
    }

    /**
     * {@code permission.edit} denied and {@code permission.bash} asked, each unless the user set it; a
     * {@code opencode.jsonc} beside it is the user's config, and left alone rather than shadowed by a second one.
     */
    static Result opencode(Path bot, Path file) {
        Path jsonc = bot.resolve(file).resolveSibling("opencode.jsonc");
        if (Files.exists(jsonc) && !Files.exists(bot.resolve(file))) return Result.LEFT_ALONE;
        return json(bot, file, (root, created) -> {
            JsonNode permission = root.path("permission");
            if (!permission.isMissingNode() && !permission.isObject()) return Result.LEFT_ALONE;
            ObjectNode perms = permission.isObject() ? (ObjectNode) permission : JSON.createObjectNode();
            JsonNode edit = perms.path("edit");
            if (!edit.isMissingNode() && !"deny".equals(edit.asText())) return Result.LEFT_ALONE;
            boolean changed = false;
            if (created) root.put("$schema", "https://opencode.ai/config.json");
            if (edit.isMissingNode()) {
                perms.put("edit", "deny");
                changed = true;
            }
            if (perms.path("bash").isMissingNode()) {
                perms.put("bash", "ask");
                changed = true;
            }
            if (changed) root.set("permission", perms);
            return changed ? Result.WRITTEN : Result.ALREADY;
        });
    }

    /**
     * The {@code read-only} sandbox, unless the user chose another: written above the file's first table, where a
     * top-level key belongs. Read as lines, not TOML: a top-level {@code sandbox_mode} of any other value, or a
     * multi-line string the lines cannot see past, leaves the file as it is, so a second key never makes it invalid.
     */
    static Result codex(Path bot, Path file) {
        Path path = bot.resolve(file);
        if (linked(bot, file)) return Result.LEFT_ALONE;
        try {
            if (!Files.isRegularFile(path)) return write(path, CODEX_CONFIG);
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) text = text.substring(1);
            for (String line : text.lines().toList()) {
                if (line.stripLeading().startsWith("[")) break;
                if (line.contains("\"\"\"") || line.contains("'''")) return Result.LEFT_ALONE;
                Matcher mode = SANDBOX.matcher(line);
                if (mode.find()) {
                    return mode.group(2).matches("[\"']read-only[\"']\\s*(#.*)?") ? Result.ALREADY : Result.LEFT_ALONE;
                }
            }
            return write(path, CODEX_CONFIG + text);
        } catch (IOException e) {
            return Result.LEFT_ALONE;
        }
    }

    /** How to work on the bot, written when the file is missing and never touched after. */
    static Result agents(Path bot, Path file) {
        Path path = bot.resolve(file);
        if (Files.exists(path) || Files.isSymbolicLink(path)) return Result.ALREADY;
        return write(path, AGENTS_TEXT);
    }

    /** Whether {@code file}, or a folder between it and the bot's, is a link: a dotfile shared elsewhere. */
    private static boolean linked(Path bot, Path file) {
        Path at = bot;
        for (Path part : file) {
            at = at.resolve(part);
            if (Files.isSymbolicLink(at)) return true;
        }
        return false;
    }

    /**
     * Reads {@code file}'s JSON object (an empty one when there is none, {@code created} then true), lets
     * {@code guard} add what is missing, and writes it back when it says {@link Result#WRITTEN}. A link, or a file
     * that is not a strict JSON object (a comment in an opencode {@code jsonc} too), is left alone.
     */
    private static Result json(Path bot, Path file, BiFunction<ObjectNode, Boolean, Result> guard) {
        Path path = bot.resolve(file);
        if (linked(bot, file)) return Result.LEFT_ALONE;
        ObjectNode root;
        boolean created = !Files.isRegularFile(path);
        try {
            if (created) {
                root = JSON.createObjectNode();
            } else {
                JsonNode read = JSON.readTree(path.toFile());
                if (!(read instanceof ObjectNode object)) return Result.LEFT_ALONE;
                root = object;
            }
        } catch (IOException e) {
            return Result.LEFT_ALONE;
        }
        Result result = guard.apply(root, created);
        if (result != Result.WRITTEN) return result;
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            JSON.writeValue(path.toFile(), root);
            return Result.WRITTEN;
        } catch (IOException e) {
            System.err.println("Could not write " + path + ": " + e.getMessage());
            return Result.FAILED;
        }
    }

    private static Result write(Path file, String text) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return Result.WRITTEN;
        } catch (IOException e) {
            System.err.println("Could not write " + file + ": " + e.getMessage());
            return Result.FAILED;
        }
    }

    /**
     * Adds each {@link #EXCLUDED} line that the bot's {@code .git/info/exclude} lacks, as {@code ProjectVcs} does for
     * {@code .botmaker/}. A file already tracked stays tracked; a folder with no {@code .git} directory (none yet, or
     * a worktree's pointer file) gets nothing.
     */
    private static void exclude(Path bot) {
        Path git = bot.resolve(".git");
        if (!Files.isDirectory(git)) return;
        Path exclude = git.resolve("info").resolve("exclude");
        try {
            String existing = Files.exists(exclude) ? Files.readString(exclude) : "";
            StringBuilder added = new StringBuilder();
            for (String line : EXCLUDED) {
                if (existing.lines().noneMatch(l -> l.trim().equals(line))) added.append(line).append(System.lineSeparator());
            }
            if (added.isEmpty()) return;
            Files.createDirectories(exclude.getParent());
            String separator = existing.isEmpty() || existing.endsWith("\n") ? "" : System.lineSeparator();
            Files.writeString(exclude, existing + separator + added);
        } catch (IOException e) {
            System.err.println("Could not write " + exclude + ": " + e.getMessage());
        }
    }
}
