package com.botmaker.cli.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.botmaker.cli.catalog.FakeFacts.botJson;
import static com.botmaker.cli.catalog.FakeFacts.botRelease;
import static com.botmaker.cli.catalog.FakeFacts.commitOf;
import static com.botmaker.cli.catalog.FakeFacts.json;
import static com.botmaker.cli.catalog.FakeFacts.pluginJson;
import static com.botmaker.cli.catalog.FakeFacts.pluginRelease;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntryGateTest {

    private static final String SDK = "plugins/BotMakerDev__botmaker-sdk.json";
    private static final String SDK_REPO = "BotMakerDev/botmaker-sdk";
    private static final String BOT = "bots/somedev__firestone.json";
    private static final long ADMIN_ID = 330995542;

    @TempDir
    Path head;
    @TempDir
    Path base;

    private final FakeFacts facts = new FakeFacts();

    @BeforeEach
    void catalog() throws IOException {
        write(base, SDK, pluginJson(SDK_REPO, "v2.1.0"));
        write(head, SDK, pluginJson(SDK_REPO, "v2.1.0"));
        facts.repo(SDK_REPO, 1, "The SDK.", null)
                .release(SDK_REPO, "v2.1.0", "LiQiyeDev")
                .repo("somedev/firestone", 2, "Idle runs.", null)
                .release("somedev/firestone", "v1.2.0", "somedev")
                .repo("mallory/sdk", 9, "", null)
                .release("mallory/sdk", "v1", "mallory");
    }

    @Test
    void a_new_bot_by_the_releases_author_passes() throws IOException {
        write(head, BOT, botJson("somedev/firestone", "v1.2.0"));
        assertEquals(List.of(), check("somedev", BOT));
        assertEquals(List.of(), check("SomeDev", BOT), "GitHub logins ignore case");
    }

    @Test
    void two_paths_or_a_path_that_is_no_entry_are_refused() throws IOException {
        write(head, BOT, botJson("somedev/firestone", "v1.2.0"));
        assertRefused(check("somedev", BOT, "README.md"), "exactly one");
        assertRefused(check("somedev", ".github/workflows/index.yml"), "not an entry path");
        assertRefused(check("somedev", "catalog.json"), "not an entry path");
    }

    @Test
    void a_file_whose_name_is_not_its_content_is_refused() throws IOException {
        write(head, "bots/somedev__other.json", botJson("somedev/firestone", "v1.2.0"));
        assertRefused(check("somedev", "bots/somedev__other.json"), "rename it to " + BOT);
    }

    @Test
    void a_field_outside_the_schema_or_a_tag_twice_is_refused() throws IOException {
        write(head, BOT, botJson("somedev/firestone", "v1.2.0").replaceFirst("}\\s*$", ", \"rank\": \"admin\" }"));
        assertRefused(check("somedev", BOT), "schema");
        write(head, BOT, "{\"repo\":\"somedev/firestone\",\"releases\":[" + json(botRelease("v1.2.0")) + ","
                + json(botRelease("v1.2.0")) + "]}");
        assertRefused(check("somedev", BOT), "no tag twice");
        write(head, BOT, "{\"repo\":\"somedev/firestone\",\"releases\":[" + json(pluginRelease("v1.2.0")) + "]}");
        assertRefused(check("somedev", BOT), "schema");
    }

    @Test
    void a_release_made_by_someone_else_is_refused() throws IOException {
        write(head, BOT, botJson("somedev/firestone", "v1.2.0"));
        assertRefused(check("mallory", BOT), "made by somedev, not mallory");
    }

    @Test
    void an_actions_release_passes_for_the_owner_of_a_user_repository() throws IOException {
        facts.release("somedev/firestone", "v1.2.0", EntryGate.ACTIONS_BOT);
        write(head, BOT, botJson("somedev/firestone", "v1.2.0"));
        assertEquals(List.of(), check("somedev", BOT));
    }

    @Test
    void an_actions_release_of_an_organisation_is_refused_with_the_way_out() throws IOException {
        facts.repo("SomeOrg/bot", 7, "", null).release("SomeOrg/bot", "v1", EntryGate.ACTIONS_BOT);
        write(head, "bots/SomeOrg__bot.json", botJson("SomeOrg/bot", "v1"));
        assertRefused(check("somedev", "bots/SomeOrg__bot.json"), "from Studio or the CLI");
    }

    @Test
    void a_listed_entry_keeps_its_repository() throws IOException {
        // The file is named by the repository, a plugin's as a bot's (2026-10-27): another one is another file.
        write(head, SDK, pluginJson("mallory/sdk", "v1"));
        assertRefused(check("mallory", SDK), "rename it to plugins/mallory__sdk.json");
    }

    @Test
    void the_controller_appends_a_release() throws IOException {
        facts.release(SDK_REPO, "v2.2.0", "LiQiyeDev");
        write(head, SDK, pluginJson(SDK_REPO, "v2.1.0", "v2.2.0"));
        assertEquals(List.of(), check("LiQiyeDev", SDK));
    }

    @Test
    void someone_else_cannot_append_to_an_entry() throws IOException {
        facts.release(SDK_REPO, "v2.2.0", "mallory");
        write(head, SDK, pluginJson(SDK_REPO, "v2.1.0", "v2.2.0"));
        assertRefused(check("mallory", SDK), "only they may change it");
    }

    @Test
    void an_append_that_also_edits_an_approved_release_is_refused_naming_it() throws IOException {
        facts.release(SDK_REPO, "v2.2.0", "LiQiyeDev");
        ApprovedRelease moved = new ApprovedRelease("v2.1.0", commitOf("elsewhere"),
                pluginRelease("v2.1.0").jarSha256(), pluginRelease("v2.1.0").pomSha256());
        write(head, SDK, json(new PluginEntry(SDK_REPO, List.of(moved, pluginRelease("v2.2.0")))));
        assertRefused(check("LiQiyeDev", SDK), "release v2.1.0 was edited");
    }

    @Test
    void a_reorder_two_appends_or_a_new_entry_with_two_releases_are_refused() throws IOException {
        facts.release(SDK_REPO, "v2.2.0", "LiQiyeDev").release(SDK_REPO, "v2.3.0", "LiQiyeDev");
        write(base, SDK, pluginJson(SDK_REPO, "v2.1.0", "v2.2.0"));
        write(head, SDK, pluginJson(SDK_REPO, "v2.2.0", "v2.1.0"));
        assertRefused(check("LiQiyeDev", SDK), "never edited or reordered");
        write(head, SDK, pluginJson(SDK_REPO, "v2.1.0", "v2.2.0", "v2.3.0", "v2.4.0"));
        assertRefused(check("LiQiyeDev", SDK), "appends one release");
        write(head, BOT, botJson("somedev/firestone", "v1.1.0", "v1.2.0"));
        assertRefused(check("somedev", BOT), "lists one release");
    }

    @Test
    void the_controller_removes_a_release_and_nobody_else_does() throws Exception {
        facts.release(SDK_REPO, "v2.2.0", "LiQiyeDev");
        write(base, SDK, pluginJson(SDK_REPO, "v2.1.0", "v2.2.0"));
        write(head, SDK, pluginJson(SDK_REPO, "v2.2.0"));
        assertEquals(List.of(), check("LiQiyeDev", SDK));
        assertRefused(check("mallory", SDK), "only they may change it");
        assertEquals(new EntryGate.Change.Remove(List.of("v2.1.0")), EntryGate.Change.between(
                Optional.of(PluginEntry.read(base.resolve(SDK))), PluginEntry.read(head.resolve(SDK))));
    }

    @Test
    void a_bot_detaches_with_its_releases_kept() throws IOException {
        write(base, BOT, botJson("somedev/firestone", "v1.2.0"));
        write(head, BOT, json(new BotEntry("somedev/firestone", List.of(botRelease("v1.2.0")), true)));
        assertEquals(List.of(), check("somedev", BOT));
        write(head, BOT, botJson("somedev/firestone", "v1.2.0") + "\n");
        assertRefused(check("somedev", BOT), "changes nothing");
        facts.release("somedev/firestone", "v1.3.0", "somedev");
        write(head, BOT, json(new BotEntry("somedev/firestone", List.of(botRelease("v1.2.0"), botRelease("v1.3.0")),
                true)));
        assertRefused(check("somedev", BOT), "a pull request of its own");
    }

    @Test
    void a_tag_that_points_elsewhere_than_the_pinned_commit_is_refused() throws IOException {
        facts.release(SDK_REPO, "v2.2.0", "LiQiyeDev");
        facts.commits.put(SDK_REPO + "@v2.2.0", commitOf("moved"));
        write(head, SDK, pluginJson(SDK_REPO, "v2.1.0", "v2.2.0"));
        assertRefused(check("LiQiyeDev", SDK), "not the pinned commit");
    }

    @Test
    void a_pin_that_is_no_hash_is_refused() throws IOException {
        write(head, BOT, json(new BotEntry("somedev/firestone", List.of(ApprovedRelease.ofBot("v1.2.0", "abc")),
                false)));
        assertRefused(check("somedev", BOT), "not a full commit hash");
    }

    @Test
    void only_the_controller_removes_an_entry_even_when_its_repository_answers_404() throws IOException {
        Files.delete(head.resolve(SDK));
        assertRefused(check("mallory", SDK), "only whoever controls");
        assertEquals(List.of(), check("LiQiyeDev", SDK));
        facts.repos.remove(SDK_REPO);
        facts.releases.remove(SDK_REPO + "@v2.1.0");
        assertRefused(check("mallory", SDK), "only whoever controls");
    }

    @Test
    void a_pull_request_opened_by_an_action_controls_nothing() throws IOException {
        facts.release("somedev/firestone", "v1.2.0", EntryGate.ACTIONS_BOT);
        write(head, BOT, botJson("somedev/firestone", "v1.2.0"));
        assertRefused(check(EntryGate.ACTIONS_BOT, BOT), "from Studio or the CLI");
    }

    @Test
    void an_admin_may_change_somebody_elses_entry() throws IOException {
        admins();
        facts.release(SDK_REPO, "v2.2.0", "somebody-else");
        write(head, SDK, pluginJson(SDK_REPO, "v2.1.0", "v2.2.0"));
        assertEquals(List.of(), check("BotMakerDev", ADMIN_ID, SDK));
        assertRefused(check("mallory", 9, SDK), "only they may change it");
    }

    @Test
    void an_admin_may_remove_somebody_elses_entry() throws IOException {
        admins();
        Files.delete(head.resolve(SDK));
        assertEquals(List.of(), check("BotMakerDev", ADMIN_ID, SDK));
    }

    @Test
    void a_login_that_matches_an_admin_but_not_its_id_is_no_admin() throws IOException {
        admins();
        Files.delete(head.resolve(SDK));
        assertRefused(check("Admin", 0, SDK), "only whoever controls");
        assertRefused(check("Admin", 12345, SDK), "only whoever controls");
    }

    @Test
    void an_admin_still_needs_a_published_release() throws IOException {
        admins();
        write(head, SDK, pluginJson(SDK_REPO, "v2.1.0", "v9"));
        assertRefused(check("BotMakerDev", ADMIN_ID, SDK), "no GitHub Release v9");
        facts.releases.put(SDK_REPO + "@v9", new EntryFacts.Release("LiQiyeDev", true, false));
        assertRefused(check("BotMakerDev", ADMIN_ID, SDK), "draft");
    }

    @Test
    void a_changed_path_is_never_read_as_a_flag() {
        assertEquals(2, EntryGate.run(new String[]{head.toString(), "--author", "somedev", "--base",
                base.toString(), "--author", "victim", BOT}, facts), "a path outside -- or @file is refused");
    }

    @Test
    void a_draft_or_a_prerelease_is_refused() throws IOException {
        facts.releases.put("somedev/firestone@v1.2.0", new EntryFacts.Release("somedev", false, true));
        write(head, BOT, botJson("somedev/firestone", "v1.2.0"));
        assertRefused(check("somedev", BOT), "prerelease");
        facts.releases.put("somedev/firestone@v1.2.0", new EntryFacts.Release("somedev", true, false));
        assertRefused(check("somedev", BOT), "draft");
    }

    @Test
    void a_tag_with_no_release_is_refused() throws IOException {
        write(head, BOT, botJson("somedev/firestone", "v9"));
        assertRefused(check("somedev", BOT), "no GitHub Release v9");
    }

    @Test
    void an_entry_differing_only_by_case_from_a_listed_one_is_refused() throws IOException {
        write(base, BOT, botJson("somedev/firestone", "v1.2.0"));
        facts.repo("SomeDev/Firestone", 2, "", null).release("SomeDev/Firestone", "v1.2.0", "somedev");
        write(head, "bots/SomeDev__Firestone.json", botJson("SomeDev/Firestone", "v1.2.0"));
        assertRefused(check("somedev", "bots/SomeDev__Firestone.json"), "differs only by case");
    }

    @Test
    void a_symbolic_link_is_refused() throws IOException {
        Path target = Files.writeString(head.resolve("elsewhere.json"), botJson("somedev/firestone", "v1.2.0"));
        Files.createDirectories(head.resolve("bots"));
        Files.createSymbolicLink(head.resolve(BOT), target);
        assertRefused(check("somedev", BOT), "symbolic link");
    }

    @Test
    void without_the_base_tree_nothing_is_decided() throws IOException {
        write(head, BOT, botJson("somedev/firestone", "v1.2.0"));
        assertRefused(EntryGate.check(new EntryGate.Request(head, Optional.empty(), List.of(BOT), "somedev", 0),
                facts), "without --base");
    }

    @Test
    void the_invocation_is_checked_before_anything_runs() {
        assertEquals(2, EntryGate.run(new String[]{head.toString()}, facts));
        assertEquals(2, EntryGate.run(new String[]{head.toString(), "--author", "somedev", "--part", "some",
                "--", BOT}, facts));
    }

    @Test
    void the_rules_part_runs_no_kind_check_and_the_kind_part_still_wants_one_entry() throws IOException {
        write(head, BOT, botJson("somedev/firestone", "v1.2.0"));
        assertEquals(0, EntryGate.run(new String[]{head.toString(), "--author", "somedev", "--base",
                base.toString(), "--part", "rules", "--", BOT}, facts), "no Maven build of the bot");
        assertEquals(1, EntryGate.run(new String[]{head.toString(), "--author", "somedev", "--part", "kind",
                "--", BOT, "README.md"}, facts));
        assertEquals(1, EntryGate.run(new String[]{head.toString(), "--author", "somedev", "--part", "kind",
                "--", "catalog.json"}, facts));
        assertEquals(1, EntryGate.run(new String[]{head.toString(), "--author", "somedev", "--part", "kind",
                "--", BOT}, facts), "the kind's checks need the base to know the approved release");
        write(head, BOT, botJson("somedev/firestone", "--upload-pack=x"));
        assertEquals(1, EntryGate.run(new String[]{head.toString(), "--author", "somedev", "--base",
                base.toString(), "--part", "kind", "--", BOT}, facts), "a tag that would reach git as a flag");
    }

    @Test
    void a_removal_runs_no_kind_check() throws IOException {
        facts.release(SDK_REPO, "v2.2.0", "LiQiyeDev");
        write(base, SDK, pluginJson(SDK_REPO, "v2.1.0", "v2.2.0"));
        assertEquals(0, EntryGate.run(new String[]{head.toString(), "--author", "LiQiyeDev", "--base",
                base.toString(), "--part", "kind", "--", SDK}, facts), "nothing downloads for a removal");
    }

    private List<Finding> check(String author, String... changed) {
        return check(author, 0, changed);
    }

    private List<Finding> check(String author, long authorId, String... changed) {
        return EntryGate.check(new EntryGate.Request(head, Optional.of(base), List.of(changed), author, authorId),
                facts);
    }

    /** The catalog's current publishers.json: one admin, BotMakerDev, by id. */
    private void admins() throws IOException {
        write(base, Publishers.FILE, "{\"admin\":[{\"id\":" + ADMIN_ID + ",\"login\":\"Admin\"}],\"trusted\":[]}");
    }

    private static void assertRefused(List<Finding> findings, String expected) {
        assertTrue(Finding.refused(findings), "refused: " + findings);
        assertTrue(findings.stream().anyMatch(f -> f.message().contains(expected)),
                "a finding saying \"" + expected + "\": " + findings);
    }

    private static void write(Path root, String path, String json) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }
}
