package com.botmaker.cli;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which remote names the repository a .bmk is made from (2026-10-25, finding N2). */
class BotExportCommandTest {

    private static Optional<String> slug(Map<String, String> remotes) {
        return BotExportCommand.slug(name -> Optional.ofNullable(remotes.get(name)));
    }

    @Test
    void yoursFirstThenTheAuthors() {
        assertEquals(Optional.of("me/bot"), slug(Map.of("origin", "git@github.com:me/bot.git",
                "upstream", "https://github.com/author/bot.git")));
        // a clone never published has only the author's
        assertEquals(Optional.of("author/bot"), slug(Map.of("upstream", "https://github.com/author/bot.git")));
    }

    @Test
    void theOldNamesStillCount() {
        assertEquals(Optional.of("author/bot"), slug(Map.of("original", "https://github.com/author/bot.git")));
        // Studio's old backup origin is a folder, no GitHub repository: mine is read instead
        assertEquals(Optional.of("me/bot"), slug(Map.of("origin", "/home/me/.backup/bot.git",
                "mine", "https://github.com/me/bot.git")));
        assertEquals(Optional.empty(), slug(Map.of()));
    }
}
