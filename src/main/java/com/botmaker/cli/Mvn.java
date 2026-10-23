package com.botmaker.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
