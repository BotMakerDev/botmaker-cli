package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ArchetypePinTest {

    /** The descriptor's shape: a comment between properties, and the default on the line after the key. */
    private static final String DESCRIPTOR = """
            <archetype-descriptor>
                <requiredProperties>
                    <requiredProperty key="pluginId">
                        <defaultValue>${groupId}</defaultValue>
                    </requiredProperty>
                    <!-- the two BotMaker versions -->
                    <requiredProperty key="studioApiVersion">
                        <defaultValue>v0.2.2</defaultValue>
                    </requiredProperty>
                    <requiredProperty key="toolkitVersion">
                        <defaultValue>v0.1.9</defaultValue>
                    </requiredProperty>
                </requiredProperties>
            </archetype-descriptor>
            """;

    private static Path descriptor(Path umbrella, String text) throws IOException {
        Path file = umbrella.resolve(Module.PLUGIN_ARCHETYPE.directory()).resolve(ArchetypePin.DESCRIPTOR);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    @Test
    void bothDefaultsMoveToTheTagsThisRunCuts(@TempDir Path umbrella) throws IOException {
        Path file = descriptor(umbrella, DESCRIPTOR);

        ArchetypePin.bump(new Runner(false, line -> { }), umbrella, Map.of(
                Module.STUDIO_API, new Version(0, 4, 0),
                Module.PLUGIN_TOOLKIT, new Version(0, 3, 0)));

        String after = Files.readString(file);
        assertTrue(after.contains("<defaultValue>v0.4.0</defaultValue>"), after);
        assertTrue(after.contains("<defaultValue>v0.3.0</defaultValue>"), after);
        // A default that is not a BotMaker version is left alone.
        assertTrue(after.contains("<defaultValue>${groupId}</defaultValue>"), after);
    }

    @Test
    void aDescriptorThatLostAKeyIsARefusal(@TempDir Path umbrella) throws IOException {
        descriptor(umbrella, DESCRIPTOR.replace("toolkitVersion", "somethingElse"));

        assertThrows(ReleaseRefusal.class, () -> ArchetypePin.bump(new Runner(false, line -> { }), umbrella,
                Map.of(Module.STUDIO_API, new Version(0, 4, 0), Module.PLUGIN_TOOLKIT, new Version(0, 3, 0))));
    }

    @Test
    void aDryRunEchoesTheEditAndPerformsNone(@TempDir Path umbrella) throws IOException {
        Path file = descriptor(umbrella, DESCRIPTOR);
        var log = new ArrayList<String>();

        ArchetypePin.bump(new Runner(true, log::add), umbrella, Map.of(
                Module.STUDIO_API, new Version(0, 4, 0),
                Module.PLUGIN_TOOLKIT, new Version(0, 3, 0)));

        assertEquals(DESCRIPTOR, Files.readString(file));
        assertEquals(2, log.stream().filter(line -> line.contains("archetype-metadata.xml")).count(), log::toString);
    }

    /**
     * The real descriptor still has both anchors, and its defaults are released tags. A {@code main-SNAPSHOT}
     * default builds against a contract no released Studio has, and the plugin is refused at load.
     */
    @Test
    void theShippedDefaultsAreTagsThePatternsFind() throws IOException {
        Path real = Path.of("..", Module.PLUGIN_ARCHETYPE.directory(), ArchetypePin.DESCRIPTOR);
        assumeTrue(Files.isRegularFile(real), "not run from the umbrella checkout");
        String text = Files.readString(real);
        for (String key : ArchetypePin.PINS.values()) {
            Matcher match = ArchetypePin.declaration(key).matcher(text);
            assertTrue(match.find(), key + " has no default the release can move");
            String value = text.substring(match.end(1), match.start(2));
            assertTrue(value.matches("v\\d+\\.\\d+\\.\\d+"), key + " defaults to " + value + ", not a tag");
        }
    }
}
