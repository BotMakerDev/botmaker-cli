package com.botmaker.cli.bmk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code .bmk} writer: reproducible, Central jars listed not packed, nothing but sources (2026-10-25, phase 5). */
class BotFileTest {

    static final BotJson.Head HEAD = new BotJson.Head("Firestone", "LiQiyeDev", "Firestone", "v1.0.0", "7f57e0e", 25,
            "firestone.Main");

    @TempDir
    Path tmp;

    /** A commit's files (a pom, a source, a picture, a stray class in target/, a README) and two jars. */
    static Fixture fixture(Path tmp) throws IOException {
        Map<String, byte[]> sources = new LinkedHashMap<>();
        sources.put("README.md", bytes("not packed"));
        sources.put("pom.xml", bytes("<project/>\n"));
        sources.put("src/main/java/firestone/Main.java",
                bytes("package firestone; class Main { public static void main(String[] a) {} }"));
        sources.put("src/main/resources/shot.png", new byte[]{1, 2, 3});
        sources.put("target/x.class", new byte[]{9});
        Path repo = Files.createDirectories(tmp.resolve("m2"));
        Path sdk = Files.write(repo.resolve("botmaker-sdk-2.0.0.jar"), new byte[]{4, 5, 6});
        Path opencv = Files.write(repo.resolve("opencv-4.9.0-0.jar"), new byte[]{7, 8});
        List<BotFile.Jar> jars = List.of(new BotFile.Jar("org.openpnp:opencv:4.9.0-0", opencv),
                new BotFile.Jar("com.github.BotMakerDev:botmaker-sdk:2.0.0", sdk));
        return new Fixture(sources, jars);
    }

    record Fixture(Map<String, byte[]> sources, List<BotFile.Jar> jars) {}

