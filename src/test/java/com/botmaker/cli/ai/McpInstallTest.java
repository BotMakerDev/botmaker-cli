package com.botmaker.cli.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code botmaker mcp install}: the server's own entry written into each tool's user config, replaced on a second
 * install, everything else kept, and a file it cannot be sure of left alone.
 */
class McpInstallTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final McpInstall.Server SERVER = new McpInstall.Server(
            List.of("C:\\jdk\\bin\\java.exe", "-jar", "C:\\cli\\botmaker-all.jar", "mcp-bridge"), Map.of());
    private static final McpInstall.Server DEV = new McpInstall.Server(
            List.of("/jdk/bin/java", "-jar", "/u/botmaker-cli/target/b-all.jar", "mcp-bridge", "--umbrella", "/u"),
            Map.of("MAVEN_HOME", "/opt/maven"));

    @TempDir Path home;

    @Test
    void eachToolsUserConfigIsWhereTheToolReadsIt() {
        assertEquals(home.resolve(".codex/config.toml"), McpInstall.configFile(AiTool.CODEX, home, Map.of()).orElseThrow());
        assertEquals(Path.of("/c/config.toml"),
                McpInstall.configFile(AiTool.CODEX, home, Map.of("CODEX_HOME", "/c")).orElseThrow());
        assertEquals(home.resolve(".config/opencode/opencode.json"),
                McpInstall.configFile(AiTool.OPENCODE, home, Map.of()).orElseThrow());
        assertEquals(home.resolve(".gemini/settings.json"),
                McpInstall.configFile(AiTool.GEMINI, home, Map.of()).orElseThrow());
        assertTrue(McpInstall.configFile(AiTool.CLAUDE_CODE, home, Map.of()).isEmpty(), "registered by claude mcp add");
    }

    @Test
    void codexGetsItsTableAndASecondInstallReplacesIt() throws IOException {
        Path file = home.resolve("config.toml");
        Files.writeString(file, """
                model = "gpt-5"

                [mcp_servers.botmaker-studio]
                command = 'old'
                args = []

                [mcp_servers.botmaker-studio.env]
                X = '1'

                [mcp_servers.other]
                command = 'other'
                """);
        assertEquals(McpInstall.Result.WRITTEN, McpInstall.codex(file, DEV).result());
        String text = Files.readString(file);
        assertTrue(text.startsWith("model = \"gpt-5\"\n\n[mcp_servers.other]\ncommand = 'other'\n\n"), text);
        assertTrue(text.endsWith("""
                [mcp_servers.botmaker-studio]
                command = '/jdk/bin/java'
                args = ['-jar','/u/botmaker-cli/target/b-all.jar','mcp-bridge','--umbrella','/u']
                env = { MAVEN_HOME = '/opt/maven' }
                """), text);
        assertTrue(!text.contains("old") && !text.contains("X = "), "the earlier entry and its nested table went");
        assertEquals(McpInstall.Result.ALREADY, McpInstall.codex(file, DEV).result());
        assertEquals(McpInstall.Result.WRITTEN, McpInstall.codex(file, SERVER).result());
        assertTrue(Files.readString(file).contains("command = 'C:\\jdk\\bin\\java.exe'"), "a Windows path as it is");
    }

    @Test
    void aMissingCodexConfigIsCreated() throws IOException {
        Path file = home.resolve(".codex/config.toml");
        assertEquals(McpInstall.Result.WRITTEN, McpInstall.codex(file, SERVER).result());
        assertEquals(McpInstall.codexTable(SERVER), Files.readString(file));
    }

    @Test
    void aCodexConfigItCannotBeSureOfIsLeftAlone() throws IOException {
        Path file = home.resolve("config.toml");
        for (String mine : List.of("notes = '''\n[x]\n'''\n", "mcp_servers.botmaker-studio.command = 'x'\n",
                "[mcp_servers]\nbotmaker-studio = { command = 'x' }\n")) {
            Files.writeString(file, mine);
            assertEquals(McpInstall.Result.LEFT_ALONE, McpInstall.codex(file, SERVER).result(), mine);
            assertEquals(mine, Files.readString(file));
        }
    }

    @Test
    void opencodeKeepsTheUsersConfig() throws IOException {
        Path file = home.resolve("opencode.json");
        assertEquals(McpInstall.Result.WRITTEN, McpInstall.opencode(file, DEV).result());
        JsonNode root = JSON.readTree(file.toFile());
        assertEquals(McpInstall.OPENCODE_SCHEMA, root.path("$schema").asText(), "a file it creates gets the schema");
        JsonNode entry = root.path("mcp").path(AiTool.SERVER);
        assertEquals("local", entry.path("type").asText());
        assertEquals(DEV.command(), JSON.convertValue(entry.path("command"), List.class));
        assertEquals("/opt/maven", entry.path("environment").path("MAVEN_HOME").asText());
        assertEquals(McpInstall.Result.ALREADY, McpInstall.opencode(file, DEV).result());

        Files.writeString(file, "{\"model\": \"ollama/qwen\", \"mcp\": {\"mine\": {\"type\": \"local\"}}}");
        assertEquals(McpInstall.Result.WRITTEN, McpInstall.opencode(file, SERVER).result());
        root = JSON.readTree(file.toFile());
        assertEquals("ollama/qwen", root.path("model").asText());
        assertEquals("local", root.path("mcp").path("mine").path("type").asText());
        assertTrue(root.path("$schema").isMissingNode(), "the user's file stays theirs");
        assertTrue(root.path("mcp").path(AiTool.SERVER).path("environment").isMissingNode());
    }

    @Test
    void opencodeLeavesAFileWithCommentsAlone() throws IOException {
        Files.writeString(home.resolve("opencode.jsonc"), "{ // mine\n}");
        Path file = home.resolve("opencode.json");
        assertEquals(McpInstall.Result.LEFT_ALONE, McpInstall.opencode(file, SERVER).result());
        assertTrue(Files.notExists(file), "a second file would shadow the user's");
        Files.writeString(file, "{ // mine\n}");
        assertEquals(McpInstall.Result.LEFT_ALONE, McpInstall.opencode(file, SERVER).result());
        Files.writeString(file, "{\"mcp\": []}");
        assertEquals(McpInstall.Result.LEFT_ALONE, McpInstall.opencode(file, SERVER).result());
    }

    @Test
    void geminiGetsItsServerAndKeepsTheRest() throws IOException {
        Path file = home.resolve("settings.json");
        Files.writeString(file, "{\"theme\": \"Dracula\", \"mcpServers\": {\"mine\": {\"command\": \"x\"}}}");
        assertEquals(McpInstall.Result.WRITTEN, McpInstall.gemini(file, DEV).result());
        JsonNode root = JSON.readTree(file.toFile());
        assertEquals("Dracula", root.path("theme").asText());
        assertEquals("x", root.path("mcpServers").path("mine").path("command").asText());
        JsonNode entry = root.path("mcpServers").path(AiTool.SERVER);
        assertEquals("/jdk/bin/java", entry.path("command").asText());
        assertEquals("--umbrella", entry.path("args").get(3).asText());
        assertTrue(entry.path("trust").isMissingNode(), "every call still asks: the user's own sessions, unguarded");
        assertTrue(root.path("$schema").isMissingNode());
    }

    @Test
    void claudeCodeIsRegisteredByItsOwnCommandInUserScope() {
        List<List<String>> commands = McpInstall.claudeCommands(Path.of("claude"), DEV);
        assertEquals(List.of("claude", "mcp", "remove", AiTool.SERVER, "--scope", "user"), commands.getFirst());
        assertEquals(List.of("claude", "mcp", "add", AiTool.SERVER, "--scope", "user", "-e", "MAVEN_HOME=/opt/maven",
                "--", "/jdk/bin/java", "-jar", "/u/botmaker-cli/target/b-all.jar", "mcp-bridge", "--umbrella", "/u"),
                commands.getLast(), "the name before -e, which takes several values");
    }

    @Test
    void theDevBuildIsFoundInTheCheckout() throws IOException {
        Files.createDirectories(home.resolve("botmaker-studio"));
        Files.writeString(home.resolve("botmaker-studio/pom.xml"), "<project/>");
        Path target = Files.createDirectories(home.resolve("botmaker-cli/target"));
        Files.writeString(home.resolve("botmaker-cli/pom.xml"), "<project/>");
        assertEquals(home.toAbsolutePath().normalize(),
                McpInstall.umbrellaAbove(Files.createDirectories(home.resolve("botmaker-cli/src"))).orElseThrow());
        assertTrue(McpInstall.builtJar(home).isEmpty());
        Path old = Files.writeString(target.resolve("botmaker-cli-0.1.0-SNAPSHOT-all.jar"), "");
        Path built = Files.writeString(target.resolve("botmaker-cli-0.2.0-SNAPSHOT-all.jar"), "");
        Files.writeString(target.resolve("botmaker-cli-0.2.0-SNAPSHOT.jar"), "");
        assertTrue(old.toFile().setLastModified(1_000));
        assertEquals(built, McpInstall.builtJar(home).orElseThrow(), "the newest -all jar");
    }
}
