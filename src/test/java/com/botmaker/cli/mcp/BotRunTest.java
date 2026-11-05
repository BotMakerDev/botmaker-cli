package com.botmaker.cli.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code botmaker bot run}'s run against a fake core: what it streams, and how each kind of end is told. */
class BotRunTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void aRunStreamsOnlyItsNewLinesAndEndsWithItsLog(@TempDir Path bot) {
        FakeCore core = new FakeCore(List.of("── stopped the bot after 4s ──"));
        core.then(() -> core.busy = true);
        core.then(() -> {
            core.starts++;
            core.log.add("── started the bot ──");
            core.log.add("hello");
            writeLog(bot);
        });
        core.then(() -> core.log.add("[Flow] clicked Play"));
        core.then(() -> {
            core.log.add("── stopped the bot after 2s ──");
            core.ends++;
            core.busy = false;
        });

        BotRun.End end = run(core, bot, Optional.of("Explore map"));

        assertEquals(BotRun.End.ENDED, end);
        assertEquals(List.of("── started the bot ──", "hello", "[Flow] clicked Play", "── stopped the bot after 2s ──"),
                text(out).lines().toList(), "the earlier run's lines are not shown again");
        assertEquals(Map.of("path", bot.toString()), core.calls.getFirst().arguments());
        assertEquals(Map.of("entry", "Explore map"), core.argumentsOf("run_bot"));
        assertTrue(text(err).contains("Run log: " + bot.resolve("target/botmaker-runs/20261105-120000/run.jsonl")));
    }

    @Test
    void aCompileThatFailsIsNeverStarted(@TempDir Path bot) {
        FakeCore core = new FakeCore(List.of());
        core.then(() -> core.busy = true);
        core.then(() -> {
            core.log.add("Main.java:3: error: ';' expected");
            core.busy = false;
        });

        assertEquals(BotRun.End.NEVER_STARTED, run(core, bot, Optional.empty()));
        assertEquals("Main.java:3: error: ';' expected\n", text(out));
        assertEquals(Map.of(), core.argumentsOf("run_bot"));
        assertFalse(text(err).contains("Run log:"), "no run, no log");
    }

    @Test
    void aRefusedStartIsNeverStartedAndABotThatCannotOpenIsRefused(@TempDir Path bot) {
        FakeCore busy = new FakeCore(List.of());
        busy.refuse = "run_bot";
        assertEquals(BotRun.End.NEVER_STARTED, run(busy, bot, Optional.empty()));

        FakeCore core = new FakeCore(List.of());
        core.refuse = "open_project";
        assertEquals(BotRun.End.REFUSED, run(core, bot, Optional.empty()));
        assertTrue(text(err).contains("refused open_project"));
        assertEquals(1, core.calls.size(), "nothing runs after a refused open");
    }

    @Test
    void ctrlCStopsTheRun(@TempDir Path bot) throws Exception {
        FakeCore core = new FakeCore(List.of());
        core.then(() -> {
            core.busy = true;
            core.starts++;
            core.log.add("── started the bot ──");
        });
        core.onStop = () -> {
            core.log.add("── stopped the bot after 1s ──");
            core.ends++;
            core.busy = false;
        };
        BotRun run = new BotRun(core, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Duration.ofMillis(10), Duration.ofMillis(100));

        CompletableFuture<BotRun.End> end = CompletableFuture.supplyAsync(() -> run.run(bot, Optional.empty()));
        while (!core.started()) Thread.sleep(5);
        run.stop();

        assertEquals(BotRun.End.STOPPED, end.get(5, TimeUnit.SECONDS));
        assertTrue(text(err).contains("The run was stopped."));
        assertTrue(text(out).contains("── stopped the bot after 1s ──"), "the last lines still come");
    }

    @Test
    void aCallThroughTheBridgeGetsItsOwnAnswer() throws Exception {
        CoreTools tools = new CoreTools.Bridged((launch, in, stream, poll) -> new McpBridge(Optional::empty, launch,
                in, stream, poll));

        CoreTools.Answer down = tools.call("open_project", Map.of("path", "/nowhere"));

        assertTrue(down.error());
        assertEquals(McpBridge.DOWN, down.text(), "no core: the bridge's own answer, matched to the call");
    }

    private BotRun.End run(FakeCore core, Path bot, Optional<String> entry) {
        BotRun run = new BotRun(core, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Duration.ofMillis(5), Duration.ofMillis(60));
        return run.run(bot, entry);
    }

    private static void writeLog(Path bot) {
        try {
            Path folder = Files.createDirectories(bot.resolve("target/botmaker-runs/20261105-120000"));
            Files.writeString(folder.resolve("run.jsonl"), "{}\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8);
    }

    /**
     * A core whose log is followed by cursor, as {@code RunLog.since} gives it; once the run is asked for, each read
     * moves one scripted step on.
     */
    private static final class FakeCore implements CoreTools {
        record Call(String tool, Map<String, Object> arguments) {}

        final List<String> log;
        final List<Call> calls = new ArrayList<>();
        private final Deque<Runnable> steps = new ArrayDeque<>();
        long starts;
        long ends;
        boolean busy;
        String refuse;
        Runnable onStop = () -> {};
        private boolean asked;

        FakeCore(List<String> earlier) {
            log = new ArrayList<>(earlier);
        }

        void then(Runnable step) {
            steps.add(step);
        }

        synchronized boolean started() {
            return starts > 0;
        }

        Map<String, Object> argumentsOf(String tool) {
            return calls.stream().filter(c -> c.tool().equals(tool)).findFirst().orElseThrow().arguments();
        }

        @Override
        public synchronized Answer call(String tool, Map<String, Object> arguments) {
            calls.add(new Call(tool, arguments));
            if (tool.equals(refuse)) return new Answer("refused " + tool, true);
            return switch (tool) {
                case "open_project" -> new Answer("Opened " + arguments.get("path") + ".", false);
                case "run_bot" -> {
                    asked = true;
                    yield new Answer("Starting the bot.", false);
                }
                case "read_trace" -> {
                    long cursor = (Long) arguments.get("from");
                    long from = cursor < 0 ? log.size() : cursor;
                    List<String> lines = log.subList((int) from, log.size());
                    String head = "next=" + log.size() + " starts=" + starts + " ends=" + ends + " busy=" + busy
                            + " skipped=0";
                    String answer = lines.isEmpty() ? head : head + "\n" + String.join("\n", lines);
                    if (asked && !steps.isEmpty()) steps.removeFirst().run();
                    yield new Answer(answer, false);
                }
                case "stop" -> {
                    onStop.run();
                    yield new Answer("Stopped.", false);
                }
                default -> new Answer("no tool " + tool, true);
            };
        }
    }
}
