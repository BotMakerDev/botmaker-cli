package com.botmaker.cli.bmk;

import com.botmaker.cli.project.Poms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What a host needs around {@link BotFile}: the file's name and sha256, the bot's main class, its jars. */
public final class BotFiles {

    /**
     * One line of {@code dependency:list -DoutputAbsoluteArtifactFilename}:
     * {@code group:artifact:type[:classifier]:version:scope:/abs/path}, maybe followed by {@code -- module …}.
     */
    private static final Pattern LISTED = Pattern.compile(
            "\\s*([^:\\s]+):([^:\\s]+):([^:\\s]+)(?::([^:\\s]+))?:([^:\\s]+):(compile|runtime|provided|system|test)"
                    + ":(.+?)(?:\\s+--\\s.*)?\\s*");

    /**
     * A classifier naming an operating system or a processor: such a jar differs per system, and its natives come
     * from the installed BotMaker (spec § 2). JavaFX's {@code linux}/{@code win}/{@code mac-aarch64}, LWJGL's
     * {@code natives-linux}, and the like.
     */
    private static final Pattern NATIVE = Pattern.compile(
            "(?i)(natives?|linux|win|windows|mac|macos|osx|darwin)([-_].*)?|.*[-_](x86_64|x86|amd64|aarch64|arm64)");

    /** One record of {@code git ls-tree -r -z}: {@code <mode> <type> <id>\t<path>}. */
    private static final Pattern TREE = Pattern.compile("(\\d{6}) (\\w+) ([0-9a-f]+)\t(.+)", Pattern.DOTALL);

    private static final Pattern PACKAGE =Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    private BotFiles() {}

    /** The {@code .bmk}'s sha256, lowercase hex: what the catalog pins as {@code bmkSha256}. */
    public static String sha256(Path bmk) throws IOException {
        return BotFile.sha256(bmk);
    }

