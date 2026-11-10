package com.botmaker.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MvnTest {

    @Test
    void aWrapperWinsOnEitherPlatform(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("mvnw"));
        Files.createFile(dir.resolve("mvnw.cmd"));
        assertEquals(dir.resolve("mvnw").toAbsolutePath().toString(), Mvn.executable(dir, false, null, null));
        assertEquals(dir.resolve("mvnw.cmd").toAbsolutePath().toString(), Mvn.executable(dir, true, null, null));
    }

    @Test
    void windowsNamesTheCmdUnderMavenHomeNeverThePosixScript(@TempDir Path dir) throws IOException {
        Path home = dir.resolve("maven");
        Files.createDirectories(home.resolve("bin"));
        Files.createFile(home.resolve("bin").resolve("mvn"));
        Files.createFile(home.resolve("bin").resolve("mvn.cmd"));
        Path project = Files.createDirectory(dir.resolve("project"));
        assertEquals(home.resolve("bin").resolve("mvn.cmd").toString(),
                Mvn.executable(project, true, home.toString(), null));
        assertEquals(home.resolve("bin").resolve("mvn").toString(),
                Mvn.executable(project, false, home.toString(), null));
    }

    @Test
    void windowsFindsTheCmdOnThePath(@TempDir Path dir) throws IOException {
        Path bin = Files.createDirectory(dir.resolve("bin"));
        Files.createFile(bin.resolve("mvn.cmd"));
        Path project = Files.createDirectory(dir.resolve("project"));
        String path = "C:\\nowhere;;\"" + bin + "\"";
        assertEquals(bin.resolve("mvn.cmd").toString(), Mvn.executable(project, true, null, path));
        assertEquals("mvn.cmd", Mvn.executable(project, true, null, "C:\\nowhere"),
                "no Maven found: the name Windows' own search would try");
        assertEquals("mvn", Mvn.executable(project, false, null, path));
    }

    @Test
    void aRelativePathEntryIsSkippedForTheCallerMovesTheWorkingDirectory(@TempDir Path dir) throws IOException {
        Path project = Files.createDirectory(dir.resolve("project"));
        assertEquals("mvn.cmd", Mvn.executable(project, true, null, ".;tools"));
    }
}
