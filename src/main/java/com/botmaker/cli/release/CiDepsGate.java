package com.botmaker.cli.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every module being cut must have a CI that can build it standalone — {@code release.sh}'s
 * {@code check_ci_deps}.
 *
 * <p><b>The bug it exists for is invisible from the umbrella.</b> A module's pom declares its upstreams as
 * {@code ${botmaker.<key>.version}} defaulting to {@code 0.0.0-SNAPSHOT}, which resolves for free in the
 * reactor — the upstream is a sibling module there — so {@code mvn install} at the root is green while that
 * module's own CI is red, having nothing to resolve. A module whose own CI cannot build it is a module whose
 * release job cannot publish it, and a pushed tag cannot be edited.
 *
 * <p>So the rule is: for every property this pom declares at {@code 0.0.0-SNAPSHOT}, that upstream's
 * repository name must appear somewhere in {@code .github/workflows/ci.yml}. A grep, deliberately — what is
 * being asked is <i>does this workflow know about that repository at all</i>, and a YAML parse would answer
 * a narrower question about where it appears.
 *
 * <p><b>An unmapped key is refused even under {@code --force}</b>, and that asymmetry is the point: every
 * other arm here says "this looks wrong", while an unknown {@code ${botmaker.X.version}} says the gate does
 * not know what it is looking at. Overriding a check that failed is a judgement; overriding one that could
 * not run is not.
 */
public final class CiDepsGate {

    /** Property keys this pom declares at {@code 0.0.0-SNAPSHOT} — those resolved from a source install. */
    private static final Pattern SNAPSHOT_PIN =
            Pattern.compile("<botmaker\\.([a-z]*)\\.version>0\\.0\\.0-SNAPSHOT<");

    private CiDepsGate() {
    }

    /** Reads the module's two files and checks them; a module with no pom is not a module being built. */
    public static GateVerdict check(Path umbrella, Module module, boolean force) {
        Path dir = umbrella.resolve(module.directory());
        Optional<String> pom = read(dir.resolve("pom.xml"));
        if (pom.isEmpty()) {
            return GateVerdict.ok("");
        }
        Optional<String> ci = read(dir.resolve(".github/workflows/ci.yml"));
        return check(module, pom.get(), ci, force);
    }

    /** The rule itself, over the two files' text — pure, so every arm is testable without a checkout. */
    public static GateVerdict check(Module module, String pom, Optional<String> ci, boolean force) {
        if (ci.isEmpty()) {
            return GateVerdict.skipped("  " + module.shortName() + ": no ci.yml — skipped");
        }
        Set<String> missing = new TreeSet<>();
        for (String key : keys(pom)) {
            String repo = repositoryFor(key)
                    .orElseThrow(() -> new ReleaseRefusal(module.directory()
                            + ": pom.xml declares ${botmaker." + key + ".version} and this check does not"
                            + " know which\n     repository that is. Add it to check_ci_deps' table in"
                            + " release.sh — an upstream nothing maps is\n     exactly the case this gate"
                            + " exists for."));
            if (!ci.get().contains(repo)) {
                missing.add(repo);
            }
        }
        if (missing.isEmpty()) {
            return GateVerdict.ok("  " + module.shortName()
                    + ": ci.yml checks out every 0.0.0-SNAPSHOT upstream — ok");
        }
        String names = String.join(" ", missing);
        if (force) {
            return GateVerdict.forced("  " + module.shortName()
                    + ": ci.yml is missing " + names + " — FORCED");
        }
        return GateVerdict.refused(module.directory() + ": pom.xml resolves " + names
                + " at 0.0.0-SNAPSHOT and .github/workflows/ci.yml never checks it out.\n"
                + "     That module's own CI cannot build, and the umbrella reactor hides it: there the"
                + " upstream is a sibling\n"
                + "     module and resolves for free, so `mvn install` at the root is green while the"
                + " module's build is red.\n"
                + "     Add a checkout step and an install line for each, beside the ones already there. "
                + " --force overrides.");
    }

    /**
     * The property keys a pom pins at {@code 0.0.0-SNAPSHOT}, deduplicated and sorted.
     *
     * <p>Sorted because the script's is: {@code sed … | sort -u}. It only shows in the order a refusal names
     * the missing repositories, which is exactly the kind of difference a stdout diff catches and a reader
     * would not.
     */
    public static Set<String> keys(String pom) {
        Set<String> keys = new TreeSet<>();
        Matcher matcher = SNAPSHOT_PIN.matcher(pom);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    /**
     * The repository a property key names.
     *
     * <p>A table rather than a derivation, because the keys are not the directory names with the dashes
     * removed by rule — {@code studioapi} is {@code botmaker-studio-api} and {@code plugintoolkit} is
     * {@code botmaker-plugin-toolkit}, but nothing makes {@code sdk} mean {@code botmaker-sdk} except that
     * somebody wrote both. Deriving it would silently map a typo to a plausible repository.
     */
    public static Optional<Module> repositoryModule(String key) {
        return switch (key) {
            case "shared" -> Optional.of(Module.SHARED);
            case "session" -> Optional.of(Module.SESSION);
            case "sdk" -> Optional.of(Module.SDK);
            case "studioapi" -> Optional.of(Module.STUDIO_API);
            case "plugintoolkit" -> Optional.of(Module.PLUGIN_TOOLKIT);
            case "pluginbasics" -> Optional.of(Module.PLUGIN_BASICS);
            case "pluginhost" -> Optional.of(Module.PLUGIN_HOST);
            default -> Optional.empty();
        };
    }

    private static Optional<String> repositoryFor(String key) {
        return repositoryModule(key).map(Module::directory);
    }

    private static Optional<String> read(Path file) {
        try {
            return Files.exists(file) ? Optional.of(Files.readString(file)) : Optional.empty();
        } catch (IOException e) {
            // Unreadable is not absent: a file that exists and cannot be read is a broken checkout, and
            // treating it as "no ci.yml" would silently skip the gate.
            throw new ReleaseRefusal(file + ": cannot be read (" + e.getMessage() + ")");
        }
    }
}
