package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UmbrellaTest {

    private static Runner recording(List<String> log) {
        return new Runner(true, log::add);
    }

    @Test
    void thePointerCommitNamesEveryModuleInDecideOrderAndCarriesTheLog(@TempDir Path umbrella) {
        Map<Module, Version> released = new EnumMap<>(Module.class);
        released.put(Module.SDK, new Version(1, 1, 7));
        released.put(Module.SHARED, new Version(0, 0, 21));
        List<String> log = new ArrayList<>();

        String subject = Umbrella.recordPointers(recording(log), umbrella, released, true);

        assertEquals("shared v0.0.21 sdk v1.1.7", subject);
        assertTrue(log.stream().anyMatch(line -> line.endsWith("add botmaker-shared")));
        assertTrue(log.stream().anyMatch(line -> line.endsWith("add botmaker-sdk")));
        // The log goes in the SAME commit as the pointers: what was released and whether it landed are one
        // fact, and a log committed separately is a log somebody forgets to commit.
        assertTrue(log.stream().anyMatch(line -> line.endsWith("add releases")));
        assertTrue(log.stream().anyMatch(line ->
                line.contains("commit -m 'release: shared v0.0.21 sdk v1.1.7'")), log.toString());
    }

    @Test
    void aReleaseOfNothingCommitsNothing(@TempDir Path umbrella) {
        List<String> log = new ArrayList<>();

        assertEquals("", Umbrella.recordPointers(recording(log), umbrella, Map.of(), false));
        assertFalse(log.stream().anyMatch(line -> line.contains("commit")));
    }

    @Test
    void theUmbrellaIsPushedLastBecauseItsCommitNamesSubmoduleCommits(@TempDir Path umbrella) {
        List<String> log = new ArrayList<>();

        // A dry run inspects for real and pushes nothing, so every module here reports what a directory
        // that is not a git repository truthfully is — which is also the shape of a detached HEAD.
        Umbrella.pushBranches(recording(log), umbrella);

        List<String> visited = log.stream().filter(line -> line.contains("nothing pushed")).toList();
        assertEquals(Order.DECIDE.size() + 1, visited.size(), visited.toString());
        assertTrue(visited.get(visited.size() - 1).startsWith("  umbrella:"), visited.toString());
    }

    @Test
    void aDetachedHeadIsSaidRatherThanPushed(@TempDir Path dir) {
        List<String> log = new ArrayList<>();

        // Not a git repository at all, so symbolic-ref answers nothing — the same shape as a detached HEAD,
        // and the same right answer: say so, push nothing, do not stop the release.
        assertTrue(Umbrella.pushBranch(new Runner(false, log::add), dir, "botmaker-sdk"));
        assertEquals(List.of("  botmaker-sdk: detached HEAD — nothing pushed"), log);
    }

    @Test
    void theJitpackUrlsAreTheOnesAConsumerWouldUse() {
        Version version = new Version(0, 0, 5);

        assertEquals("https://jitpack.io/com/github/LiQiyeDev/botmaker-plugin-host/v0.0.5/"
                + "botmaker-plugin-host-v0.0.5.pom", Jitpack.pomUrl(Module.PLUGIN_HOST, version));
        assertEquals("https://jitpack.io/api/builds/com.github.LiQiyeDev/botmaker-plugin-host/v0.0.5",
                Jitpack.buildUrl(Module.PLUGIN_HOST, version));
    }

    @Test
    void aDryRunPollsNothing() {
        List<String> log = new ArrayList<>();

        // The wait reaches the network, so a dry run must not: this is the one gate-like step that would
        // otherwise make a preview take ten minutes.
        assertTrue(Jitpack.waitFor(recording(log), Module.SDK, new Version(1, 1, 7),
                duration -> {
                    throw new AssertionError("a dry run slept");
                }));
        assertTrue(log.get(0).contains("(dry-run) would poll"));
    }
}
