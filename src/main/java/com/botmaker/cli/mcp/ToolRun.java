// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.mcp;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code botmaker tool}: any plugin's operations from the shell (web discovery plan, phase 5), through BotMaker's core
 * as {@code bot run} reaches it. The core lists what its bound plugins offer ({@link Operations}); a tool is called by
 * its MCP name and its reply printed, a run is started by {@code run_bot} and followed as {@code bot run} follows one.
 *
 * <p>No operation's arguments are taken (the user's call, 2026-11-19): the bot is the configuration. A run starts with
 * what it reads from the bot and its own defaults; a tool is called with none, so one whose schema requires a value
 * is refused, naming them, and left to Studio and the assistant.
 *
 * <p>The plugins are the open bot's: {@code --project}, or this folder when it holds one. With none, the plugins the
 * core binds by itself, whose tools work with no bot (one that needs a bot says so) and whose runs are refused: a run
 * needs a bot (the user's call, 2026-11-18).
 */
public final class ToolRun {

    /** How the command ends: its exit code. A run ends with {@link BotRun.End}'s. */
    public enum End {
        /** Listed, or the tool answered. */
        DONE(0),
        /** The tool refused or failed. */
        FAILED(1),
        /** The command line named nothing that is, or the core could not be reached or the bot opened. */
        USAGE(2);

        private final int code;

        End(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    /**
     * What was asked.
     *
     * @param plugin    the plugin, by its short name or {@code groupId:artifactId}; empty lists the plugins
     * @param operation the operation; empty lists the plugin's
     * @param project   {@code --project}
     * @param here      the folder the command runs in
     */
    public record Ask(Optional<String> plugin, Optional<String> operation, Optional<Path> project, Path here) {}

    /** Starts a declared run in a bot and follows it to its end, as {@code bot run} does: its exit code. */
    @FunctionalInterface
    public interface Runner {
        int run(Path bot, String entry);
    }

    private final CoreTools tools;
    private final PrintStream out;
    private final PrintStream err;
    private final Runner runner;
    private final Duration poll;
    /** How long an open may take before the listing is the opened bot's: Studio opens one in its window. */
    private final Duration openWait;

    public ToolRun(CoreTools tools, PrintStream out, PrintStream err, Runner runner, Duration poll,
                   Duration openWait) {
        this.tools = tools;
        this.out = out;
        this.err = err;
        this.runner = runner;
        this.poll = poll;
        this.openWait = openWait;
    }

    /** Does what {@code ask} asks; the exit code. */
    public int run(Ask ask) {
        try {
            return runOnce(ask);
        } catch (IOException | IllegalArgumentException e) {
            err.println(e.getMessage());
            return End.USAGE.code();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return BotRun.End.STOPPED.code();
        }
    }

    private int runOnce(Ask ask) throws IOException, InterruptedException {
        Optional<Path> bot = ask.project().map(ToolRun::absolute);
        if (bot.isEmpty() && isBot(ask.here())) bot = Optional.of(absolute(ask.here()));
        Operations listed = bot.isPresent() ? opened(bot.get()) : list();
        if (bot.isEmpty()) {
            listed.project().ifPresent(open -> err.println("BotMaker has " + open + " open: these are its plugins."));
        }
        if (ask.plugin().isEmpty()) return plugins(listed, bot.isPresent());

        Operations.Plugin plugin = listed.plugin(ask.plugin().get());
        if (ask.operation().isEmpty()) return operations(plugin);

        Operations.Operation operation = plugin.operation(ask.operation().get());
        if (operation.kind() == Operations.Kind.UNKNOWN) {
            throw new IllegalArgumentException(operation.name() + " is an operation of a kind this botmaker cannot "
                    + "start: update it.");
        }
        if (operation.kind() == Operations.Kind.RUN && bot.isEmpty()) {
            throw new IllegalArgumentException(operation.name() + " is a run, and a run needs a bot: give "
                    + "--project <dir>, or run it in a bot's folder.");
        }
        List<String> needed = needed(operation);
        if (!needed.isEmpty()) {
            throw new IllegalArgumentException(operation.name() + " needs " + String.join(", ", needed)
                    + ", which the shell does not give: call it from Studio or the assistant.");
        }
        return operation.kind() == Operations.Kind.RUN ? runner.run(bot.orElseThrow(), operation.name())
                : call(operation);
    }

    /** The values {@code operation}'s schema requires: none for one the shell can start. */
    static List<String> needed(Operations.Operation operation) {
        List<String> out = new ArrayList<>();
        operation.schema().path("required").forEach(n -> out.add(n.asText()));
        return out;
    }

    private int call(Operations.Operation operation) throws IOException {
        CoreTools.Answer answer = tools.call(operation.call(), Map.of());
        (answer.error() ? err : out).println(answer.text());
        return answer.error() ? End.FAILED.code() : End.DONE.code();
    }

    private int plugins(Operations listed, boolean inBot) {
        if (listed.plugins().isEmpty()) {
            out.println(inBot ? "No plugin of this bot has operations." : "No plugin has operations here: give "
                    + "--project <dir>, or run this in a bot's folder.");
            return End.DONE.code();
        }
        for (Operations.Plugin plugin : listed.plugins()) {
            out.println(plugin.shortName() + "  " + plugin.name() + " " + plugin.version() + "  ("
                    + plugin.coordinate() + ", " + plugin.operations().size() + " operations)");
        }
        out.println("botmaker tool <plugin> lists a plugin's operations.");
        return End.DONE.code();
    }

    private int operations(Operations.Plugin plugin) {
        for (Operations.Operation operation : plugin.operations()) {
            List<String> needed = needed(operation);
            out.println(operation.command() + "  (" + operation.kind().displayName() + ")"
                    + (needed.isEmpty() ? "" : "  needs " + String.join(", ", needed) + ": Studio or the assistant"));
            if (!operation.description().isBlank()) out.println("    " + operation.description());
        }
        out.println("botmaker tool " + plugin.shortName() + " <operation> runs one.");
        return End.DONE.code();
    }

    /** The core's listing as it stands. */
    private Operations list() throws IOException {
        CoreTools.Answer answer = tools.call(Operations.TOOL, Map.of());
        if (answer.error()) throw new IOException(answer.text());
        return Operations.read(answer.text());
    }

    /** Opens {@code bot} in the core and lists once the core has it open. */
    private Operations opened(Path bot) throws IOException, InterruptedException {
        CoreTools.Answer opened = tools.call("open_project", Map.of("path", bot.toString()));
        if (opened.error()) throw new IOException(opened.text());
        Instant asked = Instant.now();
        while (true) {
            Operations listed = list();
            if (listed.project().filter(open -> same(open, bot)).isPresent()) return listed;
            if (Duration.between(asked, Instant.now()).compareTo(openWait) > 0) {
                throw new IOException(bot + " did not open in BotMaker within " + openWait.toSeconds() + " s.");
            }
            Thread.sleep(poll.toMillis());
        }
    }

    /** Whether {@code dir} holds a bot: a pom.xml and a src/main/java folder, as the core opens one. */
    static boolean isBot(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("src/main/java"));
    }

    /** One folder, however each names it: through a link, or in another case on Windows. */
    private static boolean same(Path open, Path bot) {
        try {
            return Files.isSameFile(absolute(open), bot);
        } catch (IOException | RuntimeException gone) {
            return absolute(open).equals(bot);
        }
    }

    private static Path absolute(Path dir) {
        return dir.toAbsolutePath().normalize();
    }
}
