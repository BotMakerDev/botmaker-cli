// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli;

import com.botmaker.cli.mcp.BotRun;
import com.botmaker.cli.mcp.CoreTools;
import com.botmaker.cli.mcp.Operations;
import com.botmaker.cli.mcp.ToolRun;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker tool [<plugin> [<operation> [--<field> <value>…]]]} — any plugin's operations from the shell (web
 * discovery plan, phase 5): its assistant tools and its declared runs, through BotMaker's core ({@link ToolRun}). The
 * cli knows no plugin: what each offers, and the options each operation takes, come from the core's listing.
 *
 * <p>The {@code tool} prefix stays: a built-in command added later never clashes with a published plugin. The
 * operation's own options are parsed by it, not here: {@code Main} reads every word this command does not declare as
 * one of {@link #words}.
 */
@Command(name = "tool",
        header = "Run a plugin's operation: one of its assistant tools, or one of its runs.",
        description = "With no plugin, the plugins with operations; with a plugin, its operations and what each takes; "
                + "with an operation, runs it. A tool prints its reply; a run prints what it prints and ends as "
                + "`bot run` does (Ctrl+C stops it). The bot is --project, --new's, or this folder when it holds one; "
                + "with none, the plugins BotMaker binds by itself, whose runs then need a bot. --project and --new "
                + "come before the plugin: every word after it is the operation's. "
                + "`botmaker tool <plugin> <operation> --help` says what an operation takes.%n%n"
                + "Exit 0: done; 1: the tool refused, or the run did not start; 2: the command line named nothing "
                + "that is, or BotMaker could not be reached; 130: stopped.",
        synopsisSubcommandLabel = "")
final class ToolCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Parameters(arity = "0..*", paramLabel = "<plugin> <operation> <option>",
            description = "The plugin (sdk, or groupId:artifactId), the operation (keep-exploring), then its options.")
    private List<String> words = new ArrayList<>();

    @Option(names = "--project", paramLabel = "<dir>", description = "The bot to run it in.")
    private Path project;

    @Option(names = "--new", paramLabel = "<dir>",
            description = "Make a blank bot there first, with the plugin, then run it in that bot.")
    private Path fresh;

    @Option(names = "--complete", hidden = true,
            description = "Print what the shell may complete after the words given, from the last listing.")
    private boolean complete;

    @Override
    public Integer call() {
        if (complete) {
            ToolRun.complete(words, cached()).forEach(System.out::println);
            return 0;
        }
        Optional<String> plugin = words.isEmpty() ? Optional.empty() : Optional.of(words.getFirst());
        Optional<String> operation = words.size() < 2 ? Optional.empty() : Optional.of(words.get(1));
        List<String> options = words.size() < 3 ? List.of() : words.subList(2, words.size());
        if (plugin.filter(p -> p.equals("--help") || p.equals("-h")).isPresent()) {
            spec.commandLine().usage(System.out);
            return 0;
        }
        CoreTools core = CoreTools.onThisComputer();
        ToolRun run = new ToolRun(core, System.out, System.err, (bot, entry, args) -> followed(core, bot, entry, args),
                ToolRun::makeBlank, Duration.ofMillis(500), Duration.ofSeconds(30),
                Optional.of(ToolRun.cacheFile()));
        return run.run(new ToolRun.Ask(plugin, operation, options, Optional.ofNullable(project),
                Optional.ofNullable(fresh), Path.of("")));
    }

    /** A run followed to its end as {@code bot run} follows one, Ctrl+C its stop. */
    private static int followed(CoreTools core, Path bot, String entry, Optional<String> args) {
        BotRun run = new BotRun(core, System.out, System.err, Duration.ofMillis(500), Duration.ofSeconds(3));
        Thread stopper = new Thread(run::stop, "tool-run-stop");
        Runtime.getRuntime().addShutdownHook(stopper);
        BotRun.End end = run.run(bot, Optional.of(entry), args);
        try {
            Runtime.getRuntime().removeShutdownHook(stopper);
        } catch (IllegalStateException shuttingDown) {
            // Ctrl+C: the hook runs, and ends once the run has.
        }
        return end.code();
    }

    private static Optional<Operations> cached() {
        try {
            Path file = ToolRun.cacheFile();
            return Files.isRegularFile(file) ? Optional.of(Operations.read(Files.readString(file))) : Optional.empty();
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}
