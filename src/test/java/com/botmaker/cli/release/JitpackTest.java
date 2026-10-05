package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JitPack wait ends on the first of: the pom is there, JitPack says the build failed, the operator stopped
 * it, ten minutes. The 2026-10-05 release waited the full ten on a build that had failed in its first minute.
 */
class JitpackTest {

    private static final Version V = new Version(0, 4, 1);

    private static Jitpack.Probe probe(Optional<String> failure, boolean built) {
        return new Jitpack.Probe() {
            @Override
            public boolean built(String pomUrl) {
                return built;
            }

            @Override
            public Optional<String> failure(String buildUrl) {
                return failure;
            }
        };
    }

    @Test
    void aFailedBuildEndsTheWaitAtOnceWithJitpacksMessage() {
        List<String> said = new ArrayList<>();
        AtomicInteger slept = new AtomicInteger();

        Jitpack.Waited waited = Jitpack.waitFor(new Runner(false, said::add), Module.STUDIO_API, V,
                duration -> slept.incrementAndGet() >= 0,
                probe(Optional.of("No build artifacts found. See the log: com/github/x/build.log"), false));

        assertEquals(Jitpack.Build.FAILED, waited.build());
        assertEquals("No build artifacts found. See the log: com/github/x/build.log", waited.detail());
        assertEquals(0, slept.get());
        assertTrue(said.stream().anyMatch(line -> line.startsWith("JitPack failed to build "
                + "botmaker-studio-api:v0.4.1")), said.toString());
    }

    @Test
    void aBuildThatNeverAppearsTimesOutAfterSixtyTries() {
        AtomicInteger slept = new AtomicInteger();

        Jitpack.Waited waited = Jitpack.waitFor(new Runner(false, line -> { }), Module.STUDIO_API, V,
                duration -> slept.incrementAndGet() > 0, probe(Optional.empty(), false));

        assertEquals(Jitpack.Build.TIMEOUT, waited.build());
        assertEquals(60, slept.get());
    }

    @Test
    void aStopEndsTheWaitBeforeTheNextSleep() {
        AtomicBoolean stop = new AtomicBoolean(true);
        AtomicInteger slept = new AtomicInteger();

        Jitpack.Waited waited = Jitpack.waitFor(new Runner(false, line -> { }, stop::get), Module.STUDIO_API, V,
                duration -> slept.incrementAndGet() > 0, probe(Optional.empty(), false));

        assertEquals(Jitpack.Build.STOPPED, waited.build());
        assertEquals(0, slept.get());
    }

    @Test
    void theRealSleeperGivesUpOnAStopInsteadOfSleepingTheGapOut() {
        long started = System.nanoTime();

        boolean slept = Jitpack.Sleeper.real(() -> true).sleep(java.time.Duration.ofSeconds(10));

        assertEquals(false, slept);
        assertTrue(System.nanoTime() - started < 2_000_000_000L);
    }

    @Test
    void theBuildApiSaysFailedOnlyForStatusError() {
        assertEquals(Optional.of("No build artifacts found. See the log: com/github/LiQiyeDev/botmaker-studio-api"
                        + "/v0.4.1/build.log"),
                Jitpack.failureIn("""
                        {"version":"v0.4.1","status":"Error","message":"No build artifacts found. See the log: \
                        com/github/LiQiyeDev/botmaker-studio-api/v0.4.1/build.log","isTag":true}"""));
        assertEquals(Optional.empty(), Jitpack.failureIn("{\"status\":\"ok\",\"message\":\"Notfound\"}"));
        assertEquals(Optional.empty(), Jitpack.failureIn("{\"status\":\"none\"}"));
        assertEquals(Optional.empty(), Jitpack.failureIn("<html>"));
        assertEquals(Optional.of("the build failed"), Jitpack.failureIn("{\"status\":\"Error\"}"));
    }
}
