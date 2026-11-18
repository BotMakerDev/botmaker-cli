// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

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

/** {@code botmaker tool} against a fake core: names, options from a schema, a tool's reply, a run, {@code --new}. */
class ToolRunTest {

    /** As the core's {@code operations} writes it: the SDK with a tool and a run, and two plugins named web. */
    private static final String LISTING = """
            {"project": %s, "plugins": [
              {"groupId": "com.github.BotMakerDev", "artifactId": "botmaker-sdk", "name": "BotMaker SDK",
               "version": "1.4.0", "operations": [
                 {"kind": "tool", "name": "crop_picture", "call": "botmaker_sdk_crop_picture",
                  "description": "Cut a picture.", "schema": {"type": "object", "properties": {
                    "name": {"type": "string", "description": "its name"},
                    "area": {"type": "object", "properties": {
                      "x": {"type": "integer"}, "y": {"type": "integer"}}, "required": ["x", "y"]},
                    "look": {"type": "string", "enum": ["exact", "coarse"]},
                    "tags": {"type": "array", "items": {"type": "string"}},
                    "notes": {"type": "object", "additionalProperties": {"type": "string"}},
                    "steps": {"type": "array", "items": {"type": "object", "properties": {}}},
                    "dim": {"type": "boolean"}},
                   "required": ["name", "area"]}},
                 {"kind": "run", "name": "Keep exploring", "description": "", "schema": {"type": "object",
                  "properties": {"minutes": {"type": "integer"}, "maxScreens": {"type": "integer"}},
                  "required": []}}]},
              {"groupId": "a.one", "artifactId": "web", "name": "Web", "version": "1.0.0", "operations": []},
              {"groupId": "b.two", "artifactId": "botmaker-web", "name": "Other web", "version": "2.0.0",
               "operations": []}]}
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
        assertEquals("crop_picture", listed.plugin("sdk").operation("crop-picture").name());
    }

    @Test
    void aSchemaBecomesOptionsAndTheOptionsBecomeItsArgumentsAgain() throws IOException {
        Operations.Operation crop = Operations.read(LISTING.formatted("null")).plugin("sdk").operation("crop-picture");
        OperationOptions options = new OperationOptions("botmaker tool sdk crop-picture", crop);

        Map<String, Object> arguments = options.arguments(List.of("--name", "ore", "--area.x", "3", "--area.y", "4",
                "--look", "coarse", "--tags", "a,b", "--tags", "c", "--notes", "k=v", "--steps", "{\"go\": 1}",
                "--dim"));

        assertEquals(Map.of("name", "ore", "area", Map.of("x", 3L, "y", 4L), "look", "coarse",
                "tags", List.of("a", "b", "c"), "notes", Map.of("k", "v"), "steps", List.of(Map.of("go", 1)),
                "dim", true), arguments);
        assertEquals(List.of("exact", "coarse"), options.choices("--look"));
        assertTrue(options.usage().contains("--area.x=<integer>"), options.usage());
        assertThrows(CommandLine.MissingParameterException.class, () -> options.arguments(List.of("--name", "ore")),
                "a required nested field is required");
        assertThrows(IllegalArgumentException.class, () -> options.arguments(List.of("--name", "o", "--area.x", "1",
                "--area.y", "2", "--steps", "not json")));
    }

    @Test
    void typedItemsStayTypedAndNamesTheShellCannotTellApartAreRefused(@TempDir Path here) throws IOException {
        Operations listed = Operations.read("""
                {"project": null, "plugins": [{"groupId": "g", "artifactId": "x", "name": "X", "version": "1",
                  "operations": [
                    {"kind": "tool", "name": "crop_picture", "call": "x_crop_picture", "schema": {"type": "object",
                     "properties": {"ids": {"type": "array", "items": {"type": "integer"}},
                       "modes": {"type": "array", "items": {"enum": ["a", "b"]}},
                       "weights": {"type": "object", "additionalProperties": {"type": "number"}}}}},
                    {"kind": "run", "name": "Crop picture", "schema": {"type": "object"}},
                    {"kind": "later", "name": "watch", "schema": {"type": "object"}}]}]}
                """);
        Operations.Plugin plugin = listed.plugin("x");
        Operations.Operation crop = plugin.operations().getFirst();
        OperationOptions options = new OperationOptions("botmaker tool x crop-picture", crop);

        assertEquals(Map.of("ids", List.of(1L, 2L), "weights", Map.of("k", 3.0)),
                options.arguments(List.of("--ids", "1,2", "--weights", "k=3")));
        assertTrue(options.usage().contains("--modes=<choice>"), options.usage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> plugin.operation("crop-picture")).getMessage()
                .contains("cannot tell them apart"));
        assertEquals(Operations.Kind.UNKNOWN, plugin.operation("watch").kind());

        FakeCore core = new FakeCore(LISTING.formatted("null"));
        assertEquals(0, tool(core, ask(here, "sdk", "--help")));
        assertTrue(text(out).contains("keep-exploring  (run)"), text(out));
    }

    @Test
    void aToolPrintsItsReplyAndARefusalEndsOne(@TempDir Path here) {
        FakeCore core = new FakeCore(LISTING.formatted("null"));
        core.reply = new CoreTools.Answer("Cut ore.", false);

        int code = tool(core, ask(here, "sdk", "crop-picture", "--name", "ore", "--area.x", "1", "--area.y", "2"));

        assertEquals(0, code);
        assertEquals("Cut ore.\n", text(out));
        assertEquals(Map.of("name", "ore", "area", Map.of("x", 1L, "y", 2L)), core.argumentsOf("botmaker_sdk_crop_picture"));

        core.reply = new CoreTools.Answer("No such area.", true);
        assertEquals(1, tool(core, ask(here, "sdk", "crop-picture", "--name", "o", "--area.x", "1", "--area.y", "2")));
        assertEquals(2, tool(core, ask(here, "sdk", "crop-picture")), "a missing option is a command line's fault");
        assertTrue(text(err).contains("Missing required options"), text(err));
    }

    @Test
    void aRunNeedsABotAndEndsWithItsRunsExitCode(@TempDir Path bot) throws IOException {
        FakeCore none = new FakeCore(LISTING.formatted("null"));
        assertEquals(2, tool(none, ask(Files.createDirectories(bot.resolve("empty")), "sdk", "keep-exploring")));
        assertTrue(text(err).contains("a run needs a bot"), text(err));

        Path project = Files.createDirectories(bot.resolve("farm/src/main/java")).getParent().getParent().getParent();
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        FakeCore core = new FakeCore(LISTING.formatted("\"" + project.toString().replace("\\", "\\\\") + "\""));

        int code = tool(core, ask(project, "sdk", "keep-exploring", "--minutes", "2"));

        assertEquals(130, code, "the run's own exit code");
        assertEquals(Map.of("path", project.toString()), core.argumentsOf("open_project"),
                "a folder that holds a bot is the bot");
        assertEquals(List.of(project + " Keep exploring {\"minutes\":2}"), runs);
    }

    @Test
    void newMakesTheBotWithThePluginBeforeItOpensIt(@TempDir Path parent) {
        Path fresh = parent.resolve("site");
        FakeCore core = new FakeCore(LISTING.formatted("null")) {
            @Override
            public Answer call(String tool, Map<String, Object> arguments) {
                if (tool.equals("open_project")) listing = LISTING.formatted("\"" + fresh.toString()
                        .replace("\\", "\\\\") + "\"");
                return super.call(tool, arguments);
            }
        };
        List<String> made = new ArrayList<>();
        ToolRun run = new ToolRun(core, stream(out), stream(err), (b, e, a) -> 0, (dir, plugin) -> {
            made.add(dir + " " + plugin.coordinate() + " " + plugin.version() + " after " + core.called());
            ToolRun.makeBlank(dir, plugin);
        }, Duration.ofMillis(1), Duration.ofSeconds(1), Optional.empty());

        int code = run.run(new ToolRun.Ask(Optional.of("sdk"), Optional.of("keep-exploring"), List.of(),
                Optional.empty(), Optional.of(fresh), parent));

        assertEquals(0, code, text(err));
        assertEquals(List.of(fresh + " com.github.BotMakerDev:botmaker-sdk 1.4.0 after [operations]"), made);
        assertTrue(core.called().indexOf("open_project") > 0, "opened after it was made");
        assertTrue(ToolRun.isBot(fresh));
    }

    @Test
    void theShellCompletesFromTheLastListing() throws IOException {
        Optional<Operations> cached = Optional.of(Operations.read(LISTING.formatted("null")));

        assertEquals(List.of("sdk", "web", "web", "--project", "--new"), ToolRun.complete(List.of(), cached));
        assertEquals(List.of("crop-picture", "keep-exploring"), ToolRun.complete(List.of("sdk"), cached));
        assertEquals(List.of("--minutes", "--maxScreens", "--help"),
                ToolRun.complete(List.of("sdk", "keep-exploring"), cached));
        assertEquals(List.of("exact", "coarse"), ToolRun.complete(List.of("sdk", "crop-picture", "--look"), cached));
        assertEquals(List.of(), ToolRun.complete(List.of(), Optional.empty()), "no listing yet, nothing offered");
    }

    private int tool(FakeCore core, ToolRun.Ask ask) {
        ToolRun run = new ToolRun(core, stream(out), stream(err), (bot, entry, args) -> {
            runs.add(bot + " " + entry + " " + args.orElse(""));
            return 130;
        }, (dir, plugin) -> {
            throw new IOException("not asked");
        }, Duration.ofMillis(1), Duration.ofMillis(50), Optional.empty());
        return run.run(ask);
    }

    private static ToolRun.Ask ask(Path here, String... words) {
        List<String> all = List.of(words);
        return new ToolRun.Ask(Optional.of(all.get(0)), Optional.of(all.get(1)), all.subList(2, all.size()),
                Optional.empty(), Optional.empty(), here);
    }

    private static PrintStream stream(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    private static String text(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** A core that lists {@link #listing}, opens anything, and answers every other tool {@link #reply}. */
    private static class FakeCore implements CoreTools {
        record Call(String tool, Map<String, Object> arguments) {}

        final List<Call> calls = new ArrayList<>();
        String listing;
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
