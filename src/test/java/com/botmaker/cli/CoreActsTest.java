// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli;

import com.botmaker.cli.CoreCommand.Act;
import com.botmaker.cli.CoreCommand.Tool;
import com.botmaker.cli.core.CoreFeed;
import com.botmaker.cli.mcp.CoreTools;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code botmaker core}'s acts (core dashboard plan, phase 8), over a fake core. */
class CoreActsTest {

    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final Duration PAUSE = Duration.ofMillis(10);

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    /** Each command is its tool and its arguments; only start and open may start a core. */
    @Test
    void eachCommandIsItsTool() {
        assertEquals(new Act(Tool.CORE_STATUS, Map.of(), true), Act.start());
        assertEquals(new Act(Tool.QUIT_CORE, Map.of("force", true), false), Act.quit(true));
        assertEquals(new Act(Tool.STOP, Map.of(), false), Act.stop(null));
        assertEquals(new Act(Tool.STOP, Map.of("run_id", "all"), false), Act.stop("all"));
        Path bot = Path.of("bots", "..", "Nunu");
        assertEquals(new Act(Tool.OPEN_PROJECT, Map.of("path", Path.of("Nunu").toAbsolutePath().toString()), true),
                Act.open(bot));
        assertEquals(new Act(Tool.CLOSE_PROJECT, Map.of("keep_run", true), false), Act.close(true));
        assertEquals(new Act(Tool.RELOAD_PLUGINS, Map.of(), false), Act.reload());
        assertEquals("reload_plugins", Tool.RELOAD_PLUGINS.id());
        assertEquals(Tool.UNKNOWN, Tool.fromId("nope"));
    }

    /** An act that must start no core is called through a bridge that starts none, should the core end meanwhile. */
    @Test
    void onlyStartAndOpenAreCalledThroughABridgeThatStarts() {
        Fake core = new Fake(true, new CoreTools.Answer("ok", false));
        for (Act act : List.of(Act.start(), Act.quit(false), Act.stop(null), Act.open(Path.of("x")), Act.close(false),
                Act.reload())) {
            CoreCommand.act(core, act, print(out), print(err));
        }
        assertEquals(List.of(true, false, false, true, false, false), core.starting);
    }

    /** No core gave the answer (the bridge found none, and started none): exit 2, as for no core at all. */
    @Test
    void anUnreachedAnswerExitsTwo() {
        Fake core = new Fake(true, new CoreTools.Answer("BotMaker's core is not running", true, true));
        assertEquals(CoreCommand.NO_CORE, CoreCommand.act(core, Act.reload(), print(out), print(err)));
    }

    /** Without core.json's pid, a core that cannot answer while it ends is waited for, not given up on. */
    @Test
    void restartWaitsThroughACoreThatCannotAnswerWhileItEnds() {
        Fake core = new Fake(true, new CoreTools.Answer("Quitting.", false)) {
            private int probes;

            @Override
            public boolean runs() throws IOException {
                probes++;
                // Restart's check and quit's; then, while it ends, one that cannot answer.
                if (probes <= 2) return true;
                if (probes == 3) throw new IOException("What answers on 7431 is not this computer's Studio");
                return false;
            }

            @Override
            public OptionalLong pid() {
                return OptionalLong.empty();
            }
        };
        assertEquals(0, CoreCommand.restart(core, false, WAIT, PAUSE, print(out), print(err)));
        assertEquals(List.of("quit_core " + Map.of("force", false), "core_status {}"), core.calls);
    }

