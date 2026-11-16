// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli;

import com.botmaker.cli.update.GitHubReleases;
import com.botmaker.cli.update.SelfUpdate;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker update} — replaces the jar this command runs from with the latest release's, checked against the
 * release's checksums ({@link SelfUpdate}, 2026-11-17).
 *
 * <p>Windows keeps a running jar locked, so there the checked jar waits beside it as {@code <jar>.new} and a small
 * PowerShell started here moves it in once this process ends ({@link #handOver}). Should that fail (another
 * {@code botmaker} still holding the jar), the next start hands it over again ({@link #finishPending}).
 */
@Command(name = "update",
        header = "Replace this botmaker with the latest release.",
        description = "Downloads the latest botmaker-cli-all.jar, checks it against the release's "
                + "checksums_sha256.txt and puts it in place of the jar this command runs from. A package "
                + "manager's, JBang's or the Claude plugin's copy is left to them.",
        mixinStandardHelpOptions = true)
final class UpdateCommand implements Callable<Integer> {

    @ParentCommand
    private Main parent;

    @Override
    public Integer call() throws IOException {
        // update() refuses a jar that is not this command's to replace, and the refusal is the exit-1 message.
        SelfUpdate.Outcome outcome = new SelfUpdate(new GitHubReleases())
                .update(runningJar().orElse(null), Main.releasedVersion());
        switch (outcome) {
            case SelfUpdate.Outcome.Current current ->
                    parent.console().out("botmaker is up to date (latest release " + current.tag() + ").");
            case SelfUpdate.Outcome.Staged staged when staged.swapped() ->
                    parent.console().out("botmaker updated to " + staged.tag() + ".");
            case SelfUpdate.Outcome.Staged staged when isWindows() -> {
                handOver(staged.jar());
                parent.console().out("botmaker " + staged.tag() + " is downloaded and checked; it replaces this "
                        + "jar when this command ends.");
            }
            case SelfUpdate.Outcome.Staged staged -> {
                parent.console().error("botmaker " + staged.tag() + " is downloaded and checked as "
                        + SelfUpdate.pending(staged.jar()) + ", but could not be moved over " + staged.jar()
                        + "; the next start tries again.");
                return 1;
            }
        }
        return 0;
    }

    /**
     * At every start: a jar staged by an earlier {@code update} and not yet in place is swapped in, or, while this
     * process holds the jar (Windows), handed over for when it ends. Never throws and does nothing without one.
     */
    static void finishPending() {
        try {
            Optional<Path> jar = runningJar();
            if (jar.isEmpty() || !Files.isRegularFile(SelfUpdate.pending(jar.get()))) return;
            if (!SelfUpdate.swap(jar.get())) handOver(jar.get());
        } catch (RuntimeException | IOException ignored) {
            // A failed swap leaves the staged jar for the next start; the command itself still runs.
        }
    }

    /** The jar this class was loaded from, or empty when it was not loaded from one. */
    static Optional<Path> runningJar() {
        try {
            CodeSource source = UpdateCommand.class.getProtectionDomain().getCodeSource();
            if (source == null) return Optional.empty();
            Path path = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Windows only: starts a PowerShell that waits for this process to end, then moves {@code <jar>.new} over
     * {@code jar}. It outlives this process; elsewhere {@link SelfUpdate#swap} already did the move.
     */
    private static void handOver(Path jar) throws IOException {
        if (!isWindows()) return;
        String script = "Wait-Process -Id " + ProcessHandle.current().pid() + " -ErrorAction SilentlyContinue; "
                + "Start-Sleep -Milliseconds 500; "
                + "Move-Item -Force -LiteralPath " + quoted(SelfUpdate.pending(jar)) + " -Destination " + quoted(jar);
        new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-Command", script)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** A PowerShell single-quoted string: nothing in it is expanded, and a quote is doubled. */
    private static String quoted(Path path) {
        return "'" + path.toAbsolutePath().toString().replace("'", "''") + "'";
    }
}
