package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two edits a release makes to files it does not own — {@link Japicmp} over a pom's baseline and
 * {@link Fallback} over Studio's {@code SDK_FALLBACK_VERSION} — and the {@link Runner#replace} both go
 * through.
 *
 * <p>Both were missing from the port until 2026-09-16 and were found by the parity diff against
 * {@code ./release.sh --dry-run}: the decide pass, the gates and the tag order all matched, and two
 * {@code sed}s that land inside a module's own release commit did not exist. That is the failure mode this
 * file exists for — an omission on a write path is invisible in a preview that never looked for it.
 */
class SourceEditsTest {

    private static Runner recording(List<String> log) {
        return new Runner(true, log::add);
    }

    private static Runner executing(List<String> log) {
        return new Runner(false, log::add);
    }

    private static final String POM = """
            <project>
              <properties>
                <botmaker.japicmp.baseline>v1.2.0</botmaker.japicmp.baseline>
              </properties>
            </project>
            """;

    private static final String MAVEN_SERVICE = """
            public final class MavenService {
                public static final String SDK_FALLBACK_VERSION = "1.1.6";
                private static final String SOMETHING_ELSE = "1.1.6";
            }
            """;

    // ---- Runner.replace ------------------------------------------------------------------------------

    @Test
    void aDryRunEchoesTheSedAndRewritesNothing(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("MavenService.java"), MAVEN_SERVICE);
        List<String> log = new ArrayList<>();

        recording(log).replace(file, Fallback.assignment("SDK_FALLBACK_VERSION"), "$11.2.0$2");

