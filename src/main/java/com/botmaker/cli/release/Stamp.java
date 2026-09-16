package com.botmaker.cli.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renames {@code ## [Unreleased]} to {@code ## [<version>] — <date>} in the module's release commit —
 * {@code release.sh}'s {@code stamp_changelog}.
 *
 * <p><b>This is the half that makes {@code --all} work.</b> A changelog is written as the work happens, when
 * the version number is not knowable — it is what the decide pass <i>computes</i>, by bumping each module
 * off its own latest tag. So the section is called {@code [Unreleased]}, {@link ChangelogGate} accepts that,
 * and this stamps the number onto it a moment before the tag. Until 2026-09-02 the maintainer did it by
 * hand, guessing the number the script was about to choose.
 *
 * <p><b>Idempotent by checking for the stamped heading first</b>, which is the case a resumed release hits:
 * a run that died after tagging one module and is re-run must not stamp a second heading onto a file that
 * already has one. Doing nothing when neither heading is present is equally deliberate — the gate already
 * refused, or {@code --force} was passed and the maintainer has said to release without notes.
 */
public final class Stamp {

    /**
     * The heading, and only the heading.
     *
     * <p><b>An anchored replacement rather than a whole-file rewrite</b>, which is what this did until
     * 2026-09-16. A rewrite through {@code readAllLines}/{@code join("\n")} normalises everything the reader
     * did not model — line endings, a missing or doubled final newline — in a file the maintainer has been
     * editing all week, and the release commit then carries that reformatting as if it were the release's.
     * The script's {@code sed '0,/^## \[Unreleased\]/s//…/'} touches one line, and so does this.
     *
     * <p>The {@code ^} is multiline and {@link Runner#replace} takes the <b>first</b> match, which together
     * are the script's {@code 0,/…/} address: a changelog carrying both a stamped section and a fresh
     * {@code [Unreleased]} is the ordinary state one release after another, and only the newer one is the
     * release being cut.
     */
    private static final Pattern UNRELEASED = Pattern.compile("(?m)^## \\[Unreleased]");

    private Stamp() {
    }

    /** Stamps the file if there is something to stamp; answers what it did, for the caller to print. */
    public static Optional<String> changelog(Runner runner, Path umbrella, Module module, Version version) {
        Path file = umbrella.resolve(module.directory()).resolve("CHANGELOG.md");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();                       // the pilot has none, and is exempt
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new ReleaseRefusal(file + ": could not be read (" + e.getMessage() + ")");
        }
        String stamped = "## [" + version + "] — " + LocalDate.now();
        if (Pattern.compile("(?m)^## \\[" + Pattern.quote(version.toString()) + "]").matcher(text).find()) {
            return Optional.empty();                       // already stamped — a resumed release
        }
        if (!UNRELEASED.matcher(text).find()) {
            return Optional.empty();                       // nothing to stamp
        }
        runner.say("  stamping " + module.directory() + " CHANGELOG.md: [Unreleased] -> [" + version + "]");
        runner.replace(file, UNRELEASED, Matcher.quoteReplacement(stamped));
        return Optional.of(stamped);
    }
}
