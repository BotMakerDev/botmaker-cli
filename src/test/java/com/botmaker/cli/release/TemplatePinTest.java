package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplatePinTest {

    /** The shape of the worked bot's pom, down to the two things that make the pattern non-trivial. */
    private static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <groupId>com.botmaker.gamebot</groupId>
                <artifactId>gamebot</artifactId>
                <version>0.0.1-SNAPSHOT</version>

                <properties>
                    <botmaker.sdk.version>1.1.9</botmaker.sdk.version>
                </properties>

                <dependencies>
                    <dependency>
                        <groupId>com.github.LiQiyeDev</groupId>
                        <artifactId>botmaker-sdk</artifactId>
                        <version>${botmaker.sdk.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>net.java.dev.jna</groupId>
                        <artifactId>jna</artifactId>
                        <version>5.13.0</version>
                    </dependency>
                </dependencies>
            </project>
            """;

    private static Runner real(List<String> log) {
        return new Runner(false, log::add);
    }

    private static Path template(Path umbrella) throws IOException {
        Path dir = umbrella.resolve(Module.GAMEBOT.directory());
        Files.createDirectories(dir);
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, POM);
        return pom;
    }

    @Test
    void theSdkPinMovesToTheVersionBeingCut(@TempDir Path umbrella) throws IOException {
        Path pom = template(umbrella);

        TemplatePin.bump(real(new ArrayList<>()), umbrella, Module.GAMEBOT,
                Map.of(Module.SDK, new Version(1, 1, 15)));

        String after = Files.readString(pom);
        assertTrue(after.contains("<botmaker.sdk.version>1.1.15</botmaker.sdk.version>"), after);
        // The dependency keeps naming the property: the umbrella's templates profile overrides it with -D.
        assertTrue(after.contains("<version>${botmaker.sdk.version}</version>"), after);
        assertFalse(after.contains("1.1.9"), "the old pin is still there");
        // Without the `v` the tag carries, which is the spelling the template's own pom already uses.
        assertFalse(after.contains("v1.1.15"));
    }

    @Test
    void theProjectsOwnVersionAndEveryOtherPinAreLeftAlone(@TempDir Path umbrella) throws IOException {
        Path pom = template(umbrella);

        TemplatePin.bump(real(new ArrayList<>()), umbrella, Module.GAMEBOT,
                Map.of(Module.SDK, new Version(1, 1, 15)));

        String after = Files.readString(pom);
        // The project's own version is the FIRST <version> in the file, which is what a bare <version>
        // pattern would have rewritten — the whole reason the pattern is anchored on the artifactId.
        assertTrue(after.contains("<version>0.0.1-SNAPSHOT</version>"), after);
        assertTrue(after.contains("<version>5.13.0</version>"), after);
    }

    @Test
    void aRunCuttingNoSdkTouchesNothing(@TempDir Path umbrella) throws IOException {
        Path pom = template(umbrella);

        // A template may be cut on its own — its own code changed, no SDK in the run — and then there is
        // no pin to move. Doing nothing is the answer, not refusing.
        TemplatePin.bump(real(new ArrayList<>()), umbrella, Module.GAMEBOT,
                Map.of(Module.STUDIO, new Version(1, 2, 0)));

        assertEquals(POM, Files.readString(pom));
    }

    @Test
    void aTemplateThatStoppedDeclaringTheSdkIsARefusal(@TempDir Path umbrella) throws IOException {
        Path dir = umbrella.resolve(Module.GAMEBOT.directory());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>gamebot</artifactId></project>\n");

        // Runner.replace throws when nothing matches, and that is the point: a release that believes it
        // moved a pin it did not move is the failure worth being loud about.
        assertThrows(ReleaseRefusal.class, () -> TemplatePin.bump(
                real(new ArrayList<>()), umbrella, Module.GAMEBOT,
                Map.of(Module.SDK, new Version(1, 1, 15))));
    }

    @Test
    void aDryRunEchoesTheEditAndPerformsNone(@TempDir Path umbrella) throws IOException {
        Path pom = template(umbrella);
        List<String> log = new ArrayList<>();

        TemplatePin.bump(new Runner(true, log::add), umbrella, Module.GAMEBOT,
                Map.of(Module.SDK, new Version(1, 1, 15)));

        assertEquals(POM, Files.readString(pom), "a dry run edited the pom");
        assertTrue(log.stream().anyMatch(line -> line.contains("sed -i -E") && line.contains("pom.xml")),
                log.toString());
    }

    @Test
    void theToolkitIsDeliberatelyNotPinnedHere() {
        // The SDK declares botmaker-plugin-toolkit itself, at compile and not optional, so it arrives
        // transitively at the version that SDK was built against. A template naming it again would pin it
        // by Maven's nearest-wins to whatever number the file said — which is how a template ended up
        // linked against a contract that had moved, dying on open with a bare class name.
        assertEquals(Map.of(Module.SDK, "botmaker.sdk.version"), TemplatePin.PINS);
    }
}
