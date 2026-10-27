package com.botmaker.cli.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.botmaker.cli.catalog.FakeFacts.botJson;
import static com.botmaker.cli.catalog.FakeFacts.commitOf;
import static com.botmaker.cli.catalog.FakeFacts.pluginJson;
import static com.botmaker.cli.catalog.FakeFacts.shaOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogBuilderTest {

    private static final String NOW = "2026-10-23T18:00:00Z";
    private static final String LATER = "2026-10-24T18:00:00Z";
    private static final String SDK = "plugins/BotMakerDev__botmaker-sdk.json";
    private static final String SDK_REPO = "BotMakerDev/botmaker-sdk";
    private static final DerivedFacts.PluginFacts SDK_FACTS =
            new DerivedFacts.PluginFacts("BotMaker SDK", "v0.11.0", List.of());

    @TempDir
    Path root;

    private final Publishers publishers = new Publishers(List.of(new Publishers.Publisher(1, "BotMakerDev")),
            List.of(new Publishers.Publisher(2, "somedev")));
    private final FakeFacts facts = new FakeFacts();
    private DerivedFacts derived;

    @BeforeEach
    void entries() throws IOException {
        entry(SDK, pluginJson(SDK_REPO, "v2.1.0"));
        entry("bots/somedev__firestone.json", botJson("somedev/firestone", "v1.2.0"));
        entry("bots/stranger__fork.json", FakeFacts.json(new BotEntry("stranger/fork",
                List.of(FakeFacts.botRelease("v1")), true)));
        facts.repo(SDK_REPO, 1, "The SDK.", null).release(SDK_REPO, "v2.1.0", "LiQiyeDev")
                .repo("somedev/firestone", 2, "Idle runs.", "LiQiyeDev/firestone")
                .release("somedev/firestone", "v1.2.0", "somedev")
                .repo("stranger/fork", 3, "", "somedev/firestone").release("stranger/fork", "v1", "stranger");
        List<DerivedFacts.Pin> pins = List.of(new DerivedFacts.Pin(SDK_REPO, "2.1.0"));
        derived = new DerivedFacts(Map.of(SDK_REPO, Map.of("v2.1.0", SDK_FACTS)),
                Map.of("somedev/firestone", Map.of("v1.2.0", pins), "stranger/fork", Map.of("v1", pins)));
    }

    @Test
    void rows_carry_the_owner_rank_pins_and_derived_facts() throws IOException {
        Catalog catalog = CatalogBuilder.build(root, publishers, facts, derived, NOW);

        Catalog.Plugin sdk = catalog.plugins().getFirst();
        assertEquals(Rank.ADMIN, sdk.rank());
        assertEquals("com.github.BotMakerDev:botmaker-sdk", sdk.coordinate());
        assertEquals("BotMaker SDK", sdk.name());
        assertEquals(new Catalog.Plugin.Release("v2.1.0", commitOf("v2.1.0"), shaOf("jar:v2.1.0"),
                shaOf("pom:v2.1.0"), "v0.11.0", List.of()), sdk.newest());
        assertEquals(List.of("somedev/firestone", "stranger/fork"), catalog.bots().stream().map(Catalog.Bot::repo).toList());
        assertEquals(Rank.TRUSTED, catalog.bots().get(0).rank());
        assertEquals(commitOf("v1.2.0"), catalog.bots().get(0).newest().commit());
        assertEquals("LiQiyeDev/firestone", catalog.bots().get(0).forkOf());
        assertEquals(Rank.COMMUNITY, catalog.bots().get(1).rank());
        assertNull(catalog.bots().get(1).forkOf(), "a detached fork names no original");
        assertEquals(NOW, catalog.builtAt());
    }

    @Test
    void every_approved_release_is_listed_newest_first_with_its_own_facts() throws IOException {
        facts.release(SDK_REPO, "v2.2.0", "LiQiyeDev");
        entry(SDK, pluginJson(SDK_REPO, "v2.1.0", "v2.2.0"));
        DerivedFacts two = new DerivedFacts(Map.of(SDK_REPO, Map.of("v2.1.0", SDK_FACTS,
                "v2.2.0", new DerivedFacts.PluginFacts("The SDK", "v0.12.0", List.of()))), derived.botPlugins());
        Catalog.Plugin sdk = CatalogBuilder.build(root, publishers, facts, two, NOW).plugins().getFirst();
        assertEquals(List.of("v2.2.0", "v2.1.0"), sdk.releases().stream().map(Catalog.Plugin.Release::tag).toList());
        assertEquals("The SDK", sdk.name(), "the newest release's name");
        assertEquals(List.of("v0.12.0", "v0.11.0"),
                sdk.releases().stream().map(Catalog.Plugin.Release::minContractVersion).toList());
    }

    @Test
    void a_plugin_release_keeps_only_the_other_catalog_plugins_it_brings() throws IOException {
        String basicsRepo = "BotMakerDev/botmaker-plugin-basics";
        entry(EntryPath.plugin(basicsRepo), pluginJson(basicsRepo, "v0.5.0"));
        facts.repo(basicsRepo, 1, "Basics.", null).release(basicsRepo, "v0.5.0", "LiQiyeDev");
        DerivedFacts.Pin basics = new DerivedFacts.Pin(basicsRepo, "v0.5.0");
        DerivedFacts.PluginFacts sdk = new DerivedFacts.PluginFacts("BotMaker SDK", "v0.11.0", List.of(),
                List.of(new DerivedFacts.Pin(SDK_REPO, "v2.1.0"), new DerivedFacts.Pin("no/such", "v1"),
                        new DerivedFacts.Pin(basicsRepo, "${unresolved}"), basics));
        DerivedFacts withPins = new DerivedFacts(Map.of(SDK_REPO, Map.of("v2.1.0", sdk),
                basicsRepo, Map.of("v0.5.0", new DerivedFacts.PluginFacts("BotMaker Basics", "v0.11.0", List.of()))),
                derived.botPlugins());
        Catalog catalog = CatalogBuilder.build(root, publishers, facts, withPins, NOW);
        Catalog.Plugin built = catalog.plugins().stream().filter(p -> p.repo().equals(SDK_REPO)).findFirst().orElseThrow();
        assertEquals(List.of(basics), built.newest().plugins(),
                "itself, an unlisted plugin and a version of another shape are left out");
    }

    @Test
    void a_release_gone_from_github_is_left_out_and_an_entry_with_none_left_too() throws IOException {
        facts.release(SDK_REPO, "v2.2.0", "LiQiyeDev");
        entry(SDK, pluginJson(SDK_REPO, "v2.1.0", "v2.2.0"));
        DerivedFacts two = new DerivedFacts(Map.of(SDK_REPO, Map.of("v2.1.0", SDK_FACTS, "v2.2.0", SDK_FACTS)),
                derived.botPlugins());
        facts.releases.remove(SDK_REPO + "@v2.1.0");
        assertEquals(List.of("v2.2.0"), CatalogBuilder.build(root, publishers, facts, two, NOW).plugins()
                .getFirst().releases().stream().map(Catalog.Plugin.Release::tag).toList());
        facts.releases.put(SDK_REPO + "@v2.2.0", new EntryFacts.Release("LiQiyeDev", true, false));
        assertTrue(CatalogBuilder.build(root, publishers, facts, two, NOW).plugins().isEmpty(),
                "a release turned into a draft is not listed either");
    }

    @Test
    void a_gone_repository_is_left_out() throws IOException {
        facts.repos.remove("somedev/firestone");
        Catalog catalog = CatalogBuilder.build(root, publishers, facts, derived, NOW);
        assertEquals(List.of("stranger/fork"), catalog.bots().stream().map(Catalog.Bot::repo).toList());
    }

    @Test
    void a_bot_whose_plugin_was_unlisted_drops_out() throws IOException {
        Files.delete(root.resolve(SDK));
        Catalog catalog = CatalogBuilder.build(root, publishers, facts, derived, NOW);
        assertTrue(catalog.plugins().isEmpty());
        assertTrue(catalog.bots().isEmpty());
    }

    @Test
    void a_failed_derive_keeps_the_previous_facts_while_the_pin_holds() throws IOException {
        write(CatalogBuilder.build(root, publishers, facts, derived, NOW));
        DerivedFacts nothing = new DerivedFacts(Map.of(), Map.of());

        Catalog again = CatalogBuilder.build(root, publishers, facts, nothing, LATER);
        assertEquals("BotMaker SDK", again.plugins().getFirst().name());
        assertEquals(2, again.bots().size());

        facts.release(SDK_REPO, "v2.2.0", "LiQiyeDev");
        entry(SDK, pluginJson(SDK_REPO, "v2.1.0", "v2.2.0"));
        assertEquals(List.of("v2.1.0"), CatalogBuilder.build(root, publishers, facts, nothing, LATER).plugins()
                .getFirst().releases().stream().map(Catalog.Plugin.Release::tag).toList(),
                "a new release nobody derived is not listed under another release's facts");
    }

    @Test
    void built_at_moves_only_when_the_content_changed() throws IOException {
        Catalog first = CatalogBuilder.build(root, publishers, facts, derived, NOW);
        write(first);
        Catalog same = CatalogBuilder.build(root, publishers, facts, derived, LATER);
        assertEquals(first.toJson(), same.toJson());

        facts.repo("somedev/firestone", 2, "Idle runs, faster.", "LiQiyeDev/firestone");
        assertEquals(LATER, CatalogBuilder.build(root, publishers, facts, derived, LATER).builtAt());
    }

    @Test
    void a_file_with_a_missing_field_or_the_old_single_tag_is_skipped_not_a_crash() throws IOException {
        entry("plugins/a__b.json", "{\"repo\":\"a/b\",\"tag\":\"v1\"}");
        // A plugin's id went on 2026-10-27; an entry still holding one does not read.
        entry("plugins/c__d.json", "{\"id\":\"c.d\"," + pluginJson("c/d", "v1").substring(1));
        facts.repo("c/d", 1, "", null).release("c/d", "v1", "c");
        entry("bots/a__b.json", "{}");
        Catalog catalog = CatalogBuilder.build(root, publishers, facts, derived, NOW);
        assertEquals(1, catalog.plugins().size());
        assertEquals(2, catalog.bots().size());
    }

    @Test
    void a_file_whose_name_lies_or_repeats_is_skipped() throws IOException {
        entry("plugins/a.json", pluginJson("evil/x", "v1"));
        entry("bots/SomeDev__Firestone.json", botJson("SomeDev/Firestone", "v9"));
        facts.repo("evil/x", 9, "", null).repo("SomeDev/Firestone", 2, "", null);
        Catalog catalog = CatalogBuilder.build(root, publishers, facts, derived, NOW);
        assertEquals(List.of(SDK_REPO), catalog.plugins().stream().map(Catalog.Plugin::repo).toList());
        assertTrue(catalog.bots().stream().filter(b -> b.repo().equalsIgnoreCase("somedev/firestone")).count() <= 1,
                "one repository, whatever its case, is one row at most");
    }

    @Test
    void the_coordinate_follows_a_transfer_and_a_rename() throws IOException {
        facts.repo(SDK_REPO, 7, "newowner/sdk", "The SDK.", null);
        Catalog.Plugin sdk = CatalogBuilder.build(root, publishers, facts, derived, NOW).plugins().getFirst();
        assertEquals("com.github.newowner:sdk", sdk.coordinate());
        assertEquals(Rank.COMMUNITY, sdk.rank());
    }

    @Test
    void derived_facts_are_checked_again() throws IOException {
        DerivedFacts bad = new DerivedFacts(Map.of(SDK_REPO, Map.of("v2.1.0", new DerivedFacts.PluginFacts(
                "BotMaker SDK", "v0.11.0", List.of("com.github.BotMakerDev:botmaker-studio-api:0.11.0")))),
                derived.botPlugins());
        Catalog catalog = CatalogBuilder.build(root, publishers, facts, bad, NOW);
        assertTrue(catalog.plugins().isEmpty(), "an editor dependency a bot's pom must never declare");
        assertTrue(catalog.bots().isEmpty());

        DerivedFacts snapshot = new DerivedFacts(derived.plugins(), Map.of("somedev/firestone",
                Map.of("v1.2.0", List.of(new DerivedFacts.Pin(SDK_REPO, "2.2.0-SNAPSHOT")))));
        assertEquals(List.of(), CatalogBuilder.build(root, publishers, facts, snapshot, NOW).bots());
    }

    @Test
    void a_symbolic_link_is_not_read() throws IOException {
        Path secret = Files.writeString(root.resolve("secret.txt"), botJson("a/b", "v1"));
        Files.createSymbolicLink(root.resolve("bots/a__b.json"), secret);
        facts.repo("a/b", 5, "", null).release("a/b", "v1", "a");
        assertTrue(CatalogBuilder.build(root, publishers, facts, derived, NOW).bots().stream()
                .noneMatch(b -> b.repo().equals("a/b")));
    }

    @Test
    void a_previous_row_is_reused_only_for_the_same_repository() throws IOException {
        write(CatalogBuilder.build(root, publishers, facts, derived, NOW));
        entry(SDK, pluginJson("BotMakerDev/other", "v2.1.0"));
        facts.repo("BotMakerDev/other", 1, "", null).release("BotMakerDev/other", "v2.1.0", "LiQiyeDev");
        assertTrue(CatalogBuilder.build(root, publishers, facts, new DerivedFacts(Map.of(), Map.of()), LATER)
                .plugins().isEmpty());
    }

    @Test
    void the_json_reads_back_as_written() throws IOException {
        Catalog catalog = CatalogBuilder.build(root, publishers, facts, derived, NOW);
        write(catalog);
        assertEquals(catalog, Catalog.read(root.resolve(Catalog.FILE)).orElseThrow());
        assertTrue(catalog.toJson().contains("\"rank\" : \"admin\""));
        assertTrue(catalog.toJson().contains("\"schemaVersion\" : 5"));
        assertFalse(catalog.toJson().contains("\"id\""), "a plugin is named by its repo since 2026-10-27");
    }

    private void write(Catalog catalog) throws IOException {
        Files.writeString(root.resolve(Catalog.FILE), catalog.toJson());
    }

    private void entry(String path, String json) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }
}
