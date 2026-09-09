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
import static org.junit.jupiter.api.Assertions.assertTrue;

class WritesTest {

    /** A runner that records instead of running, which is what a dry run is. */
    private static Runner recording(List<String> log) {
        return new Runner(true, log::add);
    }

    @Test
    void aDryRunEchoesEveryWriteAndPerformsNone(@TempDir Path umbrella) throws IOException {
        Path sdk = umbrella.resolve(Module.SDK.directory());
        Files.createDirectories(sdk);
        List<String> log = new ArrayList<>();

        // All five of the SDK's upstreams are being cut in this run — plugin-basics joined them on
        // 2026-09-09, when the SDK started declaring plugin #2 as an ordinary dependency. An upstream that
        // is NOT being cut would be looked up in the checkout, which a temp directory cannot answer — see
        // the refusal in DepTag.
        DepsEnv.write(recording(log), umbrella, Module.SDK, Map.of(
                Module.SHARED, new Version(0, 0, 21),
                Module.SESSION, new Version(0, 0, 13),
                Module.STUDIO_API, new Version(0, 0, 5),
                Module.PLUGIN_TOOLKIT, new Version(0, 0, 6),
                Module.PLUGIN_BASICS, new Version(0, 1, 0)));

        assertFalse(Files.exists(sdk.resolve(".deps.env")), "a dry run wrote a file");
        assertTrue(log.stream().anyMatch(line -> line.startsWith("  pinning botmaker-sdk to ")));
        assertTrue(log.stream().anyMatch(line -> line.contains("cat > " + sdk.resolve(".deps.env"))));
        // The git add is echoed too: it is the line whose absence tagged three modules with no .deps.env.
        assertTrue(log.stream().anyMatch(line -> line.endsWith("add .deps.env")), log.toString());
    }

    @Test
    void aPinBeingCutThisRunIsTheVersionBeingCutRatherThanTheNewestTag(@TempDir Path umbrella)
            throws IOException {
        Files.createDirectories(umbrella.resolve(Module.SESSION.directory()));
        List<String> log = new ArrayList<>();

        // No git repository here at all, so a "newest tag" lookup would refuse. It resolves because the
        // run is cutting shared, which is the case that made dep_tag exist.
        DepsEnv.write(recording(log), umbrella, Module.SESSION,
                Map.of(Module.SHARED, new Version(1, 0, 0)));

        assertTrue(log.get(0).endsWith("SHARED_TAG=v1.0.0"), log.get(0));
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
        assertTrue(log.stream().anyMatch(l -> l.contains("commit -am release: cli v0.0.13")), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.endsWith("tag v0.0.13")));
        assertTrue(log.stream().anyMatch(l -> l.endsWith("push origin HEAD")));
        assertTrue(log.stream().anyMatch(l -> l.endsWith("push origin v0.0.13")));
    }

    @Test
    void anEmptyMessageTagsWithoutCommitting(@TempDir Path umbrella) {
        List<String> log = new ArrayList<>();

        CommitTagPush.run(recording(log), umbrella, Module.PILOT, new Version(0, 0, 12), "");

        assertFalse(log.stream().anyMatch(l -> l.contains("commit")));
        assertTrue(log.stream().anyMatch(l -> l.endsWith("tag v0.0.12")));
    }
}
