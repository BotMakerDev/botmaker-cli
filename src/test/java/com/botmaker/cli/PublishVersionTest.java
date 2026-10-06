package com.botmaker.cli;

import com.botmaker.cli.project.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What version an entry publishes, and the two answers that are refusals.
 *
 * <p>The defect these hold shut: a freshly generated plugin's pom says {@code 0.1.0-SNAPSHOT}, JitPack
 * cannot resolve a snapshot, and until 2026-09-04 {@code botmaker publish} composed an entry naming it —
 * so {@code botmaker new} followed by {@code botmaker publish} produced a pull request whose gate failed in
 * the <em>registry's</em> CI, which is the one experience the two-caller design exists to prevent.
 */
class PublishVersionTest {

    @TempDir
    Path work;

    // ---- the refusals -----------------------------------------------------------------------------------

    @Test
    void a_snapshot_is_refused_and_the_refusal_names_the_way_out() {
        String refusal = PluginPublishCommand.snapshotRefusal("0.1.0-SNAPSHOT");

        assertNotNull(refusal, "a snapshot must not reach the registry");
        assertTrue(refusal.contains("0.1.0-SNAPSHOT"), refusal);
        assertTrue(refusal.contains("--tag"), "the refusal must name the option that fixes it: " + refusal);
    }

    @Test
    void no_version_at_all_is_refused_too() {
        assertNotNull(PluginPublishCommand.snapshotRefusal(""));
        assertNotNull(PluginPublishCommand.snapshotRefusal(null));
    }

    @Test
    void a_released_version_is_accepted() {
        assertNull(PluginPublishCommand.snapshotRefusal("v1.2.0"));
        assertNull(PluginPublishCommand.snapshotRefusal("1.2.0"));
    }

    // ---- the contract version ---------------------------------------------------------------------------

    /**
     * The defect these hold shut, found on 2026-09-05 by publishing the SDK for real: the entry read
     * {@code "minContractVersion" : "${botmaker.studioapi.version}"}. Every module in this project pins its
     * BotMaker upstreams through a property, so this is the ordinary shape of a plugin's pom here — and a
     * property name copied into a registry entry is a compatibility floor nothing can be compared against.
     */
    @Test
    void an_unresolved_property_is_refused_and_the_refusal_names_the_way_out() {
        String refusal = PluginPublishCommand.contractVersionRefusal("${botmaker.studioapi.version}");

        assertNotNull(refusal, "a property name must not reach the registry as a version");
        assertTrue(refusal.contains("${botmaker.studioapi.version}"), refusal);
        assertTrue(refusal.contains("--min-contract-version"),
                "the refusal must name the option that fixes it: " + refusal);
    }

    /** A committed pom on main says {@code X.Y.Z-SNAPSHOT}; only a tag's pom names a released contract. */
    @Test
    void a_snapshot_contract_version_is_refused_too() {
        String refusal = PluginPublishCommand.contractVersionRefusal("0.4.3-SNAPSHOT");

        assertNotNull(refusal);
        assertTrue(refusal.contains("released botmaker-studio-api version"),
                "the refusal must say where a module of this project finds the real answer: " + refusal);
    }

    @Test
    void no_contract_dependency_at_all_is_refused() {
        assertNotNull(PluginPublishCommand.contractVersionRefusal(""));
        assertNotNull(PluginPublishCommand.contractVersionRefusal(null));
    }

    @Test
    void a_resolved_contract_version_is_accepted() {
        assertNull(PluginPublishCommand.contractVersionRefusal("v0.0.4"));
        assertNull(PluginPublishCommand.contractVersionRefusal("0.0.4"));
    }

    // ---- the tag ----------------------------------------------------------------------------------------

    /**
     * The real {@code git describe}, over a real repository, because the whole value of this lookup is that
     * it agrees with what JitPack will serve — and a stubbed answer would agree with nothing.
     */
    @Test
    void the_newest_tag_is_what_git_reports() throws Exception {
        Files.writeString(work.resolve("a.txt"), "one\n");
        git("init", "-q");
        git("config", "user.email", "test@example.invalid");
        git("config", "user.name", "test");
        git("add", "a.txt");
        git("commit", "-qm", "one");
        git("tag", "v0.1.0");

        assertEquals(Optional.of("v0.1.0"), Tags.newest(work));
    }

    /** Not a git repository, or one with no tag yet: a fact, not an error — the pom version is the fallback. */
    @Test
    void a_working_copy_with_no_tag_answers_empty() throws Exception {
        assertEquals(Optional.empty(), Tags.newest(work), "no repository at all");

        git("init", "-q");
        assertEquals(Optional.empty(), Tags.newest(work), "a repository with no commit and no tag");
    }

    private void git(String... args) throws IOException, InterruptedException {
        List<String> argv = new java.util.ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        int status = new ProcessBuilder(argv)
                .directory(work.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
        assertEquals(0, status, "git " + String.join(" ", args));
    }
}
