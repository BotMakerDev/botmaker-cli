package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A gate whose Maven run could not find an upstream's {@code -SNAPSHOT} says so, with the install, rather than
 * refusing the module (2026-10-12: the redirect-pointer and plugin gates refused a sound SDK).
 */
class MissingSnapshotTest {

    private static final Proc.Result MISSING = new Proc.Result(1, "[ERROR] Failed to execute goal on project"
            + " botmaker-sdk: Could not resolve dependencies for project com.github.BotMakerDev:botmaker-sdk:jar:"
            + "1.4.5-SNAPSHOT: Could not find artifact com.github.BotMakerDev:botmaker-studio-api:jar:0.9.1-SNAPSHOT"
            + " in jitpack.io");

    @Test
    void theArtifactMavenCouldNotFindIsNamed() {
        MissingSnapshot missing = MissingSnapshot.in(MISSING.out()).orElseThrow();
        assertEquals("com.github.BotMakerDev:botmaker-studio-api:jar:0.9.1-SNAPSHOT", missing.artifact());
        assertEquals(Optional.of(Module.STUDIO_API), missing.module());
        assertEquals(Optional.of(Module.SHARED), MissingSnapshot.in("[ERROR] Failed to execute goal on project "
                + "botmaker-sdk: Could not resolve dependencies for project com.github.BotMakerDev:botmaker-sdk:jar:"
                + "1.4.5-SNAPSHOT\n[ERROR] dependency: com.github.BotMakerDev:botmaker-shared:jar:0.2.6-SNAPSHOT "
                + "(compile)\n[ERROR] \tcom.github.BotMakerDev:botmaker-shared:jar:0.2.6-SNAPSHOT was not found; "
                + "The following artifacts could not be resolved: "
                + "com.github.BotMakerDev:botmaker-shared:jar:0.2.6-SNAPSHOT (absent)")
                .flatMap(MissingSnapshot::module), "Maven 3.9's wording");
        assertEquals(Optional.of(Module.SHARED), MissingSnapshot.in("[ERROR] Failed to execute goal on project "
                + "botmaker-dashboard: Could not resolve dependencies for project com.github.BotMakerDev:"
                + "botmaker-dashboard:jar:0.3.3-SNAPSHOT\n[ERROR] \tcom.github.BotMakerDev:botmaker-shared:jar:"
                + "0.2.6-SNAPSHOT was not found in https://jitpack.io during a previous attempt.")
                .flatMap(MissingSnapshot::module), "a miss Maven cached");
        assertEquals(Optional.empty(), MissingSnapshot.in("[ERROR] /x/A.java:[3,1] cannot find symbol"));
    }

    @Test
    void theSdkGateSkipsWithTheInstallEvenWhenForced() {
        for (boolean force : new boolean[] {false, true}) {
            assertEquals(GateVerdict.Status.SKIPPED, SdkGates.validated(MISSING, force).status());
        }
        assertEquals(GateVerdict.Status.REFUSED,
                SdkGates.validated(new Proc.Result(1, "FAIL pom-scopes"), false).status());
    }

    @Test
    void aTemplateInstallsTheModuleItsPinNames() {
        GateVerdict verdict = TemplateGate.verdict("botmaker-gamebot", new Proc.Result(1, "[ERROR] Could not find "
                + "artifact com.github.BotMakerDev:botmaker-sdk:jar:1.4.5-SNAPSHOT in jitpack.io"), false);
        assertEquals(GateVerdict.Status.SKIPPED, verdict.status());
        assertTrue(verdict.line().contains("mvn -pl botmaker-sdk -am install"), verdict.line());
    }
}
