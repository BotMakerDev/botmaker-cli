package com.botmaker.cli.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bot's folder guards every client: each one's project file gets what is missing, the user's own settings stay,
 * a foreign file is left alone, and the machine's own files stay out of the bot's versions.
 */
class BotGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path bot;

    private BotGuard.Result claude() {
        return BotGuard.claude(bot, BotGuard.FILE);
    }

    private BotGuard.Result codex(String text) throws IOException {
        Files.createDirectories(bot.resolve(".codex"));
        Files.writeString(bot.resolve(BotGuard.CODEX), text);
        return BotGuard.codex(bot, BotGuard.CODEX);
    }

    private List<String> deny() throws IOException {
        List<String> rules = new ArrayList<>();
        JSON.readTree(bot.resolve(BotGuard.FILE).toFile()).path("permissions").path("deny")
                .forEach(r -> rules.add(r.asText()));
        return rules;
    }

    @Test
    void aBareBotIsGuardedForEveryClientAndASecondOpenWritesNothing() throws IOException {
        List<BotGuard.Guard> first = BotGuard.ensure(bot);
        assertTrue(first.stream().allMatch(g -> g.result() == BotGuard.Result.WRITTEN), first.toString());
        assertEquals(BotGuard.DENY, deny());
        JsonNode opencode = JSON.readTree(bot.resolve(BotGuard.OPENCODE).toFile());
        assertEquals("deny", opencode.path("permission").path("edit").asText());
        assertEquals("ask", opencode.path("permission").path("bash").asText());
        assertEquals("https://opencode.ai/config.json", opencode.path("$schema").asText());
        assertTrue(opencode.path("mcp").isMissingNode(), "the server's command is this machine's, not the bot's");
        assertEquals(BotGuard.CODEX_CONFIG, Files.readString(bot.resolve(BotGuard.CODEX)));
        assertTrue(Files.readString(bot.resolve(BotGuard.AGENTS)).contains("plugin_guide"));
        assertTrue(first.stream().allMatch(g -> g.warning(bot).isEmpty()));

        List<BotGuard.Guard> second = BotGuard.ensure(bot);
        assertTrue(second.stream().allMatch(g -> g.result() == BotGuard.Result.ALREADY), second.toString());
    }

    @Test
    void theMachinesOwnFilesStayOutOfTheBotsVersions() throws IOException {
        Path exclude = Files.createDirectories(bot.resolve(".git/info")).resolve("exclude");
        Files.writeString(exclude, "/.botmaker/");
        BotGuard.ensure(bot);
        BotGuard.ensure(bot);
        List<String> lines = Files.readString(exclude).lines().toList();
        assertEquals("/.botmaker/", lines.getFirst(), "the lines there stay, and a newline is not lost");
        for (String line : BotGuard.EXCLUDED) {
            assertEquals(1, lines.stream().filter(line::equals).count(), line + " once: " + lines);
        }
        assertTrue(lines.stream().noneMatch(l -> l.contains(".claude")), "Claude Code's settings were shared before");
    }

    @Test
    void theUsersOwnSettingsAndRulesStay() throws IOException {
        Files.createDirectories(bot.resolve(".claude"));
        Files.writeString(bot.resolve(BotGuard.FILE), """
                {"model": "opus", "permissions": {"allow": ["Bash(git *)"], "deny": ["WebFetch", "Write(./src/**)"]}}
                """);
        assertEquals(BotGuard.Result.WRITTEN, claude());
        JsonNode root = JSON.readTree(bot.resolve(BotGuard.FILE).toFile());
        assertEquals("opus", root.path("model").asText());
        assertEquals("Bash(git *)", root.path("permissions").path("allow").get(0).asText());
        List<String> rules = deny();
        assertEquals("WebFetch", rules.get(0));
        assertTrue(rules.containsAll(BotGuard.DENY));
    }

    @Test
    void aRuleAlreadyThereIsNotDoubledAndTheOldGuardsRulesGo() throws IOException {
        Files.createDirectories(bot.resolve(".claude"));
        Files.writeString(bot.resolve(BotGuard.FILE), """
                {"permissions": {"deny": ["Edit(./src/**)", "MultiEdit(./src/**)", "Write(./src/**)", "Read(./x)"]}}
                """);
        assertEquals(BotGuard.Result.WRITTEN, claude());
        List<String> rules = deny();
        assertEquals(1, rules.stream().filter("Edit(./src/**)"::equals).count(), "a rule present is not doubled");
        assertTrue(rules.stream().noneMatch(BotGuard.OBSOLETE::contains), rules.toString());
        assertTrue(rules.contains("Read(./x)"), "the user's own rule stays");
        assertEquals(BotGuard.Result.ALREADY, claude());
    }

    @Test
    void aFileThatIsNotSettingsIsLeftAlone() throws IOException {
        Files.createDirectories(bot.resolve(".claude"));
        Files.writeString(bot.resolve(BotGuard.FILE), "not json {");
        assertEquals(BotGuard.Result.LEFT_ALONE, claude());
        assertEquals("not json {", Files.readString(bot.resolve(BotGuard.FILE)));
        Files.writeString(bot.resolve(BotGuard.FILE), "{\"permissions\": {\"deny\": \"Edit\"}}");
        assertEquals(BotGuard.Result.LEFT_ALONE, claude());
    }

    @Test
    void opencodeKeepsTheUsersSettingsAndChoices() throws IOException {
        Path file = bot.resolve(BotGuard.OPENCODE);
        Files.writeString(file, """
                {"model": "ollama/qwen", "permission": {"bash": "deny", "webfetch": "allow"}}
                """);
        assertEquals(BotGuard.Result.WRITTEN, BotGuard.opencode(bot, BotGuard.OPENCODE));
        JsonNode root = JSON.readTree(file.toFile());
        assertEquals("ollama/qwen", root.path("model").asText());
        assertEquals("deny", root.path("permission").path("edit").asText());
        assertEquals("deny", root.path("permission").path("bash").asText(), "the user's stricter choice stays");
        assertEquals("allow", root.path("permission").path("webfetch").asText());
        assertTrue(root.path("$schema").isMissingNode(), "only a file BotMaker creates gets the schema");

        Files.writeString(file, "{}");
        assertEquals(BotGuard.Result.WRITTEN, BotGuard.opencode(bot, BotGuard.OPENCODE));
        assertTrue(JSON.readTree(file.toFile()).path("$schema").isMissingNode(), "an empty file is still the user's");

        Files.writeString(file, "{\"permission\": {\"edit\": \"allow\"}}");
        assertEquals(BotGuard.Result.LEFT_ALONE, BotGuard.opencode(bot, BotGuard.OPENCODE),
                "the user allowed edits: said, not undone");
        Files.writeString(file, "{ // jsonc\n}");
        assertEquals(BotGuard.Result.LEFT_ALONE, BotGuard.opencode(bot, BotGuard.OPENCODE),
                "a comment would be lost on a rewrite");
    }

    @Test
    void anOpencodeJsoncIsNotShadowedByASecondConfig() throws IOException {
        Files.writeString(bot.resolve("opencode.jsonc"), "{ // mine\n}");
        assertEquals(BotGuard.Result.LEFT_ALONE, BotGuard.opencode(bot, BotGuard.OPENCODE));
        assertTrue(Files.notExists(bot.resolve(BotGuard.OPENCODE)));
    }

    @Test
    void codexGetsTheReadOnlySandboxAboveItsTables() throws IOException {
        assertEquals(BotGuard.Result.WRITTEN, codex("""
                model = "gpt-5"

                [profiles.fast]
                sandbox_mode = "workspace-write"
                """), "a table's sandbox_mode is not the top level's");
        String text = Files.readString(bot.resolve(BotGuard.CODEX));
        assertTrue(text.startsWith(BotGuard.CODEX_CONFIG), text);
        assertTrue(text.endsWith("sandbox_mode = \"workspace-write\"\n"), text);
        assertEquals(BotGuard.Result.ALREADY, BotGuard.codex(bot, BotGuard.CODEX));
    }

    @Test
    void codexNeverGetsASecondSandboxKey() throws IOException {
        for (String mine : List.of("sandbox_mode = 'danger-full-access'\n", "﻿sandbox_mode = \"workspace-write\"\n",
                "\"sandbox_mode\" = \"workspace-write\"\n", "notes = '''\n[x]\n'''\nsandbox_mode = \"workspace-write\"\n")) {
            assertEquals(BotGuard.Result.LEFT_ALONE, codex(mine), mine);
            assertEquals(mine, Files.readString(bot.resolve(BotGuard.CODEX)));
        }
        assertEquals(BotGuard.Result.ALREADY, codex("sandbox_mode = 'read-only' # mine\n"));
    }

    @Test
    void anAgentsFileIsNeverRewritten() throws IOException {
        Path file = bot.resolve(BotGuard.AGENTS);
        Files.writeString(file, "# The author's own\n");
        assertEquals(BotGuard.Result.ALREADY, BotGuard.agents(bot, BotGuard.AGENTS));
        assertEquals("# The author's own\n", Files.readString(file));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "creating a symbolic link needs a privilege there")
    void aLinkedSettingsFileOrFolderIsNotFollowed(@TempDir Path dotfiles) throws IOException {
        Path shared = Files.writeString(dotfiles.resolve("settings.json"), "{}");
        Files.createDirectories(bot.resolve(".claude"));
        Files.createSymbolicLink(bot.resolve(BotGuard.FILE), shared);
        assertEquals(BotGuard.Result.LEFT_ALONE, claude());
        assertEquals("{}", Files.readString(shared));

        Files.writeString(dotfiles.resolve("config.toml"), "model = 'x'\n");
        Files.createSymbolicLink(bot.resolve(".codex"), dotfiles);
        assertEquals(BotGuard.Result.LEFT_ALONE, BotGuard.codex(bot, BotGuard.CODEX));
        assertEquals("model = 'x'\n", Files.readString(dotfiles.resolve("config.toml")));
    }
}