    @Test
    void theToolsAnswerIsPrinted() {
        Fake core = new Fake(true, new CoreTools.Answer("Closed NunuSite.", false));
        assertEquals(0, CoreCommand.act(core, Act.close(false), print(out), print(err)));
        assertEquals(List.of("close_project " + Map.of("keep_run", false)), core.calls);
        assertEquals("Closed NunuSite.\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aRefusalExitsOne() {
        Fake core = new Fake(true, new CoreTools.Answer("Studio holds NunuSite: close it there.", true));
        assertEquals(1, CoreCommand.act(core, Act.close(false), print(out), print(err)));
        assertEquals("Studio holds NunuSite: close it there.\n", err.toString(StandardCharsets.UTF_8));
    }

    /** Quitting, stopping or reloading a core that is not running would start one only to act on it: none starts. */
    @Test
    void actingOnNoCoreStartsNone() {
        Fake core = new Fake(false, new CoreTools.Answer("ok", false));
        for (Act act : List.of(Act.quit(false), Act.stop("all"), Act.close(false), Act.reload())) {
            assertEquals(CoreCommand.NO_CORE, CoreCommand.act(core, act, print(out), print(err)), act.tool().id());
        }
        assertEquals(List.of(), core.calls);
        assertTrue(err.toString(StandardCharsets.UTF_8).startsWith(CoreFeed.NONE), err::toString);
    }

    @Test
    void startStartsOne() {
        Fake core = new Fake(false, new CoreTools.Answer("core 1.3.0, pid 42", false));
        assertEquals(0, CoreCommand.act(core, Act.start(), print(out), print(err)));
        assertEquals(List.of("core_status {}"), core.calls);
    }

    /** Restart quits, waits until the old process is gone, then starts a fresh one. */
    @Test
    void restartWaitsForTheOldPid() {
        Fake core = new Fake(true, new CoreTools.Answer("Quitting.", false));
        core.aliveFor = 3;
        assertEquals(0, CoreCommand.restart(core, true, WAIT, PAUSE, print(out), print(err)));
        assertEquals(List.of("quit_core " + Map.of("force", true), "core_status {}"), core.calls);
        assertEquals(4, core.asked.get(), "asked until the old pid was gone");
    }

    @Test
    void aCoreThatDoesNotEndIsNotRestarted() {
        Fake core = new Fake(true, new CoreTools.Answer("Quitting.", false));
        core.aliveFor = Integer.MAX_VALUE;
        assertEquals(1, CoreCommand.restart(core, false, Duration.ofMillis(100), PAUSE, print(out), print(err)));
        assertEquals(List.of("quit_core " + Map.of("force", false)), core.calls);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("pid 42"), err::toString);
    }

    @Test
    void aRefusedQuitIsNotRestarted() {
        Fake core = new Fake(true, new CoreTools.Answer("A bot runs: stop it first.", true));
        assertEquals(1, CoreCommand.restart(core, false, WAIT, PAUSE, print(out), print(err)));
        assertEquals(1, core.calls.size());
    }

    /** No core to restart: restart starts one. */
    @Test
    void restartingNoCoreStartsOne() {
        Fake core = new Fake(false, new CoreTools.Answer("core 1.3.0", false));
        assertEquals(0, CoreCommand.restart(core, false, WAIT, PAUSE, print(out), print(err)));
        assertEquals(List.of("core_status {}"), core.calls);
    }

    private static class Fake implements CoreCommand.Core {
        final List<String> calls = new ArrayList<>();
        final List<Boolean> starting = new ArrayList<>();
        final AtomicInteger asked = new AtomicInteger();
        private final boolean runs;
        private final CoreTools.Answer answer;
        int aliveFor;

        Fake(boolean runs, CoreTools.Answer answer) {
            this.runs = runs;
            this.answer = answer;
        }

        @Override
        public boolean runs() throws IOException {
            return runs;
        }

        @Override
        public CoreTools tools(boolean startsOne) {
            starting.add(startsOne);
            return (tool, arguments) -> {
                calls.add(tool + " " + arguments);
                // A started core answers its status whatever the quit said.
                return tool.equals("core_status") ? new CoreTools.Answer("core 1.3.0", false) : answer;
            };
        }

        @Override
        public OptionalLong pid() {
            return OptionalLong.of(42);
        }

        @Override
        public boolean alive(long pid) {
            return asked.incrementAndGet() <= aliveFor;
        }
    }

    private static PrintStream print(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    /** A core that could not be reached through the bridge is said, exit 2, as {@code tool} says it. */
    @Test
    void anUnreachableCoreExitsTwo() {
        Fake core = new Fake(true, null) {
            @Override
            public CoreTools tools(boolean startsOne) {
                return (tool, arguments) -> {
                    throw new IOException("the bridge to BotMaker's core ended");
                };
            }
        };
        assertEquals(CoreCommand.NO_CORE, CoreCommand.act(core, Act.reload(), print(out), print(err)));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("bridge to BotMaker's core ended"), err::toString);
    }
}
