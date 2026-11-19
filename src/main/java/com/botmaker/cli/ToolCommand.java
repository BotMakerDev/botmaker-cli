// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli;

import com.botmaker.cli.mcp.BotRun;
import com.botmaker.cli.mcp.CoreTools;
import com.botmaker.cli.mcp.ToolRun;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker tool [<plugin> [<operation>]] [--project <dir>]} — any plugin's operations from the shell (web
 * discovery plan, phase 5): its assistant tools and its declared runs, through BotMaker's core ({@link ToolRun}). The
 * cli knows no plugin: what each offers comes from the core's listing.
 *
 * <p>The command takes no operation's arguments: the bot is the configuration. A run reads what it needs from the
 * bot's Java (its launch target, its parameters), and a tool that needs values per call is left to Studio and the
 * assistant (the user's call, 2026-11-19).
 *
 * <p>The {@code tool} prefix stays: a built-in command added later never clashes with a published plugin.
 */
@Command(name = "tool",
        header = "Run a plugin's operation: one of its assistant tools, or one of its runs.",
        description = "With no plugin, the plugins with operations; with a plugin, its operations; with an operation, "
                + "runs it in the bot. A tool prints its reply; a run prints what it prints and ends as `bot run` does "
                + "(Ctrl+C stops it). The bot is --project, or this folder when it holds one: a run reads what it "
                + "needs from the bot, and a tool that needs values is called from Studio or the assistant.%n%n"
                + "Exit 0: done; 1: the tool refused, or the run did not start; 2: the command line named nothing "
                + "that is, or BotMaker could not be reached; 130: stopped.",
        mixinStandardHelpOptions = true)
final class ToolCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<plugin>",
            description = "The plugin: sdk, or groupId:artifactId.")
    private String plugin;

    @Parameters(index = "1", arity = "0..1", paramLabel = "<operation>",
            description = "The operation, as the plugin's list names it: keep-exploring.")
    private String operation;

    @Option(names = "--project", paramLabel = "<dir>", description = "The bot to run it in.")
    private Path project;

    @Override
    public Integer call() {
        CoreTools core = CoreTools.onThisComputer();
        ToolRun run = new ToolRun(core, System.out, System.err, (bot, entry) -> followed(core, bot, entry),
                Duration.ofMillis(500), Duration.ofSeconds(30));
        return run.run(new ToolRun.Ask(Optional.ofNullable(plugin), Optional.ofNullable(operation),
                Optional.ofNullable(project), Path.of("")));
    }

    /** A run followed to its end as {@code bot run} follows one, Ctrl+C its stop. */
    private static int followed(CoreTools core, Path bot, String entry) {
        BotRun run = new BotRun(core, System.out, System.err, Duration.ofMillis(500), Duration.ofSeconds(3));
        Thread stopper = new Thread(run::stop, "tool-run-stop");
        Runtime.getRuntime().addShutdownHook(stopper);
        BotRun.End end = run.run(bot, Optional.of(entry));
        try {
            Runtime.getRuntime().removeShutdownHook(stopper);
        } catch (IllegalStateException shuttingDown) {
            // Ctrl+C: the hook runs, and ends once the run has.
        }
        return end.code();
    }
}
