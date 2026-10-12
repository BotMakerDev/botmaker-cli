package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JitpackPluginsGateTest {

    private static MavenPrerequisite.Pin needing(String version, String maven) {
        return new MavenPrerequisite.Pin("flatten-maven-plugin", version, Optional.of(maven), "");
    }

    @Test
    void theCeilingIsComparedPartByPartAndNotAsText() {
        // 3.6.10 above 3.6.3, which every string comparison gets backwards — and being wrong here means
        // either a refused release or a tag that resolves to nothing.
        assertTrue(MavenPrerequisite.exceeds("3.6.3", "3.6.1"));
        assertTrue(MavenPrerequisite.exceeds("3.6.10", "3.6.3"));
        assertFalse(MavenPrerequisite.exceeds("3.6.1", "3.6.1"));
        assertFalse(MavenPrerequisite.exceeds("3.0", "3.6.1"));
        assertFalse(MavenPrerequisite.exceeds("3.6", "3.6.1"));
    }

    @Test
    void aPinNeedingANewerMavenThanJitpacksIsRefused() {
        // Three release chains burned on this when the ceiling was JitPack's own 3.6.1 (flatten 1.6.0 needed
        // 3.6.3); since 2026-10-12 every build runs tools/jitpack/build.sh's 3.9.9, and Maven 4 is above it.
        GateVerdict verdict = JitpackPluginsGate.verdict(Module.CLI,
                List.of(needing("9.0.0", "4.0.0")), false);

        assertTrue(verdict.stops());
        assertTrue(verdict.refusal().startsWith(
                "botmaker-cli: a pinned Maven plugin cannot run on JitPack's Maven 3.9.9."));
        assertTrue(verdict.refusal().contains("     flatten-maven-plugin:9.0.0 requires Maven 4.0.0"));
        // The sentence that says why it is invisible locally, which is the whole trap.
        assertTrue(verdict.refusal().contains("It builds locally because your Maven is newer."));
    }

    @Test
    void anUnknownWarnsAndDoesNotRefuse() {
        // The usual cause is a property inherited from a parent pom nobody publishes, or no network. A gate
        // must not stop a release over what it could not read.
        MavenPrerequisite.Pin unknown = new MavenPrerequisite.Pin("some-plugin", "1.0",
                Optional.empty(), "could not read its pom: HTTP Error 404");
        GateVerdict verdict = JitpackPluginsGate.verdict(Module.SDK, List.of(unknown), false);

        assertEquals(GateVerdict.Status.OK, verdict.status());
        assertTrue(verdict.line().contains("1 plugin(s) with no readable prerequisite — not checked"));
        assertTrue(verdict.line().endsWith("every pinned Maven plugin runs on JitPack's Maven 3.9.9 — ok"));
    }

    @Test
    void forceDowngradesItToALineThatStillSaysItFailed() {
        GateVerdict verdict = JitpackPluginsGate.verdict(Module.CLI,
                List.of(needing("9.0.0", "4.0.0")), true);

        assertEquals(GateVerdict.Status.FORCED, verdict.status());
        assertEquals("  cli: plugin too new for JitPack's Maven — FORCED", verdict.line());
    }

    @Test
    void aPluginPinnedByAPropertyIsSkippedRatherThanGuessedAt(@TempDir Path repo) {
        String pom = """
                <project><build><plugins>
                  <plugin><artifactId>maven-shade-plugin</artifactId><version>${shade.version}</version></plugin>
                  <plugin><artifactId>no-version-plugin</artifactId></plugin>
                </plugins></build></project>
                """;
        // Resolving it would mean interpolating this pom's properties and its parent's, which is a Maven
        // model reader's job; guessing wrong refuses a release over a number nobody pinned.
        assertTrue(MavenPrerequisite.read(pom, repo).isEmpty());
    }

    @Test
    void aPrerequisiteIsReadFromThePluginsOwnPomIncludingThroughAProperty(@TempDir Path repo)
            throws IOException {
        Path pom = repo.resolve("org/apache/maven/plugins/maven-shade-plugin/3.5.2");
        Files.createDirectories(pom);
        Files.writeString(pom.resolve("maven-shade-plugin-3.5.2.pom"), """
                <project>
                  <properties><mavenVersion>4.0.0</mavenVersion></properties>
                  <prerequisites><maven>${mavenVersion}</maven></prerequisites>
                </project>
                """);
        String module = """
                <project><build><plugins>
                  <plugin><artifactId>maven-shade-plugin</artifactId><version>3.5.2</version></plugin>
                </plugins></build></project>
                """;

        List<MavenPrerequisite.Pin> pins = MavenPrerequisite.read(module, repo);

        assertEquals(1, pins.size());
        assertEquals(Optional.of("4.0.0"), pins.get(0).needs());
        assertTrue(JitpackPluginsGate.verdict(Module.CLI, pins, false).stops());
    }

    @Test
    void studioAndThePilotAreNotBuiltByJitpackAndSoAreNotGated(@TempDir Path umbrella) {
        assertEquals(GateVerdict.Status.OK,
                JitpackPluginsGate.check(umbrella, Module.STUDIO, false, GatePlan.JITPACK_MAVEN, umbrella).status());
        assertEquals(GateVerdict.Status.OK,
                JitpackPluginsGate.check(umbrella, Module.PILOT, false, GatePlan.JITPACK_MAVEN, umbrella).status());
    }
}
