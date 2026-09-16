package com.botmaker.cli.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Points a module's japicmp baseline at its newest existing tag, in the release commit and before the new
 * tag is cut — {@code release.sh}'s {@code bump_japicmp_baseline}.
 *
 * <p><b>Why it is automated at all.</b> {@code botmaker-studio-api} and {@code botmaker-sdk} both carry
 * {@code <botmaker.japicmp.baseline>}, and both {@code CLAUDE.md} files instruct a human to move it to the
 * previous tag in every release commit. Nothing ever did. Left alone the baseline names a tag that no longer
 * sorts below the release being cut, and {@code ignoreMissingOldVersion} — which exists so a first release is
 * not refused for a reason its author cannot act on — turns the whole gate into a no-op that reports success.
 * A gate enforced by somebody remembering an undocumented {@code sed} is not a gate, and this one carries the
 * two strongest rules in the project: never-delete on {@code com.botmaker.sdk.api.**}, and binary
 * compatibility for every plugin already compiled against the contract.
 *
 * <p><b>It never moves backwards, and that is the part to keep.</b> The SDK's baseline is deliberately
 * {@code v1.2.0} — the release never-delete <i>begins</i> at — while its newest tag is older, because the
 * span before it removed {@code api.config.Wire}, {@code api.config.Settings}, {@code @Palette},
 * {@code @Scaffolding} and {@code Text}'s nine shared-{@code OcrOptions} overloads, each a decision recorded
 * while {@code api.*} was still freely breakable. Pointing the baseline at the newest tag would make the gate
 * refuse this project's own history. So the newest tag wins only when it sorts <b>above</b> the committed
 * value; otherwise the committed value stands and is reported saying so.
 *
 * <p><b>Which modules it runs for is read off their poms, not listed here.</b> The script names its two
 * call sites; this asks whether the property is present, which is the same answer for the same two and the
 * right answer for a third the day one grows the property.
 */
public final class Japicmp {

    /** What {@link Runner#replace} rewrites: the element, its value, and the closing tag. */
    static final Pattern PROPERTY = Pattern.compile(
            "(<botmaker\\.japicmp\\.baseline>)[^<]*(</botmaker\\.japicmp\\.baseline>)");

    /**
     * What the committed value reads as — the {@code v} optional, because the pom carries it and the
     * comparison is between versions rather than between tag spellings.
     */
    private static final Pattern VALUE = Pattern.compile(
            "<botmaker\\.japicmp\\.baseline>\\s*v?([^<\\s]*)\\s*</botmaker\\.japicmp\\.baseline>");

    private Japicmp() {
    }

    /**
     * Moves the module's baseline up to its newest tag, or says why it stays where it is.
     *
     * <p>A module with no such property is not this function's business and returns silently: that is every
     * module but two, and it is how the caller avoids keeping a list.
     */
    public static void bump(Runner runner, Path umbrella, Module module) {
        Path pom = umbrella.resolve(module.directory()).resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return;
        }
        String text;
        try {
            text = Files.readString(pom);
        } catch (IOException e) {
            throw new ReleaseRefusal(pom + ": could not be read (" + e.getMessage() + ")");
        }
        Matcher value = VALUE.matcher(text);
        if (!value.find()) {
            return;                                        // no baseline: nothing to bump
        }
        String committed = value.group(1);
        String label = "  " + module.shortName() + ": ";

        Optional<Version> newest = Tags.latest(umbrella, module);
        if (newest.isEmpty()) {
            runner.say(label + "no tag yet — japicmp baseline stays v" + committed);
            return;
        }
        Optional<Version> current = Version.parse(committed);
        if (current.isPresent() && newest.get().compareTo(current.get()) <= 0) {
            // The committed value is the newer of the two, which is the SDK's ordinary state.
            runner.say(label + "japicmp baseline stays v" + committed + " (newest tag v" + newest.get()
                    + " is older — the rule begins at v" + committed + ")");
            return;
        }
        if (current.isEmpty()) {
            // A value this port cannot order is one it must not overwrite: an unparseable baseline is
            // somebody's deliberate edit, and guessing the direction of a move is how a gate gets disarmed.
            runner.say(label + "japicmp baseline '" + committed + "' is not x.y.z — left alone");
            return;
        }
        runner.say(label + "japicmp baseline v" + committed + " -> v" + newest.get());
        runner.replace(pom, PROPERTY, "$1v" + newest.get() + "$2");
    }
}
