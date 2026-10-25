package com.botmaker.cli.bmk;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Writes and reads a {@code .bmk} (spec 2026-10-24 § 2): the bot's {@code pom.xml}, {@code icon.png}, sources and
 * resources, its {@code bot.json}, and in {@code lib/} every jar it runs with that Maven Central does not serve byte
 * for byte ({@link Central}; 2026-10-25, was every {@code com.github.} jar). Nothing compiled and
 * nothing system-specific, so one commit and one set of resolved jars always make the same bytes.
 *
 * <p><b>Reproducible:</b> entries sorted by path, each dated {@link #ENTRY_TIME} (local DOS time, so no time zone
 * enters), no directory entries, no extra fields, no permissions recorded, and every entry {@code STORED}: deflate's
 * output depends on the zlib a JDK is linked against (the system's on Fedora, zlib-ng there since 2024), and the
 * catalog's check rebuilds the file on another machine. The jars are compressed already; sources are small.
 */
public final class BotFile {

    /** Every entry's time: the earliest a zip can record. */
    static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

    /** The file's {@code bot.json} entry. */
    public static final String BOT_JSON = "bot.json";

    /** The most a {@code .bmk} unpacks to; a file claiming more is refused before it fills the disk. */
    static final long MAX_UNPACKED = 2L << 30;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A Windows device name, alone or before an extension. */
    private static final Pattern DEVICE = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");

    /** An owner, a repository, a tag, a coordinate's part: one plain name, not starting with a dot. */
    private static final Pattern FOLDER_NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}");

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** How a file Git LFS stores begins in the commit: a pointer, not the file. */
    private static final byte[] LFS_POINTER = "version https://git-lfs.github.com/spec/".getBytes(StandardCharsets.UTF_8);

    /** Indented with {@code \n} on every system: Jackson's default is the system's line separator. */
    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"));

    private BotFile() {}

    /** A resolved jar the bot runs with: its coordinate ({@link BotJson.Lib#coordinate()}) and its file. */
    public record Jar(String coordinate, Path file) {}

    /** Why a folder cannot be written as a {@code .bmk}, or a file cannot be opened as one; said to the user. */
    public static final class Refused extends IOException {
        public Refused(String message) {
            super(message);
        }
    }

    /**
     * Writes a bot as a {@code .bmk} to {@code out}.
     *
     * <p>The sources are bytes, not a folder: the host reads them from the tagged commit's tree, so a checkout's
     * line-ending conversion, filters or skipped files never reach the file, and the catalog's rebuild of the same
     * commit gives the same bytes.
     *
     * @param sources the commit's files by path, relative with {@code /}; only {@code pom.xml}, {@code icon.png} and
     *                those under {@code src/main/java/} and {@code src/main/resources/} are taken
     * @param jars    the bot's resolved classpath; a jar {@code central} serves with the same bytes is listed in
     *                {@code bot.json} only, every other is packed
     * @return the {@code bot.json} written
     */
    public static BotJson write(Map<String, byte[]> sources, BotJson.Head head, List<Jar> jars, Central central,
                                OutputStream out) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, byte[]> source : sources.entrySet()) {
            String name = source.getKey();
            if (!isSource(name)) continue;
            // what read refuses, write refuses first: the author hears it, not the people opening the file
            if (!isSafe(name)) throw new Refused(name + " is a name some systems cannot hold; rename it.");
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new Refused(name + " differs from another file only by case: one file on Windows and macOS.");
            }
            if (startsWith(source.getValue(), LFS_POINTER)) {
                throw new Refused(name + " is a Git LFS pointer; a .bmk holds the files themselves, so commit it "
                        + "without LFS.");
            }
            entries.put(name, source.getValue());
        }
        if (!entries.containsKey("pom.xml")) throw new Refused("the bot has no tracked pom.xml.");

        List<BotJson.Lib> libs = new ArrayList<>();
        for (Jar jar : jars.stream().sorted(Comparator.comparing(Jar::coordinate)).toList()) {
            byte[] bytes = Files.readAllBytes(jar.file());   // read once: the sha listed is of the bytes packed
            String sha = HexFormat.of().formatHex(digest().digest(bytes));
            BotJson.Lib unpacked = new BotJson.Lib(null, jar.coordinate(), sha);
            if (central.serves(unpacked, bytes)) {
                libs.add(unpacked);
                continue;
            }
            String path = libPath(unpacked);
            if (entries.putIfAbsent(path, bytes) != null) {
                throw new Refused("two jars would both be " + path + ": " + jar.coordinate() + " and another.");
            }
            libs.add(new BotJson.Lib(path, jar.coordinate(), sha));
        }
        BotJson bot = BotJson.of(head, libs);
        entries.put(BOT_JSON, (JSON.writer(PRETTY).writeValueAsString(bot) + "\n").getBytes(StandardCharsets.UTF_8));

        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        for (Map.Entry<String, byte[]> named : entries.entrySet()) {
            String name = named.getKey();
            byte[] bytes = named.getValue();
            ZipEntry entry = new ZipEntry(name);
            entry.setMethod(ZipEntry.STORED);
            entry.setTimeLocal(ENTRY_TIME);
            entry.setSize(bytes.length);
            entry.setCompressedSize(bytes.length);
            CRC32 crc = new CRC32();
            crc.update(bytes);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(bytes);
            zip.closeEntry();
        }
        zip.finish();
        return bot;
    }

    /**
     * Unpacks {@code bmk} into {@code into}, which must not exist yet, and returns its {@code bot.json}. Everything
     * is checked before anything is written: every entry's name (no {@code ..}, no absolute path, no duplicate, only
     * what a {@code .bmk} holds), the format, and each {@code lib/} jar against its {@code bot.json} sha256. The
     * files land in a fresh folder beside {@code into} and are moved there whole, so a refusal leaves nothing.
     */
    public static BotJson read(Path bmk, Path into) throws IOException {
        if (Files.exists(into, LinkOption.NOFOLLOW_LINKS)) throw new IOException(into + " exists already.");
        try (ZipFile zip = new ZipFile(bmk.toFile(), StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> all = Collections.list(zip.entries());
            BotJson bot = botJson(zip, all);

            Map<String, String> listed = new HashMap<>();
            for (BotJson.Lib lib : bot.libs()) lib.packed().ifPresent(path -> listed.put(path, lib.sha256()));
            long total = 0;
            for (ZipEntry entry : all) {
                if (!isLibEntry(entry.getName())) continue;
                String sha = listed.remove(entry.getName());
                if (sha == null) throw new Refused(entry.getName() + " is not listed in bot.json.");
                MessageDigest digest = digest();
                try (InputStream in = zip.getInputStream(entry)) {
                    total += copy(in, digest, null, MAX_UNPACKED - total);
                }
                if (!HexFormat.of().formatHex(digest.digest()).equals(sha)) {
                    throw new Refused(entry.getName() + " is not the jar bot.json lists; the file was changed.");
                }
            }
            if (!listed.isEmpty()) {
                throw new Refused("bot.json lists " + listed.keySet().iterator().next() + ", which the file lacks.");
            }

            Path parent = into.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path staging = Files.createTempDirectory(parent, ".bmk");
            try {
                total = 0;
                for (ZipEntry entry : all) {
                    Path target = staging.resolve(entry.getName()).normalize();
                    if (!target.startsWith(staging)) throw new Refused(entry.getName() + " is outside the folder.");
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(entry);
                         OutputStream file = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        total += copy(in, null, file, MAX_UNPACKED - total);
                    }
                }
                Files.move(staging, into, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                deleteTree(staging);
            }
            return bot;
        }
    }

    /**
     * {@code bmk}'s {@code bot.json}, read without unpacking anything: who made the bot and which tag it is, for a
     * host to decide where it goes and whether to trust it before {@link #read}. The entries' names and the format
     * are checked as {@link #read} checks them; the jars are not.
     */
    public static BotJson peek(Path bmk) throws IOException {
        try (ZipFile zip = new ZipFile(bmk.toFile(), StandardCharsets.UTF_8)) {
            return botJson(zip, Collections.list(zip.entries()));
        } catch (ZipException e) {
            throw new Refused("this file is not a .bmk: " + e.getMessage());
        }
    }

    /**
     * Every entry's name checked, then {@code bot.json} read and checked: a format this reader knows, and an owner,
     * repository and tag that are each one plain folder name, since a host files the bot under them.
     */
    private static BotJson botJson(ZipFile zip, List<? extends ZipEntry> all) throws IOException {
        Set<String> seen = new HashSet<>();
        for (ZipEntry entry : all) {
            String name = entry.getName();
            if (!isSafe(name) || !(isSource(name) || name.equals(BOT_JSON) || isLibEntry(name))) {
                throw new Refused("this file holds " + name + ", which no .bmk holds; it was not made by BotMaker.");
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new Refused("this file holds " + name + " twice; it was not made by BotMaker.");
            }
        }
        ZipEntry jsonEntry = zip.getEntry(BOT_JSON);
        if (jsonEntry == null) throw new Refused("this file has no bot.json; it is not a .bmk.");
        BotJson bot;
        try (InputStream in = zip.getInputStream(jsonEntry)) {
            bot = JSON.readValue(bounded(in, 1 << 20), BotJson.class);
        } catch (Refused e) {
            throw e;
        } catch (IOException e) {
            throw new Refused("this file's bot.json cannot be read: " + e.getMessage());
        }
        if (bot.format() > BotJson.FORMAT) {
            throw new Refused("this bot was made by a newer BotMaker (format " + bot.format()
                    + "). Update BotMaker to open it.");
        }
        if (bot.format() < 1 || !isFolderName(bot.owner()) || !isFolderName(bot.repo()) || !isFolderName(bot.tag())
                || isBlank(bot.main()) || isBlank(bot.name()) || isBlank(bot.commit())) {
            throw new Refused("this file's bot.json is incomplete.");
        }
        // A host fetches each listed jar by its coordinate into a local repository: every part one plain name.
        for (BotJson.Lib lib : bot.libs()) {
            if (lib == null || lib.coordinate() == null || lib.sha256() == null || !SHA256.matcher(lib.sha256()).matches()
                    || !Arrays.stream(lib.coordinate().split(":", -1)).allMatch(BotFile::isFolderName)
                    || lib.coordinate().split(":", -1).length < 3 || lib.coordinate().split(":", -1).length > 4) {
                throw new Refused("this file's bot.json lists a jar it does not name properly"
                        + (lib == null || lib.coordinate() == null ? "." : ": " + lib.coordinate() + "."));
            }
        }
        return bot;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** One plain folder name: letters, digits, {@code . _ -}, not starting with a dot. */
    static boolean isFolderName(String part) {
        return part != null && FOLDER_NAME.matcher(part).matches() && isSafe(part);
    }

    /** A source entry: what {@link #write} takes from the folder. */
    public static boolean isSource(String name) {
        return name.equals("pom.xml") || name.equals("icon.png")
                || (name.startsWith("src/main/java/") || name.startsWith("src/main/resources/")) && !name.endsWith("/");
    }

    private static boolean isLibEntry(String name) {
        return name.startsWith("lib/") && name.endsWith(".jar") && name.indexOf('/', 4) < 0;
    }

    /**
     * Relative, {@code /}-separated, no empty, {@code .} or {@code ..} segment, nothing a file system reads twice:
     * none of Windows' forbidden characters, no segment ending in {@code .} or a space ({@code x.java.} is
     * {@code x.java} there) and no device name ({@code NUL}, {@code CON.txt}).
     */
    static boolean isSafe(String name) {
        if (name.isEmpty() || name.chars().anyMatch(c -> c < 0x20 || "\\:<>\"|?*".indexOf(c) >= 0)) return false;
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.endsWith(".") || segment.endsWith(" ")
                    || DEVICE.matcher(segment).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static String libPath(BotJson.Lib lib) {
        return "lib/" + lib.artifactId() + "-" + lib.version() + lib.classifier().map(c -> "-" + c).orElse("") + ".jar";
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            copy(in, digest, null, Long.MAX_VALUE);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }

    /** Copies {@code in} to a digest and/or a stream, refusing past {@code limit} bytes; the count copied. */
    private static long copy(InputStream in, MessageDigest digest, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long count = 0;
        for (int n; (n = in.read(buffer)) > 0; ) {
            count += n;
            if (count > limit) throw new Refused("this file unpacks to more than a .bmk ever holds.");
            if (digest != null) digest.update(buffer, 0, n);
            if (out != null) out.write(buffer, 0, n);
        }
        return count;
    }

    private static InputStream bounded(InputStream in, int limit) throws IOException {
        byte[] bytes = in.readNBytes(limit + 1);
        if (bytes.length > limit) throw new Refused("this file's bot.json is too large.");
        return new ByteArrayInputStream(bytes);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
