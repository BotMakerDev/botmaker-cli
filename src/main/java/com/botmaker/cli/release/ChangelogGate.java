package com.botmaker.cli.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Every released module must describe the version being cut — {@code release.sh}'s {@code check_changelog}.
 *
 * <p><b>It invokes the module's own extractor and does not read the file itself.</b>
 * {@code <mod>/tools/changelog-section.sh} has two readers in two repositories: this gate, which asks <i>is
 * there prose for the release I am about to cut</i>, and the module's {@code ci.yml}, which asks <i>what was
 * the prose for the version that was cut</i> and feeds it to JReleaser as the release body. A release whose
 * body is extracted by a different rule than the one that gated it can pass the gate and then publish
 * something else — so there is one script, and porting it into Java here would create the second
 * implementation the file exists to prevent.
 *
 * <p><b>{@code --allow-unreleased} is passed here and nowhere else.</b> This side of the rename accepts a
 * {@code ## [Unreleased]} heading, because the version is not knowable while the prose is being written — it
 * is what the decide pass <i>computes</i>. {@code stamp_changelog} renames the heading a moment before the
 * tag, so the module's CI, running after it on the tag, stays strict. A fallback there could only fire if
 * the stamp had failed, and would publish a release whose notes say "Unreleased".
 *
 * <p><b>A missing extractor is refused even under {@code --force}</b>, like {@link CiDepsGate}'s unmapped
 * key and for the same reason: {@code --force} overrides a gate that failed, never one that could not run.
 */
public final class ChangelogGate {

    /** {@code botmaker-pilot} has no {@code CHANGELOG.md}: it is an APK, and nothing reads notes out of it. */
    public static boolean exempt(Module module) {
        return module == Module.PILOT;
    }

    private ChangelogGate() {
    }

    public static GateVerdict check(Path umbrella, Module module, Version version, boolean force) {
        // Absolute, and not merely for tidiness: the extractor is launched WITH THE MODULE AS ITS WORKING
        // DIRECTORY, so a relative path here is resolved against that directory rather than against the
        // caller's. A relative umbrella therefore refused a changelog that was in fact present. The caller
        // normalises too; this is the half that must not depend on the caller having done so, because it is
        // the only gate whose argv carries a path at all.
        Path dir = umbrella.toAbsolutePath().resolve(module.directory());
        Path script = dir.resolve("tools/changelog-section.sh");
        if (!Files.isExecutable(script)) {
            throw new ReleaseRefusal(module.directory() + ": " + module.directory()
                    + "/tools/changelog-section.sh is missing or not executable.\n"
                    + "     It is what this gate reads AND what that module's own release workflow feeds to"
                    + " JReleaser as the\n"
                    + "     release body — one extractor, so the two can never disagree. Copy it from"
                    + " another module.");
        }
        if (Proc.run(dir, script.toString(), "--allow-unreleased", version.toString()).ok()) {
            return GateVerdict.ok("  " + module.shortName()
                    + ": CHANGELOG.md describes v" + version + " — ok");
        }
        if (force) {
            return GateVerdict.forced("  " + module.shortName()
                    + ": no CHANGELOG.md section for v" + version + " — FORCED");
        }
        return GateVerdict.refused(module.directory() + ": CHANGELOG.md has no section for v" + version
                + ".\n"
                + "     That file is what this release TELLS PEOPLE — the GitHub Release body, and for the"
                + " sdk the\n"
                + "     whats-new.md inside the jar that Studio's upgrade dialog leads with. A pushed tag"
                + " cannot be edited.\n"
                + "     " + hint(dir, module, version) + "  --force overrides.");
    }

    /**
     * The refusal's last sentence, which is the instruction {@code stamp_changelog} now carries out
     * automatically — kept because the gate still refuses when there is no heading of either kind.
     */
    static String hint(Path dir, Module module, Version version) {
        String stamped = "## [" + version + "] — " + LocalDate.now();
        if (hasUnreleased(dir.resolve("CHANGELOG.md"))) {
            return "Rename '## [Unreleased]' in " + module.directory()
                    + "/CHANGELOG.md to '" + stamped + "'.";
        }
        return "Add a '" + stamped + "' section to " + module.directory() + "/CHANGELOG.md.";
    }

    private static boolean hasUnreleased(Path changelog) {
        try {
            return Files.exists(changelog)
                    // startsWith, not equals: the script greps `^## \[Unreleased\]` with no anchor at the
                    // end, so a heading carrying a date after it still counts.
                    && Files.readAllLines(changelog).stream()
                    .anyMatch(line -> line.startsWith("## [Unreleased]"));
        } catch (IOException e) {
            return false;   // The script's `2>/dev/null`: an unreadable file simply gets the other hint.
        }
    }
}
