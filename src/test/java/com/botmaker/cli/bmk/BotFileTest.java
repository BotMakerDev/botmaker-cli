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

    /** Maven Central serving opencv and jai-imageio, each as the bytes asked about. */
    static final Central CENTRAL = (lib, bytes) -> lib.artifactId().equals("opencv")
            || lib.artifactId().equals("jai-imageio-core");

    static Path written(Path tmp, Fixture f, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BotFile.write(f.sources(), HEAD, f.jars(), CENTRAL, out);
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

    /** Central decides, not the groupId: a com.github jar on Central is listed, an org one it lacks is packed. */
    @Test
    void whatCentralServesIsListedWhateverTheGroupId() throws IOException {
        Fixture f = fixture(tmp);
        Path imageio = Files.write(tmp.resolve("jai.jar"), new byte[]{1});
        Path own = Files.write(tmp.resolve("own.jar"), new byte[]{2});
        List<BotFile.Jar> jars = List.of(new BotFile.Jar("com.github.jai-imageio:jai-imageio-core:1.4.0", imageio),
                new BotFile.Jar("org.example:own:1.0", own));
        BotJson bot = BotFile.write(f.sources(), HEAD, jars, CENTRAL, new ByteArrayOutputStream());
        assertEquals(List.of(Optional.empty(), Optional.of("lib/own-1.0.jar")),
                bot.libs().stream().map(BotJson.Lib::packed).toList());
    }

    @Test
    void centralsChecksumIsOfTheJarsBytes() {
        BotJson.Lib lib = new BotJson.Lib(null, "com.github.jai-imageio:jai-imageio-core:1.4.0", "x");
        assertEquals("https://repo1.maven.org/maven2/com/github/jai-imageio/jai-imageio-core/1.4.0/"
                + "jai-imageio-core-1.4.0.jar", Central.jarUrl(lib));
        assertTrue(Central.jarUrl(new BotJson.Lib(null, "a.b:c:1:natives", "x")).endsWith("/a/b/c/1/c-1-natives.jar"));
        byte[] bytes = bytes("abc");
        assertTrue(Central.matches(new String[]{"sha1", "a9993e364706816aba3e25717850c26c9cd0d89d"}, bytes));
        assertTrue(Central.matches(new String[]{"sha256",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}, bytes));
        assertFalse(Central.matches(new String[]{"sha1", "0".repeat(40)}, bytes));
    }

    /** The pom's name, else the repository's: never the artifactId a cloned blank keeps (gamebot). */
    @Test
    void aBotWithNoNameIsNamedAfterItsRepository() throws IOException {
        Path named = Files.writeString(tmp.resolve("named.xml"),
                "<project><artifactId>gamebot</artifactId><name>Fire Stone</name></project>");
        Path bare = Files.writeString(tmp.resolve("bare.xml"), "<project><artifactId>gamebot</artifactId></project>");
        assertEquals("Fire Stone", BotFiles.name(named, "Firestone"));
        assertEquals("Firestone", BotFiles.name(bare, "Firestone"));
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
            assertThrows(BotFile.Refused.class, () -> BotFile.write(sources, HEAD, f.jars(), CENTRAL,
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
