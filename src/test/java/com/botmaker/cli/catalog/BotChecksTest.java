package com.botmaker.cli.catalog;

import com.botmaker.cli.BotExport;
import com.botmaker.cli.bmk.BotFile;
import com.botmaker.cli.bmk.BotFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.tools.ToolProvider;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotChecksTest {

    private static final String PATH = "bots/somedev__firestone.json";
    private static final Map<String, String> CATALOG =
            Map.of(PomFacts.key("com.github.BotMakerDev", "botmaker-sdk"), "com.botmaker.sdk");

    @TempDir
    Path dir;

    @Test
    void a_pom_pinning_a_catalog_plugin_at_a_release_passes() throws IOException {
        assertEquals(List.of(), BotChecks.checkPom(pom("${sdk.version}", "<sdk.version>2.1.0</sdk.version>"),
                PATH, CATALOG));
    }

    @Test
    void a_pom_with_no_catalog_plugin_is_refused() throws IOException {
        List<Finding> findings = BotChecks.checkPom(pom("2.1.0", ""), PATH, Map.of());
        assertTrue(Finding.refused(findings));
        assertTrue(findings.getFirst().message().contains("no plugin the catalog lists"), findings.toString());
    }

    @Test
    void a_snapshot_or_an_unresolved_pin_is_refused() throws IOException {
        for (String version : List.of("2.2.0-SNAPSHOT", "${nowhere}")) {
            List<Finding> findings = BotChecks.checkPom(pom(version, ""), PATH, CATALOG);
            assertTrue(Finding.refused(findings), version);
            assertTrue(findings.getFirst().message().contains("not a released version"), findings.toString());
        }
    }

    @Test
    void no_pom_is_refused() throws IOException {
        assertTrue(Finding.refused(BotChecks.checkPom(dir.resolve("pom.xml"), PATH, CATALOG)));
    }

    @Test
    void the_classes_must_hold_a_main() throws IOException {
        Path classes = dir.resolve("classes");
        compile(classes, "Helper", "public class Helper { static void main(String[] args) {} }");
        List<Finding> findings = BotChecks.checkClasses(classes, PATH);
        assertTrue(Finding.refused(findings), "a main that is not public is no entry point");

        compile(classes, "MyBot", "public class MyBot { public static void main(String[] args) {} }");
        assertEquals(List.of(), BotChecks.checkClasses(classes, PATH));
    }

    private static final String REPO = "somedev/firestone";
    private static final String SDK_JAR = "com.github.BotMakerDev:botmaker-sdk:v2.1.0";

    /** The release's .bmk is the one the commit builds, GitHub serves it, and the entry pins it. */
    @Test
    void bmkMatches() throws IOException {
        Bot bot = bot();
        facts.assets.put(REPO + "@v1.2.0/" + bot.name, bot.sha);
        assertEquals(List.of(), BotChecks.checkBmk(bot.folder, REPO, bot.pinned(bot.sha), PATH, bot.jars,
                catalog(bot.jarSha), facts));
    }

    @Test
    void bmkDiffers() throws IOException {
        Bot bot = bot();
        String other = "0".repeat(64);
        facts.assets.put(REPO + "@v1.2.0/" + bot.name, other);
        assertRefused(BotChecks.checkBmk(bot.folder, REPO, bot.pinned(other), PATH, bot.jars, catalog(bot.jarSha),
                facts), "The .bmk on the release is not the one its commit builds.");
        assertRefused(BotChecks.checkBmk(bot.folder, REPO, bot.pinned(bot.sha), PATH, bot.jars, catalog(bot.jarSha),
                facts), "not the one the entry pins");
        assertRefused(BotChecks.checkBmk(bot.folder, REPO, bot.pinned(bot.sha), PATH, bot.jars, catalog(other),
                facts), SDK_JAR + " is not the jar the catalog pins");
        // the build swapped a packed jar in its own repository: JitPack serves another
        facts.assets.put(REPO + "@v1.2.0/" + bot.name, bot.sha);
        facts.hashes.put("com.github.BotMakerDev:botmaker-sdk:jar:v2.1.0", other);
        assertRefused(BotChecks.checkBmk(bot.folder, REPO, bot.pinned(bot.sha), PATH, bot.jars, Optional.empty(),
                facts), SDK_JAR + " is not the jar JitPack serves");
    }

    @Test
    void bmkMissing() throws IOException {
        Bot bot = bot();
        assertRefused(BotChecks.checkBmk(bot.folder, REPO, bot.pinned(bot.sha), PATH, bot.jars, catalog(bot.jarSha),
                facts), "has no " + bot.name);
    }

    @Test
    void iconTooBig() throws IOException {
        byte[] png = Files.readAllBytes(png(dir.resolve("icon.png")));
        byte[] big = Arrays.copyOf(png, BotChecks.MAX_ICON_BYTES + 1);
        Files.write(dir.resolve("icon.png"), big);
        assertRefused(BotChecks.checkIcon(dir, PATH), "at most 512 KB");
        png(dir.resolve("icon.png"));
        assertEquals(List.of(), BotChecks.checkIcon(dir, PATH));
        Files.delete(dir.resolve("icon.png"));
        assertEquals(List.of(), BotChecks.checkIcon(dir, PATH), "a bot without an icon passes");
    }

    @Test
    void iconNotPng() throws IOException {
        Files.writeString(dir.resolve("icon.png"), "GIF89a…");
        assertRefused(BotChecks.checkIcon(dir, PATH), "not a PNG");
        Files.delete(dir.resolve("icon.png"));
        Files.createSymbolicLink(dir.resolve("icon.png"), png(dir.resolve("real.png")));
        assertRefused(BotChecks.checkIcon(dir, PATH), "not a plain file");
    }

    private final FakeFacts facts = new FakeFacts();

    /** A committed bot standing in for the check's clone, its .bmk written once as the author's publish would. */
    private record Bot(Path folder, List<BotFile.Jar> jars, String name, String sha, String jarSha) {
        ApprovedRelease pinned(String bmk) {
            return ApprovedRelease.ofBot("v1.2.0", FakeFacts.commitOf("v1.2.0"), bmk);
        }
    }

    private Bot bot() throws IOException {
        Path clone = Files.createDirectories(dir.resolve("clone"));
        Files.writeString(clone.resolve("pom.xml"), "<project><name>Firestone</name><artifactId>firestone</artifactId>"
                + "<properties><maven.compiler.release>25</maven.compiler.release></properties></project>\n");
        Files.createDirectories(clone.resolve("src/main/java/fs"));
        Files.writeString(clone.resolve("src/main/java/fs/Main.java"),
                "package fs; public class Main { public static void main(String[] args) {} }\n");
        for (List<String> step : List.of(List.of("init", "-q"), List.of("add", "-A"),
                List.of("-c", "user.name=t", "-c", "user.email=t@example.invalid", "-c", "commit.gpgsign=false",
                        "commit", "-qm", "one"))) {
            List<String> argv = new ArrayList<>(List.of("git"));
            argv.addAll(step);
            try {
                assertEquals(0, new ProcessBuilder(argv).directory(clone.toFile()).inheritIO().start().waitFor());
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }
        Path jar = Files.write(dir.resolve("botmaker-sdk.jar"), new byte[]{'P', 'K', 3, 4});
        List<BotFile.Jar> jars = List.of(new BotFile.Jar(SDK_JAR, jar));
        Path out = Files.createDirectories(dir.resolve("out"));
        Path bmk = BotExport.write(clone, "v1.2.0", REPO, out, jars);
        facts.jitpack.add(SDK_JAR);
        facts.hashes.put("com.github.BotMakerDev:botmaker-sdk:jar:v2.1.0", BotFiles.sha256(jar));
        return new Bot(clone, jars, bmk.getFileName().toString(), BotFiles.sha256(bmk), BotFiles.sha256(jar));
    }

    private static Optional<Catalog> catalog(String jarSha) {
        Catalog.Plugin sdk = new Catalog.Plugin("com.botmaker.sdk", "BotMakerDev/botmaker-sdk", Rank.ADMIN,
                "com.github.BotMakerDev:botmaker-sdk", "SDK", "", List.of(), 0, List.of(new Catalog.Plugin.Release(
                "v2.1.0", FakeFacts.commitOf("v2.1.0"), jarSha, "a".repeat(64), "0.11.0", List.of())));
        return Optional.of(new Catalog(Catalog.SCHEMA_VERSION, "2026-10-25T00:00:00Z", List.of(sdk), List.of()));
    }

    /** A 1×1 PNG at {@code file}. */
    private static Path png(Path file) throws IOException {
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", file.toFile());
        return file;
    }

    private static void assertRefused(List<Finding> findings, String says) {
        assertTrue(Finding.refused(findings), findings.toString());
        assertTrue(findings.getFirst().message().contains(says), findings.getFirst().message());
    }

    private Path pom(String version, String properties) throws IOException {
        return Files.writeString(dir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.github.somedev</groupId><artifactId>firestone</artifactId><version>1.2.0</version>
                  <properties>%s</properties>
                  <dependencies>
                    <dependency><groupId>com.github.BotMakerDev</groupId><artifactId>botmaker-sdk</artifactId>
                      <version>%s</version></dependency>
                  </dependencies>
                </project>
                """.formatted(properties, version));
    }

    private static void compile(Path out, String name, String source) throws IOException {
        Path src = Files.createTempDirectory("bot-src").resolve(name + ".java");
        Files.writeString(src, source);
        Files.createDirectories(out);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(),
                src.toString()));
    }
}
