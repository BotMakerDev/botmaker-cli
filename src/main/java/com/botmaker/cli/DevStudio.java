// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What a checkout's dev Studio is started with so it reloads recompiled classes while it runs (2026-11-24): the
 * {@code devreload} Maven profile of {@code botmaker-studio}, whose agent redefines changed classes, and a JetBrains
 * Runtime to run on, the one JDK that lets a class gain or lose members. Without a JBR the dev Studio still starts, and
 * reloads method bodies only.
 */
public final class DevStudio {

    /** The Maven profile argument: {@code javafx:run} with the reload agent. */
    public static final String PROFILE = "-Pdevreload";

    /** Names the JBR to use; else the newest {@code jbr-*} folder of {@code ~/.jdks}. */
    public static final String JBR_ENV = "BOTMAKER_JBR";

    private DevStudio() {}

    /** The JetBrains Runtime the dev Studio runs on, when this computer has one. */
    public static Optional<Path> jbr() {
        String named = System.getenv(JBR_ENV);
        if (named != null && !named.isBlank()) return Optional.of(Path.of(named.strip())).filter(DevStudio::hasJava);
        Path jdks = Path.of(System.getProperty("user.home"), ".jdks");
        if (!Files.isDirectory(jdks)) return Optional.empty();
        try (Stream<Path> kids = Files.list(jdks)) {
            return kids.filter(p -> p.getFileName().toString().startsWith("jbr-")).filter(DevStudio::hasJava)
                    .max(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    /** {@code JAVA_HOME} of the JBR for Maven's {@code javafx:run}; nothing when there is none. */
    public static Map<String, String> environment() {
        return jbr().map(home -> Map.of("JAVA_HOME", home.toString())).orElse(Map.of());
    }

    private static boolean hasJava(Path home) {
        return Files.isExecutable(home.resolve("bin").resolve("java"))
                || Files.isExecutable(home.resolve("bin").resolve("java.exe"));
    }
}
