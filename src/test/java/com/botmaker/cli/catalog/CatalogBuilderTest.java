package com.botmaker.cli.catalog;

import org.junit.jupiter.api.BeforeEach;
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

class CatalogBuilderTest {

    private static final String NOW = "2026-10-23T18:00:00Z";
    private static final String LATER = "2026-10-24T18:00:00Z";

    @TempDir
    Path root;

    private final Publishers publishers = new Publishers(List.of(new Publishers.Publisher(1, "BotMakerDev")),
            List.of(new Publishers.Publisher(2, "somedev")));
    private final FakeFacts facts = new FakeFacts();
    private DerivedFacts derived;

    @BeforeEach
    void entries() throws IOException {
        entry("plugins/com.botmaker.sdk.json", "{\"id\":\"com.botmaker.sdk\",\"repo\":\"BotMakerDev/botmaker-sdk\",\"tag\":\"v2.1.0\"}");
        entry("bots/somedev__firestone.json", "{\"repo\":\"somedev/firestone\",\"tag\":\"v1.2.0\"}");
        entry("bots/stranger__fork.json", "{\"repo\":\"stranger/fork\",\"tag\":\"v1\",\"detached\":true}");
        facts.repo("BotMakerDev/botmaker-sdk", 1, "The SDK.", null)
                .repo("somedev/firestone", 2, "Idle runs.", "LiQiyeDev/firestone")
                .repo("stranger/fork", 3, "", "somedev/firestone");
        derived = new DerivedFacts(
                Map.of("com.botmaker.sdk", new DerivedFacts.PluginFacts("BotMaker SDK", "v0.11.0", List.of())),
                Map.of("somedev/firestone", List.of(new DerivedFacts.Pin("com.botmaker.sdk", "2.1.0")),
                        "stranger/fork", List.of(new DerivedFacts.Pin("com.botmaker.sdk", "2.1.0"))));
    }

    @Test
    void rows_carry_the_owner_rank_and_derived_facts() throws IOException {
        Catalog catalog = CatalogBuilder.build(root, publishers, facts, derived, NOW);

        Catalog.Plugin sdk = catalog.plugins().getFirst();
        assertEquals(Rank.ADMIN, sdk.rank());
        assertEquals("com.github.BotMakerDev:botmaker-sdk", sdk.coordinate());
        assertEquals("BotMaker SDK", sdk.name());
        assertEquals(List.of("somedev/firestone", "stranger/fork"), catalog.bots().stream().map(Catalog.Bot::repo).toList());
        assertEquals(Rank.TRUSTED, catalog.bots().get(0).rank());
        assertEquals("LiQiyeDev/firestone", catalog.bots().get(0).forkOf());
        assertEquals(Rank.COMMUNITY, catalog.bots().get(1).rank());
        assertNull(catalog.bots().get(1).forkOf(), "a detached fork names no original");
        assertEquals(NOW, catalog.builtAt());
    }

    @Test
    void a_gone_repository_is_left_out() throws IOException {
        facts.repos.remove("somedev/firestone");
        Catalog catalog = CatalogBuilder.build(root, publishers, facts, derived, NOW);
        assertEquals(List.of("stranger/fork"), catalog.bots().stream().map(Catalog.Bot::repo).toList());
    }

    @Test
    void a_bot_whose_plugin_was_unlisted_drops_out() throws IOException {
        Files.delete(root.resolve("plugins/com.botmaker.sdk.json"));
        Catalog catalog = CatalogBuilder.build(root, publishers, facts, derived, NOW);
        assertTrue(catalog.plugins().isEmpty());
        assertTrue(catalog.bots().isEmpty());
    }

    @Test
    void a_failed_derive_keeps_the_previous_row_while_the_tag_holds() throws IOException {
        write(CatalogBuilder.build(root, publishers, facts, derived, NOW));
        DerivedFacts nothing = new DerivedFacts(Map.of(), Map.of());

        Catalog again = CatalogBuilder.build(root, publishers, facts, nothing, LATER);
        assertEquals("BotMaker SDK", again.plugins().getFirst().name());
        assertEquals(2, again.bots().size());

        entry("plugins/com.botmaker.sdk.json", "{\"id\":\"com.botmaker.sdk\",\"repo\":\"BotMakerDev/botmaker-sdk\",\"tag\":\"v2.2.0\"}");
        assertTrue(CatalogBuilder.build(root, publishers, facts, nothing, LATER).plugins().isEmpty(),
                "a new tag nobody derived is not listed under the old tag's facts");
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
    void a_file_with_a_missing_field_is_skipped_not_a_crash() throws IOException {
        entry("plugins/com.example.x.json", "{\"repo\":\"a/b\",\"tag\":\"v1\"}");
        entry("bots/a__b.json", "{}");
        Catalog catalog = CatalogBuilder.build(root, publishers, facts, derived, NOW);
        assertEquals(1, catalog.plugins().size());
        assertEquals(2, catalog.bots().size());
    }

    @Test
    void a_file_whose_name_lies_or_repeats_is_skipped() throws IOException {
        entry("plugins/a.json", "{\"id\":\"com.botmaker.sdk\",\"repo\":\"evil/x\",\"tag\":\"v1\"}");
        entry("bots/SomeDev__Firestone.json", "{\"repo\":\"SomeDev/Firestone\",\"tag\":\"v9\"}");
        facts.repo("evil/x", 9, "", null).repo("SomeDev/Firestone", 2, "", null);
        Catalog catalog = CatalogBuilder.build(root, publishers, facts, derived, NOW);
        assertEquals(List.of("BotMakerDev/botmaker-sdk"), catalog.plugins().stream().map(Catalog.Plugin::repo).toList());
        assertTrue(catalog.bots().stream().filter(b -> b.repo().equalsIgnoreCase("somedev/firestone")).count() <= 1,
                "one repository, whatever its case, is one row at most");
    }

    @Test
    void the_coordinate_follows_a_transfer_and_a_rename() throws IOException {
        facts.repo("BotMakerDev/botmaker-sdk", 7, "newowner/sdk", "The SDK.", null);
        Catalog.Plugin sdk = CatalogBuilder.build(root, publishers, facts, derived, NOW).plugins().getFirst();
        assertEquals("com.github.newowner:sdk", sdk.coordinate());
        assertEquals(Rank.COMMUNITY, sdk.rank());
    }

    @Test
    void the_json_reads_back_as_written() throws IOException {
        Catalog catalog = CatalogBuilder.build(root, publishers, facts, derived, NOW);
        write(catalog);
        assertEquals(catalog, Catalog.read(root.resolve(Catalog.FILE)).orElseThrow());
        assertTrue(catalog.toJson().contains("\"rank\" : \"admin\""));
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
