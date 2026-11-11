package com.botmaker.cli.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a Claude Code session started in a bot's folder from writing the bot's Java by hand (2026-10-12): the
 * bot's {@code .claude/settings.json} denies the file-editing tools on {@code src/**} and the shell commands
 * that rewrite a file in place. The bot changes through the MCP tools, where every edit compiles before it
 * lands; the sessions BotMaker starts itself are denied the same tools on their command line ({@link AiTool}).
 *
 * <p>Written as a project opens, only adding a rule that is missing and removing none but the {@link #OBSOLETE} ones
 * an earlier guard wrote: the user's own rules
 * and settings stay as they are, and a file that is not JSON is left alone. The shell rules are a guard against slips, not a
 * sandbox — a command can always be spelled another way.
 */
public final class BotGuard {

    /** The file, relative to the bot's folder, that Claude Code reads a project's shared settings from. */
    public static final Path FILE = Path.of(".claude", "settings.json");

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

    private static List<String> denied() {
        List<String> rules = new ArrayList<>(List.of("Edit(./src/**)"));
        rules.addAll(SHELL);
        return List.copyOf(rules);
    }

    /** What {@link #ensure} did. */
    public enum Result {
        WRITTEN, ALREADY,
        /** Not settings JSON, or a link to a file elsewhere (a dotfile): left alone, so the guard is not there. */
        LEFT_ALONE,
        FAILED;

        /** Whether the bot's folder now guards a plain {@code claude}. */
        public boolean guarded() {
            return this == WRITTEN || this == ALREADY;
        }
    }

    /**
     * Adds every missing rule to {@code botFolder}'s settings, writing the file only when one was missing, and
     * says on stderr when the folder could not be guarded.
     */
    public static Result ensure(Path botFolder) {
        Result result = apply(botFolder.resolve(FILE));
        if (!result.guarded()) {
            System.err.println("Claude Code sessions in " + botFolder + " are not guarded: " + FILE + " was "
                    + (result == Result.LEFT_ALONE ? "left as it is (not settings JSON, or a link)" : "not written"));
        }
        return result;
    }

    private static Result apply(Path file) {
        if (Files.isSymbolicLink(file)) return Result.LEFT_ALONE;
        ObjectNode root;
        try {
            if (Files.isRegularFile(file)) {
                JsonNode read = JSON.readTree(file.toFile());
                if (!(read instanceof ObjectNode object)) return Result.LEFT_ALONE;
                root = object;
            } else {
                root = JSON.createObjectNode();
            }
        } catch (IOException e) {
            return Result.LEFT_ALONE;
        }
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
        if (!changed) return Result.ALREADY;
        try {
            Files.createDirectories(file.getParent());
            JSON.writeValue(file.toFile(), root);
            return Result.WRITTEN;
        } catch (IOException e) {
            System.err.println("Could not write " + file + ": " + e.getMessage());
            return Result.FAILED;
        }
    }
}
