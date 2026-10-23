package com.botmaker.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Running Maven, which is the only way this CLI resolves anything.
 *
 * <p><b>Shelling out rather than embedding a resolver.</b> Maven Resolver as a library would let the CLI
 * resolve a coordinate in-process, and it would resolve it against <em>its own</em> idea of the local
 * repository, the mirrors and the settings — which is not the user's. The whole promise of {@code validate}
 * is that it answers what the registry will answer, and the way to keep that promise is for both to run the
 * same build tool the author already has configured. It also keeps the shaded jar small enough to be worth
 * shipping as one file.
 *
 * <p>A wrapper wins when there is one: a project carrying {@code mvnw} has said which Maven it wants, and
 * ignoring that is how a build behaves differently under this tool than under the author's own hands.
 */
public final class Mvn {

    private final Console console;

    public Mvn(Console console) {
        this.console = console;
    }

    /** What one Maven invocation produced. */
    public record Result(int exitCode, String output) {

        public boolean ok() {
            return exitCode == 0;
        }

        /** The last few lines, for an error message that does not paste a whole build log at the user. */
        public String tail(int lines) {
            List<String> all = output.lines().filter(line -> !line.isBlank()).toList();
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        }
    }

    /**
     * Runs Maven in {@code dir} and captures its output.
     *
     * <p>Captured rather than inherited because the caller decides what a failure looks like: a
     * {@code dependency:build-classpath} that fails should print three lines and a hint, not four hundred
     * lines of downloads. {@link #runInteractive} is the other half, for the invocations whose output
     * <em>is</em> the point.
     */
    public Result run(Path dir, String... goals) throws IOException {
        List<String> command = command(dir, goals);
        console.step("$ " + String.join(" ", command));
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        return new Result(await(process), output);
    }

    /**
     * Runs Maven on a project nobody vetted (the catalog's bot check): the system Maven, never a wrapper the
     * project ships; an environment holding only {@link #UNTRUSTED_ENV} and an empty HOME, so no token reaches the
     * project's build plugins; at most {@code timeout}, then killed with every process it started.
     */
    public Result runUntrusted(Path dir, Duration timeout, String... goals) throws IOException {
        List<String> command = new ArrayList<>(List.of(systemExecutable(), "-B"));
        command.addAll(List.of(goals));
        console.step("$ " + String.join(" ", command));
        Path log = Files.createTempFile("catalog-mvn", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        Map<String, String> env = builder.environment();
        env.keySet().retainAll(UNTRUSTED_ENV);
        // A HOME of its own: the real one holds gh's hosts.yml, ~/.m2/settings.xml and ~/.ssh.
        env.put("HOME", Files.createTempDirectory("catalog-home").toString());
        Process process = builder.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                return new Result(-1, "timed out after " + timeout.toMinutes() + " minutes");
            }
            return new Result(process.exitValue(), Files.readString(log));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill(process);
            throw new IOException("interrupted while waiting for Maven", e);
        } finally {
            Files.deleteIfExists(log);
        }
    }

    /** What {@link #runUntrusted} passes on: enough to find Java and Maven, nothing secret. HOME is replaced. */
    static final Set<String> UNTRUSTED_ENV = Set.of("PATH", "JAVA_HOME", "MAVEN_HOME", "LANG", "TMPDIR");

    /** The process and every process it started: a forked compiler outlives a killed mvn otherwise. */
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** Runs Maven with the terminal attached — for goals whose output the user is meant to read. */
    Result runInteractive(Path dir, String... goals) throws IOException {
        List<String> command = command(dir, goals);
        console.step("$ " + String.join(" ", command));
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .inheritIO()
                .start();
        return new Result(await(process), "");
    }

    private static int await(Process process) throws IOException {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IOException("interrupted while waiting for Maven", e);
        }
    }

    private List<String> command(Path dir, String... goals) {
        List<String> command = new ArrayList<>();
        command.add(executable(dir));
        command.add("-B");
        command.addAll(List.of(goals));
        return command;
    }

    /**
     * {@code ./mvnw} in the project, else {@code $MAVEN_HOME/bin/mvn}, else {@code mvn} on the PATH.
     *
     * <p>No probe for whether the last one exists: {@code ProcessBuilder} reports a missing executable
     * clearly enough, and a probe that ran {@code mvn --version} would cost a JVM start on every single
     * invocation to answer a question the next line answers for free.
     */
    static String executable(Path dir) {
        String wrapper = System.getProperty("os.name", "").toLowerCase().startsWith("windows")
                ? "mvnw.cmd" : "mvnw";
        Path local = dir.resolve(wrapper);
        if (Files.isRegularFile(local)) {
            return local.toAbsolutePath().toString();
        }
        return systemExecutable();
    }

    /** {@code $MAVEN_HOME/bin/mvn}, else {@code mvn} on the PATH. */
    private static String systemExecutable() {
        String home = System.getenv("MAVEN_HOME");
        if (home != null && !home.isBlank()) {
            Path fromHome = Path.of(home, "bin", "mvn");
            if (Files.isRegularFile(fromHome)) {
                return fromHome.toString();
            }
        }
        return "mvn";
    }
}
