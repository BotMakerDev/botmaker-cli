// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StudioPlacesTest {

    @Test
    void whatSetSavesIsReadBackAndNoFileIsNone(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config/" + StudioPlaces.FILE);
        assertEquals(StudioPlaces.NONE, StudioPlaces.read(file));
        StudioPlaces places = new StudioPlaces(Optional.of(Path.of("/work/botmaker")),
                Optional.of("/opt/Botmaker Studio/bin/BotMaker Studio"));
        places.write(file);
        assertEquals(places, StudioPlaces.read(file));
    }

    @Test
    void aTypedReleasedStudioOutranksASavedCheckoutAndAMovedOneIsSkipped(@TempDir Path dir) throws IOException {
        String before = System.getProperty("botmaker.config.dir");
        Path checkout = dir.resolve("botmaker");
        Files.createDirectories(checkout.resolve("botmaker-studio"));
        Files.writeString(checkout.resolve("botmaker-studio/pom.xml"), "<project/>");
        System.setProperty("botmaker.config.dir", dir.resolve("config").toString());
        try {
            new StudioPlaces(Optional.of(checkout), Optional.empty()).save();
            assertEquals(Optional.of(checkout), StudioPlaces.umbrellaFirst(null, null));
            assertEquals(Optional.empty(), StudioPlaces.umbrellaFirst(null, "/opt/studio"));
            assertEquals(Optional.of(Path.of("/typed")), StudioPlaces.umbrellaFirst("/typed", "/opt/studio"));
            new StudioPlaces(Optional.of(dir.resolve("moved")), Optional.empty()).save();
            assertEquals(Optional.empty(), StudioPlaces.umbrellaFirst(null, null));
        } finally {
            if (before == null) System.clearProperty("botmaker.config.dir");
            else System.setProperty("botmaker.config.dir", before);
        }
    }

    @Test
    void anUnreadablePathInTheFileIsNoCheckout(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve(StudioPlaces.FILE), "umbrella=a\\u0000b\ncommand=studio\n");
        assertEquals(new StudioPlaces(Optional.empty(), Optional.of("studio")), StudioPlaces.read(file));
    }

    @Test
    void theCheckoutIsTheUmbrellaOrTheParentOfItsStudioFolder(@TempDir Path dir) throws IOException {
        Path studio = Files.createDirectories(dir.resolve("botmaker-studio"));
        Files.writeString(studio.resolve("pom.xml"), "<project/>");
        assertEquals(dir.toAbsolutePath().normalize(), StudioPlaces.checkout(dir));
        assertEquals(dir.toAbsolutePath().normalize(), StudioPlaces.checkout(studio));
        assertThrows(IllegalArgumentException.class, () -> StudioPlaces.checkout(dir.resolve("elsewhere")));
    }

    @Test
    void anExecutablesPathKeepsItsSpacesAndACommandLineIsSplit(@TempDir Path dir) throws IOException {
        Path launcher = Files.writeString(dir.resolve("BotMaker Studio"), "");
        assertEquals(List.of(launcher.toString()), StudioPlaces.argv(" " + launcher + " "));
        assertEquals(List.of("flatpak", "run", "dev.botmaker.Studio"),
                StudioPlaces.argv("flatpak run  dev.botmaker.Studio"));
    }

    @Test
    void theConfigFolderIsSharedsOnEachPlatform() {
        Map<String, String> none = Map.of();
        assertEquals(Path.of("/h/.config/botmaker"), StudioPlaces.config("Linux", none::get, "/h"));
        assertEquals(Path.of("/x/botmaker"), StudioPlaces.config("Linux", Map.of("XDG_CONFIG_HOME", "/x")::get, "/h"));
        assertEquals(Path.of("/h/Library/Application Support/botmaker"),
                StudioPlaces.config("Mac OS X", none::get, "/h"));
        assertEquals(Path.of("L", "BotMaker"),
                StudioPlaces.config("Windows 11", Map.of("LOCALAPPDATA", "L")::get, "/h"));
    }
}
