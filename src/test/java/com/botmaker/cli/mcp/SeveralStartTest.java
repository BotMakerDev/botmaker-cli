// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code botmaker bot run --runs N} against a fake core: the count first, the question, then the start. */
class SeveralStartTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    /** A core whose {@code run_bot} counts {@code canRun} of what is asked, and starts as many. */
    private static final class FakeCore implements CoreTools {
        final List<Map<String, Object>> runBot = new ArrayList<>();
        final int canRun;

        FakeCore(int canRun) {
            this.canRun = canRun;
        }

        @Override
        public Answer call(String tool, Map<String, Object> arguments) {
            if (tool.equals("open_project")) return new Answer("Opened farm.", false);
            runBot.add(arguments);
            int asked = (int) arguments.get("runs");
            if (Boolean.TRUE.equals(arguments.get("dry_run"))) {
                return new Answer(canRun + " of " + asked + " can run: 1 instance missing.\n- run 1: INSTANCE = ld-1",
                        false);
            }
            int started = Boolean.TRUE.equals(arguments.get("all_or_none")) && canRun < asked ? 0 : canRun;
            return new Answer(started + " of " + asked + " started.\n- run 1, id r-1: INSTANCE = ld-1", false);
        }
    }

    private SeveralStart.End start(FakeCore core, Path bot, SeveralStart.Ask ask, boolean answer, List<String> asked) {
        SeveralStart start = new SeveralStart(core, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Duration.ofMillis(1), question -> {
            asked.add(question);
            return answer;
        });
        return start.start(bot, ask);
    }

    @Test
    void theCountIsShownAndAskedBeforeTheStartWithThePinsBoth(@TempDir Path bot) {
        FakeCore core = new FakeCore(1);
        List<String> asked = new ArrayList<>();
        SeveralStart.End end = start(core, bot, new SeveralStart.Ask(2, false, false, List.of("INSTANCE=ld-1"), false),
                true, asked);

        assertEquals(SeveralStart.End.STARTED, end);
        assertEquals(List.of("Start 1 run? [y/N] "), asked);
        assertEquals(List.of(Map.of("runs", 2, "pins", List.of("INSTANCE=ld-1"), "dry_run", true),
                Map.of("runs", 2, "pins", List.of("INSTANCE=ld-1"))), core.runBot);
        String shown = out.toString(StandardCharsets.UTF_8);
        assertTrue(shown.startsWith("1 of 2 can run") && shown.contains("1 of 2 started"), shown);
    }

    @Test
    void aDryRunADeclinedStartAndAYesEachAskWhatTheySay(@TempDir Path bot) {
        FakeCore counted = new FakeCore(2);
        List<String> asked = new ArrayList<>();
        assertEquals(SeveralStart.End.STARTED,
                start(counted, bot, new SeveralStart.Ask(2, true, false, List.of(), false), true, asked));
        assertEquals(1, counted.runBot.size(), "a dry run starts nothing");

        FakeCore declined = new FakeCore(2);
        assertEquals(SeveralStart.End.NONE_STARTED,
                start(declined, bot, new SeveralStart.Ask(2, false, false, List.of(), false), false, asked));
        assertEquals(1, declined.runBot.size(), "declined: only counted");

        FakeCore yes = new FakeCore(2);
        assertEquals(SeveralStart.End.STARTED,
                start(yes, bot, new SeveralStart.Ask(2, false, false, List.of(), true), false, asked));
        assertEquals(2, yes.runBot.size());
        assertEquals(List.of("Start 2 runs? [y/N] "), asked, "only the declined start asked");
    }

    @Test
    void allOrNoneThatFallsShortStartsNothingAndExitsThreeAndNoneThatCanRunExitsOne(@TempDir Path bot) {
        FakeCore shortOne = new FakeCore(1);
        SeveralStart.End end = start(shortOne, bot, new SeveralStart.Ask(2, false, true, List.of(), true), true,
                new ArrayList<>());
        assertEquals(SeveralStart.End.SHORT, end);
        assertEquals(3, end.code());
        assertEquals(1, shortOne.runBot.size(), "nothing asked to start");

        FakeCore none = new FakeCore(0);
        assertEquals(SeveralStart.End.NONE_STARTED,
                start(none, bot, new SeveralStart.Ask(2, false, false, List.of(), true), true, new ArrayList<>()));
        assertEquals(1, none.runBot.size());
    }
}
