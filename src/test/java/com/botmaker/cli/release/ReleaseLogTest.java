package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseLogTest {

    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 9, 5, 12, 12);

    @Test
    void aStudioOnlyReleaseRendersTheTableWithItsStage() {
        // The shape of releases/2026-09-05-1212.md, which release.sh itself wrote, plus the stage column
        // every log carries since 2026-09-16.
        String rendered = ReleaseLog.render(WHEN, List.of(
                new ReleaseLog.Row(Module.STUDIO, new Version(1, 0, 37))
                        .withStage(ReleaseLog.Stage.TAGGED)
                        .withActions("success (1)", "")));

        assertEquals("""
                # Release 2026-09-05 12:12

                | module | version | tag | stage | changelog | jitpack | actions |
                |---|---|---|---|---|---|---|
                | botmaker-studio | 1.0.37 | v1.0.37 | tagged | stamped | n/a (not a Maven artifact) | success (1) |
                """, rendered);
    }

    @Test
    void theTwoModulesNobodyResolvesSaySoRatherThanPending() {
        assertFalse(ReleaseLog.onJitpack(Module.STUDIO));   // an app, packaged per-OS by its own CI
        assertFalse(ReleaseLog.onJitpack(Module.PILOT));    // an APK
        assertTrue(ReleaseLog.onJitpack(Module.SDK));
    }

    @Test
    void theLogWrittenBeforeTheFirstTagIsPendingThroughout() {
        String rendered = ReleaseLog.render(WHEN, List.of(
                new ReleaseLog.Row(Module.SDK, new Version(1, 1, 7))));

        assertTrue(rendered.contains("| botmaker-sdk | 1.1.7 | v1.1.7 | pending | — | pending | pending |"),
                rendered);
    }

    @Test
    void aRowThatWasNeverTaggedSaysSoInsteadOfPending() {
        // "pending" would promise a verdict that no poll can ever produce.
        String rendered = ReleaseLog.render(WHEN, List.of(
                new ReleaseLog.Row(Module.SHARED, new Version(0, 1, 0)).failed("commit, tag and push", "boom"),
                new ReleaseLog.Row(Module.SDK, new Version(1, 2, 0)).withStage(ReleaseLog.Stage.NOT_REACHED)));

        assertTrue(rendered.contains("| botmaker-shared | 0.1.0 | v0.1.0 | FAILED | — | not tagged | not tagged |"));
        assertTrue(rendered.contains("| botmaker-sdk | 1.2.0 | v1.2.0 | not reached | — | not tagged | not tagged |"));
        assertTrue(rendered.contains("**botmaker-shared — release**\n```\ncommit, tag and push: boom\n```"));
    }

    @Test
    void thePilotHasNoChangelogAndTheCellSaysSo() {
        String rendered = ReleaseLog.render(WHEN, List.of(
                new ReleaseLog.Row(Module.PILOT, new Version(0, 0, 12)).withStage(ReleaseLog.Stage.TAGGED)));

        assertTrue(rendered.contains("| n/a (no CHANGELOG.md) | n/a (not a Maven artifact) | pending |"));
    }

    @Test
    void errorsGoUnderTheTableInFullRatherThanIntoACell() {
        ReleaseLog.Row row = new ReleaseLog.Row(Module.SESSION, new Version(0, 0, 13))
                .withStage(ReleaseLog.Stage.BUILT)
                .withJitpack("BROKEN", "Could not find artifact com.github.LiQiyeDev:botmaker-shared")
                .withActions("FAILED — ci", "ci: failure — https://example.invalid/run/1");

        String rendered = ReleaseLog.render(WHEN, List.of(row));

        assertTrue(rendered.contains("| BROKEN | FAILED — ci |"));
        assertTrue(rendered.contains("## Errors"));
        assertTrue(rendered.contains("**botmaker-session — jitpack**"));
        assertTrue(rendered.contains("Could not find artifact com.github.LiQiyeDev:botmaker-shared"));
        assertTrue(rendered.contains("**botmaker-session — actions**"));
    }

    @Test
    void rowsAreInTagOrderRatherThanTheCallersOwn() {
        Map<Module, Version> released = new EnumMap<>(Module.class);
        released.put(Module.SDK, new Version(1, 1, 7));
        released.put(Module.STUDIO, new Version(1, 0, 38));
        released.put(Module.SHARED, new Version(0, 0, 21));

        assertEquals(List.of(Module.SHARED, Module.SDK, Module.STUDIO),
                ReleaseLog.rows(released).stream().map(ReleaseLog.Row::module).toList());
    }

    @Test
    void theTableIsTheContractAndReadsBackForARePoll(@TempDir Path umbrella) throws IOException {
        Path log = ReleaseLog.path(umbrella, WHEN);
        Files.createDirectories(log.getParent());
        List<ReleaseLog.Row> written = List.of(
                new ReleaseLog.Row(Module.SHARED, new Version(0, 0, 20)).withStage(ReleaseLog.Stage.BUILT)
                        .withJitpack("BROKEN", "line one\nline two"),
                new ReleaseLog.Row(Module.SDK, new Version(1, 1, 6)).failed("jitpack wait", "gave up"),
                new ReleaseLog.Row(Module.STUDIO, new Version(1, 1, 0)).withStage(ReleaseLog.Stage.NOT_REACHED));
        Files.writeString(log, ReleaseLog.render(WHEN, written));

        // Reading the table back, rather than keeping state beside it, is what lets --status run a week
        // later from another machine on somebody else's release — and the stage and failure survive it.
        List<ReleaseLog.Row> read = ReleaseLog.read(log);

        assertEquals(written, read);
        assertEquals(log, ReleaseLog.newest(umbrella));
        assertEquals(WHEN, ReleaseStatus.stampOf(log));
    }

    @Test
    void aLogOlderThanTheStageColumnReadsAsAWholeRelease() {
        // Every log before 2026-09-16 was written after the last tag, so every row in it was tagged.
        List<ReleaseLog.Row> read = ReleaseLog.parse("""
                # Release 2026-09-05 12:12

                | module | version | tag | changelog | jitpack | actions |
                |---|---|---|---|---|---|
                | botmaker-studio | 1.0.37 | v1.0.37 | stamped | n/a (not a Maven artifact) | success (1) |
                | botmaker-sdk | 1.1.6 | v1.1.6 | stamped | pending | pending |
                """.lines().toList());

        assertEquals(2, read.size());
        assertEquals(ReleaseLog.Stage.TAGGED, read.get(0).stage());
        assertEquals("success (1)", read.get(0).actions());
        assertEquals("", read.get(0).jitpack());
        assertEquals("", read.get(1).actions());
        assertEquals("", read.get(0).elapsed(), "a log written before the section was timed by nobody");
    }

    @Test
    void aDurationIsSpelledOneWay() {
        assertEquals("41s", ReleaseLog.elapsed(Duration.ofSeconds(41)));
        assertEquals("3m41s", ReleaseLog.elapsed(Duration.ofSeconds(221)));
        assertEquals("1m00s", ReleaseLog.elapsed(Duration.ofMinutes(1)));
        // Seconds are noise at this scale, and the column is read to find the module worth looking at.
        assertEquals("1h04m", ReleaseLog.elapsed(Duration.ofSeconds(3859)));
        assertEquals("0s", ReleaseLog.elapsed(Duration.ofSeconds(-5)));
    }

    @Test
    void theTimingsAreASectionAndTheyRoundTrip(@TempDir Path umbrella) throws IOException {
        Path log = ReleaseLog.path(umbrella, WHEN);
        Files.createDirectories(log.getParent());
        List<ReleaseLog.Row> written = List.of(
                new ReleaseLog.Row(Module.SHARED, new Version(0, 0, 20)).withStage(ReleaseLog.Stage.BUILT)
                        .withElapsed(Duration.ofSeconds(221)),
                new ReleaseLog.Row(Module.STUDIO, new Version(1, 1, 0)).withStage(ReleaseLog.Stage.TAGGED)
                        .withElapsed(Duration.ofSeconds(12)));
        ReleaseLog.Timing timing = new ReleaseLog.Timing("6m40s", "24m03s");
        String rendered = ReleaseLog.render(WHEN, written, timing);
        Files.writeString(log, rendered);

        // The release table is unchanged: an eighth column would be dropped by every dashboard already
        // installed, which takes a row of six or seven cells and nothing else.
        assertTrue(rendered.contains("| module | version | tag | stage | changelog | jitpack | actions |"));
        assertTrue(rendered.contains("## Timing"));
        assertTrue(rendered.contains("| botmaker-shared | 3m41s |"));
        assertTrue(rendered.contains("| verify pass | 6m40s |"));
        assertTrue(rendered.contains("| total | 24m03s |"));

        assertEquals(written, ReleaseLog.read(log));
        assertEquals(timing, ReleaseLog.timing(log));
        // The second table's rows name modules too, so a parser that did not stop at the heading would
        // read this release as four modules.
        assertEquals(2, ReleaseLog.read(log).size());
    }

    @Test
    void aReleaseThatTimedNothingWritesNoSection() {
        String rendered = ReleaseLog.render(WHEN,
                List.of(new ReleaseLog.Row(Module.SDK, new Version(1, 1, 7))));

        assertFalse(rendered.contains("## Timing"));
    }
}
