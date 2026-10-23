package com.botmaker.cli.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntryGateTest {

    private static final String SDK = "plugins/com.botmaker.sdk.json";
    private static final String BOT = "bots/somedev__firestone.json";
    private static final long ADMIN_ID = 330995542;

    @TempDir
    Path head;
    @TempDir
    Path base;

    private final FakeFacts facts = new FakeFacts();

    @BeforeEach
    void catalog() throws IOException {
        write(base, SDK, "{\"id\":\"com.botmaker.sdk\",\"repo\":\"BotMakerDev/botmaker-sdk\",\"tag\":\"v2.1.0\"}");
        write(head, SDK, "{\"id\":\"com.botmaker.sdk\",\"repo\":\"BotMakerDev/botmaker-sdk\",\"tag\":\"v2.1.0\"}");
        facts.repo("BotMakerDev/botmaker-sdk", 1, "The SDK.", null)
                .release("BotMakerDev/botmaker-sdk", "v2.1.0", "LiQiyeDev")
                .repo("somedev/firestone", 2, "Idle runs.", null)
                .release("somedev/firestone", "v1.2.0", "somedev")
                .repo("mallory/sdk", 9, "", null)
                .release("mallory/sdk", "v1", "mallory");
    }

    @Test
    void a_new_bot_by_the_releases_author_passes() throws IOException {
        write(head, BOT, "{\"repo\":\"somedev/firestone\",\"tag\":\"v1.2.0\"}");
        assertEquals(List.of(), check("somedev", BOT));
        assertEquals(List.of(), check("SomeDev", BOT), "GitHub logins ignore case");
    }

    @Test
    void two_paths_or_a_path_that_is_no_entry_are_refused() throws IOException {
        write(head, BOT, "{\"repo\":\"somedev/firestone\",\"tag\":\"v1.2.0\"}");
        assertRefused(check("somedev", BOT, "README.md"), "exactly one");
        assertRefused(check("somedev", ".github/workflows/index.yml"), "not an entry path");
        assertRefused(check("somedev", "catalog.json"), "not an entry path");
    }

    @Test
    void a_file_whose_name_is_not_its_content_is_refused() throws IOException {
        write(head, "bots/somedev__other.json", "{\"repo\":\"somedev/firestone\",\"tag\":\"v1.2.0\"}");
        assertRefused(check("somedev", "bots/somedev__other.json"), "rename it to " + BOT);
    }

    @Test
    void a_field_outside_the_schema_is_refused() throws IOException {
        write(head, BOT, "{\"repo\":\"somedev/firestone\",\"tag\":\"v1.2.0\",\"rank\":\"admin\"}");
        assertRefused(check("somedev", BOT), "schema");
    }

    @Test
    void a_release_made_by_someone_else_is_refused() throws IOException {
        write(head, BOT, "{\"repo\":\"somedev/firestone\",\"tag\":\"v1.2.0\"}");
        assertRefused(check("mallory", BOT), "made by somedev, not mallory");
    }

    @Test
    void an_actions_release_passes_for_the_owner_of_a_user_repository() throws IOException {
        facts.release("somedev/firestone", "v1.2.0", EntryGate.ACTIONS_BOT);
        write(head, BOT, "{\"repo\":\"somedev/firestone\",\"tag\":\"v1.2.0\"}");
        assertEquals(List.of(), check("somedev", BOT));
    }

    @Test
    void an_actions_release_of_an_organisation_is_refused_with_the_way_out() throws IOException {
        facts.repo("SomeOrg/bot", 7, "", null).release("SomeOrg/bot", "v1", EntryGate.ACTIONS_BOT);
        write(head, "bots/SomeOrg__bot.json", "{\"repo\":\"SomeOrg/bot\",\"tag\":\"v1\"}");
        assertRefused(check("somedev", "bots/SomeOrg__bot.json"), "from Studio or the CLI");
    }

    @Test
    void a_plugin_id_cannot_move_to_another_persons_repository() throws IOException {
        write(head, SDK, "{\"id\":\"com.botmaker.sdk\",\"repo\":\"mallory/sdk\",\"tag\":\"v1\"}");
        assertRefused(check("mallory", SDK), "belongs to whoever controls BotMakerDev/botmaker-sdk");
    }

    @Test
    void the_controller_updates_their_own_plugin() throws IOException {
        facts.release("BotMakerDev/botmaker-sdk", "v2.2.0", "LiQiyeDev");
        write(head, SDK, "{\"id\":\"com.botmaker.sdk\",\"repo\":\"BotMakerDev/botmaker-sdk\",\"tag\":\"v2.2.0\"}");
        assertEquals(List.of(), check("LiQiyeDev", SDK));
    }

    @Test
    void only_the_controller_removes_an_entry_even_when_its_repository_answers_404() throws IOException {
        Files.delete(head.resolve(SDK));
        assertRefused(check("mallory", SDK), "only whoever controls");
        assertEquals(List.of(), check("LiQiyeDev", SDK));
        facts.repos.remove("BotMakerDev/botmaker-sdk");
        facts.releases.remove("BotMakerDev/botmaker-sdk@v2.1.0");
        assertRefused(check("mallory", SDK), "only whoever controls");
    }

    @Test
    void a_pull_request_opened_by_an_action_controls_nothing() throws IOException {
        facts.release("somedev/firestone", "v1.2.0", EntryGate.ACTIONS_BOT);
        write(head, BOT, "{\"repo\":\"somedev/firestone\",\"tag\":\"v1.2.0\"}");
        assertRefused(check(EntryGate.ACTIONS_BOT, BOT), "from Studio or the CLI");
    }

    @Test
    void an_admin_may_change_somebody_elses_entry() throws IOException {
        admins();
        facts.release("BotMakerDev/botmaker-sdk", "v2.2.0", "somebody-else");
        write(head, SDK, "{\"id\":\"com.botmaker.sdk\",\"repo\":\"BotMakerDev/botmaker-sdk\",\"tag\":\"v2.2.0\"}");
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
        write(head, SDK, "{\"id\":\"com.botmaker.sdk\",\"repo\":\"BotMakerDev/botmaker-sdk\",\"tag\":\"v9\"}");
        assertRefused(check("BotMakerDev", ADMIN_ID, SDK), "no GitHub Release v9");
        facts.releases.put("BotMakerDev/botmaker-sdk@v9", new EntryFacts.Release("LiQiyeDev", true, false));
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
        write(head, BOT, "{\"repo\":\"somedev/firestone\",\"tag\":\"v1.2.0\"}");
        assertRefused(check("somedev", BOT), "prerelease");
        facts.releases.put("somedev/firestone@v1.2.0", new EntryFacts.Release("somedev", true, false));
        assertRefused(check("somedev", BOT), "draft");
    }

    @Test
    void a_tag_with_no_release_is_refused() throws IOException {
        write(head, BOT, "{\"repo\":\"somedev/firestone\",\"tag\":\"v9\"}");
        assertRefused(check("somedev", BOT), "no GitHub Release v9");
    }

    @Test
    void an_entry_differing_only_by_case_from_a_listed_one_is_refused() throws IOException {
        write(base, BOT, "{\"repo\":\"somedev/firestone\",\"tag\":\"v1.2.0\"}");
        facts.repo("SomeDev/Firestone", 2, "", null).release("SomeDev/Firestone", "v1.2.0", "somedev");
        write(head, "bots/SomeDev__Firestone.json", "{\"repo\":\"SomeDev/Firestone\",\"tag\":\"v1.2.0\"}");
        assertRefused(check("somedev", "bots/SomeDev__Firestone.json"), "differs only by case");
    }

    @Test
    void a_symbolic_link_is_refused() throws IOException {
        Path target = Files.writeString(head.resolve("elsewhere.json"), "{\"repo\":\"somedev/firestone\",\"tag\":\"v1.2.0\"}");
        Files.createDirectories(head.resolve("bots"));
        Files.createSymbolicLink(head.resolve(BOT), target);
        assertRefused(check("somedev", BOT), "symbolic link");
    }

    @Test
    void without_the_base_tree_nothing_is_decided() throws IOException {
        write(head, BOT, "{\"repo\":\"somedev/firestone\",\"tag\":\"v1.2.0\"}");
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
        write(head, BOT, "{\"repo\":\"somedev/firestone\",\"tag\":\"v1.2.0\"}");
        assertEquals(0, EntryGate.run(new String[]{head.toString(), "--author", "somedev", "--base",
                base.toString(), "--part", "rules", "--", BOT}, facts), "no Maven build of the bot");
        assertEquals(1, EntryGate.run(new String[]{head.toString(), "--author", "somedev", "--part", "kind",
                "--", BOT, "README.md"}, facts));
        assertEquals(1, EntryGate.run(new String[]{head.toString(), "--author", "somedev", "--part", "kind",
                "--", "catalog.json"}, facts));
        write(head, BOT, "{\"repo\":\"somedev/firestone\",\"tag\":\"--upload-pack=x\"}");
        assertEquals(1, EntryGate.run(new String[]{head.toString(), "--author", "somedev", "--part", "kind",
                "--", BOT}, facts), "a tag that would reach git as a flag");
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
