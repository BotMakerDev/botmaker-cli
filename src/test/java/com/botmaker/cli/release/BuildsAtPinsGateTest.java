package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cut module compiles against the releases its tag pins (2026-10-12: SDK v1.4.3 called shared's
 * {@code LaunchSpec.runsOn}, on shared's main and in no shared release).
 */
class BuildsAtPinsGateTest {

    private static final Map<Module, String> TAGS = Map.of(Module.SHARED, "v0.2.4", Module.SESSION, "v0.2.2",
            Module.STUDIO_API, "v0.8.1", Module.CLI, "v0.3.3");

    private static Optional<String> released(Module upstream) {
        return Optional.ofNullable(TAGS.get(upstream));
    }

    @Test
    void onlyTheUpstreamsOutsideTheReleaseArePinnedToTheirTags() {
        List<BuildsAtPinsGate.Build> builds = BuildsAtPinsGate.builds(
                Set.of(Module.STUDIO_API, Module.PLUGIN_TOOLKIT, Module.SDK), BuildsAtPinsGateTest::released);
        assertEquals(1, builds.size(), "the toolkit's one upstream is in the release, so it is not built here");
        BuildsAtPinsGate.Build sdk = builds.getFirst();
        assertEquals(Module.SDK, sdk.module());
        assertEquals(List.of("-Dbotmaker.shared.version=v0.2.4", "-Dbotmaker.session.version=v0.2.2"),
                sdk.properties(), "contract and toolkit are cut with it and stay on their main snapshots");
        assertEquals("shared v0.2.4, session v0.2.2", sdk.said());
    }

    @Test
    void aProgramPackagedFromItsPinsIsBuiltAndAnApkIsNot() {
        List<Module> built = BuildsAtPinsGate.builds(Set.of(Module.DASHBOARD, Module.PILOT),
                BuildsAtPinsGateTest::released).stream().map(BuildsAtPinsGate.Build::module).toList();
        assertEquals(List.of(Module.DASHBOARD), built);
    }

    @Test
    void aCompileErrorIsRefusedNamingTheFlagsAndAMissingSnapshotIsTheMachines() {
        BuildsAtPinsGate.Build sdk = new BuildsAtPinsGate.Build(Module.SDK, Map.of(Module.SHARED, "v0.2.4"));
        GateVerdict refused = BuildsAtPinsGate.verdict(sdk, new Proc.Result(1,
                "[ERROR] /x/InstalledGame.java:[40,27] cannot find symbol\n[ERROR]   symbol: method runsOn(Os)"), false);
        assertEquals(GateVerdict.Status.REFUSED, refused.status());
        assertTrue(refused.refusal().contains("would pin (shared v0.2.4)"), refused.refusal());
        assertTrue(refused.refusal().contains("--shared"));
        assertTrue(refused.refusal().contains("cannot find symbol"), "the compiler's own words are quoted");

        GateVerdict environment = BuildsAtPinsGate.verdict(sdk, new Proc.Result(1, "[ERROR] Could not find artifact "
                + "com.github.BotMakerDev:botmaker-studio-api:jar:0.9.1-SNAPSHOT in jitpack.io"), false);
        assertEquals(GateVerdict.Status.SKIPPED, environment.status());
        assertTrue(environment.line().contains("botmaker-studio-api:jar:0.9.1-SNAPSHOT is not in ~/.m2"),
                environment.line());
        assertTrue(environment.line().contains("mvn -pl botmaker-sdk -am install"));

        assertEquals(GateVerdict.Status.FORCED, BuildsAtPinsGate.verdict(sdk, new Proc.Result(1, "[ERROR] x"), true)
                .status());
        assertEquals(GateVerdict.Status.OK, BuildsAtPinsGate.verdict(sdk, new Proc.Result(0, ""), false).status());
    }
}
