package com.botmaker.cli.publish;

import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.catalog.FakeFacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The repository a folder publishes to: its {@code origin}, whole, and {@code --repo} only when they agree. */
class PublishTargetTest {

    @TempDir
    Path dir;

    private final FakeRunner runner = new FakeRunner(new FakeFacts());

    @Test
    void origin_names_the_whole_repository_in_every_github_spelling() {
        assertEquals(Optional.of("Alice/Farmbot"), PublishTarget.slugOf("https://github.com/Alice/Farmbot.git"));
        assertEquals(Optional.of("alice/farmbot"), PublishTarget.slugOf("git@github.com:alice/farmbot.git"));
        assertEquals(Optional.of("alice/farmbot"), PublishTarget.slugOf("https://github.com/alice/farmbot/"));
        assertEquals(Optional.of("alice/farmbot"), PublishTarget.slugOf("ssh://git@github.com/alice/farmbot"));
        assertEquals(Optional.of("alice/farmbot-fork"), PublishTarget.slugOf("https://github.com/alice/farmbot-fork"));
        assertEquals(Optional.empty(), PublishTarget.slugOf("https://gitlab.com/alice/farmbot.git"));
        assertEquals(Optional.empty(), PublishTarget.slugOf("https://github.com/alice/farm/bot"));
    }

    @Test
    void repo_must_agree_with_origin() throws Exception {
        FakeRunner.repository(dir, "git@github.com:alice/farmbot.git");
        assertEquals("alice/farmbot", PublishTarget.of(dir, EntryPath.Kind.BOT, Optional.of("Alice/FarmBot"), "alice",
                runner).repo());
        Refusal refusal = assertThrows(Refusal.class,
                () -> PublishTarget.of(dir, EntryPath.Kind.BOT, Optional.of("alice/other"), "alice", runner));
        assertTrue(refusal.getMessage().contains("alice/other"), refusal.getMessage());
    }

    @Test
    void a_folder_that_is_not_a_repository_is_a_new_bot_or_a_refused_plugin() throws Exception {
        PublishTarget bot = PublishTarget.of(dir, EntryPath.Kind.BOT, Optional.empty(), "alice", runner);
        assertTrue(bot.fresh() && bot.create(), bot.toString());
        assertEquals("alice/" + dir.getFileName(), bot.repo());
        assertThrows(Refusal.class, () -> PublishTarget.of(dir, EntryPath.Kind.PLUGIN, Optional.empty(), "alice",
                runner));
    }
}
