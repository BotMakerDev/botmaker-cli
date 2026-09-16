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
