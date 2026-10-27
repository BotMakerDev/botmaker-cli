package com.botmaker.cli.publish;

import com.botmaker.cli.catalog.Entry;
import com.botmaker.cli.catalog.FakeFacts;
import com.botmaker.cli.catalog.PluginEntry;
import com.botmaker.cli.release.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tag a publish lists, decided on a real git repository: the bump, {@code HEAD}'s own tag, {@code --tag}. */
class NextVersionTest {

    private static final String REPO = "somedev/thing";

    @TempDir
    Path dir;

    private final FakeFacts facts = new FakeFacts();
    private final FakeRunner runner = new FakeRunner(facts);

    private NextVersion decide(Level bump, Optional<String> tag, Optional<Entry> listed) throws Refusal {
        return NextVersion.decide(bump, tag, listed, REPO, facts, runner.host(dir));
    }

    private NextVersion decide(Level bump) throws Refusal {
        return decide(bump, Optional.empty(), Optional.empty());
    }

    @Test
    void a_first_publish_is_v0_1_0() throws Exception {
        FakeRunner.repository(dir, null);
        assertEquals(new NextVersion("v0.1.0", NextVersion.Listing.NEW), decide(Level.PATCH));
    }

    @Test
    void the_newest_tag_is_bumped_by_the_level() throws Exception {
        FakeRunner.repository(dir, null);
        FakeRunner.git(dir, "tag", "v1.2.3");
        FakeRunner.commit(dir, "two");
        assertEquals("v1.2.4", decide(Level.PATCH).tag());
        assertEquals("v1.3.0", decide(Level.MINOR).tag());
        assertEquals(new NextVersion("v2.0.0", NextVersion.Listing.NEW), decide(Level.MAJOR));
    }

    /** Review focus 2: the tag and its release are out, the pull request is not — the re-run lists that tag. */
    @Test
    void heads_released_tag_the_catalog_lacks_is_listed_and_never_bumped() throws Exception {
        FakeRunner.repository(dir, null);
        FakeRunner.git(dir, "tag", "v0.3.0");
        facts.release(REPO, "v0.3.0", "somedev");
        assertEquals(new NextVersion("v0.3.0", NextVersion.Listing.EXISTING), decide(Level.MINOR));
    }

    /** A run that stopped after the tag, before the release: the release is made for that tag. */
    @Test
    void heads_unreleased_tag_is_released_rather_than_bumped() throws Exception {
        FakeRunner.repository(dir, null);
        FakeRunner.git(dir, "tag", "v0.3.0");
        assertEquals(new NextVersion("v0.3.0", NextVersion.Listing.NEW), decide(Level.PATCH));
    }

    @Test
    void a_head_the_catalog_lists_already_has_nothing_new() throws Exception {
        FakeRunner.repository(dir, null);
        FakeRunner.git(dir, "tag", "v0.3.0");
        Optional<Entry> listed = Optional.of(new PluginEntry(REPO,
                List.of(FakeFacts.pluginRelease("v0.3.0"))));
        Refusal refusal = assertThrows(Refusal.class, () -> decide(Level.PATCH, Optional.empty(), listed));
        assertTrue(refusal.getMessage().contains("lists already"), refusal.getMessage());
    }

    /** Review focus 3: no number to bump, nothing guessed, {@code --tag} named. */
    @Test
    void a_newest_tag_that_is_not_vxyz_is_refused_with_tag_suggested() throws Exception {
        FakeRunner.repository(dir, null);
        for (String odd : List.of("1.2", "release-3", "1.2.3")) {
            FakeRunner.git(dir, "tag", odd);
            FakeRunner.commit(dir, "after-" + odd);
            Refusal refusal = assertThrows(Refusal.class, () -> decide(Level.PATCH), odd);
            assertTrue(refusal.getMessage().contains("--tag"), refusal.getMessage());
            assertTrue(refusal.getMessage().contains(odd), refusal.getMessage());
        }
        assertEquals(List.of(), runner.changes, "nothing tagged");
    }

    @Test
    void tag_names_the_version_and_a_released_one_is_listed_as_it_is() throws Exception {
        FakeRunner.repository(dir, null);
        assertEquals(new NextVersion("v2.0.0", NextVersion.Listing.NEW),
                decide(Level.PATCH, Optional.of("v2.0.0"), Optional.empty()));
        facts.release(REPO, "v1.6.0", "somedev");
        assertEquals(new NextVersion("v1.6.0", NextVersion.Listing.EXISTING),
                decide(Level.PATCH, Optional.of("v1.6.0"), Optional.empty()));
        assertThrows(Refusal.class, () -> decide(Level.PATCH, Optional.of("--upload-pack=x"), Optional.empty()));
    }

    @Test
    void an_unreleased_tag_elsewhere_is_refused_rather_than_moved() throws Exception {
        FakeRunner.repository(dir, null);
        FakeRunner.git(dir, "tag", "v0.5.0");
        FakeRunner.git(dir, "tag", "v0.1.0");
        FakeRunner.commit(dir, "two");
        assertThrows(Refusal.class, () -> decide(Level.PATCH, Optional.of("v0.5.0"), Optional.empty()));
    }

    @Test
    void a_bumped_tag_that_exists_on_another_branch_is_refused() throws Exception {
        FakeRunner.repository(dir, null);
        FakeRunner.git(dir, "tag", "v0.1.0");
        FakeRunner.git(dir, "checkout", "-q", "-b", "side");
        FakeRunner.commit(dir, "side");
        FakeRunner.git(dir, "tag", "v0.1.1");
        FakeRunner.git(dir, "checkout", "-q", "main");
        FakeRunner.commit(dir, "main-two");
        Refusal refusal = assertThrows(Refusal.class, () -> decide(Level.PATCH));
        assertTrue(refusal.getMessage().contains("v0.1.1"), refusal.getMessage());
    }

    @Test
    void a_tag_on_head_that_is_not_a_version_is_not_published() throws Exception {
        FakeRunner.repository(dir, null);
        FakeRunner.git(dir, "tag", "v0.4.0");
        FakeRunner.commit(dir, "two");
        FakeRunner.git(dir, "tag", "v0.4.1");
        FakeRunner.git(dir, "tag", "latest");
        facts.release(REPO, "v0.4.1", "somedev");
        assertEquals(new NextVersion("v0.4.1", NextVersion.Listing.EXISTING), decide(Level.PATCH));
    }

    /** A hotfix tag nearer HEAD than a higher release: the bump starts from the highest, never going back. */
    @Test
    void the_bump_starts_from_the_highest_version_not_the_nearest_tag() throws Exception {
        FakeRunner.repository(dir, null);
        FakeRunner.git(dir, "tag", "v1.2.0");
        FakeRunner.commit(dir, "two");
        FakeRunner.git(dir, "tag", "v1.1.5");
        FakeRunner.commit(dir, "three");
        assertEquals("v1.2.1", decide(Level.PATCH).tag());
        Optional<Entry> listed = Optional.of(new PluginEntry(REPO,
                List.of(FakeFacts.pluginRelease("v1.5.0"))));
        assertEquals("v1.6.0", decide(Level.MINOR, Optional.empty(), listed).tag(), "an approved tag counts too");
    }

    @Test
    void the_folder_is_not_touched(@TempDir Path other) throws IOException, Refusal {
        dir = FakeRunner.repository(other, null);
        decide(Level.PATCH);
        assertEquals(List.of(), runner.changes);
    }
}
