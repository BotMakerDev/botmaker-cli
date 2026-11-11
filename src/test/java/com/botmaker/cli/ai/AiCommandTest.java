package com.botmaker.cli.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code botmaker ai}: which folder is a bot's, and the bridge it hands the tool. */
class AiCommandTest {

    @TempDir Path dir;

    @Test
    void onlyABotsFolderIsAccepted() throws IOException {
        assertTrue(AiCommand.notABot(dir).orElseThrow().contains("no pom.xml"));
        Files.writeString(dir.resolve("pom.xml"), "<project><properties><botmaker.plugin>x.P</botmaker.plugin>"
                + "</properties></project>");
        assertTrue(AiCommand.notABot(dir).orElseThrow().contains("plugin"), "a plugin's author writes its code");
        Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>my-bot</artifactId></project>");
        assertEquals(Optional.empty(), AiCommand.notABot(dir));
    }

    @Test
    void theBridgeIsThisCliRunByThisJava() {
        List<String> bridge = AiCommand.bridgeCommand();
        assertTrue(Files.isExecutable(Path.of(bridge.getFirst())), bridge.getFirst());
        assertEquals(List.of("-cp"), bridge.subList(1, 2));
        assertTrue(Path.of(bridge.get(2).split(File.pathSeparator)[0]).isAbsolute(), bridge.get(2));
        assertEquals(List.of("com.botmaker.cli.Main", "mcp-bridge"), bridge.subList(3, 5));
    }

    @Test
    void outsideABotNothingStarts() {
        int code = new CommandLine(new AiCommand()).execute("codex", "-C", dir.toString(), "--", "exec", "-x");
        assertEquals(CommandLine.ExitCode.USAGE, code);
        assertFalse(Files.exists(dir.resolve(".claude")), "no guard written outside a bot");
        assertEquals(CommandLine.ExitCode.USAGE, new CommandLine(new AiCommand()).execute("cursor", "-C", dir.toString()));
    }
}
