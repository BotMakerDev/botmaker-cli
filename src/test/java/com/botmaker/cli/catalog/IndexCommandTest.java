// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The index following GitHub's renames and transfers (spec 2026-11-21 § 3). */
class IndexCommandTest {

    private static final PluginEntry THING = new PluginEntry("somedev/thing", List.of(FakeFacts.pluginRelease("v1")));

    @TempDir
    Path dir;

    @Test
    void movesListsRenamed() {
        FakeFacts facts = new FakeFacts().repo("somedev/thing", 2, "newdev/thing2", "", null);
        List<IndexCommand.Move> moves = IndexCommand.moves(List.of(THING), facts);
        assertEquals(List.of(new IndexCommand.Move(THING, THING.moved("newdev/thing2"))), moves);
        assertEquals(List.of("somedev/thing"), moves.getFirst().to().formerly());
    }

    @Test
    void aRenameBackDropsTheSlugFromFormerly() {
        PluginEntry back = THING.moved("newdev/thing2").moved("somedev/thing");
        assertEquals("somedev/thing", back.repo());
        assertEquals(List.of("newdev/thing2"), back.formerly());
    }

    @Test
    void movesSkipsCaseOnly() {
        FakeFacts facts = new FakeFacts().repo("somedev/thing", 2, "SomeDev/Thing", "", null);
        assertEquals(List.of(), IndexCommand.moves(List.of(THING), facts));
        assertEquals(List.of(), IndexCommand.moves(List.of(THING), new FakeFacts()), "a repository gone is left");
    }

    @Test
    void moveChangeRenamesFile() throws IOException {
        Files.createDirectories(dir.resolve("plugins"));
        Files.writeString(dir.resolve(EntryPath.plugin(THING.repo())), FakeFacts.json(THING));
        FakeFacts facts = new FakeFacts().repo("somedev/thing", 2, "newdev/thing2", "A thing.", null)
                .repo("newdev/thing2", 2, "newdev/thing2", "A thing.", null)
                .release("newdev/thing2", "v1", "newdev")
                .pom("newdev/thing2", "v1", "<project><name>Thing</name></project>");
        List<String> said = new ArrayList<>();

        assertTrue(IndexCommand.write(dir, facts, "2026-11-21T18:00:00Z", said::add));

        assertFalse(Files.exists(dir.resolve("plugins/somedev__thing.json")));
        PluginEntry moved = PluginEntry.read(dir.resolve("plugins/newdev__thing2.json"));
        assertEquals(THING.moved("newdev/thing2"), moved);
        assertEquals(List.of("moved somedev/thing to newdev/thing2"), said);
        Catalog.Plugin listed = Catalog.read(dir.resolve(Catalog.FILE)).orElseThrow().plugins().getFirst();
        assertEquals("newdev/thing2", listed.repo());
        assertEquals(List.of("somedev/thing"), listed.formerly());
    }

    @Test
    void aMoveOntoAnEntryAlreadyListedIsLeft() throws IOException {
        Files.createDirectories(dir.resolve("plugins"));
        Files.writeString(dir.resolve(EntryPath.plugin(THING.repo())), FakeFacts.json(THING));
        PluginEntry other = new PluginEntry("newdev/thing2", List.of(FakeFacts.pluginRelease("v1")));
        Files.writeString(dir.resolve(EntryPath.plugin(other.repo())), FakeFacts.json(other));
        FakeFacts facts = new FakeFacts().repo("somedev/thing", 2, "newdev/thing2", "", null)
                .repo("newdev/thing2", 2, "newdev/thing2", "", null);
        List<String> said = new ArrayList<>();

        IndexCommand.write(dir, facts, "2026-11-21T18:00:00Z", said::add);

        assertTrue(Files.exists(dir.resolve("plugins/somedev__thing.json")), "left for the admin");
        assertTrue(said.getFirst().startsWith("not moved"), said.toString());
    }
}
