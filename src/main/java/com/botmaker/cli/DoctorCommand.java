package com.botmaker.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker doctor} — every external thing this program needs, answered at once.
 *
 * <p><b>Why it exists.</b> Nothing here is a new capability: each verb already reports the tool it is
 * missing. It reports it <em>at the moment it is needed</em>, which is halfway through the first real use —
 * {@code plugin publish} discovers there is no {@code gh} after building, validating and resolving a
 * coordinate, and {@code plugin run} discovers there is no Studio after a full {@code mvn install}. The set
 * is small and knowable up front, so this asks the whole of it in about a second, and it is the natural
 * first thing to paste into a bug report.
 *
 * <p><b>It reaches no network and changes nothing.</b> A version probe is a process, not a request: the
 * question "can this machine run the other verbs" must be answerable on a train.
 *
 * <p>The report is the command's <em>result</em>, so it goes to stdout — the same rule {@link Console}
 * opens with, which is why {@code plugin list} can be redirected into a file.
 */
@Command(name = "doctor",
        header = "Check the tools the other commands need.",
        description = "Java, Maven, gh and Studio, answered together instead of one at a time, halfway "
                + "through a build. Reaches no network.",
        mixinStandardHelpOptions = true)
final class DoctorCommand implements Callable<Integer> {

    /**
     * The Java the generated projects are compiled with, and so the floor for the toolchain this program
     * hands work to. This program's own jar is built for it too, so a lower runtime cannot have got here —
     * it is checked anyway, because {@code $JAVA_HOME} and the {@code java} on the PATH are two different
     * questions and Maven asks the second one.
     */
    private static final int REQUIRED_JAVA = 25;

    @ParentCommand
    private Main parent;

    /** One line of the report. {@code required} decides the exit code; nothing else does. */
    private record Finding(boolean ok, boolean required, String subject, String detail) {
    }

    @Override
    public Integer call() {
        Path here = Path.of("").toAbsolutePath();
        List<Finding> findings = new ArrayList<>();
        findings.add(java());
        findings.add(maven(here));
        findings.add(gh(here));
        findings.add(ghAuth(here));
        findings.add(studio());
        findings.add(projects());

        Console console = parent.console();
        int width = findings.stream().mapToInt(f -> f.subject().length()).max().orElse(0);
        for (Finding finding : findings) {
            String badge = finding.ok() ? "ok  " : finding.required() ? "MISS" : "warn";
            console.out(badge + "  " + pad(finding.subject(), width) + "  " + finding.detail());
        }

        boolean blocked = findings.stream().anyMatch(f -> !f.ok() && f.required());
        if (blocked) {
            console.error("A required tool is missing. Every verb that resolves a coordinate runs your own "
                    + "Maven, so nothing else will work until it does.");
        }
        return blocked ? 1 : 0;
    }

    private static String pad(String subject, int width) {
        return subject + " ".repeat(width - subject.length());
    }

    private Finding java() {
        String version = System.getProperty("java.version", "?");
        String vendor = System.getProperty("java.vendor", "?");
        boolean ok = Runtime.version().feature() >= REQUIRED_JAVA;
        return new Finding(ok, true, "java",
                version + " (" + vendor + ")" + (ok ? "" : " — " + REQUIRED_JAVA + " or newer is required"));
    }

    /**
     * Which Maven, and whether it answers.
     *
     * <p>{@link Mvn#executable(Path)} rather than a probe of its own, because "which Maven" has one answer
     * in this program and a second implementation of that precedence would be the interesting kind of wrong:
     * a doctor reporting the {@code mvn} on the PATH while every other verb runs the {@code ./mvnw} beside
     * it is worse than no doctor.
     */
    private Finding maven(Path dir) {
        String executable = Mvn.executable(dir);
        Optional<String> out = Shell.capture(dir, executable, "-v");
        String version = out.map(DoctorCommand::firstLine).orElse("");
        return new Finding(out.isPresent(), true, "maven",
                out.isPresent() ? version + "  [" + executable + "]"
                        : "not runnable as `" + executable + "` — install Maven, or add a ./mvnw");
    }

    private Finding gh(Path dir) {
        Optional<String> out = Shell.capture(dir, "gh", "--version");
        return new Finding(out.isPresent(), false, "gh",
                out.map(DoctorCommand::firstLine)
                        .orElse("not installed — `plugin publish`, `bot publish` and `unpublish` need it"));
    }

    private Finding ghAuth(Path dir) {
        Optional<String> out = Shell.capture(dir, "gh", "auth", "status");
        return new Finding(out.isPresent(), false, "gh auth",
                out.isPresent() ? "signed in" : "not signed in — run `gh auth login`");
    }

    /**
     * Studio, which this program launches and never discovers.
     *
     * <p>A packaged Studio has no canonical location on Linux, so there is nothing to probe for: the
     * question is only whether the person has said where it is. That is {@code $BOTMAKER_STUDIO} or
     * {@code --studio}, and this reports the first.
     */
    private Finding studio() {
        String command = System.getenv("BOTMAKER_STUDIO");
        boolean set = command != null && !command.isBlank();
        return new Finding(set, false, "studio",
                set ? "$BOTMAKER_STUDIO = " + command
                        : "$BOTMAKER_STUDIO not set — `plugin run` needs --studio or --umbrella");
    }

    private Finding projects() {
        Path root = PluginRunCommand.PROJECTS_ROOT;
        boolean exists = Files.isDirectory(root);
        return new Finding(exists, false, "projects",
                exists ? root.toString()
                        : root + " does not exist yet — Studio creates it with your first project");
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text.trim() : text.substring(0, newline).trim();
    }
}
