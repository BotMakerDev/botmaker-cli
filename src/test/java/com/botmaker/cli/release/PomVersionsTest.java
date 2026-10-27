package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PomVersionsTest {

    private static final String POM = """
            <project>
              <!-- a comment naming <version>9.9.9</version> is not the project's -->
              <groupId>com.github.BotMakerDev</groupId>
              <artifactId>botmaker-plugin-host</artifactId>
              <version>0.3.3-SNAPSHOT</version>
              <properties>
                <!-- studio-api's main version -->
                <botmaker.studioapi.version>0.4.3-SNAPSHOT</botmaker.studioapi.version>
              </properties>
              <dependencies>
                <dependency><artifactId>junit</artifactId><version>5.10.2</version></dependency>
              </dependencies>
            </project>
            """;

    @Test
    void theProjectVersionIsTheFirstOneOutsideAComment() {
        assertEquals(Optional.of("0.3.3-SNAPSHOT"), PomVersions.projectVersion(POM));
        assertEquals(Optional.of("0.4.3-SNAPSHOT"), PomVersions.property(POM, Module.STUDIO_API));
        assertEquals(Optional.empty(), PomVersions.property(POM, Module.SHARED));
    }

    @Test
    void aPomWithAParentIsReadForItsOwnVersionAndItsParentPin() {
        String pom = "<project><!-- <version>0.0.0</version> --><parent><groupId>com.github.BotMakerDev</groupId>"
                + "<artifactId>botmaker-plugin-parent</artifactId><version>1.0.0</version>"
                + "<relativePath>../botmaker-plugin-parent/pom.xml</relativePath></parent>"
                + "<artifactId>x</artifactId><version>2.0.0</version></project>";

        assertEquals(Optional.of("2.0.0"), PomVersions.projectVersion(pom));
        assertEquals(Optional.of("1.0.0"), PomVersions.property(pom, Module.PLUGIN_PARENT));
        // Only its own version follows the parent's: a parent that leaves none is not read as the project's.
        assertEquals(Optional.empty(), PomVersions.projectVersion(
                "<project><parent><version>1.0.0</version></parent><artifactId>x</artifactId></project>"));
        // A parent that is not the plugin parent pins nothing.
        assertEquals(Optional.empty(), PomVersions.property(pom.replace("botmaker-plugin-parent<", "other<"),
                Module.PLUGIN_PARENT));
        // A release moves the parent's version like a property: nothing else changed.
        assertTrue(PomVersions.versionsOnly(pom, pom.replace("1.0.0", "v1.1.0").replace("2.0.0", "2.0.1")));
        assertFalse(PomVersions.versionsOnly(pom, pom.replace("<artifactId>x", "<artifactId>y")));
    }

    @Test
    void aParentPinIsMovedAsItsVersionLiteral(@TempDir Path umbrella) throws Exception {
        writeOnParent(umbrella, Module.PLUGIN_ARCHETYPE, "0.5.1-SNAPSHOT", "0.1.0-SNAPSHOT");
        write(umbrella, Module.PLUGIN_PARENT, "0.1.0-SNAPSHOT", "");
        List<String> said = new ArrayList<>();

        PomVersions.release(new Runner(true, said::add), umbrella, Module.PLUGIN_ARCHETYPE, new Version(0, 5, 1),
                Map.of(Module.PLUGIN_PARENT, new Version(0, 1, 0)));

        assertTrue(said.stream().anyMatch(line -> line.contains("sed -i") && line.contains("v0.1.0")
                && line.contains("botmaker-plugin-parent")), said::toString);
        assertTrue(said.stream().noneMatch(line -> line.contains("-Dproperty=botmaker.pluginparent")), said::toString);

        // The pattern a real run applies: the parent's version and nothing else, whatever the whitespace.
        String pom = "<parent>\n  <artifactId> botmaker-plugin-parent </artifactId>\n  <version> 0.1.0-SNAPSHOT"
                + " </version>\n</parent><version>0.5.1-SNAPSHOT</version>";
        String moved = PomVersions.parentVersion(Module.PLUGIN_PARENT).matcher(pom)
                .replaceFirst("$1v0.1.0$2");
        assertEquals(Optional.of("v0.1.0"), PomVersions.property(moved, Module.PLUGIN_PARENT));
        assertEquals(Optional.of("0.5.1-SNAPSHOT"), PomVersions.projectVersion(moved));
    }

    @Test
    void theNextSnapshotIsTheNextPatch() {
        assertEquals("0.3.4-SNAPSHOT", PomVersions.nextSnapshot(new Version(0, 3, 3)));
        assertEquals("1.0.1-SNAPSHOT", PomVersions.nextSnapshot(new Version(1, 0, 0)));
    }

    @Test
    void aPomThatMovedOnlyVersionsIsNotAChange() {
        String released = POM.replace("0.3.3-SNAPSHOT", "0.3.3").replace(">0.4.3-SNAPSHOT<", ">0.4.2<");
        assertTrue(PomVersions.versionsOnly(released, POM), "a back-to-snapshot commit publishes nothing new");
        // A dependency's literal version is a real change, though it is spelled like the project's.
        assertFalse(PomVersions.versionsOnly(POM, POM.replace("5.10.2", "5.11.0")));
        assertFalse(PomVersions.versionsOnly(POM, POM.replace("</dependencies>",
                "<dependency><artifactId>x</artifactId></dependency></dependencies>")));
    }

    @Test
    void onlyAMavenModuleMovesItsVersion() {
        assertTrue(PomVersions.moves(Module.STUDIO));
        assertTrue(PomVersions.moves(Module.REMOTE_SERVER));
        assertFalse(PomVersions.moves(Module.PILOT));
    }

    /**
     * The table the release writes from against the poms it writes into: every {@link Module#upstreams()} pin
     * is a property the pom declares, and every {@code botmaker.<key>.version} naming a module is in the table.
     * Run against this checkout, so a pin added to a pom and not to the table fails here before a release.
     */
    @Test
    void everyPomOnMainIsWhereTheLastReleaseLeftIt() {
        Path umbrella = Path.of("..");
        assumeTrue(Files.isRegularFile(umbrella.resolve(".gitmodules")), "not run from the umbrella checkout");
        for (Module module : Module.values()) {
            if (!PomVersions.moves(module) || !Files.exists(PomVersions.pom(umbrella, module))) {
                continue;
            }
            GateVerdict verdict = VersionsGate.check(module, PomVersions.read(PomVersions.pom(umbrella, module)),
                    VersionsGate.upstreamVersions(umbrella, module), false);
            assertEquals(GateVerdict.Status.OK, verdict.status(), module + ": " + verdict.refusal());
        }
    }

    @Test
    void syncPinsMovesExactlyThePinsTheGateRefuses(@TempDir Path umbrella) throws Exception {
        // 2026-10-07: cli's push failed after its back-to-snapshot, and the dashboard kept cli 0.2.1-SNAPSHOT.
        write(umbrella, Module.STUDIO_API, "0.5.1-SNAPSHOT", "");
        write(umbrella, Module.PLUGIN_HOST, "0.4.1-SNAPSHOT", "<botmaker.studioapi.version>0.5.1-SNAPSHOT"
                + "</botmaker.studioapi.version>");
        write(umbrella, Module.PLUGIN_TOOLKIT, "0.4.2-SNAPSHOT", "<botmaker.studioapi.version>0.5.1-SNAPSHOT"
                + "</botmaker.studioapi.version>");
        write(umbrella, Module.PLUGIN_PARENT, "0.1.1-SNAPSHOT", "<botmaker.studioapi.version>0.5.1-SNAPSHOT"
                + "</botmaker.studioapi.version><botmaker.plugintoolkit.version>0.4.2-SNAPSHOT"
                + "</botmaker.plugintoolkit.version>");
        writeOnParent(umbrella, Module.PLUGIN_ARCHETYPE, "0.2.1-SNAPSHOT", "0.1.1-SNAPSHOT");
        write(umbrella, Module.SHARED, "0.1.3-SNAPSHOT", "");
        write(umbrella, Module.CLI, "0.2.2-SNAPSHOT", "<botmaker.studioapi.version>0.5.1-SNAPSHOT"
                + "</botmaker.studioapi.version><botmaker.pluginhost.version>0.4.1-SNAPSHOT"
                + "</botmaker.pluginhost.version><botmaker.pluginarchetype.version>0.2.1-SNAPSHOT"
                + "</botmaker.pluginarchetype.version>");
        write(umbrella, Module.DASHBOARD, "0.2.1-SNAPSHOT", "<botmaker.shared.version>0.1.3-SNAPSHOT"
                + "</botmaker.shared.version><botmaker.cli.version>0.2.1-SNAPSHOT</botmaker.cli.version>");
        List<String> said = new ArrayList<>();

        PomVersions.Synced synced = PomVersions.syncPins(new Runner(true, said::add), umbrella);

        assertEquals(List.of(Module.DASHBOARD), synced.moved());
        assertEquals(0, synced.left());
        assertTrue(said.stream().anyMatch(line -> line.contains("-Dproperty=botmaker.cli.version")
                && line.contains("-DnewVersion=0.2.2-SNAPSHOT")), said.toString());
        assertTrue(said.stream().anyMatch(line -> line.contains("botmaker-dashboard")
                && line.endsWith("commit -m 'pin cli 0.2.2-SNAPSHOT' -- pom.xml")), said.toString());
        assertTrue(said.stream().noneMatch(line -> line.contains("botmaker.shared.version")), said.toString());
    }

    private static void write(Path umbrella, Module module, String version, String pins) throws Exception {
        Path dir = Files.createDirectories(umbrella.resolve(module.directory()));
        Files.writeString(dir.resolve("pom.xml"), "<project><version>" + version + "</version><properties>"
                + pins + "</properties></project>");
    }

    /** A plugin's pom, pinning the plugin parent as its {@code <parent>}. */
    private static void writeOnParent(Path umbrella, Module module, String version, String parent) throws Exception {
        Path dir = Files.createDirectories(umbrella.resolve(module.directory()));
        Files.writeString(dir.resolve("pom.xml"), "<project><parent><groupId>com.github.BotMakerDev</groupId>"
                + "<artifactId>botmaker-plugin-parent</artifactId><version>" + parent + "</version></parent>"
                + "<version>" + version + "</version></project>");
    }
}
