// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli;

import com.botmaker.cli.mcp.BotRun;
import com.botmaker.cli.mcp.CoreTools;
import com.botmaker.cli.mcp.SeveralStart;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker bot run} — a bot run from the terminal, with no Studio (plan phase 12): BotMaker's core opens it and
 * runs it ({@link BotRun}), its output streamed here; Ctrl+C stops the run. With {@code --runs N}, several runs each
 * dealt a row of the bot's pools ({@link SeveralStart}, plan run-claims phase 12): what can run is shown, and asked,
 * before any starts.
 */
@Command(name = "run",
        header = "Run a bot from the terminal, with no Studio.",
        description = "Opens the bot in BotMaker's core (found, or started as the last Studio started it), runs its "
                + "main or a declared run, and prints what it prints. Ends with how the run ended and its run log. "
                + "Ctrl+C stops the run. Exit 0: the run ended; 1: the bot did not start; 2: it could not be opened "
                + "or the core not reached; 130: stopped.%n%n"
                + "With --runs N, several runs of the bot's main, each dealt its own row of the bot's pools: what can "
                + "run is shown first and the start asked. The runs go on after the command ends. Exit 0: they "
                + "started, or a dry run counted them; 1: none started; 2: as above; 3: --all-or-none, and fewer can "
                + "run than asked.",
        mixinStandardHelpOptions = true)
final class BotRunCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<folder>",
            description = "The bot's folder; this folder when not given.")
    private Path folder;

    @Option(names = "--entry", paramLabel = "<title>",
            description = "A run a plugin declares, by its toolbar title (Explore map); the bot's main when not given.")
    private String entry;

    @Option(names = "--runs", paramLabel = "<N>",
            description = "Start N runs at once, each dealt its own row of the bot's pools.")
    private Integer runs;

    @Option(names = "--dry-run", description = "With --runs: only show how many can run and what each is dealt.")
    private boolean dryRun;

    @Option(names = "--all-or-none", description = "With --runs: start none unless all N can run (exit 3).")
    private boolean allOrNone;

    @Option(names = "--pin", paramLabel = "[<run>:]<pool>=<value>",
            description = "With --runs: fix a pick, the pool and value as --dry-run writes them; run 1 when no run "
                    + "is given. Repeatable.")
    private List<String> pins = new ArrayList<>();

    @Option(names = {"-y", "--yes"}, description = "With --runs: start what can run without asking.")
    private boolean yes;

    @Override
    public Integer call() {
        Path bot = folder == null ? Path.of("") : folder;
        if (runs != null) return several(bot, System.out, System.err);
        if (dryRun || allOrNone || !pins.isEmpty() || yes) {
            System.err.println("--dry-run, --all-or-none, --pin and --yes go with --runs.");
            return BotRun.End.REFUSED.code();
        }
        BotRun run = new BotRun(CoreTools.onThisComputer(), System.out, System.err, Duration.ofMillis(500),
                Duration.ofSeconds(3));
        Thread stopper = new Thread(run::stop, "bot-run-stop");
        Runtime.getRuntime().addShutdownHook(stopper);
        BotRun.End end = run.run(bot, Optional.ofNullable(entry).filter(s -> !s.isBlank()));
        try {
            Runtime.getRuntime().removeShutdownHook(stopper);
        } catch (IllegalStateException shuttingDown) {
            // Ctrl+C: the hook runs, and ends once the run has.
        }
        return end.code();
    }

    private int several(Path bot, PrintStream out, PrintStream err) {
        if (entry != null && !entry.isBlank()) {
            err.println("--runs starts the bot's main, not a declared run.");
            return SeveralStart.End.REFUSED.code();
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        SeveralStart start = new SeveralStart(CoreTools.onThisComputer(), out, err, Duration.ofMillis(500),
                question -> {
                    err.print(question);
                    err.flush();
                    String answer = in.readLine();
                    if (answer == null) err.println("(no answer: --yes starts without asking)");
                    return answer != null && List.of("y", "yes").contains(answer.strip().toLowerCase(Locale.ROOT));
                });
        return start.start(bot, new SeveralStart.Ask(runs, dryRun, allOrNone, pins, yes)).code();
    }
}
