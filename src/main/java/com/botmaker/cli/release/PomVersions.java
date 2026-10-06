package com.botmaker.cli.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The versions a release writes into poms — its own and each upstream's — through the standard
 * {@code versions-maven-plugin}, never by hand (umbrella {@code docs/refactor/43-real-versions.md}).
 *
 * <p><b>Two commits per module.</b> The <i>release commit</i> sets the module's own version to the one being
 * cut and every {@link Module#upstreams()} pin to the released version {@link DepTag} chooses; it is the
 * commit the tag goes on, so the tag's pom says what it was built against and JitPack needs nothing beside
 * the checkout. The <i>back-to-snapshot commit</i> follows it on {@code main}: the module's next patch
 * {@code -SNAPSHOT}, and every pin back at its upstream's {@code main} version. The same run then moves each
 * dependent that is <i>not</i> being released to the new snapshot, so no {@code main} pins a version nobody
 * builds; a dependent being released later in the run gets it from its own back-to-snapshot commit.
 *
 * <p><b>It runs on the operator's machine, never on JitPack</b>, so JitPack's Maven 3.6.1 ceiling does not
 * bind {@link #PLUGIN}'s version. Every call goes through {@link Runner}: a dry run echoes the Maven lines.
 */
public final class PomVersions {

    /** Pinned, so a release run does not pick up whatever the plugin's latest happens to do. */
    static final String PLUGIN = "org.codehaus.mojo:versions-maven-plugin:2.18.0";

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");

    private PomVersions() {
    }

    /** Whether a release moves this module's pom version at all: a Maven build that is not a template. */
    public static boolean moves(Module module) {
        return module.mavenBuild() && !module.template();
    }

    /** {@code 0.3.4-SNAPSHOT} after {@code 0.3.3}: what {@code main} says once a release is cut. */
    public static String nextSnapshot(Version released) {
        return released.bump(Level.PATCH) + "-SNAPSHOT";
    }

    /**
     * The release commit's edits: the module's own version and each upstream pin.
     *
     * @param releasing every module this run cuts, so a pin on one of them is the version being cut
     */
    public static void release(Runner runner, Path umbrella, Module module, Version version,
                               Map<Module, Version> releasing) {
        if (!moves(module)) {
            return;
        }
        Path pom = pom(umbrella, module);
        List<String> pins = new ArrayList<>();
        set(runner, pom, version.toString());
        for (Module upstream : module.upstreams()) {
            String pinned = DepTag.version(umbrella, upstream, Optional.ofNullable(releasing.get(upstream)))
                    .toString();
            setProperty(runner, pom, upstream, pinned);
            pins.add(upstream.shortName() + " " + pinned);
        }
        runner.say("  " + module.directory() + " " + version + (pins.isEmpty() ? ""
                : " pinning " + String.join(", ", pins)));
    }

    /** The back-to-snapshot commit, on top of the tagged release commit. */
    public static void backToSnapshot(Runner runner, Path umbrella, Module module, Version version,
                                      Map<Module, Version> releasing) {
        if (!moves(module)) {
            return;
        }
        Path pom = pom(umbrella, module);
        String next = nextSnapshot(version);
        set(runner, pom, next);
        for (Module upstream : module.upstreams()) {
            setProperty(runner, pom, upstream, mainVersion(umbrella, upstream, releasing));
        }
        commitPom(runner, pom.getParent(), "back to snapshot: " + module.shortName() + " " + next);
        runner.say("  " + module.directory() + " main is " + next);
    }

    /**
     * Each dependent outside this release, moved to {@code module}'s new snapshot and committed in its own
     * repository; pushed with the release's branches.
     *
     * <p><b>Never fatal.</b> The module is tagged and back on its snapshot by now; a dependent left behind
     * is a pin {@link VersionsGate} names on the next release, where a person can fix it, and stopping here
     * would leave a pushed tag looking like a failed release.
     *
     * @param skip the dependents not to move: those this run releases (their own back-to-snapshot moves
     *             them) and those already moved. When the chain stops, the caller passes only what was
     *             tagged and moved, so a dependent the chain never reached is moved too
     * @return the dependents committed, whose pointers the umbrella records with the release's
     */
    public static List<Module> follow(Runner runner, Path umbrella, Module module, Version version,
                                      java.util.Set<Module> skip) {
        if (!moves(module)) {
            return List.of();
        }
        String next = nextSnapshot(version);
        List<Module> moved = new ArrayList<>();
        for (Module dependent : module.dependents()) {
            Path theirs = pom(umbrella, dependent);
            if (skip.contains(dependent) || !Files.exists(theirs)) {
                continue;
            }
            Path dir = theirs.getParent();
            if (!runner.dryRun() && (!Git.run(dir, "diff", "--quiet", "--", "pom.xml").ok()
                    || !Git.run(dir, "diff", "--cached", "--quiet", "--", "pom.xml").ok())) {
                // Committing it would sweep somebody's edit, staged or not, into the pin commit.
                runner.say("warn: " + dependent.directory() + ": pom.xml has uncommitted changes — its "
                        + module.shortName() + " pin was not moved to " + next);
                continue;
            }
            if (!runner.dryRun() && Git.capture(dir, "symbolic-ref", "--quiet", "--short", "HEAD")
                    .filter(branch -> !branch.isBlank()).isEmpty()) {
                // A commit on a detached HEAD is never pushed, and the umbrella would record a pointer to it.
                runner.say("warn: " + dependent.directory() + ": detached HEAD — its " + module.shortName()
                        + " pin was not moved to " + next);
                continue;
            }
            try {
                setProperty(runner, theirs, module, next);
                commitPom(runner, dir, "pin " + module.shortName() + " " + next);
                moved.add(dependent);
            } catch (ReleaseRefusal e) {
                runner.git(dir, "checkout", "--", "pom.xml");
                runner.say("warn: " + dependent.directory() + ": its " + module.shortName()
                        + " pin was not moved to " + next + " — " + e.getMessage());
            }
        }
        return List.copyOf(moved);
    }

    /**
     * Puts the snapshot back in the working tree after a failure: the pom as committed when the release
     * commit was not made yet, or as it was before it when it was. Nothing is pushed by then, so this leaves
     * the module's {@code main} building at its {@code -SNAPSHOT} rather than at a version that was never
     * published.
     *
     * @param committed whether the release commit was made
     */
    public static void restore(Runner runner, Path umbrella, Module module, boolean committed) {
        if (!moves(module)) {
            return;
        }
        Path dir = umbrella.resolve(module.directory());
        if (committed) {
            runner.git(dir, "checkout", "HEAD~1", "--", "pom.xml");
        } else {
            runner.git(dir, "checkout", "--", "pom.xml");
        }
    }

    /**
     * What {@code main} pins {@code upstream} at: the snapshot it moves to when this run cuts it, else its
     * pom's own version as checked out.
     */
    static String mainVersion(Path umbrella, Module upstream, Map<Module, Version> releasing) {
        Version cut = releasing.get(upstream);
        return cut != null ? nextSnapshot(cut) : projectVersion(read(pom(umbrella, upstream)))
                .orElseThrow(() -> new ReleaseRefusal(upstream.directory() + ": pom.xml names no version"));
    }

    /**
     * The project's own version: the first {@code <version>} once comments are gone. Every pom here has no
     * {@code <parent>}, so the first one is the project's; a pom that grows a parent is refused rather than
     * read as its parent's version.
     */
    public static Optional<String> projectVersion(String pom) {
        String text = COMMENT.matcher(pom).replaceAll("");
        if (text.contains("<parent>")) {
            throw new ReleaseRefusal("a pom with a <parent> — its first <version> is not its own");
        }
        Matcher m = VERSION.matcher(text);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /**
     * Whether two poms differ in nothing but the project's own version, the {@code botmaker.*.version}
     * pins and comments — the edits a release makes, which change nothing a consumer downloads.
     */
    public static boolean versionsOnly(String before, String after) {
        return normalised(before).equals(normalised(after));
    }

    private static String normalised(String pom) {
        String text = COMMENT.matcher(pom).replaceAll("");
        text = VERSION.matcher(text).replaceFirst("<version/>");
        text = text.replaceAll("<botmaker\\.([a-z]+)\\.version>[^<]*</botmaker\\.\\1\\.version>",
                "<botmaker.$1.version/>");
        return text.replaceAll("\\s+", " ").strip();
    }

    /** The value of {@code <botmaker.<key>.version>}, comments ignored. */
    public static Optional<String> property(String pom, Module upstream) {
        String key = upstream.propertyKey().orElseThrow();
        Matcher m = Pattern.compile("<botmaker\\." + key + "\\.version>\\s*([^<\\s]*)\\s*</botmaker\\."
                + key + "\\.version>").matcher(COMMENT.matcher(pom).replaceAll(""));
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    static Path pom(Path umbrella, Module module) {
        return umbrella.resolve(module.directory()).resolve("pom.xml");
    }

    static String read(Path pom) {
        try {
            return Files.readString(pom);
        } catch (IOException e) {
            throw new ReleaseRefusal(pom + ": cannot be read (" + e.getMessage() + ")");
        }
    }

    private static void set(Runner runner, Path pom, String version) {
        mvn(runner, pom, PLUGIN + ":set", "-DnewVersion=" + version, "-DgenerateBackupPoms=false",
                "-DprocessAllModules=false");
    }

    private static void setProperty(Runner runner, Path pom, Module upstream, String version) {
        if (!runner.dryRun() && property(read(pom), upstream).isEmpty()) {
            // set-property is silent about a property the pom does not declare; a pin that did not move is
            // the failure worth being loud about.
            throw new ReleaseRefusal(pom + ": declares no botmaker." + upstream.propertyKey().orElseThrow()
                    + ".version to pin " + upstream.directory() + " with");
        }
        mvn(runner, pom, PLUGIN + ":set-property", "-Dproperty=botmaker." + upstream.propertyKey().orElseThrow()
                + ".version", "-DnewVersion=" + version, "-DgenerateBackupPoms=false");
    }

    private static void mvn(Runner runner, Path pom, String goal, String... properties) {
        List<String> argv = new ArrayList<>(List.of("mvn", "-B", "-q", "-f", pom.toString(), goal));
        argv.addAll(List.of(properties));
        Proc.Result result = runner.run(argv);
        if (!result.ok()) {
            throw new ReleaseRefusal(pom + ": " + goal.substring(goal.lastIndexOf(':') + 1) + " failed\n"
                    + result.out().lines().filter(line -> line.contains("[ERROR]")).limit(10)
                    .reduce((a, b) -> a + "\n" + b).orElse(result.out().strip()));
        }
    }

    private static void commitPom(Runner runner, Path dir, String message) {
        // Path-limited: only the pom this step edited, whatever else the working tree holds.
        if (!runner.git(dir, "commit", "-m", message, "--", "pom.xml").ok()) {
            throw new ReleaseRefusal(dir.getFileName() + ": committing pom.xml (" + message + ") failed");
        }
    }
}
