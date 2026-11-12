package com.botmaker.cli.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartingParamsTest {

    private static final String GROUP = """
            package com.example.params.bot;

            import com.botmaker.plugin.api.params.Param;

            @Param
            public class Progress {
                public int gold() {
                    return 0;
                }
            }
            """;

    @Test
    void aCheckoutGetsItsParamsFromTheStartingCopy(@TempDir Path bot) throws Exception {
        Path starting = bot.resolve("starting-params/bot/Progress.java");
        Files.createDirectories(starting.getParent());
        Files.writeString(starting, GROUP);
        Files.createDirectories(bot.resolve("src/main/java/com/example"));

        StartingParams.fill(bot);

        Path params = bot.resolve("src/main/java/com/example/params/bot/Progress.java");
        assertTrue(Files.isRegularFile(params));
        assertEquals(GROUP, Files.readString(params));
    }

    @Test
    void aBotWithoutAStartingCopyIsLeftAlone(@TempDir Path bot) throws Exception {
        StartingParams.fill(bot);

        assertFalse(Files.exists(bot.resolve("src")));
    }
}
