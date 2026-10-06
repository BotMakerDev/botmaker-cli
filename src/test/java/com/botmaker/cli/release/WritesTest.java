package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WritesTest {

    /** A runner that records instead of running, which is what a dry run is. */
    private static Runner recording(List<String> log) {
        return new Runner(true, log::add);
    }

    /** A pom as a module on main has it: its own snapshot and each pin at the upstream's. */
    private static void pom(Path umbrella, Module module, String version, String pins) throws IOException {
        Path dir = Files.createDirectories(umbrella.resolve(module.directory()));
        Files.writeString(dir.resolve("pom.xml"), "<project><version>" + version + "</version><properties>"
                + pins + "</properties></project>");
    }

    @Test
    void aDryRunEchoesEveryVersionWriteAndPerformsNone(@TempDir Path umbrella) throws IOException {
        pom(umbrella, Module.SDK, "1.3.1-SNAPSHOT", "<botmaker.shared.version>0.1.3-SNAPSHOT</botmaker.shared.version>");
        String before = Files.readString(umbrella.resolve("botmaker-sdk/pom.xml"));
        List<String> log = new ArrayList<>();

        // All five of the SDK's upstreams are being cut in this run. An upstream that is NOT being cut would
        // be looked up in the checkout, which a temp directory cannot answer — see the refusal in DepTag.
        Map<Module, Version> releasing = Map.of(
                Module.SHARED, new Version(0, 1, 3),
                Module.SESSION, new Version(0, 1, 3),
                Module.STUDIO_API, new Version(0, 5, 0),
                Module.PLUGIN_TOOLKIT, new Version(0, 3, 3),
                Module.PLUGIN_BASICS, new Version(0, 2, 1),
                Module.SDK, new Version(1, 4, 0));
        PomVersions.release(recording(log), umbrella, Module.SDK, new Version(1, 4, 0), releasing);
        PomVersions.backToSnapshot(recording(log), umbrella, Module.SDK, new Version(1, 4, 0), releasing);

        assertEquals(before, Files.readString(umbrella.resolve("botmaker-sdk/pom.xml")), "a dry run wrote");
        assertTrue(log.stream().anyMatch(l -> l.contains(PomVersions.PLUGIN + ":set -DnewVersion=1.4.0 ")),
                log.toString());
        assertTrue(log.stream().anyMatch(l -> l.contains("-Dproperty=botmaker.studioapi.version"
                + " -DnewVersion=0.5.0 ")), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.equals("  botmaker-sdk 1.4.0 pinning shared 0.1.3, session"
                + " 0.1.3, studio-api 0.5.0, plugin-toolkit 0.3.3, plugin-basics 0.2.1")), log.toString());
        // Back on main: the next patch snapshot, and each pin at the snapshot its upstream moved to.
        assertTrue(log.stream().anyMatch(l -> l.contains(":set -DnewVersion=1.4.1-SNAPSHOT ")), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.contains("-Dproperty=botmaker.studioapi.version"
                + " -DnewVersion=0.5.1-SNAPSHOT ")), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.endsWith("commit -m 'back to snapshot: sdk 1.4.1-SNAPSHOT'"
                + " -- pom.xml")), log.toString());
    }

    @Test
    void aPinNotBeingCutIsTheNewestTagOnTheTagAndThePomOnMain(@TempDir Path umbrella) throws IOException {
        // No git repository at all, so the newest-tag lookup refuses — which is the point: it is asked, and
        // a module never tagged cannot be pinned by a release commit.
        pom(umbrella, Module.SESSION, "0.1.3-SNAPSHOT", "<botmaker.shared.version>0.1.3-SNAPSHOT</botmaker.shared.version>");
        pom(umbrella, Module.SHARED, "0.1.3-SNAPSHOT", "");

        assertThrows(ReleaseRefusal.class, () -> PomVersions.release(recording(new ArrayList<>()), umbrella,
                Module.SESSION, new Version(0, 1, 3), Map.of()));
        // On main it is whatever the upstream's own pom says.
        assertEquals("0.1.3-SNAPSHOT", PomVersions.mainVersion(umbrella, Module.SHARED, Map.of()));
    }

    @Test
    void aDependentOutsideTheReleaseFollowsTheNewSnapshot(@TempDir Path umbrella) throws IOException {
        pom(umbrella, Module.PLUGIN_TOOLKIT, "0.3.3-SNAPSHOT",
                "<botmaker.studioapi.version>0.4.3-SNAPSHOT</botmaker.studioapi.version>");
        List<String> log = new ArrayList<>();

        List<Module> moved = PomVersions.follow(recording(log), umbrella, Module.STUDIO_API, new Version(0, 5, 0),
                java.util.Set.of(Module.STUDIO_API));

        // Only the toolkit exists here; every other dependent of the contract has no pom in this checkout.
        assertEquals(List.of(Module.PLUGIN_TOOLKIT), moved);
        assertTrue(log.stream().anyMatch(l -> l.contains("botmaker-plugin-toolkit/pom.xml " + PomVersions.PLUGIN
                + ":set-property -Dproperty=botmaker.studioapi.version -DnewVersion=0.5.1-SNAPSHOT")),
                log.toString());
        assertTrue(log.stream().anyMatch(l -> l.endsWith("commit -m 'pin studio-api 0.5.1-SNAPSHOT' -- pom.xml")));
    }

    @Test
    void aDependentInTheReleaseIsLeftToItsOwnBackToSnapshot(@TempDir Path umbrella) throws IOException {
        pom(umbrella, Module.PLUGIN_TOOLKIT, "0.3.3-SNAPSHOT", "");

        assertEquals(List.of(), PomVersions.follow(recording(new ArrayList<>()), umbrella, Module.STUDIO_API,
                new Version(0, 5, 0), java.util.Set.of(Module.STUDIO_API, Module.PLUGIN_TOOLKIT)));
    }

    @Test
    void stampingRenamesTheFirstUnreleasedHeadingOnly(@TempDir Path umbrella) throws IOException {
        Path dir = umbrella.resolve(Module.CLI.directory());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("CHANGELOG.md"), """
                # Changelog

                ## [Unreleased]

                - something

                ## [0.0.12] — 2026-09-05
                """);
        List<String> log = new ArrayList<>();
        Runner real = new Runner(false, log::add);

        Stamp.changelog(real, umbrella, Module.CLI, new Version(0, 0, 13));

        String after = Files.readString(dir.resolve("CHANGELOG.md"));
        assertTrue(after.contains("## [0.0.13] — " + LocalDate.now()));
        assertFalse(after.contains("## [Unreleased]"));
        assertTrue(after.contains("## [0.0.12] — 2026-09-05"), "the previous section was rewritten");
    }

    @Test
    void stampingIsIdempotentBecauseAResumedReleaseRunsItAgain(@TempDir Path umbrella) throws IOException {
        Path dir = umbrella.resolve(Module.CLI.directory());
        Files.createDirectories(dir);
        String already = "## [0.0.13] — 2026-09-05\n\n## [Unreleased]\n";
        Files.writeString(dir.resolve("CHANGELOG.md"), already);
        List<String> log = new ArrayList<>();

        assertTrue(Stamp.changelog(new Runner(false, log::add), umbrella, Module.CLI,
                new Version(0, 0, 13)).isEmpty());
        assertEquals(already, Files.readString(dir.resolve("CHANGELOG.md")));
    }

    @Test
    void aModuleWithNoChangelogIsSkippedRatherThanRefused(@TempDir Path umbrella) throws IOException {
        Files.createDirectories(umbrella.resolve(Module.PILOT.directory()));

        assertTrue(Stamp.changelog(recording(new ArrayList<>()), umbrella, Module.PILOT,
                new Version(0, 0, 12)).isEmpty());
    }

    @Test
    void aDryRunStillShowsTheCommitAndTheTag(@TempDir Path umbrella) {
        List<String> log = new ArrayList<>();

        CommitTagPush.run(recording(log), umbrella, Module.CLI, new Version(0, 0, 13),
                "release: cli v0.0.13");

        // Asking git in a dry run would answer "clean" and the commit would vanish from the plan — the one
        // thing a preview must not do.
        // Quoted, because the echo is read as a shell line: a subject joined bare would read as three
        // arguments where it is one.
        assertTrue(log.stream().anyMatch(l -> l.contains("commit -am 'release: cli v0.0.13'")),
                log.toString());
        assertTrue(log.stream().anyMatch(l -> l.endsWith("tag v0.0.13")));
        assertTrue(log.stream().anyMatch(l -> l.endsWith("push origin HEAD")));
        assertTrue(log.stream().anyMatch(l -> l.endsWith("push origin v0.0.13")));
    }

    @Test
    void aReleaseCommitThatFailsStopsBeforeTheTag(@TempDir Path umbrella) throws IOException {
        // Not a repository, so the commit fails as a hook or a lock would. Tagging after it would tag the
        // commit before the release, whose pom names -SNAPSHOT upstreams nobody can resolve.
        Files.createDirectories(umbrella.resolve(Module.CLI.directory()));
        List<String> log = new ArrayList<>();

        assertThrows(ReleaseRefusal.class, () -> CommitTagPush.commit(new Runner(false, log::add), umbrella,
                Module.CLI, "release: cli v0.0.13"));
        assertFalse(log.stream().anyMatch(l -> l.contains(" tag ")), log.toString());
    }

    @Test
    void anEmptyMessageTagsWithoutCommitting(@TempDir Path umbrella) {
        List<String> log = new ArrayList<>();

        CommitTagPush.run(recording(log), umbrella, Module.PILOT, new Version(0, 0, 12), "");

        assertFalse(log.stream().anyMatch(l -> l.contains("commit")));
        assertTrue(log.stream().anyMatch(l -> l.endsWith("tag v0.0.12")));
    }
}