    static Path written(Path tmp, Fixture f, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BotFile.write(f.sources(), HEAD, f.jars(), out);
        return Files.write(tmp.resolve(name), out.toByteArray());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void sameInputsSameBytes() throws IOException {
        Fixture f = fixture(tmp);
        Path a = written(tmp, f, "a.bmk");
        Map<String, byte[]> reordered = new TreeMap<>(Collections.reverseOrder());
        reordered.putAll(f.sources());
        Path b = written(tmp, new Fixture(reordered, f.jars().reversed()), "b.bmk");
        assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b));
        assertEquals(BotFiles.sha256(a), BotFiles.sha256(b));
    }

    @Test
    void centralJarsAreListedNotPacked() throws IOException {
        Path bmk = written(tmp, fixture(tmp), "f.bmk");
        try (ZipFile zip = new ZipFile(bmk.toFile())) {
            List<String> names = Collections.list(zip.entries()).stream().map(ZipEntry::getName).toList();
            assertEquals(List.of("bot.json", "lib/botmaker-sdk-2.0.0.jar", "pom.xml",
                    "src/main/java/firestone/Main.java", "src/main/resources/shot.png"), names);
            String json = new String(zip.getInputStream(zip.getEntry("bot.json")).readAllBytes());
            assertTrue(json.startsWith("{\n  \"format\" : 1,\n  \"name\" : \"Firestone\""), json);
            assertFalse(json.contains("\r"), "\\n on every system");
        }
        BotJson bot = BotFile.read(bmk, tmp.resolve("open"));
        BotJson.Lib opencv = bot.libs().stream().filter(l -> l.artifactId().equals("opencv")).findFirst().orElseThrow();
        assertEquals(Optional.empty(), opencv.packed());
        assertEquals(64, opencv.sha256().length());
        assertEquals(Optional.of("lib/botmaker-sdk-2.0.0.jar"),
                bot.libs().stream().filter(l -> l.artifactId().equals("botmaker-sdk")).findFirst().orElseThrow().packed());
        assertEquals("firestone.Main", bot.main());
    }

    @Test
    void excludesTarget() throws IOException {
        Path bmk = written(tmp, fixture(tmp), "f.bmk");
        try (ZipFile zip = new ZipFile(bmk.toFile())) {
            assertEquals(null, zip.getEntry("target/x.class"));
            assertEquals(null, zip.getEntry("README.md"));
        }
    }

    /** What read would refuse, write refuses first; and an LFS pointer is no file. */
    @Test
    void writeRefusesWhatReadWould() throws IOException {
        Fixture f = fixture(tmp);
        for (Map.Entry<String, byte[]> extra : List.of(
                Map.entry("src/main/resources/Shot.png", new byte[]{1}),
                Map.entry("src/main/resources/x.png.", new byte[]{1}),
                Map.entry("src/main/resources/NUL.txt", new byte[]{1}),
                Map.entry("src/main/resources/a?.png", new byte[]{1}),
                Map.entry("src/main/resources/big.png", bytes("version https://git-lfs.github.com/spec/v1\n")))) {
            Map<String, byte[]> sources = new TreeMap<>(f.sources());
            sources.put(extra.getKey(), extra.getValue());
            assertThrows(BotFile.Refused.class, () -> BotFile.write(sources, HEAD, f.jars(),
                    new ByteArrayOutputStream()), extra.getKey());
        }
    }

    @Test
    void sourcesComeFromTheCommitsTree() throws IOException {
        String tree = String.join("\u0000", "100644 blob aaa1\tpom.xml", "100644 blob bbb2\tREADME.md",
                "100755 blob ccc3\tsrc/main/java/a b/Main.java");
        assertEquals(Map.of("pom.xml", "aaa1", "src/main/java/a b/Main.java", "ccc3"), BotFiles.sources(tree));
        assertThrows(BotFile.Refused.class,
                () -> BotFiles.sources("120000 blob ddd4\tsrc/main/resources/link.txt\0"));
        assertThrows(BotFile.Refused.class, () -> BotFiles.sources("160000 commit eee5\tsrc/main/java/sub\0"));

        Map<String, String> blobs = new TreeMap<>(Map.of("pom.xml", "aaa1", "src/x.java", "ccc3"));
        byte[] batch = bytes("aaa1 blob 3\n<p>\nccc3 blob 0\n\n");
        Map<String, byte[]> contents = BotFiles.contents(blobs, batch);
        assertArrayEquals(bytes("<p>"), contents.get("pom.xml"));
        assertArrayEquals(new byte[0], contents.get("src/x.java"));
        assertThrows(BotFile.Refused.class, () -> BotFiles.contents(blobs, bytes("aaa1 blob 9\n<p>\n")));
        assertThrows(BotFile.Refused.class, () -> BotFiles.contents(blobs, bytes("aaa1 missing\n")));
    }

    @Test
    void namesAndJarsComeFromTheBuild() throws IOException {
        Fixture f = fixture(tmp);
        assertEquals(Optional.of("firestone.Main"), BotFiles.mainClass(f.sources()));
        assertEquals("Firestone-1.0.0.bmk", BotFiles.fileName("Firestone", "v1.0.0"));
        assertEquals("My-Bot-2.1.bmk", BotFiles.fileName(" My Bot ", "2.1"));

        String listed = """

                The following files have been resolved:
                   com.github.BotMakerDev:botmaker-sdk:jar:2.0.0:compile:/m2/sdk.jar -- module botmaker.sdk
                   org.openjfx:javafx-base:jar:linux:21:compile:/m2/fx-linux.jar
                   org.openjfx:javafx-base:jar:21:compile:/m2/fx.jar
                   org.junit:junit:jar:5:test:/m2/junit.jar
                   com.example:bom:pom:1:compile:/m2/bom.pom
                   net.sf:tess4j:jar:jakarta:5.0:runtime:C:\\m2\\tess4j.jar
                """;
        List<BotFile.Jar> jars = BotFiles.jars(listed);
        assertEquals(List.of("com.github.BotMakerDev:botmaker-sdk:2.0.0", "net.sf:tess4j:5.0:jakarta",
                "org.openjfx:javafx-base:21"), jars.stream().map(BotFile.Jar::coordinate).toList());
        assertEquals(Path.of("/m2/sdk.jar"), jars.getFirst().file());
        assertEquals("C:\\m2\\tess4j.jar", jars.get(1).file().toString());
    }
}
