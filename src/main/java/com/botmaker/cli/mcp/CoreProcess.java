package com.botmaker.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Starts BotMaker's core with no Studio running (plan phase 11), as the last Studio started it: the cli cannot link the
 * core, so it runs the command that Studio saved beside the core's files ({@code CoreLauncher.COMMAND_FILE}). It starts
 * under the lock Studio's starts take ({@code core.lock}), so a Studio starting at the same moment finds this one's.
 */
public final class CoreProcess implements McpBridge.CoreStart {

    /** {@code CoreLauncher.COMMAND_FILE}. */
    static final String COMMAND_FILE = "core-command.json";
    /** {@code CoreLauncher.START_LOCK}. */
    static final String START_LOCK = "core.lock";
    /** {@code CoreLauncher.WAIT}. */
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration PAUSE = Duration.ofMillis(200);

    private final Path dir;
    private final BooleanSupplier answers;

    /** A core for {@code dir}, the core's folder; {@code answers} says whether one answers already. */
    public CoreProcess(Path dir, BooleanSupplier answers) {
        this.dir = dir;
        this.answers = answers;
    }

    @Override
    public Optional<String> start() {
        Optional<List<String>> command = command(dir);
        if (command.isEmpty()) {
            return Optional.of("No BotMaker Studio has started a core on this computer yet, so the bridge does not "
                    + "know how to start one: start Studio once (start_studio), then call again.");
        }
        try {
            Files.createDirectories(dir);
            try (FileChannel file = FileChannel.open(dir.resolve(START_LOCK), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                 FileLock ignored = file.lock()) {
                if (answers.getAsBoolean()) return Optional.empty();
                Path log = dir.resolve("core.log");
                // Never this process's stdout: that is the protocol. The core outlives the bridge, to end its runs.
                Process process = new ProcessBuilder(command.get()).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                        .redirectInput(ProcessBuilder.Redirect.from(nullFile())).start();
                long until = System.nanoTime() + WAIT.toNanos();
                while (System.nanoTime() < until) {
                    if (answers.getAsBoolean()) return Optional.empty();
                    if (!process.isAlive()) {
                        return Optional.of("BotMaker's core stopped as it started (exit " + process.exitValue()
                                + "); its log is " + log + ".");
                    }
                    Thread.sleep(PAUSE);
                }
                return Optional.of("BotMaker's core did not answer within " + WAIT.toSeconds() + " s; its log is "
                        + log + ".");
            }
        } catch (IOException e) {
            return Optional.of("BotMaker's core did not start: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("The core's start was interrupted.");
        }
    }

    /** The command a Studio saved in {@code dir}; empty when none did, or it cannot be read. */
    static Optional<List<String>> command(Path dir) {
        Path file = dir.resolve(COMMAND_FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            JsonNode saved = new ObjectMapper().readTree(file.toFile()).path("command");
            List<String> command = new ArrayList<>();
            saved.forEach(part -> command.add(part.asText()));
            return command.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(command));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    private static File nullFile() {
        return new File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    }
}
