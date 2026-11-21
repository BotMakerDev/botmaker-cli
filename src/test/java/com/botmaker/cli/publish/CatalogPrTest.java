// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.publish;

import com.botmaker.cli.catalog.Advisory;
import com.botmaker.cli.catalog.ApprovedRelease;
import com.botmaker.cli.catalog.BotEntry;
import com.botmaker.cli.catalog.Catalog;
import com.botmaker.cli.catalog.CatalogJson;
import com.botmaker.cli.catalog.Entry;
import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.catalog.FakeFacts;
import com.botmaker.cli.catalog.PluginEntry;
import com.botmaker.cli.catalog.Publishers;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pull request {@code publish} and {@code unpublish} open on the catalog: one file, one branch, via {@code gh}. */
class CatalogPrTest {

    @Test
    void a_bot_publish_forks_writes_its_one_file_and_opens_the_pr_from_the_fork() {
        String commit = "0123456789abcdef0123456789abcdef01234567";
        CatalogPr.Change change = CatalogPr.Change.put(new BotEntry("somedev/firestone",
                List.of(ApprovedRelease.ofBot("v1.2.0", commit)), false), "botmaker bot publish");
        assertEquals("bot: somedev/firestone v1.2.0", change.title());

        String branch = "entry/bots/somedev__firestone.json";
        assertEquals(List.of(
                new CatalogPr.Step.Run(false, List.of("gh", "repo", "clone", "BotMakerDev/botmaker-catalog",
                        "botmaker-catalog")),
                new CatalogPr.Step.Run(true, List.of("gh", "repo", "fork", "--remote=true", "--remote-name=fork")),
                new CatalogPr.Step.Run(true, List.of("git", "checkout", "-b", branch)),
                new CatalogPr.Step.Write("bots/somedev__firestone.json",
                        "{\n  \"repo\" : \"somedev/firestone\",\n  \"releases\" : [ {\n    \"tag\" : \"v1.2.0\",\n"
                                + "    \"commit\" : \"" + commit + "\"\n  } ]\n}\n"),
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
                new PluginEntry("somedev/thing", List.of(new ApprovedRelease("v0.3.0",
                        "0123456789abcdef0123456789abcdef01234567", "a".repeat(64), "b".repeat(64)))),
                "botmaker plugin publish");
        assertEquals("plugin: somedev/thing v0.3.0", change.title());
        assertTrue(change.body().contains("\"repo\" : \"somedev/thing\""), change.body());

        List<CatalogPr.Step> steps = CatalogPr.steps(change, true, "LiQiyeDev");
        assertTrue(steps.stream().noneMatch(s -> s instanceof CatalogPr.Step.Run run && run.argv().contains("fork")),
                steps.toString());
        assertEquals(new CatalogPr.Step.Run(true, List.of("git", "push", "--force", "-u", "origin",
                "entry/plugins/somedev__thing.json")), steps.get(5));
        assertEquals("entry/plugins/somedev__thing.json", ((CatalogPr.Step.PullRequest) steps.getLast()).head());
    }

    @Test
    void an_unpublish_deletes_the_file_and_writes_nothing() {
        EntryPath path = EntryPath.parse("plugins/somedev__thing.json").orElseThrow();
        CatalogPr.Change change = CatalogPr.Change.remove(path, "botmaker plugin unpublish");
        assertEquals("plugin: remove somedev/thing", change.title());

        List<CatalogPr.Step> steps = CatalogPr.steps(change, false, "somedev");
        assertTrue(steps.stream().noneMatch(CatalogPr.Step.Write.class::isInstance), steps.toString());
        assertEquals(new CatalogPr.Step.Run(true, List.of("git", "rm", "--quiet", "--",
                "plugins/somedev__thing.json")), steps.get(3));
    }

    @Test
    void unpublishing_one_release_keeps_the_others_and_the_last_one_removes_the_entry() throws Refusal {
        FakeFacts facts = new FakeFacts().repo("somedev/thing", 2, "", null)
                .release("somedev/thing", "v1", "somedev").release("somedev/thing", "v2", "somedev");
        EntryPath path = EntryPath.parse("plugins/somedev__thing.json").orElseThrow();
        Map<String, Entry> held = new HashMap<>(Map.of(path.path(), new PluginEntry("somedev/thing",
                List.of(FakeFacts.pluginRelease("v1"), FakeFacts.pluginRelease("v2")))));
        CatalogPr.Reader catalog = reader(held);

        FakeRunner runner = new FakeRunner(facts);
        CatalogPr.unpublish(path, Optional.of("v1"), SOMEDEV, "test", facts, catalog, runner.host(HERE)::pullRequest,
                FakeRunner.QUIET);
        assertTrue(runner.commands().contains("git add -- plugins/somedev__thing.json"), runner.commands().toString());
        assertTrue(runner.commands().getLast().contains("plugin: somedev/thing remove v1"), runner.commands().getLast());

        held.put(path.path(), new PluginEntry("somedev/thing",
                List.of(FakeFacts.pluginRelease("v2"))));
        FakeRunner last = new FakeRunner(facts);
        CatalogPr.unpublish(path, Optional.of("v2"), SOMEDEV, "test", facts, catalog, last.host(HERE)::pullRequest,
                FakeRunner.QUIET);
        assertTrue(last.commands().contains("git rm --quiet -- plugins/somedev__thing.json"), last.commands().toString());

        Refusal unknown = assertThrows(Refusal.class, () -> CatalogPr.unpublish(path, Optional.of("v9"), SOMEDEV,
                "test", facts, catalog, new FakeRunner(facts).host(HERE)::pullRequest, FakeRunner.QUIET));
        assertTrue(unknown.getMessage().contains("lists no release v9"), unknown.getMessage());
        assertThrows(Refusal.class, () -> CatalogPr.unpublish(path, Optional.empty(),
                new PublishHost.Account("otherdev", 8), "test", facts, catalog, new FakeRunner(facts).host(HERE)::pullRequest,
                FakeRunner.QUIET), "only whoever controls the entry");
    }

    @Test
    void a_yank_writes_the_entry_with_that_release_pulled_and_undo_lists_it_again() throws Exception {
        FakeFacts facts = new FakeFacts().repo("somedev/firestone", 2, "", null)
                .release("somedev/firestone", "v1", "somedev").release("somedev/firestone", "v2", "somedev");
        EntryPath path = EntryPath.parse("bots/somedev__firestone.json").orElseThrow();
        Map<String, Entry> held = new HashMap<>(Map.of(path.path(), new BotEntry("somedev/firestone",
                List.of(FakeFacts.botRelease("v1"), FakeFacts.botRelease("v2")), false)));
        CatalogPr.Reader catalog = reader(held);
        Instant now = Instant.parse("2026-11-21T10:00:00Z");
        List<CatalogPr.Change> opened = new ArrayList<>();
        CatalogPr.Opener host = (change, account, listener) -> {
            opened.add(change);
            return Optional.empty();
        };

        CatalogPr.yank(path, "v1", Optional.of("Deletes saves."), now, SOMEDEV, "test", facts, catalog, host,
                FakeRunner.QUIET);
        CatalogPr.Change yank = opened.getFirst();
        assertEquals("bot: yank somedev/firestone v1", yank.title());
        BotEntry written = CatalogJson.strict().readValue(yank.content().orElseThrow(), BotEntry.class);
        assertEquals(new ApprovedRelease.Yanked("Deletes saves.", "2026-11-21T10:00:00Z"),
                written.releases().getFirst().yanked());
        assertEquals(FakeFacts.botRelease("v2"), written.releases().getLast(), "the other release untouched");

        Refusal stranger = assertThrows(Refusal.class, () -> CatalogPr.yank(path, "v1", Optional.of("x"), now,
                new PublishHost.Account("otherdev", 8), "test", facts, catalog, host, FakeRunner.QUIET));
        assertTrue(stranger.getMessage().contains("or an admin"), stranger.getMessage());
        Refusal notYanked = assertThrows(Refusal.class, () -> CatalogPr.yank(path, "v1", Optional.empty(), now,
                SOMEDEV, "test", facts, catalog, host, FakeRunner.QUIET));
        assertTrue(notYanked.getMessage().contains("is not yanked"), notYanked.getMessage());
        assertThrows(Refusal.class, () -> CatalogPr.yank(path, "v1", Optional.of("x".repeat(301)), now, SOMEDEV,
                "test", facts, catalog, host, FakeRunner.QUIET));

        held.put(path.path(), written);
        CatalogPr.yank(path, "v1", Optional.empty(), now, SOMEDEV, "test", facts, catalog, host, FakeRunner.QUIET);
        assertEquals("bot: unyank somedev/firestone v1", opened.getLast().title());
        assertEquals(FakeFacts.botRelease("v1"), CatalogJson.strict().readValue(opened.getLast().content()
                .orElseThrow(), BotEntry.class).releases().getFirst());
    }

    @Test
    void advisoryChange() throws Exception {
        EntryPath path = EntryPath.parse("bots/somedev__firestone.json").orElseThrow();
        Map<String, Entry> held = Map.of(path.path(), new BotEntry("somedev/firestone",
                List.of(FakeFacts.botRelease("v1"), FakeFacts.botRelease("v2")), false));
        Map<String, Advisory> advisories = new HashMap<>(Map.of("BMA-2026-0001", new Advisory("BMA-2026-0001",
                "somedev/firestone", List.of("v1"), Advisory.Severity.LOW, "old", null, "2026-01-02", "x")));
        CatalogPr.Reader catalog = new CatalogPr.Reader() {
            @Override
            public Optional<Entry> listed(EntryPath p) {
                return Optional.ofNullable(held.get(p.path()));
            }

            @Override
            public Optional<Catalog> catalog() {
                return Optional.empty();
            }

            @Override
            public Optional<Publishers> publishers() {
                return Optional.of(new Publishers(List.of(new Publishers.Publisher(9, "admin")), List.of()));
            }

            @Override
            public Optional<List<String>> advisoryIds() {
                return Optional.of(List.copyOf(advisories.keySet()));
            }

            @Override
            public Optional<Advisory> advisory(String id) {
                return Optional.ofNullable(advisories.get(id));
            }
        };
        PublishHost.Account admin = new PublishHost.Account("admin", 9);
        List<CatalogPr.Change> opened = new ArrayList<>();
        CatalogPr.Opener host = (change, account, listener) -> {
            opened.add(change);
            return Optional.empty();
        };

        CatalogPr.fileAdvisory("somedev/firestone", List.of("v1"), Advisory.Severity.HIGH, "Leaks the token.",
                Optional.empty(), LocalDate.parse("2026-11-21"), admin, "test", catalog, host, FakeRunner.QUIET);
        CatalogPr.Change filed = opened.getLast();
        assertEquals("advisories/BMA-2026-0002.json", filed.file(), "after the withdrawn 0001, which keeps its number");
        assertEquals("advisory/advisories/BMA-2026-0002.json", filed.branch());
        Advisory written = CatalogJson.strict().readValue(filed.content().orElseThrow(), Advisory.class);
        assertEquals(new Advisory("BMA-2026-0002", "somedev/firestone", List.of("v1"), Advisory.Severity.HIGH,
                "Leaks the token.", null, "2026-11-21", null), written);

        assertThrows(Refusal.class, () -> CatalogPr.fileAdvisory("somedev/firestone", List.of("v1"),
                Advisory.Severity.HIGH, "x", Optional.empty(), LocalDate.parse("2026-11-21"), SOMEDEV, "test", catalog,
                host, FakeRunner.QUIET), "an admin's alone");
        assertThrows(Refusal.class, () -> CatalogPr.fileAdvisory("somedev/firestone", List.of("v9"),
                Advisory.Severity.HIGH, "x", Optional.empty(), LocalDate.parse("2026-11-21"), admin, "test", catalog,
                host, FakeRunner.QUIET), "an approved release");

        advisories.put(written.id(), written);
        CatalogPr.changeAdvisory("BMA-2026-0002", List.of("v2"), Optional.empty(), admin, "test", catalog, host,
                FakeRunner.QUIET);
        assertEquals(List.of("v1", "v2"), CatalogJson.strict().readValue(opened.getLast().content().orElseThrow(),
                Advisory.class).releases());
        CatalogPr.changeAdvisory("BMA-2026-0002", List.of(), Optional.of("Never leaked."), admin, "test", catalog,
                host, FakeRunner.QUIET);
        assertEquals(written.withdraw("Never leaked."), CatalogJson.strict().readValue(opened.getLast().content()
                .orElseThrow(), Advisory.class));
        assertThrows(Refusal.class, () -> CatalogPr.changeAdvisory("BMA-2026-0001", List.of("v2"), Optional.empty(),
                admin, "test", catalog, host, FakeRunner.QUIET), "a withdrawn advisory changes no more");
    }

    private static final PublishHost.Account SOMEDEV = new PublishHost.Account("somedev", 7);
    private static final Path HERE = Path.of(".");

    private static CatalogPr.Reader reader(Map<String, Entry> held) {
        return new CatalogPr.Reader() {
            @Override
            public Optional<Entry> listed(EntryPath path) {
                return Optional.ofNullable(held.get(path.path()));
            }

            @Override
            public Optional<Catalog> catalog() {
                return Optional.empty();
            }
        };
    }
}
