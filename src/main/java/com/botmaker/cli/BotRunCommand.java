package com.botmaker.cli;

import com.botmaker.cli.mcp.BotRun;
import com.botmaker.cli.mcp.CoreTools;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker bot run} — a bot run from the terminal, with no Studio (plan phase 12): BotMaker's core opens it and
 * runs it ({@link BotRun}), its output streamed here; Ctrl+C stops the run.
 */
@Command(name = "run",
        header = "Run a bot from the terminal, with no Studio.",
        description = "Opens the bot in BotMaker's core (found, or started as the last Studio started it), runs its "
                + "main or a declared run, and prints what it prints. Ends with how the run ended and its run log. "
                + "Ctrl+C stops the run. Exit 0: the run ended; 1: the bot did not start; 2: it could not be opened "
                + "or the core not reached; 130: stopped.",
        mixinStandardHelpOptions = true)
final class BotRunCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<folder>",
            description = "The bot's folder; this folder when not given.")
    private Path folder;

    @Option(names = "--entry", paramLabel = "<title>",
            description = "A run a plugin declares, by its toolbar title (Explore map); the bot's main when not given.")
    private String entry;

    @Override
    public Integer call() {
        BotRun run = new BotRun(CoreTools.onThisComputer(), System.out, System.err, Duration.ofMillis(500),
                Duration.ofSeconds(3));
        Thread stopper = new Thread(run::stop, "bot-run-stop");
        Runtime.getRuntime().addShutdownHook(stopper);
        BotRun.End end = run.run(folder == null ? Path.of("") : folder,
                Optional.ofNullable(entry).filter(s -> !s.isBlank()));
        try {
            Runtime.getRuntime().removeShutdownHook(stopper);
        } catch (IllegalStateException shuttingDown) {
            // Ctrl+C: the hook runs, and ends once the run has.
        }
        return end.code();
    }
}
