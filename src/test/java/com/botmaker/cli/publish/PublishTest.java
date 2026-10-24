package com.botmaker.cli.publish;

import com.botmaker.cli.catalog.ApprovedRelease;
import com.botmaker.cli.catalog.BotEntry;
import com.botmaker.cli.catalog.Catalog;
import com.botmaker.cli.catalog.Entry;
import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.catalog.FakeFacts;
import com.botmaker.cli.catalog.PluginEntry;
import com.botmaker.cli.release.Jitpack;
import com.botmaker.cli.release.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Publish}'s steps in order, over a real git folder, a pretend GitHub and a pretend JitPack. */
class PublishTest {

    private static final String REPO = "somedev/thing";
    private static final String ID = "com.example.thing";
    private static final String ORIGIN = "https://github.com/somedev/thing.git";

    @TempDir
    Path dir;

    private final FakeFacts facts = new FakeFacts().repo(REPO, 2, "", null);
    private final FakeRunner runner = new FakeRunner(facts);
    private final Map<String, Entry> listed = new HashMap<>();
    private final List<String> checked = new ArrayList<>();
    private Checks.Result folderResult = Checks.Result.passed(Optional.of(ID));
    private Optional<String> jitpackFailure = Optional.empty();

    private final Checks checks = new Checks() {
        @Override
        public Result folder(PublishTarget target, Listener listener) {
            checked.add("folder after " + runner.commands());
            return target.kind() == EntryPath.Kind.BOT && folderResult.pluginId().isPresent()
                    ? Result.passed(Optional.empty()) : folderResult;
        }

        @Override
        public Result published(String coordinate, Listener listener) {
            checked.add("jar " + coordinate);
            return Result.passed(Optional.of(ID));
        }
    };

    private final CatalogPr.Reader catalog = new CatalogPr.Reader() {
        @Override
        public Optional<Entry> listed(EntryPath path) {
            return Optional.ofNullable(listed.get(path.path()));
        }

        @Override
        public Optional<Catalog> catalog() {
            return Optional.empty();
        }
    };

    private final Jitpack.Probe jitpack = new Jitpack.Probe() {
        @Override
        public boolean built(String pomUrl) {
            facts.jitpack.add("com.github.somedev:thing:" + pomUrl.split("/")[7]);
            return true;
        }

        @Override
        public Optional<String> failure(String buildUrl) {
            return jitpackFailure;
        }
    };

    private Publish.Outcome publish(EntryPath.Kind kind, Optional<String> tag, boolean dryRun) {
        return new Publish(facts, catalog, checks, jitpack, duration -> true).run(new PublishRequest(dir, kind,
                Level.PATCH, tag, Optional.empty(), dryRun, "botmaker plugin publish"), FakeRunner.QUIET, runner);
    }

    private Publish.Outcome publish() {
        return publish(EntryPath.Kind.PLUGIN, Optional.empty(), false);
    }

    @Test
    void a_plugin_is_checked_then_tagged_released_built_and_listed() throws Exception {
        FakeRunner.repository(dir, ORIGIN);
        Publish.Outcome outcome = publish();

        Publish.Outcome.Opened opened = assertInstanceOf(Publish.Outcome.Opened.class, outcome, outcome.toString());
        assertEquals(Optional.of(FakeRunner.PR_URL), opened.pullRequest());
        ApprovedRelease release = opened.entry().newest();
        assertEquals("v0.1.0", release.tag());
        assertEquals(FakeFacts.commitOf("v0.1.0"), release.commit());
        assertEquals(new PluginEntry(ID, REPO, List.of(release)), opened.entry());

        assertEquals("folder after []", checked.getFirst(), "the folder is checked before anything changes");
        assertEquals("jar com.github.somedev:thing:v0.1.0", checked.get(1));
        List<String> commands = runner.commands();
        assertEquals(List.of("git push origin HEAD", "git tag v0.1.0", "git push origin refs/tags/v0.1.0",
                "gh release create v0.1.0 --repo somedev/thing --title v0.1.0 --notes somedev/thing v0.1.0."
                        + " --verify-tag"), commands.subList(0, 4));
        assertTrue(commands.getLast().startsWith("gh pr create --repo BotMakerDev/botmaker-catalog"), commands.getLast());
    }

