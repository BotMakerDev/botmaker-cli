package com.botmaker.cli.gallery;

import com.botmaker.cli.project.Poms;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Starting from somebody else's published bot: the download, and the one thing that changes on the way in.
 *
 * <h2>The CLI holds no template content</h2>
 *
 * <p>A template is a <b>published bot</b> whose gallery entry carries {@link GalleryEntry#TEMPLATE_TAG} —
 * same repository, same release, same archive as any other bot. So this class ships no files of its own: it
 * downloads the author's release and renames one thing. A new kind of starting point therefore needs no
 * release of this command, which is the same property Studio's New Project has.
 *
 * <h2>The archive, and why it is the API's zipball</h2>
 *
 * <p>The same URL Studio's {@code BotInstaller} uses: {@code /repos/o/r/zipball/refs/tags/<tag>}. It honours
 * an {@code Authorization} header where a direct {@code codeload} URL does not — which this command has no
 * token for and does not want one, but which keeps the two clients on one endpoint — and it answers a 302 to
 * a signed URL that needs no auth of its own. GitHub wraps every entry in a single {@code repo-sha/}
 * directory, which is stripped here exactly as it is there.
 *
 * <h2>Only the package is renamed</h2>
 *
 * <p>{@code TemplateProject} in Studio is the original of this rule and states it at length: the package
 * holding {@code main} is the template's (2026-09-27; a {@code botmaker-template.properties} declared it
 * before, and a template that still ships one has it deleted from the copy), that prefix is replaced in every text file and the directories move with it, and <b>nothing else is
 * renamed</b>. The entry class keeps the author's name, their helper classes keep theirs, their javadoc
 * keeps its wording — what they shipped is what demonstrably built for them, and a copy that quietly renames
 * their types is a copy whose stack traces and README stop matching. The package is the exception because it
 * is the one name that must not be shared.
 *
 * <p>Reproduced rather than shared for the reason {@code BlankProject} gives: {@code botmaker-studio} is an
 * application, and depending on it would put JavaFX, OpenCV and JNA behind a single-jar command.
 */
public final class Templates {

    private static final String API = "https://api.github.com";

    /** Files whose bytes are not text and must be copied through untouched. */
    private static final List<String> BINARY_SUFFIXES =
            List.of(".png", ".jpg", ".jpeg", ".gif", ".ico", ".zip", ".jar", ".class", ".pdf");

    private Templates() {
    }

    /** The newest release's tag, which is the one a gallery install would take. */
    public static String latestReleaseTag(String owner, String repo) throws IOException {
        return findLatestReleaseTag(owner, repo).orElseThrow(() -> new IOException(owner + "/" + repo
                + " has no release yet, and a template is installed from its release archive. Ask its author"
                + " to cut one."));
    }

