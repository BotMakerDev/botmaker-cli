// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli;

import com.botmaker.cli.core.CoreCards;
import com.botmaker.cli.core.CoreFeed;
import com.botmaker.cli.core.CoreFeed.Frame;
import com.botmaker.cli.core.CoreFeed.Kind;
import com.botmaker.cli.mcp.CoreTools;
import com.botmaker.cli.mcp.McpBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code botmaker core} — BotMaker's core from the shell (core dashboard plan, phases 7–8): what it is now and what it
 * hears, read from the dashboard's feed ({@link CoreFeed}) with the token the core saved, and the dashboard itself in a
 * browser; and what it is told, each act one of its MCP tools through {@link CoreTools}, as {@code bot run} calls them.
 */
@Command(name = "core",
        header = "BotMaker's core: what it holds now, what it hears, its dashboard; start, quit or tell it.",
        description = "The core is the process that owns the open bot, serves the MCP and runs bots; Studio and the "
                + "assistant are its clients. Only start, restart and open start a core when none runs.%n%n"
                + "Exit 0: done; 1: the core answered but refused; 2: no core runs on this computer, or it could "
                + "not be reached.",
        mixinStandardHelpOptions = true,
        synopsisSubcommandLabel = "<command>",
        subcommands = {CoreCommand.Status.class, CoreCommand.Watch.class, CoreCommand.Dashboard.class,
                CoreCommand.Start.class, CoreCommand.Quit.class, CoreCommand.Restart.class, CoreCommand.Stop.class,
                CoreCommand.Open.class, CoreCommand.Close.class, CoreCommand.Reload.class})
final class CoreCommand implements Callable<Integer> {

    /** How long the core has to send what it is once the feed is open. */
    private static final Duration FIRST = Duration.ofSeconds(5);
    /** A gap this long after the snapshot ends the replay {@code status} prints. */
    private static final Duration QUIET = Duration.ofMillis(300);
    /** The longest {@code status} reads the replay, should no gap come. */
    private static final Duration REPLAY = Duration.ofSeconds(2);
    private static final String NO_SNAPSHOT = "The core sent no snapshot of itself.";
    /** How long {@code restart} waits for the old core to end: as long as a core may take to start. */
    private static final Duration ENDING = Duration.ofSeconds(30);
    private static final Duration PAUSE = Duration.ofMillis(200);
    private static final ObjectMapper JSON = new ObjectMapper();
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

    @Command(name = "start", header = "Start the core unless one runs, and say what it holds.",
            mixinStandardHelpOptions = true)
    static final class Start implements Callable<Integer> {
        @Override
        public Integer call() {
            return act(Core.onThisComputer(), Act.start(), System.out, System.err);
        }
    }

    @Command(name = "quit", header = "End the core.",
            description = "Refused while a bot runs, and while Studio is attached unless --force.",
            mixinStandardHelpOptions = true)
    static final class Quit implements Callable<Integer> {
        @Option(names = "--force", description = "Quit with Studio attached: it joins a fresh core.")
        private boolean force;

        @Override
        public Integer call() {
            return act(Core.onThisComputer(), Act.quit(force), System.out, System.err);
        }
    }

    @Command(name = "restart", header = "End the core, wait until it is gone, and start a fresh one.",
            description = "A fresh core runs the jars built now. With no core running, starts one.",
            mixinStandardHelpOptions = true)
    static final class Restart implements Callable<Integer> {
        @Option(names = "--force", description = "Restart with Studio attached: it joins the fresh core.")
        private boolean force;

        @Override
        public Integer call() {
            return restart(Core.onThisComputer(), force, ENDING, PAUSE, System.out, System.err);
        }
    }

