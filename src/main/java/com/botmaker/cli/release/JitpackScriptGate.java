package com.botmaker.cli.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Every module JitPack builds runs the umbrella's {@code tools/jitpack/build.sh}, through an identical copy at
 * {@code .jitpack/build.sh} that its {@code jitpack.yml} calls.
 *
 * <p><b>The script is what keeps Maven Central's {@code 429 Too Many Requests} from losing a tag.</b> JitPack's
 * builders share IPs, Central rate-limits them, and a tag built {@code Error} stays broken: shared v0.2.4 died
 * on the Maven tarball itself (2026-10-12), studio-api v0.4.1 on a plugin through JitPack's Maven 3.6.1, which
 * never retries a 429 (2026-10-05). The script fetches from Google's mirror of Central first, with backoff,
 * runs Maven {@value GatePlan#JITPACK_MAVEN}, whose resolver retries a 429, and carries the statx shim. A copy
 * that drifted, or a {@code jitpack.yml} that went back to a plain {@code mvn install}, would quietly undo it
 * for one module.
 *
 * <p><b>A copy must be committed, not only on disk</b>: the release commit is {@code git commit -am}, which adds
 * no untracked file, so a {@code jitpack.yml} naming a script git does not hold would tag a build that cannot
 * start. Each submodule is its own repository, so the umbrella's copy cannot be shared by reference; the gate
 * is what makes nine copies one file.
 */
public final class JitpackScriptGate {

    /** Where the canonical files live, in the umbrella. */
    static final Path CANONICAL = Path.of("tools", "jitpack");

    /** Each file a module carries a copy of, under {@code .jitpack/}. */
    static final List<String> FILES = List.of("build.sh", "statx.c");

    private static final Pattern RUNS_SCRIPT = Pattern.compile("\\s*-\\s*bash \\.jitpack/build\\.sh(\\s.*)?");

    private JitpackScriptGate() {
    }

    public static GateVerdict check(Path umbrella, Module module, boolean force) {
        if (!module.onJitpack()) {
            return GateVerdict.ok("");
        }
        Path canonical = umbrella.resolve(CANONICAL);
        if (!Files.isDirectory(canonical)) {
            return GateVerdict.skipped("  " + module.shortName() + ": no " + CANONICAL
                    + " in the umbrella — JitPack script gate skipped");
        }
        Path dir = umbrella.resolve(module.directory());
        try {
            return verdict(module, problems(canonical, dir,
                    file -> Git.capture(dir, "ls-files", "--error-unmatch", file).isPresent()), force);
        } catch (IOException e) {
            return GateVerdict.skipped("  " + module.shortName() + ": " + e.getMessage()
                    + " could not be read — JitPack script gate skipped");
        }
    }

    /**
     * The Maven the module's JitPack build runs, given this gate's verdict: the script's when it passed, and
     * JitPack's own when it was forced or skipped, since the module's {@code jitpack.yml} may then still be a
     * plain {@code mvn install}.
     */
    public static String ceiling(GateVerdict verdict) {
        return verdict.status() == GateVerdict.Status.OK ? GatePlan.JITPACK_MAVEN : GatePlan.JITPACK_OWN_MAVEN;
    }

    /**
     * What is wrong with one module's JitPack build, each as one line; empty when nothing is.
     *
     * @param tracked whether git holds a path relative to the module, so the release commit carries it
     */
    static List<String> problems(Path canonical, Path module, Predicate<String> tracked) throws IOException {
        List<String> problems = new ArrayList<>();
        for (String file : FILES) {
            String path = ".jitpack/" + file;
            Path copy = module.resolve(path);
            if (!Files.isRegularFile(copy)) {
                problems.add(path + " is missing");
            } else if (!Files.readString(copy).equals(Files.readString(canonical.resolve(file)))) {
                problems.add(path + " differs from the umbrella's " + CANONICAL.resolve(file));
            } else if (!tracked.test(path)) {
                problems.add(path + " is not committed, and the release commit adds no new file");
            }
        }
        Path yml = module.resolve("jitpack.yml");
        if (!Files.isRegularFile(yml) || !installRunsScript(Files.readString(yml))) {
            problems.add("jitpack.yml's install does not start with `bash .jitpack/build.sh`");
        }
        return List.copyOf(problems);
    }

    /** Whether the first command of {@code install:} is the script — one later in the list runs too late. */
    static boolean installRunsScript(String yml) {
        boolean inInstall = false;
        for (String line : yml.lines().toList()) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            if (!Character.isWhitespace(line.charAt(0)) && !text.startsWith("-")) {
                inInstall = text.equals("install:");
            } else if (inInstall) {
                return RUNS_SCRIPT.matcher(line).matches();
            }
        }
        return false;
    }

    /** The rule over the problems already found — pure, so a test needs no files. */
    static GateVerdict verdict(Module module, List<String> problems, boolean force) {
        if (problems.isEmpty()) {
            return GateVerdict.ok("  " + module.shortName() + ": JitPack builds with the umbrella's "
                    + CANONICAL.resolve("build.sh") + " — ok");
        }
        if (force) {
            return GateVerdict.forced("  " + module.shortName() + ": JitPack build script out of date — FORCED");
        }
        StringBuilder refusal = new StringBuilder(module.directory()
                + ": JitPack would not build this tag with the umbrella's " + CANONICAL.resolve("build.sh")
                + ".\n\n");
        for (String problem : problems) {
            refusal.append("     ").append(problem).append('\n');
        }
        refusal.append("""

                     Without it the build fetches from Maven Central with no retry, and one \
                429 Too Many Requests
                     loses the tag for good. Copy tools/jitpack/build.sh and statx.c into the module's \
                .jitpack/, make
                     its jitpack.yml's install `bash .jitpack/build.sh`, commit, or --force.""");
        return GateVerdict.refused(refusal.toString());
    }
}