    /** The newest release's tag, or empty when the repository has none (or does not exist). */
    public static Optional<String> findLatestReleaseTag(String owner, String repo) throws IOException {
        String url = API + "/repos/" + owner + "/" + repo + "/releases/latest";
        HttpResponse<byte[]> response = get(url);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GitHub answered " + response.statusCode() + " for " + url);
        }
        JsonNode json = new ObjectMapper().readTree(response.body());
        String tag = json.path("tag_name").asText("");
        if (tag.isBlank()) {
            throw new IOException("the newest release of " + owner + "/" + repo + " has no tag");
        }
        return Optional.of(tag);
    }

    /** Downloads the release archive for {@code tag} and unpacks it into {@code dest}. */
    public static void downloadInto(String owner, String repo, String tag, Path dest) throws IOException {
        String url = API + "/repos/" + owner + "/" + repo + "/zipball/refs/tags/" + tag;
        HttpResponse<byte[]> response = get(url);
        if (response.statusCode() / 100 != 2) {
            throw new IOException("could not download " + owner + "/" + repo + "@" + tag + ": GitHub"
                    + " answered " + response.statusCode());
        }
        Files.createDirectories(dest);
        unzipStrippingTopDirectory(response.body(), dest);
    }

    /**
     * Whether the release archive a gallery entry will point at can actually be fetched.
     *
     * <p>Asked before an entry naming it is written, which is the same rule {@code botmaker publish} follows
     * for a coordinate: <b>do not publish a pointer nobody has followed</b>. A gallery entry whose archive
     * 404s is a Studio install that fails on somebody else's machine, days later, with no way for them to
     * tell whose fault it is.
     */
    public static boolean archiveExists(String owner, String repo, String tag) throws IOException {
        String url = API + "/repos/" + owner + "/" + repo + "/zipball/refs/tags/" + tag;
        return get(url).statusCode() / 100 == 2;
    }

    /**
     * Finds the template's package and rewrites the unpacked copy into {@code newPackage}.
     *
     * <p>Text first, then the moves: rewriting after the move would mean walking a tree whose shape has
     * already changed, and a half-moved tree is the state hardest to recover from.
     */
    public static void repackage(Path projectDir, String newPackage) throws IOException {
        repackage(projectDir, newPackage, null);
    }

    /**
     * As {@link #repackage(Path, String)}, and also names the project {@code newArtifactId}.
     *
     * <p><b>The artifactId is the project's identity, not the author's code, and the two rules are
     * different.</b> Class names, file names and javadoc keep the template author's wording — what they
     * shipped is what demonstrably built for them. The Maven coordinate is the opposite: it says which
     * project this is, and a project somebody created as {@code farm} announcing itself as {@code base}
     * builds {@code base-0.0.1-SNAPSHOT.jar} and collides in {@code ~/.m2} with every other copy of the
     * same template.
     *
     * <p>Before this existed the pom came out <em>half</em>-renamed, which is what made the rule visible:
     * {@code groupId} already changed, but only by accident — it happened to equal the declared package
     * prefix, so the text pass caught it. Renaming the artifact deliberately is what makes that consistent
     * rather than incidental.
     *
     * <p>A text replacement rather than a DOM rewrite: the pom is the author's file, with the author's
     * comments and spacing in it, and re-serialising a parsed document to change one element would
     * reformat all of it.
     */
    public static void repackage(Path projectDir, String newPackage, String newArtifactId)
            throws IOException {
        String declared = packageOf(projectDir);
        Path sources = projectDir.resolve("src/main/java").resolve(declared.replace('.', '/'));
        if (!Files.isDirectory(sources)) {
            throw new IOException("this template's package is " + declared + ", but there are no sources in"
                    + " it. Ask its author to fix it.");
        }
        if (declared.equals(newPackage)) {
            renameArtifact(projectDir, newArtifactId);
            Files.deleteIfExists(projectDir.resolve(OLD_DECLARATION));
            return;
        }
        rewriteText(projectDir, declared, newPackage);
        movePackage(projectDir, declared, newPackage);
        renameArtifact(projectDir, newArtifactId);
        Files.deleteIfExists(projectDir.resolve(OLD_DECLARATION));
    }

    /**
     * Rewrites the project's own {@code <artifactId>} — never a dependency's, and never a parent's.
     *
     * <p>The parent block is skipped by starting after it: a parent coordinate is a different project and
     * changing it would repoint the build. Dependencies sit below the project's own coordinate in every
     * pom Maven itself generates, so replacing the first occurrence after the parent is the project's.
     */
    private static void renameArtifact(Path projectDir, String newArtifactId) throws IOException {
        if (newArtifactId == null || newArtifactId.isBlank()) {
            return;
        }
        Path pom = projectDir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return;
        }
        String current = Poms.coordinate(pom).artifactId();
        if (current.isBlank() || current.equals(newArtifactId)) {
            return;
        }
        String text = Files.readString(pom);
        int from = text.indexOf("</parent>");
        from = from < 0 ? 0 : from + "</parent>".length();
        String element = "<artifactId>" + current + "</artifactId>";
        int at = text.indexOf(element, from);
        if (at < 0) {
            return;   // spread over lines, or entity-escaped: leave the author's file alone
        }
        Files.writeString(pom, text.substring(0, at)
                + "<artifactId>" + newArtifactId + "</artifactId>"
                + text.substring(at + element.length()));
    }

    /**
     * The declaration file templates carried until 2026-09-27. Nothing reads it; a copy made from an older
     * template release has it removed, as Studio's {@code TemplateProject} does.
     */
    private static final String OLD_DECLARATION = "botmaker-template.properties";

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern MAIN = Pattern.compile("public\\s+static\\s+void\\s+main\\s*\\(");

    /**
     * The template's package: the package of the class holding {@code public static void main}, or, when
     * several classes do, the one every other lies under. Studio's {@code TemplateProject.derivePackage}, the
     * same rule.
     *
     * @throws IOException when no class has a {@code main}, or they sit in packages that share no root — a
     *                     template whose package is unknown cannot have it replaced
     */
    public static String packageOf(Path projectDir) throws IOException {
        Path sources = projectDir.resolve("src/main/java");
        java.util.TreeSet<String> packages = new java.util.TreeSet<>(Comparator.comparingInt(String::length)
                .thenComparing(Comparator.naturalOrder()));
        if (Files.isDirectory(sources)) {
            try (var walk = Files.walk(sources)) {
                for (Path java : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(java);
                    if (!MAIN.matcher(text).find()) continue;
                    Matcher pkg = PACKAGE.matcher(text);
                    if (pkg.find()) packages.add(pkg.group(1));
                }
            }
        }
        if (packages.isEmpty()) {
            throw new IOException("nothing can tell this template's package: no class in a package has a main"
                    + " method. Add one to the class the template starts from.");
        }
        String root = packages.first();
        for (String other : packages) {
            if (!other.equals(root) && !other.startsWith(root + ".")) {
                throw new IOException("classes with a main method sit in " + root + " and " + other
                        + ", so nothing can tell which package is the template's. Keep every main method in"
                        + " the template's package or below it.");
            }
        }
        return root;
    }

    private static void rewriteText(Path projectDir, String from, String to) throws IOException {
        for (Path file : textFilesUnder(projectDir)) {
            String before;
            try {
                before = Files.readString(file);
            } catch (MalformedInputException notText) {
                continue;   // a binary file with an unexpected extension: leave it exactly as it is
            }
            String after = before.replace(from, to);
            if (!after.equals(before)) {
                Files.writeString(file, after);
            }
        }
    }

    private static void movePackage(Path projectDir, String from, String to) throws IOException {
        for (String root : List.of("src/main/java", "src/test/java")) {
            Path source = projectDir.resolve(root).resolve(from.replace('.', '/'));
            if (!Files.isDirectory(source)) {
                continue;
            }
            Path target = projectDir.resolve(root).resolve(to.replace('.', '/'));
            Files.createDirectories(target.getParent());
            Files.move(source, target);
            pruneEmptyDirectories(projectDir.resolve(root), target);
        }
    }

    private static void pruneEmptyDirectories(Path root, Path keep) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> directories;
        try (var walk = Files.walk(root)) {
            directories = walk.filter(Files::isDirectory)
                    .filter(p -> !p.equals(root) && !keep.startsWith(p))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
        for (Path directory : directories) {
            try (var entries = Files.list(directory)) {
                if (entries.findAny().isEmpty()) {
                    Files.delete(directory);
                }
            }
        }
    }

    private static List<Path> textFilesUnder(Path projectDir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (var walk = Files.walk(projectDir)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString().toLowerCase();
                if (BINARY_SUFFIXES.stream().noneMatch(name::endsWith)) {
                    out.add(path);
                }
            }
        }
        return out;
    }

    /** Unzips a GitHub archive, stripping the single {@code repo-sha/} directory. Guards against zip-slip. */
    static void unzipStrippingTopDirectory(byte[] zipBytes, Path dest) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                int slash = entry.getName().indexOf('/');
                String stripped = slash < 0 ? "" : entry.getName().substring(slash + 1);
                if (stripped.isEmpty()) {
                    continue;   // the top-level directory entry itself
                }
                Path target = root.resolve(stripped).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("blocked unsafe zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(target)) {
                        zip.transferTo(out);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static HttpResponse<byte[]> get(String url) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "botmaker-cli")
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching " + url, e);
        }
    }
}
