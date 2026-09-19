package com.botmaker.cli.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Whether a published artifact is <b>usable</b>, not merely present — {@code release.sh}'s
 * {@code resolve_clean_room}.
 *
 * <p><b>A {@code HEAD} on the {@code .pom} is the check that looks equivalent and is not.</b> It answers
 * <i>did JitPack publish something</i>; this answers <i>can anybody resolve it</i>, by running a real
 * {@code dependency:resolve} from a throwaway local repository. That is the only thing that catches a
 * published pom naming a dependency nobody can resolve — the {@code 0.0.0-SNAPSHOT} bug that shipped in
 * every SDK up to v1.0.24. {@code dependency:tree} is not a substitute either: it warns on an unresolvable
 * transitive pom and exits 0.
 *
 * <p>It costs about 40 seconds and 120 MB of downloads per release, into a temporary repository that is
 * deleted afterwards, and <b>it still blocks nothing</b> — every tag is pushed by the time it runs, and no
 * exit code recalls one.
 */
public final class CleanRoom {

    /** The organization every repository lives under since 2026-09-18 — what {@code gh} and git are given. */
    public static final String OWNER = "BotMakerDev";

    /**
     * The owner in a <b>Maven coordinate</b>, which is deliberately still the old one.
     *
     * <p>JitPack names an artifact after the address it is asked for, so moving the repositories would have
     * renamed every coordinate — {@code com.github.LiQiyeDev:botmaker-sdk} — in every bot's pom, in every
     * published pom already baked by flatten, and in the registry's entries. Maven treats the two spellings
     * as unrelated artifacts, so a project that ended up with both would carry two copies of shared. The
     * coordinate therefore stays put and resolves through GitHub's redirect; the day that stops working, it
     * moves in one release with the Studio change that rewrites a bot pom's groupId.
     */
    public static final String COORDINATE_OWNER = "LiQiyeDev";

    private CleanRoom() {
    }

    /**
     * @return empty when the artifact resolves; otherwise the resolution errors, ready for the log
     */
    public static Optional<String> resolve(Runner runner, Module module, Version version) {
        if (!Proc.onPath("mvn")) {
            runner.say("    (no mvn on PATH — skipping the resolve check for "
                    + module.directory() + ":" + version.tag() + ")");
            return Optional.empty();
        }
        Path probe;
        Path repository;
        try {
            probe = Files.createTempDirectory("botmaker-probe");
            repository = Files.createTempDirectory("botmaker-repo");
            Files.writeString(probe.resolve("pom.xml"), pom(module, version));
        } catch (IOException e) {
            return Optional.of("could not set up the probe: " + e.getMessage());
        }
        try {
            Proc.Result run = Proc.run(probe, "mvn", "-B", "-q", "-f", probe.resolve("pom.xml").toString(),
                    "-Dmaven.repo.local=" + repository, "dependency:resolve");
            return run.ok() ? Optional.empty() : Optional.of(detail(run.out()));
        } finally {
            delete(probe);
            delete(repository);
        }
    }

    /** The probe pom: one dependency, one repository, nothing else to go wrong. */
    static String pom(Module module, Version version) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.botmaker.releasecheck</groupId><artifactId>probe</artifactId><version>1</version>
                  <packaging>pom</packaging>
                  <repositories><repository><id>jitpack.io</id><url>https://jitpack.io</url></repository></repositories>
                  <dependencies><dependency>
                    <groupId>com.github.%s</groupId><artifactId>%s</artifactId><version>%s</version>
                  </dependency></dependencies>
                </project>
                """.formatted(COORDINATE_OWNER, module.directory(), version.tag());
    }

    /**
     * The resolution errors, or the tail of the build when there are none.
     *
     * <p>The fallback is not decoration: Maven fails for reasons that are not resolution failures (network,
     * a JDK mismatch), and a report that said nothing in those cases would be a release recorded as broken
     * with no evidence of what broke.
     */
    static String detail(String output) {
        List<String> errors = output.lines()
                .filter(line -> line.contains("Could not find")
                        || line.contains("Could not resolve")
                        || line.contains("Could not transfer"))
                .map(line -> line.replaceFirst("^\\[ERROR\\]\\s*", ""))
                .distinct()
                .sorted()
                .limit(10)
                .toList();
        if (!errors.isEmpty()) {
            return String.join("\n", errors);
        }
        List<String> all = output.lines().toList();
        String tail = String.join("\n", all.subList(Math.max(0, all.size() - 20), all.size()));
        return "(no resolution error in the output — the tail of the build log:)\n" + tail;
    }

    private static void delete(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A temp directory that will not delete is not worth failing a release over.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }
}