    /** {@code Firestone-1.0.0.bmk} for {@code Firestone} at {@code v1.0.0}: a leading {@code v} is dropped. */
    public static String fileName(String name, String tag) {
        String version = tag.matches("v\\d.*") ? tag.substring(1) : tag;
        String safe = name.strip().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-.]+|-+$", "");
        return (safe.isEmpty() ? "bot" : safe) + "-" + version.replaceAll("[^A-Za-z0-9._-]+", "-") + ".bmk";
    }

    /**
     * The class holding {@code main}: the first source under {@code src/main/java/} (by path) that declares
     * {@code static void main(}, named by its package. Textual, as Studio finds its entry class: nothing is compiled.
     */
    public static Optional<String> mainClass(Map<String, byte[]> sources) {
        for (String name : sources.keySet().stream()
                .filter(f -> f.startsWith("src/main/java/") && f.endsWith(".java")).sorted().toList()) {
            String source = new String(sources.get(name), StandardCharsets.UTF_8);
            if (!source.contains("static void main(") && !source.contains("static void main (")) continue;
            String simple = name.substring(name.lastIndexOf('/') + 1).replaceFirst("\\.java$", "");
            Matcher pkg = PACKAGE.matcher(source);
            return Optional.of(pkg.find() ? pkg.group(1) + "." + simple : simple);
        }
        return Optional.empty();
    }

    /**
     * The blobs a {@code .bmk} takes from {@code git ls-tree -r -z HEAD}'s output, path to blob id. A symlink or a
     * submodule where a source belongs is refused: a {@code .bmk} holds files only.
     */
    public static Map<String, String> sources(String lsTree) throws BotFile.Refused {
        Map<String, String> out = new TreeMap<>();
        for (String record : lsTree.split("\0")) {
            Matcher m = TREE.matcher(record);
            if (!m.matches() || !BotFile.isSource(m.group(4))) continue;
            if (!m.group(2).equals("blob") || m.group(1).equals("120000")) {
                throw new BotFile.Refused(m.group(4) + " is a link or a submodule, not a file; a .bmk holds files "
                        + "only.");
            }
            out.put(m.group(4), m.group(3));
        }
        return out;
    }

    /**
     * Each path's bytes from {@code git cat-file --batch}'s output, which answered the blob ids of {@code blobs} in
     * their order: {@code <id> blob <size>\n<bytes>\n} each.
     */
    public static Map<String, byte[]> contents(Map<String, String> blobs, byte[] batch) throws BotFile.Refused {
        Map<String, byte[]> out = new TreeMap<>();
        int at = 0;
        for (Map.Entry<String, String> blob : blobs.entrySet()) {
            int eol = indexOf(batch, (byte) '\n', at);
            String[] header = eol < 0 ? new String[0]
                    : new String(batch, at, eol - at, StandardCharsets.US_ASCII).split(" ");
            if (header.length != 3 || !header[0].equals(blob.getValue()) || !header[1].equals("blob")) {
                throw new BotFile.Refused("git could not read " + blob.getKey() + " from the commit.");
            }
            int size = header[2].matches("\\d{1,10}") ? (int) Math.min(Long.parseLong(header[2]), Integer.MAX_VALUE) : -1;
            if (size < 0 || eol + 1L + size > batch.length) {
                throw new BotFile.Refused("git's answer for " + blob.getKey() + " is cut short.");
            }
            out.put(blob.getKey(), Arrays.copyOfRange(batch, eol + 1, eol + 1 + size));
            at = eol + 1 + size + 1;
        }
        return out;
    }

    /**
     * Everything {@code bot.json} says but the jars, for the bot whose {@code pom} and commit {@code sources} these
     * are, at {@code tag} and {@code commit} of the GitHub repository {@code slug} ({@code owner/repo}). Refused
     * when the pom names no {@code maven.compiler.release} or no source holds {@code main}. The name is
     * {@link #name}'s. A tag that is no plain folder name is refused, as opening would refuse it. One reading for
     * every host that writes a {@code .bmk}.
     */
    public static BotJson.Head head(Path pom, Map<String, byte[]> sources, String tag, String commit, String slug)
            throws IOException {
        String release = Poms.properties(pom).getOrDefault("maven.compiler.release", "");
        if (!release.matches("[1-9][0-9]{0,2}")) {
            throw new BotFile.Refused("the pom names no maven.compiler.release: a .bmk says which Java it needs.");
        }
        String main = mainClass(sources).orElseThrow(() -> new BotFile.Refused(
                "no source under src/main/java declares public static void main(String[]), which is how a bot "
                        + "is started."));
        String[] ownerRepo = slug.split("/", 2);
        if (ownerRepo.length != 2 || !BotFile.isFolderName(ownerRepo[0]) || !BotFile.isFolderName(ownerRepo[1])) {
            throw new BotFile.Refused(slug + " is not owner/repo.");
        }
        String name = name(pom, ownerRepo[1]);
        // Opening files the bot under its tag, a folder: refused here rather than by every reader of the file.
        if (!BotFile.isFolderName(tag)) {
            throw new BotFile.Refused("the tag " + tag + " is not a plain name (letters, digits, . _ -), which a "
                    + ".bmk needs. Tag the commit vX.Y.Z.");
        }
        return new BotJson.Head(name.strip(), ownerRepo[0], ownerRepo[1], tag, commit, Integer.parseInt(release), main);
    }

    /**
     * A bot's name: the pom's {@code <name>}, else its GitHub repository's name {@code repo} (2026-10-25: Firestone's
     * pom has none, and its artifactId, {@code gamebot}, is a cloned blank's). Never the folder: the catalog rebuilds
     * the file from a clone in a folder of its own, and the two files must be the same.
     */
    static String name(Path pom, String repo) throws IOException {
        String name = Poms.name(pom);
        return name.isBlank() ? repo : name;
    }

    /** Whether a jar's {@code classifier} names a system ({@link #NATIVE}), so no {@code .bmk} lists it. */
    public static boolean isNative(String classifier) {
        return classifier != null && !classifier.isEmpty() && NATIVE.matcher(classifier).matches();
    }

    private static int indexOf(byte[] bytes, byte b, int from) {
        for (int i = from; i < bytes.length; i++) if (bytes[i] == b) return i;
        return -1;
    }

    /**
     * The jars a {@code dependency:list} output names, each once, sorted by coordinate: every scope but
     * {@code test} (the bot compiles against {@code provided} too), {@code jar} types only, and no jar whose
     * classifier names a system ({@link #NATIVE}).
     */
    public static List<BotFile.Jar> jars(String dependencyList) {
        Map<String, BotFile.Jar> out = new LinkedHashMap<>();
        for (String line : dependencyList.lines().toList()) {
            Matcher m = LISTED.matcher(line);
            if (!m.matches() || !m.group(3).equals("jar") || m.group(6).equals("test")) continue;
            String classifier = m.group(4);
            if (isNative(classifier)) continue;
            String coordinate = m.group(1) + ":" + m.group(2) + ":" + m.group(5)
                    + (classifier == null ? "" : ":" + classifier);
            out.putIfAbsent(coordinate, new BotFile.Jar(coordinate, Path.of(m.group(7).strip())));
        }
        List<BotFile.Jar> sorted = new ArrayList<>(out.values());
        sorted.sort(Comparator.comparing(BotFile.Jar::coordinate));
        return sorted;
    }
}
