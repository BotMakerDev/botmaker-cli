package com.botmaker.cli.gallery;

import com.botmaker.cli.registry.Registry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two generated files. {@code index.json} is what every Studio before 2026-09-16 reads, so the assertions
 * about it are the compatibility promise: vetted listings only, as a bare array, with the fields those readers
 * knew.
 */
class GalleryCatalogTest {

    @TempDir
    Path root;

    private void write(String path, String json) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    private void seed() throws IOException {
        // The four entries the gallery held on 2026-09-16, as they were: no schemaVersion anywhere.
        write("bots/LiQiyeDev-botmaker-base.json", """
                {"name": "base", "owner": "LiQiyeDev", "repo": "botmaker-base",
                 "description": "A blank bot project.", "tags": ["template"]}""");
        write("bots/LiQiyeDev-botmaker-gamebot.json", """
                {"name": "gamebot", "owner": "LiQiyeDev", "repo": "botmaker-gamebot",
                 "description": "A game bot.", "tags": ["game", "template", "vision"]}""");
        write("bots/LiQiyeDev-PushTest.json", """
                {"name": "PushTest", "owner": "LiQiyeDev", "repo": "PushTest", "description": "", "tags": []}""");
        write("bots/LiQiyeDev-Update.json", """
                {"name": "Update", "owner": "LiQiyeDev", "repo": "Update", "description": "", "tags": [],
                 "launchTargets": ["heroic"]}""");
        write("vetted/LiQiyeDev-botmaker-base.json", """
                {"schemaVersion": 1, "owner": "LiQiyeDev", "repo": "botmaker-base", "vettedVersion": "v0.1.0",
                 "vettedAt": "2026-09-16", "vettedBy": "LiQiyeDev"}""");
        write("vetted/liqiyedev-UPDATE.json", """
                {"owner": "liqiyedev", "repo": "UPDATE", "vettedVersion": "v2"}""");
    }

    @Test
    void a_vetted_record_makes_its_bot_vetted_and_everything_else_is_community() throws Exception {
        seed();
        GalleryCatalog catalog = GalleryCatalog.read(root);

        assertEquals(4, catalog.listings().size());
        assertEquals(Tier.VETTED, catalog.find("LiQiyeDev", "botmaker-base").orElseThrow().tier());
        assertEquals(Tier.COMMUNITY, catalog.find("LiQiyeDev", "botmaker-gamebot").orElseThrow().tier());
        assertEquals(Tier.VETTED, catalog.find("LiQiyeDev", "Update").orElseThrow().tier(),
                "owner and repo compare the way GitHub compares them — ignoring case");
        assertTrue(catalog.listings().stream().allMatch(
                l -> l.entry().schemaVersion() == GalleryEntry.CURRENT_SCHEMA), "read as the current schema");
    }

    @Test
    void the_catalog_carries_every_listing_with_its_tier_and_one_schema_number() throws Exception {
        seed();
        JsonNode catalog = Registry.mapper().readTree(GalleryCatalog.read(root).catalogJson());

        assertEquals(GalleryCatalog.CATALOG_SCHEMA, catalog.path("schemaVersion").asInt());
        assertEquals(4, catalog.path("bots").size());
        for (JsonNode bot : catalog.path("bots")) {
            assertFalse(bot.has("schemaVersion"), "one schema number, at the top: " + bot);
            assertTrue(bot.has("tier"), bot.toString());
        }
        // Sorted by filename, which is a byte order: upper case before lower.
        assertEquals("PushTest", catalog.path("bots").get(0).path("name").asText());
        assertEquals("community", catalog.path("bots").get(0).path("tier").asText());
        JsonNode base = catalog.path("bots").get(2);
        assertEquals("botmaker-base", base.path("repo").asText());
        assertEquals("vetted", base.path("tier").asText());
        assertEquals("v0.1.0", base.path("vettedVersion").asText());
    }

    @Test
    void the_legacy_index_is_the_vetted_set_in_the_shape_an_older_studio_parses() throws Exception {
        seed();
        JsonNode index = Registry.mapper().readTree(GalleryCatalog.read(root).legacyIndexJson());

        assertTrue(index.isArray(), "a bare array, as it always was");
        assertEquals(2, index.size(), "community listings are not shown to a reader that cannot label them");
        for (JsonNode bot : index) {
            assertTrue(bot.has("description") && bot.has("tags"), bot.toString());
            assertFalse(bot.has("tier") || bot.has("schemaVersion") || bot.has("vettedVersion"), bot.toString());
        }
        JsonNode update = index.get(0);
        assertEquals("Update", update.path("name").asText());
        assertEquals("heroic", update.path("launchTargets").get(0).asText(), "Studio's declaration survives");
        assertEquals("base", index.get(1).path("name").asText());
        assertFalse(index.get(1).has("launchTargets"), "absent when undeclared, which is how Studio reads it");
    }

    @Test
    void an_empty_gallery_is_still_valid_json() throws Exception {
        GalleryCatalog catalog = GalleryCatalog.read(root);
        assertEquals("[ ]", catalog.legacyIndexJson().trim());
        assertEquals(0, Registry.mapper().readTree(catalog.catalogJson()).path("bots").size());
    }

    @Test
    void an_orphaned_vetted_record_is_reported_not_listed() throws Exception {
        write("vetted/someone-gone.json", """
                {"owner": "someone", "repo": "gone", "vettedVersion": "v1"}""");
        GalleryCatalog catalog = GalleryCatalog.read(root);

        assertTrue(catalog.listings().isEmpty());
        assertEquals(1, catalog.problems().size());
    }

    /** An index silently missing one bot is a delisting nobody asked for. */
    @Test
    void a_file_that_does_not_parse_stops_the_build_and_names_itself() throws Exception {
        write("bots/broken.json", "{ not json");
        IOException e = assertThrows(IOException.class, () -> GalleryCatalog.read(root));
        assertTrue(e.getMessage().contains("bots/broken.json"), e.getMessage());
    }

    @Test
    void the_builder_writes_both_files() throws Exception {
        seed();
        assertEquals(0, CatalogBuilder.run(new String[]{root.toString()}));
        assertTrue(Files.isRegularFile(root.resolve(GalleryCatalog.CATALOG)));
        assertTrue(Files.isRegularFile(root.resolve(GalleryCatalog.INDEX)));
        assertEquals(2, CatalogBuilder.run(new String[]{}));
    }
}
