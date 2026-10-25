package com.botmaker.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Running {@code git} and {@code gh}, the two programs this command automates rather than reimplements.
 *
 * <p><b>{@code gh} rather than the REST API</b>, because it already holds the user's credential — and a tool
 * that asked a bot author for a GitHub token would be asking for the one thing this program has no business
 * holding. {@code git} for the same reason: the working copy's remotes, identity and hooks are the author's
 * configuration, and a library talking to the same directory would be a second opinion about it.
 *
 * <p>Two shapes, and the difference matters. {@link #run} inherits the terminal, so the user watches
 * {@code gh} do its work and answers its prompts; {@link #capture} takes stdout as an answer and discards
 * stderr, for the questions this program asks on the user's behalf ("does that repository exist?").
 */
final class Shell {

    private Shell() {
    }

    /** Runs it in front of the user, echoing the command first. Returns the exit code. */
    static int run(Console console, Path dir, String... argv) throws IOException {
        console.step("$ " + String.join(" ", argv));
        try {
            return new ProcessBuilder(argv).directory(dir.toFile()).inheritIO().start().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running " + argv[0], e);
        }
    }

    /** Same, with {@code gh} in front. */
    static int gh(Console console, Path dir, String... args) throws IOException {
        List<String> argv = new ArrayList<>(List.of("gh"));
        argv.addAll(List.of(args));
        return run(console, dir, argv.toArray(String[]::new));
    }

    /**
     * Runs it with stdin read from {@code in} and stdout written to {@code out}, bytes untouched, stderr discarded;
     * whether it succeeded. For answers that are not text ({@code git cat-file --batch}).
     */
    static boolean pipe(Path dir, Path in, Path out, String... argv) {
        try {
            return new ProcessBuilder(argv).directory(dir.toFile())
                    .redirectInput(in.toFile()).redirectOutput(out.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The command's stdout when it succeeded, empty otherwise — a question, not an action.
     *
     * <p>A non-zero exit is an answer here rather than an error: "no such repository", "not a git working
     * copy" and "{@code gh} is not installed" are all things the caller has something sensible to do about,
     * and none of them is worth a stack trace.
     */
    static Optional<String> capture(Path dir, String... argv) {
        try {
            Process process = new ProcessBuilder(argv)
                    .directory(dir.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String out;
            try (var stdout = process.getInputStream()) {
                out = new String(stdout.readAllBytes()).trim();
            }
            return process.waitFor() == 0 ? Optional.of(out) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
