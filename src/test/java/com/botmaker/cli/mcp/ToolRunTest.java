// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code botmaker tool} against a fake core: names, a tool's reply, a tool that needs values, a run in its bot. */
class ToolRunTest {

    /** As the core's {@code operations} writes it: the SDK with two tools and a run, and two plugins named web. */
    private static final String LISTING = """
            {"project": %s, "plugins": [
              {"groupId": "com.github.BotMakerDev", "artifactId": "botmaker-sdk", "name": "BotMaker SDK",
               "version": "1.4.0", "operations": [
                 {"kind": "tool", "name": "crop_picture", "call": "botmaker_sdk_crop_picture",
                  "description": "Cut a picture.", "schema": {"type": "object", "properties": {
                    "name": {"type": "string"}, "look": {"type": "string"}}, "required": ["name", "area"]}},
                 {"kind": "tool", "name": "describe", "call": "botmaker_sdk_describe",
                  "description": "Say what is on the game.", "schema": {"type": "object", "properties": {
                    "scale": {"type": "number"}}}},
                 {"kind": "run", "name": "Keep exploring", "description": "", "schema": {"type": "object",
                  "properties": {"minutes": {"type": "integer"}}, "required": []}}]},
              {"groupId": "a.one", "artifactId": "web", "name": "Web", "version": "1.0.0", "operations": []},
              {"groupId": "b.two", "artifactId": "botmaker-web", "name": "Other web", "version": "2.0.0",
               "operations": [{"kind": "later", "name": "watch", "schema": {"type": "object"}}]}]}
            """;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final List<String> runs = new ArrayList<>();

    @Test
    void aPluginIsNamedShortOrByCoordinateAndAShortNameTwoShareIsRefusedListingBoth() throws IOException {
        Operations listed = Operations.read(LISTING.formatted("null"));

        assertEquals("botmaker-sdk", listed.plugin("sdk").artifactId());
        assertEquals("botmaker-sdk", listed.plugin("com.github.BotMakerDev:botmaker-sdk").artifactId());
        IllegalArgumentException both = assertThrows(IllegalArgumentException.class, () -> listed.plugin("web"));
        assertTrue(both.getMessage().contains("a.one:web") && both.getMessage().contains("b.two:botmaker-web"),
                both.getMessage());
        assertEquals("Web", listed.plugin("a.one:web").name());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> listed.plugin("nope")).getMessage()
                .contains("These have: sdk, web, web"));
        assertEquals("Keep exploring", listed.plugin("sdk").operation("keep-exploring").name());
        assertEquals(Operations.Kind.UNKNOWN, listed.plugin("b.two:botmaker-web").operation("watch").kind());

        Operations twins = Operations.read("""
                {"project": null, "plugins": [{"groupId": "g", "artifactId": "x", "name": "X", "version": "1",
                  "operations": [{"kind": "tool", "name": "crop_picture", "call": "x_crop_picture",
                                  "schema": {"type": "object"}},
                                 {"kind": "run", "name": "Crop picture", "schema": {"type": "object"}}]}]}
                """);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> twins.plugin("x").operation("crop-picture"))
                .getMessage().contains("cannot tell them apart"));
    }

    @Test
    void aPluginsListSaysWhichOperationsNeedValues(@TempDir Path here) {
        assertEquals(0, tool(new FakeCore(LISTING.formatted("null")), ask(here, "sdk")));

        assertTrue(text(out).contains("crop-picture  (tool)  needs name, area: Studio or the assistant"), text(out));
        assertTrue(text(out).contains("keep-exploring  (run)\n"), text(out));
    }

    @Test
    void aToolIsCalledWithNoValuesAndOneThatNeedsSomeIsRefused(@TempDir Path here) {
        FakeCore core = new FakeCore(LISTING.formatted("null"));
        core.reply = new CoreTools.Answer("A title screen.", false);

        assertEquals(0, tool(core, ask(here, "sdk", "describe")));
        assertEquals("A title screen.\n", text(out));
        assertEquals(Map.of(), core.argumentsOf("botmaker_sdk_describe"));

        core.reply = new CoreTools.Answer("No game.", true);
        assertEquals(1, tool(core, ask(here, "sdk", "describe")), "the tool's refusal");
        assertEquals(2, tool(core, ask(here, "sdk", "crop-picture")));
        assertTrue(text(err).contains("crop_picture needs name, area"), text(err));
        assertTrue(core.called().stream().noneMatch("botmaker_sdk_crop_picture"::equals), "never called");
    }

    @Test
    void aRunNeedsABotAndEndsWithItsRunsExitCode(@TempDir Path bot) throws IOException {
        FakeCore none = new FakeCore(LISTING.formatted("null"));
        assertEquals(2, tool(none, ask(Files.createDirectories(bot.resolve("empty")), "sdk", "keep-exploring")));
        assertTrue(text(err).contains("a run needs a bot"), text(err));

        Path project = Files.createDirectories(bot.resolve("farm/src/main/java")).getParent().getParent().getParent();
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        FakeCore core = new FakeCore(LISTING.formatted("\"" + project.toString().replace("\\", "\\\\") + "\""));

        int code = tool(core, ask(project, "sdk", "keep-exploring"));

        assertEquals(130, code, "the run's own exit code");
        assertEquals(Map.of("path", project.toString()), core.argumentsOf("open_project"),
                "a folder that holds a bot is the bot");
        assertEquals(List.of(project + " Keep exploring"), runs);
    }

    private int tool(FakeCore core, ToolRun.Ask ask) {
        ToolRun run = new ToolRun(core, stream(out), stream(err), (bot, entry) -> {
            runs.add(bot + " " + entry);
            return 130;
        }, Duration.ofMillis(1), Duration.ofMillis(50));
        return run.run(ask);
    }

    private static ToolRun.Ask ask(Path here, String plugin) {
        return new ToolRun.Ask(Optional.of(plugin), Optional.empty(), Optional.empty(), here);
    }

    private static ToolRun.Ask ask(Path here, String plugin, String operation) {
        return new ToolRun.Ask(Optional.of(plugin), Optional.of(operation), Optional.empty(), here);
    }

    private static PrintStream stream(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    /** What was printed, its line ends {@code \n} on every platform. */
    private static String text(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n");
    }

    /** A core that lists {@link #listing}, opens anything, and answers every other tool {@link #reply}. */
    private static class FakeCore implements CoreTools {
        record Call(String tool, Map<String, Object> arguments) {}

        final List<Call> calls = new ArrayList<>();
        final String listing;
        Answer reply = new Answer("", false);

        FakeCore(String listing) {
            this.listing = listing;
        }

        List<String> called() {
            return calls.stream().map(Call::tool).toList();
        }

        Map<String, Object> argumentsOf(String tool) {
            return calls.stream().filter(c -> c.tool().equals(tool)).findFirst().orElseThrow().arguments();
        }

        @Override
        public Answer call(String tool, Map<String, Object> arguments) {
            calls.add(new Call(tool, arguments));
            return switch (tool) {
                case "operations" -> new Answer(listing, false);
                case "open_project" -> new Answer("Opened.", false);
                default -> reply;
            };
        }
    }
}
