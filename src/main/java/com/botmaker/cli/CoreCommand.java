// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli;

import com.botmaker.cli.core.CoreCards;
import com.botmaker.cli.core.CoreFeed;
import com.botmaker.cli.core.CoreFeed.Frame;
import com.botmaker.cli.core.CoreFeed.Kind;
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code botmaker core} — BotMaker's core from the shell (core dashboard plan, phases 7–8): what it is now and what it
 * hears, read from the dashboard's feed ({@link CoreFeed}) with the token the core saved, and the dashboard itself in a
 * browser.
 */
@Command(name = "core",
        header = "Look at BotMaker's core: what it holds now, what it hears, its dashboard.",
        description = "The core is the process that owns the open bot, serves the MCP and runs bots; Studio and the "
                + "assistant are its clients.%n%n"
                + "Exit 0: done; 1: the core answered but refused; 2: no core runs on this computer.",
        mixinStandardHelpOptions = true,
        synopsisSubcommandLabel = "<command>",
        subcommands = {CoreCommand.Status.class, CoreCommand.Watch.class, CoreCommand.Dashboard.class})
final class CoreCommand implements Callable<Integer> {

    /** How long the core has to send what it is once the feed is open. */
    private static final Duration FIRST = Duration.ofSeconds(5);
    /** A gap this long after the snapshot ends the replay {@code status} prints. */
    private static final Duration QUIET = Duration.ofMillis(300);
    /** The longest {@code status} reads the replay, should no gap come. */
    private static final Duration REPLAY = Duration.ofSeconds(2);
    private static final String NO_SNAPSHOT = "The core sent no snapshot of itself.";
    static final int NO_CORE = 2;

