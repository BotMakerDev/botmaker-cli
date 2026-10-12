package com.botmaker.cli.release;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Maven run that failed because an upstream's {@code -SNAPSHOT} is not in {@code ~/.m2}: the machine, not the
 * module. Every gate that shells to Maven reads its failure through this first and says
 * {@link GateVerdict.Status#SKIPPED} with the install that fixes it, rather than its own refusal.
 *
 * <p>Since 2026-10-12, when the redirect-pointer and plugin gates refused an SDK release whose code was fine: the
 * machine had never installed the contract's new {@code -SNAPSHOT}, and both refusals read as defects in the SDK.
 * {@code --force} does not lift it either, by the gates' rule: it overrides a gate that failed, never one that
 * could not run.
 *
 * @param artifact   the coordinate Maven could not find, {@code com.github.BotMakerDev:botmaker-sdk:jar:1.5.0-SNAPSHOT}
 * @param artifactId its artifact id, which is one of ours when it names a {@link Module#directory()}
 */
record MissingSnapshot(String artifact, String artifactId) {

    private static final Pattern ARTIFACT = Pattern.compile("(com\\.github\\.[\\w.]+:([\\w-]+):\\w+:[\\w.]+-SNAPSHOT)");
    /** Maven 3.6 says "Could not find artifact", 3.9 "could not be resolved"; both after "Could not resolve…". */
    private static final Pattern UNRESOLVED =
            Pattern.compile("could not find artifact|could not resolve|could not be resolved", Pattern.CASE_INSENSITIVE);
    /** The project being built, which the line names first and which is never what is missing. */
    private static final Pattern FOR_PROJECT = Pattern.compile("for project \\S+");

    /** The {@code -SNAPSHOT} Maven could not find locally, when that is why the run failed. */
    static Optional<MissingSnapshot> in(String output) {
        return output.lines()
                .filter(line -> UNRESOLVED.matcher(line).find())
                .map(line -> ARTIFACT.matcher(FOR_PROJECT.matcher(line).replaceAll("")))
                .filter(Matcher::find)
                .map(m -> new MissingSnapshot(m.group(1), m.group(2)))
                .findFirst();
    }

    /** The module the missing artifact is, when it is one of ours. */
    Optional<Module> module() {
        return Module.byDirectory(artifactId);
    }

    /**
     * The gate's line.
     *
     * @param who     the gate's subject, {@code sdk}
     * @param didNot  what could not happen, {@code the redirect pointers were not checked}
     * @param install the module whose {@code -am install} puts the snapshot there: the one the gate builds, so a
     *                second missing upstream is installed by the same command
     */
    GateVerdict skipped(String who, String didNot, Module install) {
        return GateVerdict.skipped("  " + who + ": " + artifact + " is not in ~/.m2, so " + didNot
                + " (environment) — run `mvn -pl " + install.directory() + " -am install -DskipTests` first");
    }
}
