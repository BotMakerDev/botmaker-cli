package com.botmaker.cli.publish;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every program a publish runs ({@code git}, {@code gh}) and every file it writes, so a test answers in their place
 * and {@code --dry-run} is a publish that stops before the first {@link #change}.
 *
 * <p><b>{@code gh} rather than the REST API</b>, because it already holds the user's credential, and a publish that
 * asked for a token would be asking for the one thing it has no business holding. {@code git} for the same reason:
 * the folder's remotes and identity are the author's.
 */
public interface StepRunner {

    /** What a {@link #change} did: its exit code and its output, stdout and stderr together. */
    record Ran(int exit, String output) {

        public boolean ok() {
            return exit == 0;
        }

        /** The last non-blank line of the output: where {@code gh pr create} prints the pull request's URL. */
        public String lastLine() {
            List<String> lines = output.lines().filter(line -> !line.isBlank()).toList();
            return lines.isEmpty() ? "" : lines.getLast().strip();
        }
    }

    /**
     * A question, which changes nothing: the program's stdout when it exited 0, else empty. A non-zero exit is an
     * answer ("no such tag", "not a repository"), never an error.
     */
    Optional<String> read(Path dir, List<String> argv);

    /** An action: the program run, its output reported as it comes. */
    Ran change(Path dir, List<String> argv);

    /** A file written: the catalog entry in the catalog's clone. */
    void write(Path file, String content) throws IOException;

    /**
     * The programs themselves. {@code gh} is told never to prompt: nothing reads this process's terminal, and a
     * question nobody answers is a publish that never ends.
     *
     * @param listener where each {@link #change} is echoed, and each line of its output
     */
    static StepRunner system(Listener listener) {
        return new StepRunner() {
            @Override
            public Optional<String> read(Path dir, List<String> argv) {
                try {
                    Process process = start(dir, argv, false);
                    String out;
                    try (InputStream stdout = process.getInputStream()) {
                        out = new String(stdout.readAllBytes(), StandardCharsets.UTF_8).strip();
                    }
                    return process.waitFor() == 0 ? Optional.of(out) : Optional.empty();
                } catch (IOException e) {
                    return Optional.empty();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }

            @Override
            public Ran change(Path dir, List<String> argv) {
                listener.step("$ " + String.join(" ", argv));
                StringBuilder output = new StringBuilder();
                try {
                    Process process = start(dir, argv, true);
                    try (var lines = process.inputReader(StandardCharsets.UTF_8)) {
                        lines.lines().forEach(line -> {
                            output.append(line).append('\n');
                            listener.step("  " + line);
                        });
                    }
                    return new Ran(process.waitFor(), output.toString());
                } catch (IOException e) {
                    return new Ran(-1, "could not run " + argv.getFirst() + ": " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Ran(-1, "interrupted while running " + argv.getFirst());
                }
            }

            @Override
            public void write(Path file, String content) throws IOException {
                Files.writeString(file, content);
            }

            private static Process start(Path dir, List<String> argv, boolean merged) throws IOException {
                ProcessBuilder builder = new ProcessBuilder(argv).directory(dir.toFile());
                if (merged) {
                    builder.redirectErrorStream(true);
                } else {
                    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                }
                Map<String, String> env = builder.environment();
                env.put("GH_PROMPT_DISABLED", "1");
                env.put("GIT_TERMINAL_PROMPT", "0");
                Process process = builder.start();
                // Its stdin closed: a program that reads it sees the end, never a terminal.
                process.getOutputStream().close();
                return process;
            }
        };
    }
}
