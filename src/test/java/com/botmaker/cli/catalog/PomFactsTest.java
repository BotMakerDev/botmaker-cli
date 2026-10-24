package com.botmaker.cli.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomFactsTest {

    private static final PluginEntry THING = new PluginEntry("com.example.thing", "somedev/thing",
            List.of(FakeFacts.pluginRelease("v1")));
    private static final BotEntry BOT = new BotEntry("somedev/firestone", List.of(FakeFacts.botRelease("v1.2.0")),
            false);
    private static final Map<String, String> IDS = Map.of(PomFacts.key("com.github.somedev", "thing"), THING.id());

    @TempDir
    Path dir;

    @Test
    void a_plugin_pom_gives_its_name_contract_floor_and_editor_dependencies() {
        FakeFacts poms = new FakeFacts().pom("somedev/thing", "v1", """
                <project>
                  <name>${pretty}</name>
                  <properties><pretty>The Thing</pretty><contract>0.11.0</contract></properties>
                  <dependencies>
                    <dependency><groupId>com.github.BotMakerDev</groupId><artifactId>botmaker-studio-api</artifactId>
                      <version>${contract}</version><scope>provided</scope></dependency>
                    <dependency><groupId>io.javalin</groupId><artifactId>javalin</artifactId><version>6.7.0</version>
                      <optional>true</optional></dependency>
                    <dependency><groupId>org.openjfx</groupId><artifactId>javafx-controls</artifactId>
                      <version>25</version><optional>true</optional></dependency>
                    <dependency><groupId>com.google.zxing</groupId><artifactId>core</artifactId>
                      <version>${unknown}</version><optional>true</optional></dependency>
                  </dependencies>
                </project>
                """);
        DerivedFacts.PluginFacts facts = PomFacts.read(List.of(THING), poms, IDS).plugin(THING.id(), "v1");
        assertEquals("The Thing", facts.name());
        assertEquals("0.11.0", facts.minContractVersion());
        assertEquals(List.of("io.javalin:javalin:6.7.0"), facts.editorDependencies());
    }

    @Test
    void no_name_is_the_repository_name_and_an_unresolved_contract_is_missing() {
        FakeFacts poms = new FakeFacts().pom("somedev/thing", "v1", """
                <project><dependencies>
                  <dependency><groupId>com.github.BotMakerDev</groupId><artifactId>botmaker-studio-api</artifactId>
                    <version>${botmaker.studioapi.version}</version></dependency>
                </dependencies></project>
                """);
        DerivedFacts.PluginFacts facts = PomFacts.read(List.of(THING), poms, IDS).plugin(THING.id(), "v1");
        assertEquals("thing", facts.name());
        assertNull(facts.minContractVersion());
    }

    @Test
    void a_bot_pom_gives_its_catalog_plugin_pins_ignoring_case() {
        FakeFacts poms = new FakeFacts().pom("somedev/firestone", "v1.2.0", """
                <project><dependencies>
                  <dependency><groupId>com.github.SomeDev</groupId><artifactId>thing</artifactId><version>1.0</version></dependency>
                  <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0</version></dependency>
                </dependencies></project>
                """);
        assertEquals(List.of(new DerivedFacts.Pin(THING.id(), "1.0")),
                PomFacts.read(List.of(BOT), poms, IDS).bot(BOT.repo(), "v1.2.0"));
    }

    @Test
    void a_plugin_pom_gives_the_catalog_plugins_it_brings() {
        FakeFacts poms = new FakeFacts().pom("somedev/thing", "v1", """
                <project>
                  <properties><botmaker.basics.version>v0.5.0</botmaker.basics.version></properties>
                  <dependencies>
                    <dependency><groupId>com.github.BotMakerDev</groupId><artifactId>botmaker-plugin-basics</artifactId>
                      <version>${botmaker.basics.version}</version></dependency>
                    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0</version></dependency>
                  </dependencies>
                </project>
                """);
        Map<String, String> ids = Map.of(PomFacts.key("com.github.somedev", "thing"), THING.id(),
                PomFacts.key("com.github.BotMakerDev", "botmaker-plugin-basics"), "com.botmaker.basics");
        DerivedFacts.PluginFacts facts = PomFacts.read(List.of(THING), poms, ids).plugin(THING.id(), "v1");
        assertEquals(List.of(new DerivedFacts.Pin("com.botmaker.basics", "v0.5.0")), facts.plugins());
    }

    @Test
    void a_pom_with_a_doctype_or_none_at_all_gives_no_facts() {
        FakeFacts poms = new FakeFacts().pom("somedev/thing", "v1", """
                <?xml version="1.0"?>
                <!DOCTYPE project [<!ENTITY secret SYSTEM "file:///etc/hostname">]>
                <project><name>&secret;</name></project>
                """);
        DerivedFacts derived = PomFacts.read(List.of(THING, BOT), poms, IDS);
        assertTrue(derived.plugins().isEmpty());
        assertTrue(derived.botPlugins().isEmpty());
    }

    @Test
    void the_index_writes_the_catalog_once_and_leaves_it_when_nothing_changed() throws IOException {
        Files.createDirectories(dir.resolve("plugins"));
        Files.writeString(dir.resolve("plugins/com.example.thing.json"), FakeFacts.json(THING));
        FakeFacts facts = new FakeFacts().repo("somedev/thing", 2, "A thing.", null)
                .release("somedev/thing", "v1", "somedev")
                .pom("somedev/thing", "v1", "<project><name>Thing</name></project>");

        assertTrue(IndexCommand.write(dir, facts, "2026-10-23T18:00:00Z"));
        Catalog catalog = Catalog.read(dir.resolve(Catalog.FILE)).orElseThrow();
        assertEquals("Thing", catalog.plugins().getFirst().name());
        assertEquals(Rank.COMMUNITY, catalog.plugins().getFirst().rank());
        assertTrue(!IndexCommand.write(dir, facts, "2026-10-24T18:00:00Z"), "unchanged content, unchanged file");
    }
}
