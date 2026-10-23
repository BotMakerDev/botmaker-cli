package com.botmaker.cli;

import com.botmaker.cli.catalog.BotEntry;
import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.catalog.PluginEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pull request {@code publish} and {@code unpublish} open on the catalog: one file, one branch, via {@code gh}. */
class CatalogPrTest {

    @Test
    void a_bot_publish_forks_writes_its_one_file_and_opens_the_pr_from_the_fork() {
        CatalogPr.Change change = CatalogPr.Change.put(new BotEntry("somedev/firestone", "v1.2.0", false),
                "botmaker bot publish");
        assertEquals("bot: somedev/firestone v1.2.0", change.title());

        String branch = "entry/bots/somedev__firestone.json";
        assertEquals(List.of(
                new CatalogPr.Step.Run(false, List.of("gh", "repo", "clone", "BotMakerDev/botmaker-catalog",
                        "botmaker-catalog")),
                new CatalogPr.Step.Run(true, List.of("gh", "repo", "fork", "--remote=true", "--remote-name=fork")),
                new CatalogPr.Step.Run(true, List.of("git", "checkout", "-b", branch)),
                new CatalogPr.Step.Write("bots/somedev__firestone.json",
                        "{\n  \"repo\" : \"somedev/firestone\",\n  \"tag\" : \"v1.2.0\"\n}\n"),
                new CatalogPr.Step.Run(true, List.of("git", "add", "--", "bots/somedev__firestone.json")),
                new CatalogPr.Step.Commit("bot: somedev/firestone v1.2.0"),
                new CatalogPr.Step.Run(true, List.of("git", "push", "--force", "-u", "fork", branch)),
                new CatalogPr.Step.PullRequest("somedev:" + branch, List.of("gh", "pr", "create",
                        "--repo", "BotMakerDev/botmaker-catalog", "--base", "main", "--head", "somedev:" + branch,
                        "--title", change.title(), "--body", change.body()))),
                CatalogPr.steps(change, false, "somedev"));
    }

    @Test
    void a_plugin_publish_by_someone_who_can_push_pushes_to_the_catalog_itself() {
        CatalogPr.Change change = CatalogPr.Change.put(
                new PluginEntry("com.example.thing", "somedev/thing", "v0.3.0"), "botmaker plugin publish");
        assertEquals("plugin: somedev/thing v0.3.0", change.title());
        assertTrue(change.body().contains("\"id\" : \"com.example.thing\""), change.body());

        List<CatalogPr.Step> steps = CatalogPr.steps(change, true, "LiQiyeDev");
        assertTrue(steps.stream().noneMatch(s -> s instanceof CatalogPr.Step.Run run && run.argv().contains("fork")),
                steps.toString());
        assertEquals(new CatalogPr.Step.Run(true, List.of("git", "push", "--force", "-u", "origin",
                "entry/plugins/com.example.thing.json")), steps.get(5));
        assertEquals("entry/plugins/com.example.thing.json", ((CatalogPr.Step.PullRequest) steps.getLast()).head());
    }

    @Test
    void an_unpublish_deletes_the_file_and_writes_nothing() {
        EntryPath path = EntryPath.parse("plugins/com.example.thing.json").orElseThrow();
        CatalogPr.Change change = CatalogPr.Change.remove(path, "botmaker plugin unpublish");
        assertEquals("plugin: remove com.example.thing", change.title());

        List<CatalogPr.Step> steps = CatalogPr.steps(change, false, "somedev");
        assertTrue(steps.stream().noneMatch(CatalogPr.Step.Write.class::isInstance), steps.toString());
        assertEquals(new CatalogPr.Step.Run(true, List.of("git", "rm", "--quiet", "--",
                "plugins/com.example.thing.json")), steps.get(3));
    }
}
