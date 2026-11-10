package com.botmaker.cli.mcp;

import com.botmaker.cli.Mvn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Starts BotMaker's core with no Studio running (plan phase 11), as the last Studio started it: the cli cannot link the
 * core, so it runs the command that Studio saved beside the core's files ({@code CoreLauncher.COMMAND_FILE}). It starts
 * under the lock Studio's starts take ({@code core.lock}), so a Studio starting at the same moment finds this one's.
 *
 * <p><b>With no Studio ever started</b> and a BotMaker checkout given ({@code --umbrella}), the command is made from the
 * checkout (2026-11-10): the dev Studio's classpath, as Maven resolves it, with the core's main. Studio's, not the
 * core's own: the core says the version {@code botmaker/host.properties} gives, which only Studio's classes carry, and
 * a Studio that finds a core of another version with a client attached refuses it. It is saved as Studio saves its
 * own, so the next start runs it straight away, and the next Studio's start writes over it.
 */
public final class CoreProcess implements McpBridge.CoreStart {

    /** {@code CoreMain}'s class and its {@code --dir}, which the cli cannot link. */
    static final String CORE_MAIN = "com.botmaker.core.server.CoreMain";
    static final String CORE_DIR = "--dir";
    /** Where the checkout's Studio keeps the classes {@code javafx:run} runs. */
    static final String STUDIO_CLASSES = "botmaker-studio/target/classes";
    /** How long Maven may take to resolve Studio's classpath offline. */
    private static final Duration RESOLVE_WAIT = Duration.ofMinutes(3);

    /** {@code CoreLauncher.COMMAND_FILE}. */
    static final String COMMAND_FILE = "core-command.json";
    /** {@code CoreLauncher.START_LOCK}. */
    static final String START_LOCK = "core.lock";
    /** {@code CoreLauncher.WAIT}. */
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration PAUSE = Duration.ofMillis(200);

    private final Path dir;
    private final BooleanSupplier answers;
    private final Optional<Path> checkout;
    /** Why the last derive failed, and the built Studio's time it failed for; start() runs under the start lock. */
    private String deriveFailed;
    private FileTime deriveFailedFor;

    /** A core for {@code dir}, the core's folder; {@code answers} says whether one answers already. */
    public CoreProcess(Path dir, BooleanSupplier answers) {
        this(dir, answers, Optional.empty());
    }

    /** As {@link #CoreProcess(Path, BooleanSupplier)}, its command made from {@code checkout} when none is saved. */
    public CoreProcess(Path dir, BooleanSupplier answers, Optional<Path> checkout) {
        this.dir = dir;
        this.answers = answers;
        this.checkout = checkout;
    }

