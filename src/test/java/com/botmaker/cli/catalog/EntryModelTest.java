package com.botmaker.cli.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ranks, publishers, entry files and the paths that name them. */
class EntryModelTest {

    @TempDir
    Path dir;

    @Test
    void an_unknown_rank_is_community() {
        assertEquals(Rank.COMMUNITY, Rank.fromId("nope"));
        assertEquals(Rank.COMMUNITY, Rank.fromId(null));
        assertEquals(Rank.ADMIN, Rank.fromId("ADMIN"));
        assertEquals(List.of("Official", "Trusted", "Community"),
                List.of(Rank.ADMIN.displayName(), Rank.TRUSTED.displayName(), Rank.COMMUNITY.displayName()));
    }

    @Test
    void publishers_rank_by_account_id_and_admin_wins() throws IOException {
        Path file = dir.resolve(Publishers.FILE);
        Files.writeString(file, """
                { "admin": [ { "id": 1, "login": "boss" } ],
                  "trusted": [ { "id": 1, "login": "boss" }, { "id": 2, "login": "dev" } ] }""");
        Publishers publishers = Publishers.read(file);
        assertEquals(Rank.ADMIN, publishers.rankOf(1));
        assertEquals(Rank.TRUSTED, publishers.rankOf(2));
        assertEquals(Rank.COMMUNITY, publishers.rankOf(3));
        assertEquals(Rank.COMMUNITY, Publishers.read(dir.resolve("missing.json")).rankOf(1));
    }

    @Test
    void a_bot_path_splits_at_the_first_double_underscore() {
        EntryPath path = EntryPath.parse("bots/a-b__c__d-e.json").orElseThrow();
        assertEquals(EntryPath.Kind.BOT, path.kind());
        assertEquals("a-b/c__d-e", path.key());
        assertEquals("bots/a-b__c__d-e.json", EntryPath.of(new BotEntry("a-b/c__d-e", "v1", false)));
    }

    @Test
    void only_entry_files_are_entry_paths() {
        for (String other : List.of("bots/a.json", "catalog.json", "publishers.json", ".github/workflows/x.yml",
                "bots/a__b.txt", "plugins/../x.json", "tools/x.json", "bots/a__...json", "bots/a__..json",
                "bots/a__x.git.json")) {
            assertEquals(Optional.empty(), EntryPath.parse(other), other);
        }
        assertEquals("com.example.x", EntryPath.parse("plugins/com.example.x.json").orElseThrow().key());
    }

    @Test
    void a_path_matches_only_the_entry_its_name_claims() {
        EntryPath bot = EntryPath.parse("bots/a__b.json").orElseThrow();
        assertTrue(bot.matches(new BotEntry("a/b", "v1", false)));
        assertFalse(bot.matches(new BotEntry("a/c", "v1", false)));
        assertFalse(bot.matches(new PluginEntry("a/b", "a/b", "v1")));
        assertTrue(EntryPath.parse("plugins/x.y.json").orElseThrow().matches(new PluginEntry("x.y", "o/r", "v1")));
    }

    @Test
    void an_entry_file_holds_exactly_its_schema() throws IOException {
        Path bot = dir.resolve("bot.json");
        Files.writeString(bot, "{ \"repo\": \"a/b\", \"tag\": \"v1\" }");
        assertEquals(new BotEntry("a/b", "v1", false), BotEntry.read(bot));
        Files.writeString(bot, "{ \"repo\": \"a/b\", \"tag\": \"v1\", \"name\": \"extra\" }");
        assertThrows(IOException.class, () -> BotEntry.read(bot));
    }

    @Test
    void detached_is_written_only_when_true() throws IOException {
        assertFalse(CatalogJson.strict().writeValueAsString(new BotEntry("a/b", "v1", false)).contains("detached"));
        assertTrue(CatalogJson.strict().writeValueAsString(new BotEntry("a/b", "v1", true)).contains("detached"));
    }
}
