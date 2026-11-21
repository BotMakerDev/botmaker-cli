// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.catalog;

import com.networknt.schema.Error;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.botmaker.cli.catalog.FakeFacts.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The catalog's {@code schema/} files (botmaker-catalog, a sibling checkout) describe what {@link EntryGate} accepts
 * and {@link IndexCommand} writes: the records' JSON, as the gate tests write their fixtures, and every file the
 * catalog holds now. Skipped where the catalog is not checked out beside the cli.
 */
class SchemaFilesTest {

    private static final Path CATALOG = Path.of("..", "botmaker-catalog");
    private static final String ID = "https://github.com/BotMakerDev/botmaker-catalog/schema/";
    private static final String COMMIT = "a".repeat(40);
    private static final String SHA = "b".repeat(64);
    private static final ApprovedRelease.Yanked YANKED =
            new ApprovedRelease.Yanked("crashes on start", "2026-11-21T10:00:00Z");
    private static final Advisory ADVISORY = new Advisory("BMA-2026-0001", "somedev/firestone", List.of("v1.2.0"),
            Advisory.Severity.HIGH, "sends the account's password to a third party",
            "https://example.org/report", "2026-11-21", null);

    private static Schema entry;
    private static Schema bot;
    private static Schema plugin;
    private static Schema advisory;
    private static Schema catalog;

    @BeforeAll
    static void load() {
        assumeTrue(Files.isDirectory(CATALOG.resolve("schema")), "botmaker-catalog is not checked out beside the cli");
        // Each schema's $id is its address on GitHub; the files beside the cli answer for it, nothing is fetched.
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(SchemaFilesTest::read));
        entry = registry.getSchema(SchemaLocation.of(ID + "entry.json"));
        bot = registry.getSchema(SchemaLocation.of(ID + "entry.json#/$defs/bot"));
        plugin = registry.getSchema(SchemaLocation.of(ID + "entry.json#/$defs/plugin"));
        advisory = registry.getSchema(SchemaLocation.of(ID + "advisory.json"));
        catalog = registry.getSchema(SchemaLocation.of(ID + "catalog.json"));
    }

    @Test
    void fixturesMatchSchemas() {
        BotEntry movedBot = new BotEntry("newdev/firestone2", List.of(ApprovedRelease.ofBot("v1.0.0", COMMIT),
                ApprovedRelease.ofBot("v1.2.0", COMMIT, SHA).yank(YANKED)), true, List.of("somedev/firestone"));
        PluginEntry pluginEntry = new PluginEntry("BotMakerDev/botmaker-sdk",
                List.of(new ApprovedRelease("v1.7.0", COMMIT, SHA, SHA).yank(YANKED)));
        assertValid(bot, json(movedBot));
        assertValid(bot, json(new BotEntry("somedev/firestone", List.of(ApprovedRelease.ofBot("v1.0.0", COMMIT)),
                false)));
        assertValid(plugin, json(pluginEntry));
        assertValid(entry, json(movedBot));
        assertValid(entry, json(pluginEntry));
        assertValid(advisory, json(ADVISORY));
        assertValid(advisory, json(ADVISORY.withdraw("the release was misread; nothing leaves the machine")));

        List<DerivedFacts.Pin> pins = List.of(new DerivedFacts.Pin("BotMakerDev/botmaker-sdk", "v1.7.0"));
        Catalog.Plugin listedPlugin = new Catalog.Plugin("BotMakerDev/botmaker-sdk", Rank.ADMIN,
                "com.github.BotMakerDev:botmaker-sdk", "BotMaker SDK", "Game bots", List.of("games"), 3,
                List.of(new Catalog.Plugin.Release("v1.7.0", COMMIT, SHA, SHA, "v0.18.0",
                        List.of("org.example:lib:1.0"), List.of(), "2026-11-20T09:00:00Z", YANKED)),
                "2026-11-20T09:00:00Z", List.of("LiQiyeDev/botmaker-sdk"));
        Catalog.Bot listedBot = new Catalog.Bot("newdev/firestone2", Rank.COMMUNITY, "Plays Firestone",
                List.of("idle"), 0, "somedev/firestone",
                List.of(new Catalog.Bot.Release("v1.2.0", COMMIT, pins, SHA, "2026-11-20T09:00:00Z", 12,
                        List.of(Platform.LINUX, Platform.WINDOWS), YANKED),
                        new Catalog.Bot.Release("v1.0.0", COMMIT, pins)),
                "2026-11-20T09:00:00Z", 12, List.of("somedev/firestone"));
        assertValid(catalog, json(new Catalog(Catalog.SCHEMA_VERSION, "2026-11-21T00:00:00Z",
                List.of(listedPlugin), List.of(listedBot), List.of(ADVISORY))));
        assertValid(catalog, json(new Catalog(Catalog.SCHEMA_VERSION, "2026-11-21T00:00:00Z", List.of(),
                List.of())));
    }

    @Test
    void catalogFilesMatchSchemas() throws IOException {
        List<String> broken = new ArrayList<>();
        check(CATALOG.resolve("bots"), bot, broken);
        check(CATALOG.resolve("plugins"), plugin, broken);
        check(CATALOG.resolve("advisories"), advisory, broken);
        assertEquals(List.of(), broken);
    }

    @Test
    void schemasRefuseWhatTheCheckRefuses() {
        assertInvalid(advisory, json(new Advisory("BMA-26-1", "somedev/firestone", List.of("v1.2.0"),
                Advisory.Severity.HIGH, "s", null, "2026-11-21", null)));
        assertInvalid(advisory, json(ADVISORY).replace("\"high\"", "\"severe\""));
        assertInvalid(advisory, json(ADVISORY).replace("\"2026-11-21\"", "\"21/11/2026\""));
        assertInvalid(bot, json(new BotEntry("somedev/firestone",
                List.of(ApprovedRelease.ofBot("v1.0.0", COMMIT).yank(new ApprovedRelease.Yanked("x".repeat(301),
                        "2026-11-21T10:00:00Z"))), false)));
        assertInvalid(plugin, json(new BotEntry("somedev/firestone",
                List.of(ApprovedRelease.ofBot("v1.0.0", COMMIT)), false)));
        assertInvalid(bot, "{\"repo\":\"somedev/firestone\",\"releases\":[]}");
    }

    private static void check(Path folder, Schema schema, List<String> broken) throws IOException {
        if (!Files.isDirectory(folder)) return;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                List<Error> errors = validate(schema, Files.readString(file));
                if (!errors.isEmpty()) broken.add(file.getFileName() + ": " + errors);
            }
        }
    }

    /** The schema file whose $id is {@code iri}, or {@code null} for any other address. */
    private static String read(String iri) {
        if (!iri.startsWith(ID)) return null;
        try {
            return Files.readString(CATALOG.resolve("schema").resolve(iri.substring(ID.length())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertValid(Schema schema, String json) {
        assertEquals(List.of(), validate(schema, json), json);
    }

    private static void assertInvalid(Schema schema, String json) {
        assertFalse(validate(schema, json).isEmpty(), json);
    }

    /** {@code format} is only an annotation by default in 2020-12: the check holds dates to it, so do we. */
    private static List<Error> validate(Schema schema, String json) {
        return schema.validate(json, InputFormat.JSON,
                (ExecutionContext context) -> context.executionConfig(config -> config.formatAssertionsEnabled(true)));
    }
}
