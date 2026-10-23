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
        assertEquals("bots/a-b__c__d-e.json", EntryPath.of(bot("a-b/c__d-e", false)));
        assertEquals("bots/a-b__c__d-e.json", EntryPath.bot("a-b/c__d-e"));
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
        assertTrue(bot.matches(bot("a/b", false)));
        assertFalse(bot.matches(bot("a/c", false)));
        assertFalse(bot.matches(plugin("a/b", "a/b")));
        assertTrue(EntryPath.parse("plugins/x.y.json").orElseThrow().matches(plugin("x.y", "o/r")));
    }

    @Test
    void an_entry_file_holds_exactly_its_schema() throws IOException {
        Path bot = dir.resolve("bot.json");
        String release = "{ \"tag\": \"v1\", \"commit\": \"" + FakeFacts.commitOf("v1") + "\" }";
        Files.writeString(bot, "{ \"repo\": \"a/b\", \"releases\": [" + release + "] }");
        assertEquals(bot("a/b", false), BotEntry.read(bot));
        Files.writeString(bot, "{ \"repo\": \"a/b\", \"releases\": [" + release + "], \"name\": \"extra\" }");
        assertThrows(IOException.class, () -> BotEntry.read(bot));
        Files.writeString(bot, "{ \"repo\": \"a/b\", \"tag\": \"v1\" }");
        assertThrows(IOException.class, () -> BotEntry.read(bot), "the single tag of schema 3 is not read");
        Files.writeString(bot, "{ \"repo\": \"a/b\", \"releases\": [] }");
        assertThrows(IOException.class, () -> BotEntry.read(bot), "an entry approves at least one release");
    }

    @Test
    void a_plugin_release_carries_both_hashes_and_a_bot_release_none() {
        ApprovedRelease bare = ApprovedRelease.ofBot("v1", FakeFacts.commitOf("v1"));
        assertThrows(IllegalArgumentException.class, () -> new PluginEntry("x.y", "o/r", List.of(bare)));
        assertThrows(IllegalArgumentException.class, () -> new BotEntry("o/r",
                List.of(FakeFacts.pluginRelease("v1")), false));
        ApprovedRelease half = new ApprovedRelease("v1", FakeFacts.commitOf("v1"), FakeFacts.shaOf("jar"), null);
        assertThrows(IllegalArgumentException.class, () -> new PluginEntry("x.y", "o/r", List.of(half)));
    }

    @Test
    void appended_keeps_the_order_and_newest_is_the_last() {
        BotEntry entry = new BotEntry("a/b", List.of(FakeFacts.botRelease("v1")), true);
        Entry more = entry.appended(FakeFacts.botRelease("v2"));
        assertEquals(List.of("v1", "v2"), more.releases().stream().map(ApprovedRelease::tag).toList());
        assertEquals("v2", more.newest().tag());
        assertTrue(((BotEntry) more).detached(), "detached is kept");
    }

    @Test
    void detached_is_written_only_when_true() throws IOException {
        assertFalse(CatalogJson.strict().writeValueAsString(bot("a/b", false)).contains("detached"));
        assertTrue(CatalogJson.strict().writeValueAsString(bot("a/b", true)).contains("detached"));
        assertFalse(CatalogJson.strict().writeValueAsString(bot("a/b", false)).contains("Sha256"),
                "a bot release writes no hashes");
    }

    private static BotEntry bot(String repo, boolean detached) {
        return new BotEntry(repo, List.of(FakeFacts.botRelease("v1")), detached);
    }

    private static PluginEntry plugin(String id, String repo) {
        return new PluginEntry(id, repo, List.of(FakeFacts.pluginRelease("v1")));
    }
}
