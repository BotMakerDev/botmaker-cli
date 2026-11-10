package com.botmaker.cli.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreProcessTest {

    @Test
    void theCoreRunsOnStudiosClassesAsCoreLauncherWritesIt() {
        Path java = Path.of("jdk", "bin", "java");
        Path classes = Path.of("umbrella", "botmaker-studio", "target", "classes");
        Path dir = Path.of("cache");
        assertEquals(List.of(java.toString(), "--enable-native-access=ALL-UNNAMED", "-cp",
                        classes + File.pathSeparator + "a.jar" + File.pathSeparator + "b.jar",
                        "com.botmaker.core.server.CoreMain", "--dir", dir.toString()),
                CoreProcess.coreCommand(java, classes, "a.jar" + File.pathSeparator + "b.jar", dir));
        assertEquals(classes.toString(), CoreProcess.coreCommand(java, classes, "", dir).get(3),
                "no dependency: the classes alone, no trailing separator");
    }

    @Test
    void studiosClasspathIsResolvedOfflineIntoTheCoresFolder() {
        Path out = Path.of("cache", "core-classpath.txt");
        assertEquals(List.of("mvn", "-B", "-o", "-q", "-pl", "botmaker-studio",
                "org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath",
                "-Dmdep.includeScope=runtime", "-Dmdep.outputFile=" + out), CoreProcess.resolveCommand("mvn", out));
    }

    @Test
    void withNoSavedCommandAndNoCheckoutItSaysHowToGiveOne(@TempDir Path dir) {
        Optional<String> why = new CoreProcess(dir, () -> false).start();
        assertTrue(why.orElseThrow().contains("--umbrella"), why.orElseThrow());
    }

    @Test
    void aCheckoutWhoseStudioIsNotBuiltSaysToBuildIt(@TempDir Path dir) throws IOException {
        Path checkout = Files.createDirectory(dir.resolve("botmaker"));
        Optional<String> why = new CoreProcess(dir.resolve("cache"), () -> false, Optional.of(checkout)).start();
        assertTrue(why.orElseThrow().contains("mvn -pl botmaker-studio -am install"), why.orElseThrow());
    }
}
