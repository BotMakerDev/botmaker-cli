package com.botmaker.cli.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Every side effect a release has, behind one switch — {@code release.sh}'s {@code run} and
 * {@code run_sh}.
 *
 * <p><b>A dry run does everything except the last step.</b> It decides, gates, computes and prints exactly
 * what a real run would, and echoes each command as {@code     $ …} instead of executing it. That is what
 * makes {@code --dry-run} worth trusting: the plan on screen is produced by the same code path, not by a
 * second "preview" implementation that can drift from what the release actually does.
 *
 * <p><b>So nothing in this package may write, commit, tag or push except through here.</b> A direct
 * {@code Files.writeString} or {@code Git.run} on a write path is a line that ignores {@code --dry-run},
 * and it will be discovered by a tag that exists, which cannot be edited.
 *
 * @param dryRun when true, commands are echoed and not run
 * @param out    where the echo goes — the caller's, because a library has no opinion about stdout
 */
public record Runner(boolean dryRun, Consumer<String> out) {

    /** A runner that prints to stdout and executes. */
    public static Runner real() {
        return new Runner(false, System.out::println);
    }

    /** A runner that prints to stdout and executes nothing. */
    public static Runner preview() {
        return new Runner(true, System.out::println);
    }

    /** Runs {@code git -C <dir> <args…>}, echoed as the script echoes it. */
    public Proc.Result git(Path dir, String... args) {
        String[] argv = new String[args.length + 3];
        argv[0] = "git";
        argv[1] = "-C";
        argv[2] = dir.toString();
        System.arraycopy(args, 0, argv, 3, args.length);
        return run(argv);
    }

    /** Runs a command, echoed. A dry run returns a successful empty result — nothing ran, nothing failed. */
    public Proc.Result run(String... argv) {
        out.accept("    $ " + Stream.of(argv).map(Runner::quoted).collect(Collectors.joining(" ")));
        return dryRun ? new Proc.Result(0, "") : Proc.run(Path.of("."), argv);
    }

    /**
     * One argument as a shell would need it written.
     *
     * <p>Argv is a list and the echo is a line, so joining on spaces loses the boundary between arguments:
     * {@code commit -am release: studio v1.0.38} reads as four arguments and is one. That matters because a
     * dry run's output is read as a script by whoever is checking it — the whole safety argument for
     * {@code --dry-run} is that the operator can see what would run.
     */
    private static String quoted(String argument) {
        return argument.matches("[A-Za-z0-9_@%+=:,./-]+")
                ? argument
                : "'" + argument.replace("'", "'\\''") + "'";
    }

    /**
     * Writes a whole file, echoed as the heredoc the script uses.
     *
     * <p>The echo names the file rather than reproducing its content, which is what {@code run_sh}'s own
     * output does for a heredoc: the interesting part of a {@code .deps.env} is the pins, and those are
     * printed by the line above it.
     */
    public void write(Path file, String content) {
        // The marker is the script's own for a .deps.env and a plain EOF for anything else: printing
        // `cat > CHANGELOG.md <<'DEPS_EOF'` names a heredoc that has nothing to do with a changelog.
        String marker = file.getFileName().toString().equals(".deps.env") ? "DEPS_EOF" : "EOF";
        out.accept("    $ cat > " + quoted(file.toString()) + " <<'" + marker + "' … " + marker);
        if (dryRun) {
            return;
        }
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new ReleaseRefusal(file + ": could not be written (" + e.getMessage() + ")");
        }
    }

    /**
     * Rewrites the first match of {@code find} in a file, echoed as the {@code sed -i -E} the script runs.
     *
     * <p>Separate from {@link #write} because the two are different promises. {@code write} composes a whole
     * file and the echo names it; this one edits <b>one property of a file somebody else owns</b> — a pom, a
     * {@code .java} constant — and the echo has to say which, because "rewrote MavenService.java" in a
     * release plan reads as something far larger than a version literal moving.
     *
     * <p><b>A pattern that matches nothing is a refusal, not a no-op.</b> The script's {@code sed} is silent
     * in that case, which is how {@code MIN_SDK_VERSION} went on being {@code sed}ded for weeks after the
     * constant was deleted (see {@code CLAUDE.md}, <i>Studio's release notes also stopped lying</i>). A
     * release that believes it moved a pin it did not move is the failure worth being loud about.
     *
     * @param replacement a {@link Matcher#replaceFirst} replacement, so {@code $1} refers to a group
     */
    public void replace(Path file, Pattern find, String replacement) {
        // `$1` is Java's group reference and `\1` is sed's. Translating it keeps the echoed line something
        // the operator can actually paste, which is the only reason the echo is spelled as a command.
        out.accept("    $ sed -i -E 's#" + find.pattern() + "#" + replacement.replaceAll("\\$(\\d)", "\\\\$1")
                + "#' " + quoted(file.toString()));
        if (dryRun) {
            return;
        }
        String before;
        try {
            before = Files.readString(file);
        } catch (IOException e) {
            throw new ReleaseRefusal(file + ": could not be read (" + e.getMessage() + ")");
        }
        Matcher match = find.matcher(before);
        if (!match.find()) {
            throw new ReleaseRefusal(file + ": nothing matches " + find.pattern()
                    + " — the constant this release moves is not there any more.");
        }
        try {
            Files.writeString(file, match.reset().replaceFirst(replacement));
        } catch (IOException e) {
            throw new ReleaseRefusal(file + ": could not be written (" + e.getMessage() + ")");
        }
    }

    /** One line of narration, the script's {@code info} without its colour. */
    public void say(String line) {
        out.accept(line);
    }

    /** Convenience for the many places that build an argument list. */
    public Proc.Result run(List<String> argv) {
        return run(argv.toArray(String[]::new));
    }
}