    /** No sub-verb: the usage text, and exit 2, as {@link BotCommand} does. */
    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return CommandLine.ExitCode.USAGE;
    }

    @Command(name = "status", header = "What the core holds now, as the dashboard's eight cards.",
            mixinStandardHelpOptions = true)
    static final class Status implements Callable<Integer> {
        @Option(names = "--json", description = "Print the core's snapshot as the dashboard reads it.")
        private boolean json;

        @Override
        public Integer call() {
            return withFeed(feed -> status(feed, json, new CoreCards(ZoneId.systemDefault()), System.out, System.err));
        }
    }

    @Command(name = "watch", header = "Follow what the core hears, one line per event, until it ends or Ctrl+C.",
            description = "Each line is HH:mm:ss, who told it (project, mcp, a client), its kind and what it says.",
            mixinStandardHelpOptions = true)
    static final class Watch implements Callable<Integer> {
        @Option(names = "--kind", paramLabel = "<kind>", split = ",",
                description = "Only these kinds, as the dashboard's feed names them: runState, toolCallStarted, "
                        + "edit, client…")
        private List<String> kinds = List.of();

        @Override
        public Integer call() {
            return withFeed(feed -> watch(feed, Set.copyOf(kinds), new CoreCards(ZoneId.systemDefault()), System.out,
                    System.err));
        }
    }

    @Command(name = "dashboard", header = "Open the core's dashboard in the browser.",
            description = "Each opening asks the core for a fresh link: its key is good once, for a minute, so a "
                    + "printed link opens one tab.",
            mixinStandardHelpOptions = true)
    static final class Dashboard implements Callable<Integer> {
        @Option(names = "--print", description = "Print the link instead of opening it.")
        private boolean print;

        @Override
        public Integer call() {
            Optional<CoreFeed.At> at = CoreFeed.onThisComputer();
            if (at.isEmpty()) return noCore(System.err);
            URI link;
            try {
                link = CoreFeed.dashboardLink(at.get());
            } catch (ConnectException nobody) {
                return noCore(System.err);
            } catch (IOException refused) {
                System.err.println(refused.getMessage());
                return 1;
            }
            if (print || !browse(link)) System.out.println(link);
            return 0;
        }
    }

    /** What a command does with the feed, its exit code. */
    @FunctionalInterface
    interface Reading {
        int read(CoreFeed feed) throws InterruptedException;
    }

    private static int withFeed(Reading reading) {
        Optional<CoreFeed.At> at = CoreFeed.onThisComputer();
        if (at.isEmpty()) return noCore(System.err);
        try {
            Optional<CoreFeed> opened = CoreFeed.open(at.get());
            if (opened.isEmpty()) return noCore(System.err);
            try (CoreFeed feed = opened.get()) {
                return reading.read(feed);
            }
        } catch (IOException refused) {
            System.err.println(refused.getMessage());
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    private static int noCore(PrintStream err) {
        err.println(CoreFeed.NONE);
        return NO_CORE;
    }

    /** The first snapshot and the events replayed with it, as cards, or the snapshot's JSON as the core wrote it. */
    static int status(CoreFeed feed, boolean json, CoreCards cards, PrintStream out, PrintStream err)
            throws InterruptedException {
        List<JsonNode> seen = new ArrayList<>();
        JsonNode inside = firstInside(feed, seen);
        if (inside == null) {
            err.println(NO_SNAPSHOT);
            return 1;
        }
        if (json) {
            out.println(inside);
            return 0;
        }
        // The replay ends at the first quiet gap; a busy core's live events end it at REPLAY all the same.
        long until = System.nanoTime() + REPLAY.toNanos();
        for (Frame frame = feed.next(QUIET); frame != null && frame.kind() != Kind.ENDED
                && System.nanoTime() < until; frame = feed.next(QUIET)) {
            if (frame.kind() == Kind.SEEN) seen.add(frame.data());
        }
        out.print(cards.cards(inside, seen));
        return 0;
    }

    /**
     * The core's first snapshot, waited for {@link #FIRST} at most; the {@code seen} frames that came before it go to
     * {@code before}. Null when none came, or the core ended first.
     */
    private static JsonNode firstInside(CoreFeed feed, List<JsonNode> before) throws InterruptedException {
        long until = System.nanoTime() + FIRST.toNanos();
        for (long left = FIRST.toNanos(); left > 0; left = until - System.nanoTime()) {
            Frame frame = feed.next(Duration.ofNanos(left));
            if (frame == null || frame.kind() == Kind.ENDED) return null;
            if (frame.kind() == Kind.INSIDE) return frame.data();
            if (frame.kind() == Kind.SEEN) before.add(frame.data());
        }
        return null;
    }

    /**
     * Each event the core hears from now on, one line each, those of {@code kinds} only when it names some; the replay
     * of what it heard up to the snapshot is left out. Ends when the core does.
     */
    static int watch(CoreFeed feed, Set<String> kinds, CoreCards cards, PrintStream out, PrintStream err)
            throws InterruptedException {
        JsonNode inside = firstInside(feed, new ArrayList<>());
        if (inside == null) {
            err.println(NO_SNAPSHOT);
            return 1;
        }
        long since = inside.path("atMillis").asLong();
        while (true) {
            Frame frame = feed.next(Duration.ofMinutes(1));
            if (frame == null) continue;
            switch (frame.kind()) {
                case ENDED -> {
                    out.println("The core ended.");
                    return 0;
                }
                case SEEN -> {
                    JsonNode seen = frame.data();
                    if (seen.path("atMillis").asLong() <= since) continue;
                    if (!kinds.isEmpty() && !kinds.contains(CoreCards.kind(seen))) continue;
                    out.println(cards.line(seen));
                    out.flush();
                }
                default -> {
                    // A later snapshot: watch prints events only.
                }
            }
        }
    }

    /**
     * Opens {@code link} with the system's own opener, not waited for: one that starts the browser itself returns only
     * once it closes. False when there is no opener.
     */
    private static boolean browse(URI link) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command = os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", link.toString())
                : os.contains("mac") ? List.of("open", link.toString()) : List.of("xdg-open", link.toString());
        try {
            new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return true;
        } catch (IOException none) {
            return false;
        }
    }
}