    @Test
    void a_local_check_that_fails_tags_nothing() throws Exception {
        FakeRunner.repository(dir, ORIGIN);
        folderResult = Checks.Result.refused("the plugin does not pass its own checks (above).");
        Publish.Outcome.Refused refused = assertInstanceOf(Publish.Outcome.Refused.class, publish());
        assertTrue(refused.reason().contains("Nothing was tagged"), refused.reason());
        assertEquals(List.of(), runner.changes);
    }

    @Test
    void a_dirty_tree_is_refused_before_the_check() throws Exception {
        FakeRunner.repository(dir, ORIGIN);
        Files.writeString(dir.resolve("pom.xml"), "<project>changed</project>\n");
        Publish.Outcome.Refused refused = assertInstanceOf(Publish.Outcome.Refused.class, publish());
        assertTrue(refused.reason().contains("uncommitted changes"), refused.reason());
        assertEquals(List.of(), checked);
        assertEquals(List.of(), runner.changes);
    }

    /** Review focus 4: the build log named, no pull request opened. */
    @Test
    void a_jitpack_error_stops_with_the_build_log_and_opens_nothing() throws Exception {
        FakeRunner.repository(dir, ORIGIN);
        jitpackFailure = Optional.of("No build artifacts found. See the log: com/github/somedev/thing/v0.1.0/build.log");
        Publish.Outcome.Refused refused = assertInstanceOf(Publish.Outcome.Refused.class, publish());
        assertTrue(refused.reason().contains("https://jitpack.io/com/github/somedev/thing/v0.1.0/build.log"),
                refused.reason());
        assertTrue(runner.commands().stream().noneMatch(c -> c.startsWith("gh repo clone") || c.startsWith("gh pr")),
                runner.commands().toString());
        assertEquals(1, checked.size(), "the jar is not checked");
    }

    @Test
    void a_dry_run_checks_and_changes_nothing() throws Exception {
        FakeRunner.repository(dir, ORIGIN);
        Publish.Outcome outcome = publish(EntryPath.Kind.PLUGIN, Optional.empty(), true);
        assertEquals(new Publish.Outcome.WouldList(REPO, "v0.1.0", NextVersion.Listing.NEW, Optional.of(ID)), outcome);
        assertEquals(1, checked.size());
        assertEquals(List.of(), runner.changes);
    }

    /** Review focus 2, end to end: the second run makes no tag and no release, and opens the pull request. */
    @Test
    void a_rerun_after_the_release_lists_that_tag_without_tagging_again() throws Exception {
        FakeRunner.repository(dir, ORIGIN);
        FakeRunner.git(dir, "tag", "v0.1.0");
        facts.release(REPO, "v0.1.0", "somedev");

        Publish.Outcome.Opened opened = assertInstanceOf(Publish.Outcome.Opened.class, publish());
        assertEquals("v0.1.0", opened.tag());
        assertTrue(runner.commands().stream().noneMatch(c -> c.startsWith("git tag") || c.startsWith("gh release")
                || c.startsWith("git push origin")), runner.commands().toString());
        assertEquals(List.of("jar com.github.somedev:thing:v0.1.0"), checked, "EXISTING: the folder is not checked");
    }

    @Test
    void a_release_the_catalog_lists_already_does_nothing() throws Exception {
        FakeRunner.repository(dir, ORIGIN);
        FakeRunner.git(dir, "tag", "v0.1.0");
        facts.release(REPO, "v0.1.0", "somedev");
        listed.put("plugins/" + ID + ".json", new PluginEntry(ID, REPO, List.of(FakeFacts.pluginRelease("v0.1.0"))));
        Publish.Outcome outcome = publish(EntryPath.Kind.PLUGIN, Optional.of("v0.1.0"), false);
        // The plugin's path is learnt from the jar for an EXISTING release, then the listed tag is seen.
        assertEquals(new Publish.Outcome.AlreadyListed(REPO, "v0.1.0"), outcome);
        assertFalse(runner.commands().stream().anyMatch(c -> c.startsWith("gh pr")), runner.commands().toString());
    }