    @Command(name = "stop", header = "Stop the run, the try or the debug session.", mixinStandardHelpOptions = true)
    static final class Stop implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "<run_id>|all",
                description = "One run, as run_state names it, or all of this bot's; none: the run.")
        private String run;

        @Override
        public Integer call() {
            return act(Core.onThisComputer(), Act.stop(run), System.out, System.err);
        }
    }

    @Command(name = "open", header = "Open a project in the core, starting it if none runs.",
            description = "Studio, when attached, opens it itself.", mixinStandardHelpOptions = true)
    static final class Open implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", paramLabel = "<dir>", description = "The project's folder.")
        private Path dir;

        @Override
        public Integer call() {
            return act(Core.onThisComputer(), Act.open(dir), System.out, System.err);
        }
    }

    @Command(name = "close", header = "Close the project open in the core.",
            description = "Refused while Studio holds it: Studio closes it itself.", mixinStandardHelpOptions = true)
    static final class Close implements Callable<Integer> {
        @Option(names = "--keep-run", description = "Let a bot running now finish first.")
        private boolean keepRun;

        @Override
        public Integer call() {
            return act(Core.onThisComputer(), Act.close(keepRun), System.out, System.err);
        }
    }

    @Command(name = "reload", header = "Resolve the open project's libraries and bind its plugins again.",
            description = "As after its pom changed; in dev mode, picks up a local build.",
            mixinStandardHelpOptions = true)
    static final class Reload implements Callable<Integer> {
        @Override
        public Integer call() {
            return act(Core.onThisComputer(), Act.reload(), System.out, System.err);
        }
    }

    /**
     * One act on the core: the MCP tool it calls, its arguments, and whether it may start a core when none runs
     * (start and open do; quitting, stopping or reloading a core that is not there would start one only to act on it).
     */
    record Act(Tool tool, Map<String, Object> arguments, boolean startsOne) {
        static Act start() {
            return new Act(Tool.CORE_STATUS, Map.of(), true);
        }

        static Act quit(boolean force) {
            return new Act(Tool.QUIT_CORE, Map.of("force", force), false);
        }

        /** {@code run} a run id or {@code all}; null for the bot's one run. */
        static Act stop(String run) {
            return new Act(Tool.STOP, run == null ? Map.of() : Map.of("run_id", run), false);
        }

        static Act open(Path dir) {
            return new Act(Tool.OPEN_PROJECT, Map.of("path", dir.toAbsolutePath().normalize().toString()), true);
        }

        static Act close(boolean keepRun) {
            return new Act(Tool.CLOSE_PROJECT, Map.of("keep_run", keepRun), false);
        }

        static Act reload() {
            return new Act(Tool.RELOAD_PLUGINS, Map.of(), false);
        }
    }

    /** The core's MCP tools an act calls, by the names the core serves them under ({@code CoreHome}, {@code RunTools}). */
    enum Tool {
        CORE_STATUS("core_status"), QUIT_CORE("quit_core"), STOP("stop"), OPEN_PROJECT("open_project"),
        CLOSE_PROJECT("close_project"), RELOAD_PLUGINS("reload_plugins"), UNKNOWN("");

        private final String id;

        Tool(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        static Tool fromId(String id) {
            for (Tool tool : values()) if (tool != UNKNOWN && tool.id.equals(id)) return tool;
            return UNKNOWN;
        }
    }

    /** The core the acts reach: whether one runs, its tools, and its process. */
    interface Core {
        /** Whether a core answers now and proves it is this computer's; asked before an act that starts none. */
        boolean runs() throws IOException;

        /**
         * Its tools; through a bridge that starts a core when none answers only when {@code startsOne}, else one that
         * answers {@link CoreTools.Answer#unreached} (a core that ended since {@link #runs} said it ran is not started).
         */
        CoreTools tools(boolean startsOne);

        /** The running core's process id, as its {@code core.json} says. */
        OptionalLong pid();

        boolean alive(long pid);

        /** The user's core, where {@code botmaker mcp-bridge} finds it. */
        static Core onThisComputer() {
            return new Core() {
                @Override
                public boolean runs() throws IOException {
                    Optional<CoreFeed.At> at = CoreFeed.onThisComputer();
                    return at.isPresent() && CoreFeed.answers(at.get());
                }

                @Override
                public CoreTools tools(boolean startsOne) {
                    return startsOne ? CoreTools.onThisComputer() : CoreTools.runningOnThisComputer();
                }

                @Override
                public OptionalLong pid() {
                    Path file = McpBridge.coreDir().resolve("core.json");
                    try {
                        if (!Files.isRegularFile(file)) return OptionalLong.empty();
                        JsonNode pid = JSON.readTree(file.toFile()).path("pid");
                        return pid.canConvertToLong() ? OptionalLong.of(pid.asLong()) : OptionalLong.empty();
                    } catch (IOException unreadable) {
                        return OptionalLong.empty();
                    }
                }

                @Override
                public boolean alive(long pid) {
                    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                }
            };
        }
    }

    /** {@code act}'s tool called on {@code core}: its answer printed, a refusal exit 1, no core exit 2. */
    static int act(Core core, Act act, PrintStream out, PrintStream err) {
        try {
            if (!act.startsOne() && !core.runs()) return noCore(err);
            CoreTools.Answer answer = core.tools(act.startsOne()).call(act.tool().id(), act.arguments());
            (answer.error() ? err : out).println(answer.text());
            if (answer.unreached()) return NO_CORE;
            return answer.error() ? 1 : 0;
        } catch (IOException unreached) {
            err.println(unreached.getMessage());
            return NO_CORE;
        }
    }

    /**
     * {@code quit_core}, then the old process waited for, {@code wait} at most, then a fresh core started; with none
     * running, only the start.
     */
    static int restart(Core core, boolean force, Duration wait, Duration pause, PrintStream out, PrintStream err) {
        try {
            if (!core.runs()) return act(core, Act.start(), out, err);
            OptionalLong pid = core.pid();
            int quit = act(core, Act.quit(force), out, err);
            if (quit != 0) return quit;
            long until = System.nanoTime() + wait.toNanos();
            while (stillThere(core, pid)) {
                if (System.nanoTime() >= until) {
                    // A run started just after quit_core answered keeps the core: status says whether one runs.
                    err.println("The core" + (pid.isPresent() ? " (pid " + pid.getAsLong() + ")" : "")
                            + " has not ended after " + wait.toSeconds() + " s; a bot started since keeps it: "
                            + "botmaker core status says.");
                    return 1;
                }
                Thread.sleep(pause.toMillis());
            }
            // A Studio told to join a fresh core may start one at the same time: CoreProcess's start lock, which
            // Studio's starts take too, lets only one start and the other find it.
            return act(core, Act.start(), out, err);
        } catch (IOException unreached) {
            err.println(unreached.getMessage());
            return NO_CORE;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    /**
     * Whether the core that was told to quit is still there: its process, or without {@code core.json}'s pid, whether
     * anything answers; one that takes the connection but cannot answer while it ends is still there.
     */
    private static boolean stillThere(Core core, OptionalLong pid) {
        if (pid.isPresent()) return core.alive(pid.getAsLong());
        try {
            return core.runs();
        } catch (IOException ending) {
            return true;
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
