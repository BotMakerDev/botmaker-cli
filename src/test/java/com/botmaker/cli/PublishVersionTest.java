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
