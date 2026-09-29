package com.botmaker.cli.release;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * One external command, run from a directory, with everything it wrote captured.
 *
 * <p><b>Most of {@code release.sh} is this and stays this.</b> Only five things in it are algorithms; the
 * rest shells to {@code git}, {@code gh}, {@code mvn}, {@code python3} and each module's own
 * {@code tools/changelog-section.sh}, which Java does at two to three times the line count and no gain — and
 * in the extractor's case must not do at all, since a second implementation of it is exactly what that file
 * exists to prevent.
 *
 * <p><b>Every failure to launch is a {@link Result}, never an exception.</b> A missing tool is an answer a
 * caller acts on — the gates turn it into {@code SKIPPED} — and the script reaches the same state through a
 * non-zero exit with a message on stderr.
 */
public final class Proc {

    /** @param out stdout and stderr together, in the order they were written */
    public record Result(int exit, String out) {

        public boolean ok() {
            return exit == 0;
        }

        public List<String> lines() {
            return out.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        }
    }

    private Proc() {
    }

    /** The exit code of a command that was not started, or was stopped, because its thread was interrupted. */
    public static final int INTERRUPTED = 130;

    /**
     * Runs {@code argv} to the end.
     *
     * <p><b>An interrupt stops it</b> (2026-09-29): the process and what it started are destroyed and the
     * answer is {@link #INTERRUPTED}, and a thread already interrupted starts nothing. The output is read on a
     * thread of its own so the wait can be interrupted; reading it to the end first, as this did, blocked in a
     * read no interrupt reaches. That is what lets the dashboard's Cancel stop a preview or a clean-room
     * resolve: every step after it answers at once, so the pass unwinds instead of running on for minutes.
     */
    public static Result run(Path dir, String... argv) {
        if (Thread.currentThread().isInterrupted()) {
            return new Result(INTERRUPTED, "interrupted");
        }
        Process process = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            process = new ProcessBuilder(argv)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start();
            InputStream stdout = process.getInputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (stdout) {
                    stdout.transferTo(out);
                } catch (IOException ignored) {
                    // Destroyed under the read: what was read is the output.
                }
            });
            int exit = process.waitFor();
            reader.join();
            return new Result(exit, out.toString(Charset.defaultCharset()));
        } catch (IOException e) {
            // No such command, or no such directory.
            return new Result(127, e.getMessage() == null ? "" : e.getMessage());
        } catch (InterruptedException e) {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return new Result(INTERRUPTED, "interrupted");
        }
    }

    /**
     * Whether a command is on {@code PATH} — the script's {@code command -v <tool>}.
     *
     * <p><b>Resolved by reading {@code PATH}, never by asking a shell.</b> The obvious spelling is
     * {@code sh -c "command -v " + name}, and it is an injection: the name reaches a shell as source text,
     * so a {@code ;} in it runs. Every caller here passes a literal, which is exactly the argument that
     * stops being true later — and this package is a library with callers in three repositories. There is
     * nothing a shell adds; {@code PATH} is a list of directories.
     */
    public static boolean onPath(String command) {
        String path = System.getenv("PATH");
        if (path == null || command.isBlank() || command.contains(java.io.File.separator)) {
            return false;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }
}
