package com.botmaker.cli.mcp;

import com.botmaker.runlog.RunLog;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One run of a bot from the terminal (plan phase 12, {@code botmaker bot run}): BotMaker's core opens the bot and runs
 * it, what the run prints is streamed here, and the end says how it ended and where its run log is. Everything goes
 * through the core's MCP tools ({@link CoreTools}): the cli links neither the core nor Studio.
 *
 * <p>The run is followed by {@code read_trace}'s cursor ({@code from}): each read gives the whole lines since the last
 * one, and how many runs have started and ended, so neither the output nor the run's end is guessed from text.
 *
 * <p>The bot's output goes to {@code out}; what this says about the run goes to {@code err}, so {@code out} can be
 * piped.
 */
public final class BotRun {

    /** How a run ended, and the exit code the command ends with. */
    public enum End {
        /** The bot ran and its run ended. */
        ENDED(0),
        /** The bot never started: its compile failed, or the core refused the start. */
        NEVER_STARTED(1),
        /** The bot could not be opened, or the core could not be reached. */
        REFUSED(2),
        /** Stopped from here, by Ctrl+C. */
        STOPPED(130);

        private final int code;

        End(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    /** {@code RunTools.followed}'s first line. */
    private static final Pattern HEAD = Pattern.compile(
            "next=(-?\\d+) starts=(\\d+) ends=(\\d+) busy=(true|false) skipped=(\\d+)");
    /** {@code HomeTools.openProject}'s answer: Studio opens the project in its window, and its tools follow. */
    private static final String OPENING = "Opening ";
    /** How long a run is asked for again while Studio still opens the project. */
    private static final Duration OPEN_WAIT = Duration.ofSeconds(30);
    /** How long the run loop waits for the run's end after a stop; the shutdown hook waits a little longer. */
    private static final Duration STOP_WAIT = Duration.ofSeconds(15);
    private static final Duration HOOK_WAIT = STOP_WAIT.plusSeconds(5);
    /** The most reads the end drains the output with. */
    private static final int DRAIN_READS = 5;

    private final CoreTools tools;
    private final PrintStream out;
    private final PrintStream err;
    private final Duration poll;
    /** How long nothing may run, after the start was asked, before the run counts as never started. */
    private final Duration grace;
    private final CountDownLatch done = new CountDownLatch(1);
    /** When Ctrl+C asked for the stop; null before. */
    private volatile Instant stopAsked;
    private long cursor = -1;

    /** One read after the cursor: where the runs stood, and whether something was on the game. */
    record Read(long starts, long ends, boolean busy, List<String> lines) {}

    public BotRun(CoreTools tools, PrintStream out, PrintStream err, Duration poll, Duration grace) {
        this.tools = tools;
        this.out = out;
        this.err = err;
        this.poll = poll;
        this.grace = grace;
    }

    /** Opens {@code folder} in the core and runs its main, or the declared run {@code entry} titles. */
    public End run(Path folder, Optional<String> entry) {
        try {
            return runOnce(folder.toAbsolutePath().normalize(), entry);
        } catch (IOException e) {
            err.println("BotMaker's core did not answer: " + e.getMessage());
            return stopAsked != null ? End.STOPPED : End.REFUSED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return End.STOPPED;
        } finally {
            done.countDown();
        }
    }

    /**
     * Ctrl+C: asks the core to stop the run, then waits a while for {@link #run} to see its end. Called from a shutdown
     * hook, off the run's thread.
     */
    public void stop() {
        if (done.getCount() == 0) return;
        stopAsked = Instant.now();
        try {
            err.println("Stopping the run…");
            CoreTools.Answer answer = tools.call("stop", Map.of());
            if (answer.error()) err.println(answer.text());
        } catch (IOException e) {
            err.println("The stop did not reach BotMaker's core: " + e.getMessage());
        }
        try {
            done.await(HOOK_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private End runOnce(Path folder, Optional<String> entry) throws IOException, InterruptedException {
        CoreTools.Answer opened = tools.call("open_project", Map.of("path", folder.toString()));
        if (opened.error()) {
            err.println(opened.text());
            return End.REFUSED;
        }
        err.println(opened.text());
        Read before = follow();
        Instant asked = Instant.now();

        Map<String, Object> run = new LinkedHashMap<>();
        entry.ifPresent(title -> run.put("entry", title));
        if (stopAsked != null) return End.STOPPED;
        CoreTools.Answer started = tools.call("run_bot", run);
        // Studio opens a project in its window: its run tool comes once it is open. A refusal while something runs
        // is a real one.
        while (started.error() && opened.text().startsWith(OPENING) && !before.busy() && stopAsked == null
                && Duration.between(asked, Instant.now()).compareTo(OPEN_WAIT) < 0) {
            Thread.sleep(poll.toMillis());
            before = follow();
            if (before.busy() || stopAsked != null) break;
            started = tools.call("run_bot", run);
        }
        if (stopAsked != null && started.error()) return End.STOPPED;
        if (started.error()) {
            err.println(started.text());
            return End.NEVER_STARTED;
        }
        err.println(started.text());

        Instant lastBusy = Instant.now();
        while (true) {
            Thread.sleep(poll.toMillis());
            Read now = follow();
            boolean ran = now.starts() > before.starts();
            if (ran && now.ends() > before.ends() && !now.busy()) return ended(folder, asked, End.ENDED);
            Instant stop = stopAsked;
            if (stop != null && Duration.between(stop, Instant.now()).compareTo(STOP_WAIT) > 0) {
                return ended(folder, asked, End.STOPPED);
            }
            if (now.busy()) {
                lastBusy = Instant.now();
            } else if (!ran && Duration.between(lastBusy, Instant.now()).compareTo(grace) > 0) {
                return ended(folder, asked, End.NEVER_STARTED);
            }
        }
    }

    /** The output's last lines, how it ended, and the run's log. */
    private End ended(Path folder, Instant asked, End end) throws IOException, InterruptedException {
        for (int i = 0; i < DRAIN_READS; i++) {
            Thread.sleep(poll.toMillis());
            if (follow().lines().isEmpty()) break;
        }
        End how = stopAsked != null && end != End.REFUSED ? End.STOPPED : end;
        err.println(switch (how) {
            case ENDED -> "The run ended.";
            case STOPPED -> "The run was stopped.";
            case NEVER_STARTED -> "The bot did not start: the lines above say why.";
            case REFUSED -> "The run was refused.";
        });
        if (how != End.NEVER_STARTED) {
            runLog(folder, asked).ifPresent(log -> {
                err.println("Run log: " + log);
                err.println("Its report: botmaker reports --run " + log.getParent());
            });
        }
        return how;
    }

    /** Reads after the cursor, prints the lines it gained, and moves the cursor past them. */
    private Read follow() throws IOException {
        CoreTools.Answer answer = tools.call("read_trace", Map.of("from", cursor));
        if (answer.error()) throw new IOException(answer.text());
        List<String> lines = answer.text().lines().toList();
        Matcher head = lines.isEmpty() ? null : HEAD.matcher(lines.getFirst());
        if (head == null || !head.matches()) {
            throw new IOException("this core's read_trace has no cursor: it is older than this command.");
        }
        boolean first = cursor < 0;
        cursor = Long.parseLong(head.group(1));
        long skipped = Long.parseLong(head.group(5));
        if (skipped > 0 && !first) err.println("(" + skipped + " lines came and went too fast to show)");
        List<String> gained = lines.subList(1, lines.size());
        gained.forEach(out::println);
        out.flush();
        return new Read(Long.parseLong(head.group(2)), Long.parseLong(head.group(3)),
                Boolean.parseBoolean(head.group(4)), gained);
    }

    /** The run's own {@code run.jsonl}: the newest under the bot's runs folder written since the start was asked. */
    static Optional<Path> runLog(Path folder, Instant asked) {
        Path runs = folder.resolve(RunLog.FOLDER);
        if (!Files.isDirectory(runs)) return Optional.empty();
        FileTime since = FileTime.from(asked.minusSeconds(1));
        try (Stream<Path> each = Files.list(runs)) {
            return each.map(dir -> dir.resolve(RunLog.FILE)).filter(Files::isRegularFile)
                    .filter(file -> modified(file).compareTo(since) >= 0)
                    .max(Comparator.comparing(BotRun::modified));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    private static FileTime modified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException gone) {
            return FileTime.fromMillis(0);
        }
    }
}