        assertEquals(MAVEN_SERVICE, Files.readString(file), "a dry run edited a file");
        assertTrue(log.get(0).startsWith("    $ sed -i -E "), log.get(0));
        assertTrue(log.get(0).endsWith(file.toString()), log.get(0));
        // The echo is spelled as a command, so it has to be one: sed's group reference is \1, not $1, and
        // a Pattern.quote'd constant name would put \Q…\E in front of an operator about to paste it.
        assertTrue(log.get(0).contains("#\\11.2.0\\2#'"), log.get(0));
        assertFalse(log.get(0).contains("\\Q"), log.get(0));
    }

    @Test
    void aRealRunRewritesTheFirstMatchAndLeavesTheRest(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("MavenService.java"), MAVEN_SERVICE);

        executing(new ArrayList<>())
                .replace(file, Fallback.assignment("SDK_FALLBACK_VERSION"), "$11.2.0$2");

        String after = Files.readString(file);
        assertTrue(after.contains("SDK_FALLBACK_VERSION = \"1.2.0\""), after);
        assertTrue(after.contains("SOMETHING_ELSE = \"1.1.6\""),
                "a constant that merely holds the same text is not this release's business");
    }

    @Test
    void aPatternThatMatchesNothingIsARefusalRatherThanASilentNoOp(@TempDir Path dir) throws IOException {
        // The script's sed is silent here, which is how MIN_SDK_VERSION went on being sed-ded for weeks
        // after the constant was deleted. A release that believes it moved a pin it did not move is worse
        // than one that stops.
        Path file = Files.writeString(dir.resolve("MavenService.java"), "class Empty {}\n");

        ReleaseRefusal refused = assertThrows(ReleaseRefusal.class, () -> executing(new ArrayList<>())
                .replace(file, Fallback.assignment("SDK_FALLBACK_VERSION"), "$11.2.0$2"));

        assertTrue(refused.getMessage().contains("nothing matches"), refused.getMessage());
    }

    // ---- Fallback ------------------------------------------------------------------------------------

    @Test
    void theGateAndTheBumpReadOneListOfConstants() {
        // Two lists is how TOOLKIT_FALLBACK_VERSION spent months being moved by a release and checked by
        // nothing. The writer owns the list and the gate reads it; the gate keeping no copy is what the
        // compiler holds, and this is the value both see.
        assertEquals(Map.of(Module.SDK, "SDK_FALLBACK_VERSION"), Fallback.CONSTANTS);
        assertSame(Fallback.SOURCE, FallbackVersionsGate.SOURCE);
    }

    @Test
    void aRunThatDoesNotCutTheSdkTouchesNothingInStudiosSource(@TempDir Path umbrella) throws IOException {
        Path studio = umbrella.resolve(Module.STUDIO.directory());
        Files.createDirectories(studio.resolve(Fallback.SOURCE).getParent());
        Path file = Files.writeString(studio.resolve(Fallback.SOURCE), MAVEN_SERVICE);
        List<String> log = new ArrayList<>();

        Fallback.bump(executing(log), umbrella, Map.of(Module.STUDIO, new Version(1, 0, 38)));

        assertEquals(MAVEN_SERVICE, Files.readString(file));
        assertTrue(log.isEmpty(), log.toString());
    }

    @Test
    void theSdkBeingCutMovesTheConstantToIt(@TempDir Path umbrella) throws IOException {
        Path studio = umbrella.resolve(Module.STUDIO.directory());
        Files.createDirectories(studio.resolve(Fallback.SOURCE).getParent());
        Path file = Files.writeString(studio.resolve(Fallback.SOURCE), MAVEN_SERVICE);

        Fallback.bump(executing(new ArrayList<>()), umbrella,
                Map.of(Module.STUDIO, new Version(1, 0, 38), Module.SDK, new Version(1, 2, 0)));

        assertTrue(Files.readString(file).contains("SDK_FALLBACK_VERSION = \"1.2.0\""));
    }

    @Test
    void theConstantCarriesNoLeadingV() throws IOException {
        // It is what a generated bot's pom declares as <version>, so a "v" there is an unresolvable pin.
        assertFalse(Fallback.assignment("SDK_FALLBACK_VERSION").matcher(MAVEN_SERVICE)
                .replaceFirst("$1" + new Version(1, 2, 0) + "$2").contains("\"v1.2.0\""));
    }

    // ---- Japicmp -------------------------------------------------------------------------------------

    @Test
    void aModuleWithNoBaselinePropertyIsSilent(@TempDir Path umbrella) throws IOException {
        Path shared = umbrella.resolve(Module.SHARED.directory());
        Files.createDirectories(shared);
        Files.writeString(shared.resolve("pom.xml"), "<project/>\n");
        List<String> log = new ArrayList<>();

        Japicmp.bump(recording(log), umbrella, Module.SHARED);

        assertTrue(log.isEmpty(), "nine of the eleven modules have no baseline, and say nothing about it");
    }

    @Test
    void aModuleWithNoTagYetKeepsItsCommittedBaseline(@TempDir Path umbrella) throws IOException {
        Path sdk = umbrella.resolve(Module.SDK.directory());
        Files.createDirectories(sdk);
        Path pom = Files.writeString(sdk.resolve("pom.xml"), POM);
        List<String> log = new ArrayList<>();

        // Not a git repository, so `git tag --list` fails and the newest version is empty — which is also
        // exactly the state of a module before its first release.
        Japicmp.bump(recording(log), umbrella, Module.SDK);

        assertEquals(List.of("  sdk: no tag yet — japicmp baseline stays v1.2.0"), log);
        assertEquals(POM, Files.readString(pom));
    }

    @Test
    void anOlderNewestTagLosesToTheCommittedBaselineBecauseTheRuleBeginsThere(@TempDir Path umbrella)
            throws Exception {
        // The SDK's real state: the baseline is v1.2.0, the release never-delete BEGINS at, while the
        // newest tag is older because the span before it removed api.config.Wire and Settings deliberately.
        // Moving the baseline down would make the gate refuse this project's own recorded history.
        Path sdk = tagged(umbrella, Module.SDK, POM, "v1.1.6");
        List<String> log = new ArrayList<>();

        Japicmp.bump(recording(log), umbrella, Module.SDK);

        assertEquals(List.of("  sdk: japicmp baseline stays v1.2.0"
                + " (newest tag v1.1.6 is older — the rule begins at v1.2.0)"), log);
        assertEquals(POM, Files.readString(sdk.resolve("pom.xml")));
    }

    @Test
    void aNewerTagMovesTheBaselineUpSoTheNextReleaseIsComparedAgainstThisOne(@TempDir Path umbrella)
            throws Exception {
        Path api = tagged(umbrella, Module.STUDIO_API,
                POM.replace("v1.2.0", "v0.0.3"), "v0.0.4");
        List<String> log = new ArrayList<>();

        Japicmp.bump(executing(log), umbrella, Module.STUDIO_API);

        assertEquals("  studio-api: japicmp baseline v0.0.3 -> v0.0.4", log.get(0));
        assertTrue(Files.readString(api.resolve("pom.xml"))
                .contains("<botmaker.japicmp.baseline>v0.0.4</botmaker.japicmp.baseline>"));
    }

    @Test
    void aBaselineThisPortCannotOrderIsLeftAlone(@TempDir Path umbrella) throws Exception {
        // Somebody's deliberate edit — a branch name, a SHA. Guessing the direction of a move is how a gate
        // gets disarmed, and this one carries never-delete.
        Path api = tagged(umbrella, Module.STUDIO_API,
                POM.replace("v1.2.0", "main-SNAPSHOT"), "v0.0.4");
        List<String> log = new ArrayList<>();

        Japicmp.bump(executing(log), umbrella, Module.STUDIO_API);

        assertEquals(List.of("  studio-api: japicmp baseline 'main-SNAPSHOT' is not x.y.z — left alone"),
                log);
        assertTrue(Files.readString(api.resolve("pom.xml")).contains("main-SNAPSHOT"));
    }

    // ---- the changelog stamp -------------------------------------------------------------------------

    @Test
    void stampingTouchesTheHeadingLineAndNoOtherByte(@TempDir Path umbrella) throws IOException {
        // The risk a whole-file rewrite carried: a changelog the maintainer has been editing all week
        // arrives with CRLF, a tab, no final newline — and the release commit carries that reformatting as
        // if it were the release's. The script's sed touches one line and so must this.
        String before = "# Changelog\r\n\r\n## [Unreleased]\r\n\r\n### Added\r\n\r\n- A thing.\t\r\n"
                + "\r\n## [0.0.12] — 2026-09-04\r\n\r\n- An older thing.";
        Path file = changelog(umbrella, Module.SESSION, before);

        Stamp.changelog(executing(new ArrayList<>()), umbrella, Module.SESSION, new Version(0, 0, 13));

        String after = Files.readString(file);
        assertEquals(before.replace("## [Unreleased]", "## [0.0.13] — " + java.time.LocalDate.now()), after);
    }

    @Test
    void onlyTheFirstUnreleasedHeadingIsStamped(@TempDir Path umbrella) throws IOException {
        // One release after another is the ordinary state: a stamped section above, a fresh [Unreleased]
        // below it in the file nobody rewrote. The script's `0,/…/` address takes the first and so does
        // Runner.replace.
        String before = "## [Unreleased]\n\n- new\n\n## [Unreleased]\n\n- older, and a mistake\n";
        Path file = changelog(umbrella, Module.SESSION, before);

        Stamp.changelog(executing(new ArrayList<>()), umbrella, Module.SESSION, new Version(0, 0, 13));

        String after = Files.readString(file);
        assertEquals(1, after.split("## \\[Unreleased]", -1).length - 1, after);
        assertTrue(after.startsWith("## [0.0.13] — "), after);
    }

    @Test
    void aResumedReleaseDoesNotStampASecondHeading(@TempDir Path umbrella) throws IOException {
        String before = "## [0.0.13] — 2026-09-16\n\n- done\n\n## [Unreleased]\n";
        Path file = changelog(umbrella, Module.SESSION, before);
        List<String> log = new ArrayList<>();

        assertTrue(Stamp.changelog(executing(log), umbrella, Module.SESSION, new Version(0, 0, 13))
                .isEmpty());
        assertEquals(before, Files.readString(file));
        assertTrue(log.isEmpty(), log.toString());
    }

    private static Path changelog(Path umbrella, Module module, String content) throws IOException {
        Path dir = umbrella.resolve(module.directory());
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve("CHANGELOG.md"), content);
    }

    // ---- the pointer commit's names ------------------------------------------------------------------

    @Test
    void thePointerNamesAreTheOnesEveryReleaseCommitSoFarUsed() {
        // Transcribed from release.sh's eleven-line POINTERS block, inconsistency included: a git log
        // --grep over the release history matches these and not a tidier derivation.
        assertEquals("studio-api", Module.STUDIO_API.pointerName());
        assertEquals("toolkit", Module.PLUGIN_TOOLKIT.pointerName());
        assertEquals("plugin-host", Module.PLUGIN_HOST.pointerName());
        assertEquals("archetype", Module.PLUGIN_ARCHETYPE.pointerName());
        assertEquals("plugin-basics", Module.PLUGIN_BASICS.pointerName());
        assertEquals("cli", Module.CLI.pointerName());
        assertEquals("shared", Module.SHARED.pointerName());
        assertEquals("session", Module.SESSION.pointerName());
        assertEquals("sdk", Module.SDK.pointerName());
        assertEquals("studio", Module.STUDIO.pointerName());
        assertEquals("pilot", Module.PILOT.pointerName());
    }

    /** A module directory that is a real git repository with one commit and one tag. */
    private static Path tagged(Path umbrella, Module module, String pom, String tag) throws IOException {
        Path dir = umbrella.resolve(module.directory());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), pom);
        Git.run(dir, "init", "--quiet", "--initial-branch=main");
        Git.run(dir, "config", "user.email", "test@example.invalid");
        Git.run(dir, "config", "user.name", "test");
        Git.run(dir, "add", "pom.xml");
        Git.run(dir, "commit", "--quiet", "-m", "fixture");
        Git.run(dir, "tag", tag);
        return dir;
    }
}
