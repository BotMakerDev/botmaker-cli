// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformTest {

    @TempDir
    Path dir;

    @Test
    void idsRoundTrip() {
        for (Platform platform : Platform.values()) {
            assertSame(platform, Platform.fromId(platform.id()));
        }
        assertSame(Platform.UNKNOWN, Platform.fromId("beos"));
        assertSame(Platform.UNKNOWN, Platform.fromId(null));
        assertEquals(List.of("linux", "windows", "macos"), Platform.known().stream().map(Platform::id).toList());
    }

    @Test
    void platformsDefaultAll() throws IOException {
        assertEquals(Platform.known(), PomFacts.platforms(pom("")));
    }

    @Test
    void platformsParsedInKnownOrder() throws IOException {
        assertEquals(List.of(Platform.LINUX, Platform.WINDOWS),
                PomFacts.platforms(pom("<botmaker.platforms> windows , linux,windows</botmaker.platforms>")));
    }

    @Test
    void unknownPlatformRefused() throws IOException {
        List<Finding> findings = BotChecks.checkPom(pom("<botmaker.platforms>linux,beos</botmaker.platforms>"),
                "bots/somedev__firestone.json",
                Map.of(PomFacts.key("com.github.BotMakerDev", "botmaker-sdk"), "BotMakerDev/botmaker-sdk"));
        assertTrue(Finding.refused(findings), findings.toString());
        assertTrue(findings.getFirst().message().contains("beos"), findings.getFirst().message());
    }

    private Path pom(String properties) throws IOException {
        return Files.writeString(dir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.github.somedev</groupId><artifactId>firestone</artifactId><version>1.2.0</version>
                  <properties>%s</properties>
                  <dependencies>
                    <dependency><groupId>com.github.BotMakerDev</groupId><artifactId>botmaker-sdk</artifactId>
                      <version>2.1.0</version></dependency>
                  </dependencies>
                </project>
                """.formatted(properties));
    }
}
