// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.mcp;

import com.botmaker.cli.project.BlankProject;
import com.botmaker.cli.project.Poms;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

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
import java.util.stream.Stream;

/**
 * {@code botmaker tool}: any plugin's operations from the shell (web discovery plan, phase 5), through BotMaker's core
 * as {@code bot run} reaches it. The core lists what its bound plugins offer ({@link Operations}); a tool is called by
 * its MCP name and its reply printed, a run is started by {@code run_bot} and followed as {@code bot run} follows one.
 *
 * <p>The plugins are the open bot's: {@code --project}, {@code --new}'s, or this folder when it holds one. With none,
 * the plugins the core binds by itself, whose tools work with no bot (one that needs a bot says so) and whose runs are
 * refused: a run needs a bot (the user's call, 2026-11-18).
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
     * @param options   the operation's own options, as typed
     * @param project   {@code --project}
     * @param fresh     {@code --new}: where a blank bot is made first
     * @param here      the folder the command runs in
     */
    public record Ask(Optional<String> plugin, Optional<String> operation, List<String> options,
                      Optional<Path> project, Optional<Path> fresh, Path here) {
        public Ask {
            options = List.copyOf(options);
        }
    }

    /** Starts a declared run in a bot and follows it to its end, as {@code bot run} does: its exit code. */
    @FunctionalInterface
    public interface Runner {
        int run(Path bot, String entry, Optional<String> args);
    }

    /** Makes the blank bot {@code --new} names, with {@code plugin} its one dependency. */
    @FunctionalInterface
    public interface Maker {
        void make(Path dir, Operations.Plugin plugin) throws IOException;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CoreTools tools;
    private final PrintStream out;
    private final PrintStream err;
    private final Runner runner;
    private final Maker maker;
    private final Duration poll;
    /** How long an open may take before the listing is the opened bot's: Studio opens one in its window. */
    private final Duration openWait;
    /** Where the last listing is kept for the shell's completion; empty keeps none. */
    private final Optional<Path> cache;

    public ToolRun(CoreTools tools, PrintStream out, PrintStream err, Runner runner, Maker maker, Duration poll,
                   Duration openWait, Optional<Path> cache) {
        this.tools = tools;
        this.out = out;
        this.err = err;
        this.runner = runner;
        this.maker = maker;
        this.poll = poll;
        this.openWait = openWait;
        this.cache = cache;
    }

    /** The cache file a listing is kept in, beside the core's own files. */
    public static Path cacheFile() {
        return McpBridge.coreDir().resolve("tool-operations.json");
    }

    /** Does what {@code ask} asks; the exit code. */
    public int run(Ask ask) {
        try {
            return runOnce(ask);
        } catch (IOException e) {
            err.println(e.getMessage());
            return End.USAGE.code();
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return End.USAGE.code();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return BotRun.End.STOPPED.code();
        }
    }

    private int runOnce(Ask ask) throws IOException, InterruptedException {
        if (ask.project().isPresent() && ask.fresh().isPresent()) {
            throw new IllegalArgumentException("--project and --new each name the bot: give one.");
        }
        Optional<Path> bot = ask.project().map(ToolRun::absolute);
        if (ask.fresh().isPresent()) {
            if (ask.plugin().isEmpty()) {
                throw new IllegalArgumentException("--new makes a bot for a plugin's operation: name the plugin.");
            }
            Path dir = absolute(ask.fresh().get());
            refuseFull(dir);
            Operations.Plugin plugin = list().plugin(ask.plugin().get());
            maker.make(dir, plugin);
            err.println("Made a blank bot in " + dir + " with " + plugin.name() + " " + plugin.version() + ".");
            bot = Optional.of(dir);
        } else if (bot.isEmpty() && isBot(ask.here())) {
            bot = Optional.of(absolute(ask.here()));
        }
        Operations listed = bot.isPresent() ? opened(bot.get()) : list();
        if (bot.isEmpty()) {
            listed.project().ifPresent(open -> err.println("BotMaker has " + open + " open: these are its plugins."));
        }
        if (ask.plugin().isEmpty()) return plugins(listed, bot.isPresent());

        Operations.Plugin plugin = listed.plugin(ask.plugin().get());
        if (ask.operation().filter(o -> !o.equals("--help") && !o.equals("-h")).isEmpty()) return operations(plugin);

        Operations.Operation operation = plugin.operation(ask.operation().get());
        OperationOptions options = new OperationOptions(
                "botmaker tool " + plugin.shortName() + " " + operation.command(), operation);
        if (ask.options().contains("--help") || ask.options().contains("-h")) {
            out.print(options.usage());
            return End.DONE.code();
        }
        Map<String, Object> arguments;
        try {
            arguments = options.arguments(ask.options());
        } catch (CommandLine.ParameterException | IllegalArgumentException e) {
            err.println(e.getMessage());
            err.print(options.usage());
            return End.USAGE.code();
        }
        return switch (operation.kind()) {
            case UNKNOWN -> throw new IllegalArgumentException(operation.name() + " is an operation of a kind this "
                    + "botmaker cannot start: update it.");
            case TOOL -> call(operation, arguments);
            case RUN -> {
                if (bot.isEmpty()) {
                    throw new IllegalArgumentException(operation.name() + " is a run, and a run needs a bot: give "
                            + "--project <dir>, run it in a bot's folder, or --new <dir> to make one.");
                }
                yield runner.run(bot.get(), operation.name(),
                        arguments.isEmpty() ? Optional.empty() : Optional.of(JSON.writeValueAsString(arguments)));
            }
        };
    }

    private int call(Operations.Operation operation, Map<String, Object> arguments) throws IOException {
        CoreTools.Answer answer = tools.call(operation.call(), arguments);
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
            String synopsis = new OperationOptions("", operation).synopsis();
            out.println(operation.command() + "  (" + operation.kind().displayName() + ")"
                    + (synopsis.isEmpty() ? "" : "  " + synopsis));
            if (!operation.description().isBlank()) out.println("    " + operation.description());
        }
        out.println("botmaker tool " + plugin.shortName() + " <operation> --help says what one takes.");
        return End.DONE.code();
    }

    /** The core's listing as it stands, kept for completion. */
    private Operations list() throws IOException {
        CoreTools.Answer answer = tools.call(Operations.TOOL, Map.of());
        if (answer.error()) throw new IOException(answer.text());
        Operations listed = Operations.read(answer.text());
        cache.ifPresent(file -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, answer.text());
            } catch (IOException | RuntimeException unwritable) {
                // Completion then offers what it had: nothing here depends on it.
            }
        });
        return listed;
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

    private static void refuseFull(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> existing = Files.list(dir)) {
            if (existing.findAny().isPresent()) throw new IOException(dir + " already exists and is not empty.");
        }
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

    /**
     * {@code --new}'s bot as {@code botmaker bot new} makes a blank one — named after its folder, its package
     * {@code com.<name>} — with {@code plugin} at the version the core bound added to its pom.
     */
    public static void makeBlank(Path dir, Operations.Plugin plugin) throws IOException {
        if (plugin.version().isBlank()) {
            throw new IOException("BotMaker lists no version of " + plugin.coordinate() + ": no bot can name it.");
        }
        String name = dir.getFileName().toString();
        String pkg = BlankProject.defaultPackage(name);
        BlankProject.requireUsable(name, pkg);
        BlankProject.write(dir, name, pkg);
        Poms.upsertDependency(dir.resolve("pom.xml"),
                new Poms.Dependency(plugin.groupId(), plugin.artifactId(), plugin.version(), ""));
    }

    /**
     * The shell's completion of {@code botmaker tool}, from the last listing kept ({@code cached}, never the core):
     * after nothing, the plugins; after a plugin, its operations; after an operation, its options, or an option's
     * choices after it.
     */
    public static List<String> complete(List<String> before, Optional<Operations> cached) {
        List<String> out = new ArrayList<>();
        List<String> words = before.stream().filter(w -> !w.isBlank()).toList();
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            if (word.equals("--project") || word.equals("--new")) {
                i++;
            } else if (!word.startsWith("-") && positional.size() < 2) {
                positional.add(word);
            } else {
                break;
            }
        }
        if (cached.isEmpty()) return out;
        Operations listed = cached.get();
        try {
            if (positional.isEmpty()) {
                listed.plugins().forEach(p -> out.add(p.shortName()));
                out.addAll(List.of("--project", "--new"));
                return out;
            }
            Operations.Plugin plugin = listed.plugin(positional.getFirst());
            if (positional.size() == 1) {
                plugin.operations().forEach(o -> out.add(o.command()));
                return out;
            }
            Operations.Operation operation = plugin.operation(positional.get(1));
            String last = words.isEmpty() ? "" : words.getLast();
            OperationOptions options = new OperationOptions("", operation);
            List<String> choices = options.choices(last);
            if (!choices.isEmpty()) return choices;
            out.addAll(options.names());
            out.add("--help");
        } catch (IllegalArgumentException unknown) {
            // Nothing to offer after a name the listing lacks.
        }
        return out;
    }
}
