// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.mcp;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Several runs of a bot started from the terminal (plan run-claims, phase 12, {@code botmaker bot run --runs N}):
 * BotMaker's core counts what can run first, which is shown, and the start is asked before anything starts. Each run
 * goes on in its own process after this ends; the core's {@code stop} with {@code run_id all} stops them. Everything
 * goes through the core's MCP tools ({@link CoreTools}), as {@link BotRun} does.
 *
 * <p>What the core answers goes to {@code out}; what this says about it, and the question, to {@code err}.
 */
public final class SeveralStart {

    /** How a start of several runs ended, and the exit code the command ends with. */
    public enum End {
        /** The runs started, or a dry run said how many could. */
        STARTED(0),
        /** None started: none could, the core refused, or the start was declined. */
        NONE_STARTED(1),
        /** The bot could not be opened, or the core could not be reached. */
        REFUSED(2),
        /** All or none was asked, and fewer can run than asked: none started. */
        SHORT(3);

        private final int code;

        End(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    /**
     * What the terminal asked.
     *
     * @param runs      how many runs
     * @param dryRun    only say how many can run
     * @param allOrNone start none unless all can
     * @param pins      each {@code [run:]pool=value}, as the core reads them
     * @param yes       start without asking
     */
    public record Ask(int runs, boolean dryRun, boolean allOrNone, List<String> pins, boolean yes) {
        public Ask {
            pins = pins == null ? List.of() : List.copyOf(pins);
        }
    }

    /** Asks the terminal's user {@code question}; true to go on. */
    @FunctionalInterface
    public interface Confirm {
        boolean yes(String question) throws IOException;
    }

    /** {@code run_bot}'s first words for a dry run, and for a start: {@code RunTools.several}. */
    private static final Pattern COUNTED = Pattern.compile("(\\d+) of (\\d+) can run\\b.*", Pattern.DOTALL);
    private static final Pattern STARTED = Pattern.compile("(\\d+) of (\\d+) started\\b.*", Pattern.DOTALL);

    private final CoreTools tools;
    private final PrintStream out;
    private final PrintStream err;
    private final Duration poll;
    private final Confirm confirm;

    public SeveralStart(CoreTools tools, PrintStream out, PrintStream err, Duration poll, Confirm confirm) {
        this.tools = tools;
        this.out = out;
        this.err = err;
        this.poll = poll;
        this.confirm = confirm;
    }

    /** Opens {@code folder} in the core, counts what {@code ask} can run, and starts it once asked. */
    public End start(Path folder, Ask ask) {
        try {
            return startOnce(folder.toAbsolutePath().normalize(), ask);
        } catch (IOException e) {
            err.println("BotMaker's core did not answer: " + e.getMessage());
            return End.REFUSED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return End.NONE_STARTED;
        }
    }

    private End startOnce(Path folder, Ask ask) throws IOException, InterruptedException {
        CoreTools.Answer opened = tools.call("open_project", Map.of("path", folder.toString()));
        if (opened.error()) {
            err.println(opened.text());
            return End.REFUSED;
        }
        err.println(opened.text());

        Map<String, Object> count = arguments(ask);
        count.put("dry_run", true);
        CoreTools.Answer counted = tools.call("run_bot", count);
        // Studio opens a project in its window: its run tool comes once it is open.
        Instant asked = Instant.now();
        while (counted.error() && opened.text().startsWith(BotRun.OPENING)
                && Duration.between(asked, Instant.now()).compareTo(BotRun.OPEN_WAIT) < 0) {
            Thread.sleep(poll.toMillis());
            counted = tools.call("run_bot", count);
        }
        if (counted.error()) {
            err.println(counted.text());
            return End.NONE_STARTED;
        }
        out.println(counted.text());
        out.flush();
        Matcher can = COUNTED.matcher(counted.text());
        if (!can.matches()) throw new IOException("this core's run_bot has no dry run: it is older than this command.");
        int canRun = Integer.parseInt(can.group(1));
        if (ask.dryRun()) return End.STARTED;
        if (canRun == 0) {
            err.println("None can run: nothing started.");
            return End.NONE_STARTED;
        }
        if (ask.allOrNone() && canRun < ask.runs()) {
            err.println("All or none was asked, and only " + canRun + " of " + ask.runs() + " can run: nothing "
                    + "started.");
            return End.SHORT;
        }
        if (!ask.yes() && !confirm.yes("Start " + canRun + (canRun == 1 ? " run" : " runs") + "? [y/N] ")) {
            err.println("Nothing started.");
            return End.NONE_STARTED;
        }

        Map<String, Object> start = arguments(ask);
        if (ask.allOrNone()) start.put("all_or_none", true);
        CoreTools.Answer started = tools.call("run_bot", start);
        if (started.error()) {
            err.println(started.text());
            return End.NONE_STARTED;
        }
        out.println(started.text());
        out.flush();
        Matcher went = STARTED.matcher(started.text());
        if (!went.matches()) throw new IOException("this core's run_bot did not say what started.");
        int startedRuns = Integer.parseInt(went.group(1));
        if (ask.allOrNone() && startedRuns < ask.runs()) return End.SHORT;
        if (startedRuns == 0) return End.NONE_STARTED;
        err.println("The runs go on after this command: stop them in BotMaker, or with the MCP's stop {run_id: all}.");
        return End.STARTED;
    }

    /** {@code run_bot}'s arguments for {@code ask}'s runs and pins. */
    private static Map<String, Object> arguments(Ask ask) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("runs", ask.runs());
        if (!ask.pins().isEmpty()) arguments.put("pins", ask.pins());
        return arguments;
    }
}
