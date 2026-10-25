package com.botmaker.cli.bmk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opening a {@code .bmk}: everything checked before anything is written (2026-10-25, plan phase 5). */
class BotFileReadTest {

    @TempDir
    Path tmp;

    @Test
    void opensWhatItWrote() throws IOException {
        Path bmk = BotFileTest.written(tmp, BotFileTest.fixture(tmp), "f.bmk");
        Path into = tmp.resolve("bots/LiQiyeDev/Firestone/v1.0.0");
        BotJson bot = BotFile.read(bmk, into);
        assertEquals("v1.0.0", bot.tag());
        assertTrue(Files.isRegularFile(into.resolve("src/main/java/firestone/Main.java")));
        assertTrue(Files.isRegularFile(into.resolve("lib/botmaker-sdk-2.0.0.jar")));
        try (var left = Files.list(into.getParent())) {
            assertEquals(List.of(into), left.toList(), "no staging folder left");
        }
    }

    @Test
    void refusesAnEntryOutsideTheFolder() throws IOException {
        byte[] json = "{\"format\":1,\"libs\":[]}".getBytes(StandardCharsets.UTF_8);
        for (List<String> names : List.of(List.of("bot.json", "../x"), List.of("bot.json", "/etc/x"),
                List.of("bot.json", "pom.xml", "pom.xml"), List.of("bot.json", "src/main/java/a/../../../x"),
                List.of("bot.json", "C:/x"), List.of("bot.json", ".git/config"))) {
            Path bmk = tmp.resolve("bad.bmk");
            try (OutputStream file = Files.newOutputStream(bmk); ZipOutputStream zip = new ZipOutputStream(file)) {
                for (String name : names) {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(name.equals("bot.json") ? json : new byte[]{1});
                    zip.closeEntry();
                }
            } catch (ZipException duplicate) {
                continue;   // ZipOutputStream itself refuses a duplicate; the hand-made case is below
            }
            Path into = tmp.resolve("open");
            assertThrows(BotFile.Refused.class, () -> BotFile.read(bmk, into), names.toString());
            assertFalse(Files.exists(into), names + ": nothing written");
            try (var left = Files.list(tmp)) {
                assertEquals(List.of(bmk), left.toList(), names + ": no staging folder");
            }
        }
    }

    /** pom.xml and POM.XML: one file on Windows and macOS, so a duplicate there. */
    @Test
    void aDuplicateNameIsRefused() throws IOException {
        Path bmk = tmp.resolve("dup.bmk");
        try (OutputStream file = Files.newOutputStream(bmk); ZipOutputStream zip = new ZipOutputStream(file)) {
            for (String name : List.of("bot.json", "pom.xml", "POM.XML")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(name.equals("bot.json") ? "{\"format\":1,\"libs\":[]}".getBytes(StandardCharsets.UTF_8)
                        : new byte[]{1});
                zip.closeEntry();
            }
        }
        assertThrows(BotFile.Refused.class, () -> BotFile.read(bmk, tmp.resolve("open")));
        assertFalse(Files.exists(tmp.resolve("open")));
    }

    @Test
    void aTamperedLibIsRefused() throws IOException {
        Path good = BotFileTest.written(tmp, BotFileTest.fixture(tmp), "f.bmk");
        Path bad = tmp.resolve("bad.bmk");
        try (ZipFile in = new ZipFile(good.toFile()); OutputStream file = Files.newOutputStream(bad);
             ZipOutputStream out = new ZipOutputStream(file)) {
            for (ZipEntry entry : Collections.list(in.entries())) {
                out.putNextEntry(new ZipEntry(entry.getName()));
                out.write(entry.getName().startsWith("lib/") ? new byte[]{0} : in.getInputStream(entry).readAllBytes());
                out.closeEntry();
            }
        }
        BotFile.Refused refused = assertThrows(BotFile.Refused.class, () -> BotFile.read(bad, tmp.resolve("open")));
        assertTrue(refused.getMessage().contains("lib/botmaker-sdk-2.0.0.jar"), refused.getMessage());
        assertFalse(Files.exists(tmp.resolve("open")));
    }

    @Test
    void aNewerFormatSaysUpdate() throws IOException {
        Path bmk = tmp.resolve("new.bmk");
        try (OutputStream file = Files.newOutputStream(bmk); ZipOutputStream zip = new ZipOutputStream(file)) {
            for (Map.Entry<String, String> e : Map.of("bot.json", "{\"format\":2,\"libs\":[],\"later\":true}").entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        BotFile.Refused refused = assertThrows(BotFile.Refused.class, () -> BotFile.read(bmk, tmp.resolve("open")));
        assertTrue(refused.getMessage().contains("newer BotMaker"), refused.getMessage());
    }
}