    @Override
    public Optional<String> start() {
        try {
            Files.createDirectories(dir);
            // Under the lock Studio's starts take: a command is derived only when none is saved at that moment, so a
            // Studio's own is never written over, and two bridges never run Maven into the same files.
            try (FileChannel file = FileChannel.open(dir.resolve(START_LOCK), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                 FileLock ignored = file.lock()) {
                if (answers.getAsBoolean()) return Optional.empty();
                Optional<List<String>> command = command(dir);
                if (command.isEmpty() && checkout.isPresent()) {
                    Optional<String> failed = derive(checkout.get().toAbsolutePath().normalize());
                    if (failed.isPresent()) return failed;
                    command = command(dir);
                }
                if (command.isEmpty()) {
                    return Optional.of("No BotMaker Studio has started a core on this computer yet, so the bridge "
                            + "does not know how to start one: start Studio once (start_studio), or give the bridge "
                            + "a BotMaker checkout (--umbrella <dir> or $BOTMAKER_UMBRELLA), then call again.");
                }
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

    /**
     * Saves in {@link #dir} the command that starts the core from {@code checkout}'s built Studio; why not, when it
     * cannot.
     */
    private Optional<String> derive(Path checkout) throws InterruptedException {
        Path classes = checkout.resolve(STUDIO_CLASSES);
        if (!Files.isDirectory(classes)) {
            return Optional.of("BotMaker's core is started from " + checkout + "'s Studio, which is not built: run "
                    + "'mvn -pl botmaker-studio -am install -DskipTests' there, then call again.");
        }
        try {
            // A failed resolve is not run again until Studio is built again: the bridge retries a failed start every
            // few seconds, and each Maven run costs a JVM start inside a client's call.
            FileTime built = Files.getLastModifiedTime(classes);
            if (deriveFailed != null && built.equals(deriveFailedFor)) return Optional.of(deriveFailed);
            Optional<String> failed = resolve(checkout, classes);
            deriveFailed = failed.orElse(null);
            deriveFailedFor = built;
            return failed;
        } catch (IOException e) {
            return Optional.of("Studio's classpath was not resolved in " + checkout + ": " + e.getMessage());
        }
    }

    /** Resolves {@code checkout}'s Studio classpath and saves the core's command over it; why not, when it fails. */
    private Optional<String> resolve(Path checkout, Path classes) throws IOException, InterruptedException {
        Path classpath = dir.resolve("core-classpath.txt");
        Path log = dir.resolve("core-classpath.log");
        // An earlier run's file would pass for this one's should Maven exit 0 without writing.
        Files.deleteIfExists(classpath);
        Process maven = new ProcessBuilder(resolveCommand(Mvn.executable(checkout), classpath))
                .directory(checkout.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
                .redirectInput(ProcessBuilder.Redirect.from(nullFile())).start();
        if (!maven.waitFor(RESOLVE_WAIT.toSeconds(), TimeUnit.SECONDS)) {
            // On Windows the child is cmd.exe running mvn.cmd; Maven's JVM is its descendant.
            Mvn.kill(maven);
            return Optional.of("Maven did not resolve Studio's classpath within " + RESOLVE_WAIT.toMinutes()
                    + " min; its log is " + log + ".");
        }
        if (maven.exitValue() != 0 || !Files.isRegularFile(classpath)) {
            return Optional.of("Maven could not resolve " + checkout + "'s Studio classpath offline (exit "
                    + maven.exitValue() + "); its log is " + log + ". Build the checkout once "
                    + "('mvn -pl botmaker-studio -am install -DskipTests'), then call again.");
        }
        List<String> command = coreCommand(Path.of(System.getProperty("java.home"), "bin", "java"), classes,
                Files.readString(classpath).strip(), dir);
        Path written = Files.createTempFile(dir, COMMAND_FILE, ".tmp");
        new ObjectMapper().writeValue(written.toFile(), Map.of("command", command));
        Files.move(written, dir.resolve(COMMAND_FILE), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        return Optional.empty();
    }

    /**
     * The dependency plugin by its whole coordinate, at the version the cli's own build uses: offline, a bare
     * {@code dependency:} prefix resolves only through plugin-group metadata that a local repository may lack.
     */
    static final String BUILD_CLASSPATH = "org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath";

    /** Maven's line writing the checkout's Studio classpath, the one {@code javafx:run} runs, into {@code out}. */
    static List<String> resolveCommand(String maven, Path out) {
        return List.of(maven, "-B", "-o", "-q", "-pl", "botmaker-studio", BUILD_CLASSPATH,
                "-Dmdep.includeScope=runtime", "-Dmdep.outputFile=" + out);
    }

    /** The core's line, as {@code CoreLauncher.command} writes it: {@code java} on Studio's classes and classpath. */
    static List<String> coreCommand(Path java, Path classes, String classpath, Path dir) {
        String path = classpath.isEmpty() ? classes.toString() : classes + File.pathSeparator + classpath;
        return List.of(java.toString(), "--enable-native-access=ALL-UNNAMED", "-cp", path, CORE_MAIN, CORE_DIR,
                dir.toString());
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
