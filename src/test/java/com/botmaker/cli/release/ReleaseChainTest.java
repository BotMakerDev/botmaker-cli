package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tag chain's record of itself — above all when it stops partway, which is the release of 2026-09-16:
 * four tags pushed, no log and no pointer commit.
 */
class ReleaseChainTest {

    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 9, 16, 10, 20);

    private static Map<Module, Version> fiveModules() {
        Map<Module, Version> releasing = new EnumMap<>(Module.class);
        releasing.put(Module.STUDIO_API, new Version(0, 1, 0));
        releasing.put(Module.PLUGIN_TOOLKIT, new Version(0, 1, 0));
        releasing.put(Module.SHARED, new Version(0, 1, 0));
        releasing.put(Module.SDK, new Version(1, 2, 0));
        releasing.put(Module.STUDIO, new Version(1, 2, 0));
        return releasing;
    }

    @Test
    void theLogExistsBeforeTheFirstTagAndIsCurrentAfterEach(@TempDir Path umbrella) throws Exception {
        List<String> said = new ArrayList<>();
        Path log = ReleaseLog.path(umbrella, WHEN);
        List<List<ReleaseLog.Stage>> seen = new ArrayList<>();

        Release.Chain chain = Release.tagChain(new Runner(false, said::add), umbrella, fiveModules(), WHEN,
                (module, version, at) -> {
                    // What the file says at the moment each module starts.
                    seen.add(ReleaseLog.read(log).stream().map(ReleaseLog.Row::stage).toList());
                    return ReleaseLog.onJitpack(module) ? ReleaseLog.Stage.BUILT : ReleaseLog.Stage.TAGGED;
                });

        assertEquals(List.of(ReleaseLog.Stage.PENDING, ReleaseLog.Stage.PENDING, ReleaseLog.Stage.PENDING,
                ReleaseLog.Stage.PENDING, ReleaseLog.Stage.PENDING), seen.get(0));
        assertEquals(ReleaseLog.Stage.BUILT, seen.get(2).get(1));
        assertEquals(ReleaseLog.Stage.PENDING, seen.get(2).get(2));
        assertEquals(log, chain.log());
        assertEquals(chain.rows(), ReleaseLog.read(log));
        assertEquals(ReleaseLog.Stage.TAGGED, chain.rows().get(4).stage());
        assertTrue(said.stream().noneMatch(line -> line.contains("commit")), said.toString());
    }

    @Test
    void aModuleThatThrowsIsRecordedWithTheTaggedPointersAndTheExceptionGoesOn(@TempDir Path umbrella)
            throws Exception {
        List<String> said = new ArrayList<>();

        ReleaseRefusal thrown = assertThrows(ReleaseRefusal.class, () ->
                Release.tagChain(new Runner(false, said::add), umbrella, fiveModules(), WHEN,
                        (module, version, at) -> {
                            if (module == Module.SHARED) {
                                at.accept("commit, tag and push");
                                throw new ReleaseRefusal("botmaker-shared: pushing v0.1.0 failed.");
                            }
                            return ReleaseLog.Stage.BUILT;
                        }));
        assertEquals("botmaker-shared: pushing v0.1.0 failed.", thrown.getMessage());

        List<ReleaseLog.Row> rows = ReleaseLog.read(ReleaseLog.path(umbrella, WHEN));
        assertEquals(List.of(ReleaseLog.Stage.BUILT, ReleaseLog.Stage.BUILT, ReleaseLog.Stage.FAILED,
                        ReleaseLog.Stage.NOT_REACHED, ReleaseLog.Stage.NOT_REACHED),
                rows.stream().map(ReleaseLog.Row::stage).toList());
        assertEquals("commit, tag and push: botmaker-shared: pushing v0.1.0 failed.", rows.get(2).failure());

        // The pointer commit names the two that were tagged, says it stopped, and carries the log.
        assertTrue(said.stream().anyMatch(line -> line.endsWith("add releases")), said.toString());
        assertTrue(said.stream().anyMatch(line ->
                line.contains("commit -m 'release (stopped): studio-api v0.1.0 toolkit v0.1.0'")),
                said.toString());
        assertTrue(said.stream().noneMatch(line -> line.contains("add botmaker-shared")), said.toString());
        // And nothing is pushed on that path.
        assertTrue(said.stream().noneMatch(line -> line.contains("$ git") && line.contains(" push ")),
                said.toString());
    }

    @Test
    void aFailureOnTheFirstModuleStillCommitsTheLog(@TempDir Path umbrella) {
        List<String> said = new ArrayList<>();

        assertThrows(IllegalStateException.class, () ->
                Release.tagChain(new Runner(false, said::add), umbrella, fiveModules(), WHEN,
                        (module, version, at) -> {
                            throw new IllegalStateException();
                        }));

        List<ReleaseLog.Row> rows = ReleaseLog.read(ReleaseLog.path(umbrella, WHEN));
        // No message on the exception is not an empty failure: the class name stands in.
        assertEquals("start: IllegalStateException", rows.get(0).failure());
        assertTrue(said.stream().anyMatch(line ->
                line.contains("commit -m 'release (stopped): nothing tagged'")), said.toString());
    }

    @Test
    void aFailedJitpackBuildStopsTheChainWithItsRowTaggedAndItsPointerRecorded(@TempDir Path umbrella)
            throws Exception {
        List<String> said = new ArrayList<>();
        List<Module> started = new ArrayList<>();

        // The 2026-10-05 release: studio-api's build failed, and the chain went on to tag two more.
        assertThrows(ReleaseRefusal.class, () ->
                Release.tagChain(new Runner(false, said::add), umbrella, fiveModules(), WHEN,
                        (module, version, at) -> {
                            started.add(module);
                            at.accept("jitpack wait");
                            throw new Release.AfterTag(ReleaseLog.Stage.JITPACK_FAILED, "429 Too Many Requests",
                                    "JitPack failed to build botmaker-studio-api v0.1.0");
                        }));

        assertEquals(List.of(Module.STUDIO_API), started);
        List<ReleaseLog.Row> rows = ReleaseLog.read(ReleaseLog.path(umbrella, WHEN));
        assertEquals(ReleaseLog.Stage.JITPACK_FAILED, rows.get(0).stage());
        assertEquals("BROKEN", rows.get(0).jitpack());
        assertEquals("429 Too Many Requests", rows.get(0).jitpackError());
        assertEquals("jitpack wait: JitPack failed to build botmaker-studio-api v0.1.0", rows.get(0).failure());
        assertEquals(ReleaseLog.Stage.NOT_REACHED, rows.get(1).stage());
        assertTrue(said.stream().anyMatch(line ->
                line.contains("commit -m 'release (stopped): studio-api v0.1.0'")), said.toString());
    }

    @Test
    void aStopMovesEachUnreachedDependentForEveryTaggedUpstream(@TempDir Path umbrella) throws Exception {
        // 2026-10-06: studio-api and the toolkit were tagged, the toolkit's JitPack build failed, and
        // plugin-basics got the contract's pin but kept the toolkit's old snapshot.
        for (Module module : List.of(Module.STUDIO_API, Module.PLUGIN_TOOLKIT, Module.PLUGIN_BASICS)) {
            Path dir = Files.createDirectories(umbrella.resolve(module.directory()));
            Files.writeString(dir.resolve("pom.xml"), "<project><version>0.1.0-SNAPSHOT</version><properties>"
                    + "<botmaker.studioapi.version>0.1.0-SNAPSHOT</botmaker.studioapi.version>"
                    + "<botmaker.plugintoolkit.version>0.1.0-SNAPSHOT</botmaker.plugintoolkit.version>"
                    + "</properties></project>");
        }
        Map<Module, Version> releasing = new EnumMap<>(Module.class);
        releasing.put(Module.STUDIO_API, new Version(0, 5, 0));
        releasing.put(Module.PLUGIN_TOOLKIT, new Version(0, 4, 0));
        releasing.put(Module.PLUGIN_BASICS, new Version(0, 3, 0));
        List<String> said = new ArrayList<>();

        assertThrows(ReleaseRefusal.class, () ->
                Release.tagChain(new Runner(true, said::add), umbrella, releasing, WHEN,
                        (module, version, at) -> {
                            if (module == Module.PLUGIN_TOOLKIT) {
                                throw new Release.AfterTag(ReleaseLog.Stage.JITPACK_FAILED, "build failed",
                                        "JitPack failed to build botmaker-plugin-toolkit v0.4.0");
                            }
                            return ReleaseLog.Stage.BUILT;
                        }));

        assertTrue(said.stream().anyMatch(line -> line.contains("botmaker-plugin-basics")
                && line.endsWith("commit -m 'pin studio-api 0.5.1-SNAPSHOT' -- pom.xml")), said.toString());
        assertTrue(said.stream().anyMatch(line -> line.contains("botmaker-plugin-basics")
                && line.endsWith("commit -m 'pin plugin-toolkit 0.4.1-SNAPSHOT' -- pom.xml")), said.toString());
    }

    @Test
    void aFailedPushStillMovesItsDependentsPinsAndStagesItsPointer(@TempDir Path umbrella)
            throws Exception {
        // 2026-10-07: cli was tagged and back at 0.2.2-SNAPSHOT locally, its push failed, and the dashboard
        // kept pinning 0.2.1-SNAPSHOT.
        for (Module module : List.of(Module.CLI, Module.DASHBOARD)) {
            Path dir = Files.createDirectories(umbrella.resolve(module.directory()));
            Files.writeString(dir.resolve("pom.xml"), "<project><version>0.2.1-SNAPSHOT</version><properties>"
                    + "<botmaker.cli.version>0.2.1-SNAPSHOT</botmaker.cli.version></properties></project>");
        }
        Map<Module, Version> releasing = new EnumMap<>(Module.class);
        releasing.put(Module.CLI, new Version(0, 2, 1));
        List<String> said = new ArrayList<>();

        assertThrows(ReleaseRefusal.class, () ->
                Release.tagChain(new Runner(true, said::add), umbrella, releasing, WHEN,
                        (module, version, at) -> {
                            at.accept("push");
                            throw new Release.NotPushed("botmaker-cli: pushing v0.2.1 failed");
                        }));

        // A dry run, so pin commits are echoed rather than made — and the log is not written.
        assertTrue(said.stream().anyMatch(line -> line.startsWith("error: botmaker-cli failed at push")
                && line.endsWith("0 of 1 modules were tagged, and botmaker-cli v0.2.1 is tagged locally only.")),
                said.toString());
        assertTrue(said.stream().anyMatch(line -> line.contains("botmaker-dashboard")
                && line.endsWith("commit -m 'pin cli 0.2.2-SNAPSHOT' -- pom.xml")), said.toString());
        assertTrue(said.stream().anyMatch(line -> line.endsWith("add botmaker-cli")), said.toString());
    }

    @Test
    void aStopIsHonouredBetweenModules(@TempDir Path umbrella) throws Exception {
        List<String> said = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();

        ReleaseRefusal thrown = assertThrows(ReleaseRefusal.class, () ->
                Release.tagChain(new Runner(false, said::add, stop::get), umbrella, fiveModules(), WHEN,
                        (module, version, at) -> {
                            stop.set(true);                // pressed while the first module ran
                            return ReleaseLog.Stage.BUILT;
                        }));

        assertTrue(thrown.getMessage().startsWith("stopped by you before botmaker-plugin-toolkit"),
                thrown.getMessage());
        List<ReleaseLog.Row> rows = ReleaseLog.read(ReleaseLog.path(umbrella, WHEN));
        assertEquals(List.of(ReleaseLog.Stage.BUILT, ReleaseLog.Stage.NOT_REACHED, ReleaseLog.Stage.NOT_REACHED,
                        ReleaseLog.Stage.NOT_REACHED, ReleaseLog.Stage.NOT_REACHED),
                rows.stream().map(ReleaseLog.Row::stage).toList());
        assertEquals("start: stopped by you", rows.get(1).failure());
        assertTrue(said.stream().anyMatch(line ->
                line.contains("commit -m 'release (stopped): studio-api v0.1.0'")), said.toString());
        assertTrue(said.stream().noneMatch(line -> line.contains("$ git") && line.contains(" push ")),
                said.toString());
    }

    @Test
    void aDryRunWritesNoLogAndStillWalksTheChain(@TempDir Path umbrella) {
        List<String> said = new ArrayList<>();
        List<Module> walked = new ArrayList<>();

        Release.Chain chain = Release.tagChain(new Runner(true, said::add), umbrella, fiveModules(), WHEN,
                (module, version, at) -> {
                    walked.add(module);
                    return ReleaseLog.Stage.TAGGED;
                });

        assertNull(chain.log());
        assertTrue(Files.notExists(umbrella.resolve("releases")));
        assertEquals(Order.toTag(fiveModules().keySet()), walked);
    }
}