    @Test
    void someone_elses_entry_is_refused_before_anything_is_tagged() throws Exception {
        FakeRunner.repository(dir, ORIGIN);
        facts.release(REPO, "v0.0.9", "otherdev");
        listed.put("plugins/" + ID + ".json", new PluginEntry(ID, REPO, List.of(FakeFacts.pluginRelease("v0.0.9"))));
        Publish.Outcome.Refused refused = assertInstanceOf(Publish.Outcome.Refused.class, publish());
        assertTrue(refused.reason().contains("belongs to whoever controls"), refused.reason());
        assertEquals(List.of(), runner.changes);
    }

    @Test
    void a_plugin_with_no_origin_is_refused() throws Exception {
        FakeRunner.repository(dir, null);
        Publish.Outcome.Refused refused = assertInstanceOf(Publish.Outcome.Refused.class, publish());
        assertTrue(refused.reason().contains("Push the plugin to GitHub first"), refused.reason());
    }

    @Test
    void a_bot_with_no_origin_gets_its_repository_created_named_after_the_folder() throws Exception {
        Path bot = Files.createDirectories(dir.resolve("farmbot"));
        dir = FakeRunner.repository(bot, null);
        facts.repo("somedev/farmbot", 2, "", null);  // what GitHub shows once `gh repo create` made it
        Publish.Outcome.Opened opened = assertInstanceOf(Publish.Outcome.Opened.class,
                publish(EntryPath.Kind.BOT, Optional.empty(), false));
        assertEquals(new BotEntry("somedev/farmbot", List.of(FakeFacts.botRelease("v0.1.0")), false), opened.entry());
        assertEquals("gh repo create somedev/farmbot --source . --push --public", runner.commands().getFirst());
    }

    @Test
    void a_bot_folder_named_like_someone_elses_existing_repository_is_refused() throws Exception {
        Path bot = Files.createDirectories(dir.resolve("farmbot"));
        dir = FakeRunner.repository(bot, null);
        runner.existingRepos.add("somedev/farmbot");
        Publish.Outcome.Refused refused = assertInstanceOf(Publish.Outcome.Refused.class,
                publish(EntryPath.Kind.BOT, Optional.empty(), false));
        assertTrue(refused.reason().contains("already exists"), refused.reason());
        assertEquals(List.of(), runner.changes);
    }

    @Test
    void a_dry_run_already_refuses_a_repository_name_someone_holds() throws Exception {
        Path bot = Files.createDirectories(dir.resolve("farmbot"));
        dir = FakeRunner.repository(bot, null);
        runner.existingRepos.add("somedev/farmbot");
        assertInstanceOf(Publish.Outcome.Refused.class, publish(EntryPath.Kind.BOT, Optional.empty(), true));
        assertEquals(List.of(), checked, "refused before the check");
    }

    /** The repository is public and the check just built target/: the first commit leaves the build out. */
    @Test
    void a_bot_folder_that_is_no_repository_is_committed_without_its_build() throws Exception {
        Path bot = Files.createDirectories(dir.resolve("farmbot"));
        Files.writeString(bot.resolve("pom.xml"), "<project/>\n");
        Files.createDirectories(bot.resolve("target/classes"));
        Files.writeString(bot.resolve("target/classes/Main.class"), "built");
        dir = bot;
        facts.repo("somedev/farmbot", 2, "", null);
        assertInstanceOf(Publish.Outcome.Opened.class, publish(EntryPath.Kind.BOT, Optional.empty(), false));
        assertEquals("target/\n", Files.readString(bot.resolve(".gitignore")));
        FakeRunner.git(bot, "ls-files", "--error-unmatch", ".gitignore");
        assertThrows(IOException.class, () -> FakeRunner.git(bot, "ls-files", "--error-unmatch",
                "target/classes/Main.class"));
        assertEquals("git init -q -b main", runner.commands().getFirst());
    }

    @Test
    void a_failed_push_stops_before_the_tag() throws Exception {
        FakeRunner.repository(dir, ORIGIN);
        runner.failing.add(List.of("git", "push", "origin", "HEAD"));
        assertInstanceOf(Publish.Outcome.Refused.class, publish());
        assertEquals(List.of("git push origin HEAD"), runner.commands());
    }
}
