package com.botmaker.cli.catalog;

import com.botmaker.cli.BotExport;
import com.botmaker.cli.Mvn;
import com.botmaker.cli.bmk.BotFile;
import com.botmaker.cli.bmk.BotFiles;
import com.botmaker.cli.bmk.BotJson;
import com.botmaker.cli.bmk.Central;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A bot's own rules, on a clone at the approved release's pinned commit:
 *
 * <ol start="6">
 *   <li>its {@code pom.xml} declares at least one catalog plugin, each at a released version;</li>
 *   <li>{@code mvn -B compile -Dmaven.test.skip} succeeds;</li>
 *   <li>a compiled class holds {@code public static void main(String[])}, read from the class files;</li>
 *   <li>a release that pins a {@code bmkSha256}: the {@code .bmk} on its GitHub Release is the one the commit
 *       builds ({@link #checkBmk});</li>
 *   <li>{@code icon.png}, when there is one, is a PNG of at most 512 KB ({@link #checkIcon}).</li>
 * </ol>
 *
 * <p>The build runs the bot's Maven plugins, which is acceptable only because validate.yml holds no secret and a
 * read-only token, and the build gets neither: {@link Mvn#runUntrusted} clears its environment.
 */
public final class BotChecks {

    private static final String MAIN_DESCRIPTOR = "([Ljava/lang/String;)V";
    private static final String ICON = "icon.png";
    static final int MAX_ICON_BYTES = 512 * 1024;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private BotChecks() {}

    /**
     * Clones {@code repo} at {@code release}'s pinned commit into a temporary directory, then {@link #check}; a
     * release that pins a {@code .bmk} is rebuilt from the clone too ({@link #checkBmk}).
     *
     * @param catalog the catalog as the pull request's tree holds it, for the plugin jars' pins
     */
    public static List<Finding> checkRelease(String repo, ApprovedRelease release, String path,
                                             Map<String, String> pluginRepos, Optional<Catalog> catalog,
                                             EntryFacts facts, Mvn mvn) throws IOException {
        Path clone = Files.createDirectories(Files.createTempDirectory("catalog-bot").resolve("bot"));
        String failure = fetch(clone, repo, release);
        if (failure != null) return List.of(Finding.error(path, failure));
        if (release.bmkSha256() == null) return check(clone, path, pluginRepos, mvn);
        Path list = Files.createTempFile("catalog-deps", ".txt");
        try {
            // One build: the dependency list's jars are in the build's own empty repository (Mvn#runUntrusted's
            // HOME), which a second run would not see.
            List<Finding> found = check(clone, path, pluginRepos, mvn, "dependency:list",
                    "-DoutputAbsoluteArtifactFilename=true", "-DoutputFile=" + list, "-DappendOutput=false");
            if (Finding.refused(found)) return found;
            // The build ran the bot's own plugins, which may have rewritten the clone, its .git and the list: the
            // .bmk is written again from a second fetch the build never saw, and every jar it packs is checked
            // against JitPack (checkBmk). A jar Central serves byte for byte is listed, not packed: Studio fetches
            // it from Central and refuses one whose sha256 is not bot.json's.
            Path pristine = Files.createDirectories(clone.resolveSibling("pristine"));
            failure = fetch(pristine, repo, release);
            if (failure != null) return List.of(Finding.error(path, failure));
            return checkBmk(pristine, repo, release, path, BotFiles.jars(Files.readString(list)), catalog, facts);
        } finally {
            Files.deleteIfExists(list);
        }
    }

    /** {@code repo} at {@code release}'s pinned commit, checked out in {@code dir}; why not, or {@code null}. */
    private static String fetch(Path dir, String repo, ApprovedRelease release) throws IOException {
        // No shell: the repository and the commit passed the entry's patterns, and reach git as arguments. The
        // commit is fetched itself, not the tag: it is what Studio installs, whatever the tag points at later.
        for (List<String> step : List.of(List.of("git", "init", "--quiet"),
                List.of("git", "fetch", "--quiet", "--depth", "1", "https://github.com/" + repo + ".git",
                        release.commit()),
                List.of("git", "checkout", "--quiet", "--detach", "FETCH_HEAD"))) {
            String failure = git(dir, step);
            if (failure != null) {
                return "could not fetch " + repo + " at " + release.tag() + " (" + release.commit() + "): " + failure;
            }
        }
        return null;
    }

    /**
     * Rule 9 (2026-10-25): the {@code .bmk} on the GitHub Release is the one the pinned commit builds — the clone
     * written again with {@code jars} ({@link BotExport}) gives the same sha256 as the file GitHub serves, and both
     * are the entry's {@code bmkSha256}. A catalog plugin among the jars must be the jar its own release pins; a jar
     * Maven Central serves with the same bytes is listed and fetched from there, and every other jar, which the file
     * packs, must be the one JitPack serves (2026-10-25: by Central's checksum, not by groupId).
     *
     * @param clone a checkout of the commit no build ran in
     */
    static List<Finding> checkBmk(Path clone, String repo, ApprovedRelease release, String path,
                                  List<BotFile.Jar> jars, Optional<Catalog> catalog, EntryFacts facts)
            throws IOException {
        Central central = facts.central();
        for (BotFile.Jar jar : jars) {
            byte[] bytes = Files.readAllBytes(jar.file());
            String sha = BotFiles.sha256(jar.file());
            Optional<String> pinned = pinnedJar(jar.coordinate(), catalog);
            if (pinned.isPresent() && !pinned.get().equals(sha)) {
                return List.of(Finding.error(path, jar.coordinate() + " is not the jar the catalog pins for that"
                        + " plugin release."));
            }
            // A jar the .bmk packs is the one JitPack serves, whatever the build put in its repository.
            if (!central.serves(new BotJson.Lib(null, jar.coordinate(), sha), bytes)) {
                String[] parts = jar.coordinate().split(":");
                Optional<String> served = parts.length == 3 ? facts.jitpackSha256(parts[0] + ":" + parts[1],
                        parts[2], EntryFacts.Artifact.JAR) : Optional.empty();
                if (!served.equals(Optional.of(sha))) {
                    return List.of(Finding.error(path, jar.coordinate() + " is not the jar JitPack serves"
                            + (parts.length == 3 ? "" : " (a jar with a classifier cannot be checked)")
                            + ", nor one Maven Central serves: a bot runs only with jars from either."));
                }
            }
        }
        Path out = Files.createTempDirectory("catalog-bmk");
        Path rebuilt = null;
        try {
            try {
                rebuilt = BotExport.write(clone, release.tag(), repo, out, jars, central);
            } catch (BotFile.Refused e) {
                return List.of(Finding.error(path, "the .bmk cannot be made from this commit: " + e.getMessage()));
            }
            String name = rebuilt.getFileName().toString();
            Optional<String> served = facts.releaseAssetSha256(repo, release.tag(), name);
            if (served.isEmpty()) {
                return List.of(Finding.error(path, "release " + release.tag() + " has no " + name + ", the .bmk"
                        + " the entry pins. Publish again: it attaches the file."));
            }
            if (!served.get().equals(release.bmkSha256())) {
                return List.of(Finding.error(path, "The .bmk on the release is not the one the entry pins."));
            }
            if (!BotFiles.sha256(rebuilt).equals(release.bmkSha256())) {
                return List.of(Finding.error(path, "The .bmk on the release is not the one its commit builds."));
            }
            return List.of();
        } finally {
            if (rebuilt != null) Files.deleteIfExists(rebuilt);
            Files.deleteIfExists(out);
        }
    }

    /** The {@code jarSha256} the catalog pins for {@code coordinate} ({@code group:artifact:version}), if a plugin's. */
    private static Optional<String> pinnedJar(String coordinate, Optional<Catalog> catalog) {
        String[] parts = coordinate.split(":");
        if (parts.length != 3 || catalog.isEmpty()) return Optional.empty();
        String key = parts[0] + ":" + parts[1];
        return catalog.get().plugins().stream().filter(p -> p.coordinate().equalsIgnoreCase(key))
                .flatMap(p -> p.releases().stream()).filter(r -> r.tag().equals(parts[2]))
                .map(Catalog.Plugin.Release::jarSha256).filter(Objects::nonNull).findFirst();
    }

    /**
     * Rule 10 (2026-10-25): {@code icon.png} at the folder's root, when there is one, is a PNG file of at most
     * {@value #MAX_ICON_BYTES} bytes. Every bot card shows it, and the website serves it raw.
     */
    public static List<Finding> checkIcon(Path dir, String path) throws IOException {
        Path icon = dir.resolve(ICON);
        if (!Files.exists(icon, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isRegularFile(icon, LinkOption.NOFOLLOW_LINKS)) {
            return List.of(Finding.error(path, ICON + " is not a plain file."));
        }
        if (Files.size(icon) > MAX_ICON_BYTES) {
            return List.of(Finding.error(path, ICON + " is " + Files.size(icon) / 1024 + " KB; an icon is at most "
                    + MAX_ICON_BYTES / 1024 + " KB (Studio's Project Settings writes a small one)."));
        }
        byte[] head;
        try (InputStream in = Files.newInputStream(icon)) {
            head = in.readNBytes(PNG.length);
        }
        if (!Arrays.equals(head, PNG)) return List.of(Finding.error(path, ICON + " is not a PNG image."));
        return List.of();
    }

    /** Runs one git step in {@code dir}, at most ten minutes; its output when it failed, else {@code null}. */
    private static String git(Path dir, List<String> command) throws IOException {
        Path log = Files.createTempFile("catalog-git", ".log");
        Process git = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            if (!git.waitFor(10, TimeUnit.MINUTES)) {
                git.destroyForcibly();
                return "timed out";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            git.destroyForcibly();
            throw new IOException("interrupted while running git", e);
        }
        String output = Files.readString(log).strip();
        Files.deleteIfExists(log);
        return git.exitValue() == 0 ? null : output;
    }

    /** Rules 6–8 and 10 on a working copy. */
    public static List<Finding> check(Path clone, String path, Map<String, String> pluginRepos, Mvn mvn)
            throws IOException {
        return check(clone, path, pluginRepos, mvn, new String[0]);
    }

    /** {@link #check(Path, String, Map, Mvn)}, with {@code goals} run in the same build after the compile. */
    private static List<Finding> check(Path clone, String path, Map<String, String> pluginRepos, Mvn mvn,
                                       String... goals) throws IOException {
        List<Finding> pom = checkPom(clone.resolve("pom.xml"), path, pluginRepos);
        if (Finding.refused(pom)) return pom;
        List<Finding> icon = checkIcon(clone, path);
        if (Finding.refused(icon)) return icon;
        List<String> build = new ArrayList<>(List.of("compile", "-Dmaven.test.skip"));
        build.addAll(List.of(goals));
        Mvn.Result compiled = mvn.runUntrusted(clone, Duration.ofMinutes(20), build.toArray(String[]::new));
        if (!compiled.ok()) {
            return List.of(Finding.error(path, "the bot does not compile at this tag:\n" + compiled.tail(15)));
        }
        return checkClasses(clone.resolve("target/classes"), path);
    }

    /**
     * Rules 6–8 on the author's own folder, before a publish tags it. Compiled as the author's own build (their
     * Maven settings and repository): it is their code on their machine, which {@link #check} cannot assume.
     */
    public static List<Finding> checkFolder(Path dir, String path, Map<String, String> pluginRepos, Mvn mvn)
            throws IOException {
        List<Finding> pom = checkPom(dir.resolve("pom.xml"), path, pluginRepos);
        if (Finding.refused(pom)) return pom;
        List<Finding> icon = checkIcon(dir, path);
        if (Finding.refused(icon)) return icon;
        Mvn.Result compiled = mvn.run(dir, "-q", "compile", "-Dmaven.test.skip");
        if (!compiled.ok()) {
            return List.of(Finding.error(path, "the bot does not compile:\n" + compiled.tail(15)));
        }
        return checkClasses(dir.resolve("target/classes"), path);
    }

    /** Rule 6. Public for a host that compiles the bot its own way (Studio). */
    public static List<Finding> checkPom(Path pom, String path, Map<String, String> pluginRepos) throws IOException {
        if (!Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)) {
            return List.of(Finding.error(path, "no pom.xml at this tag: a bot is a Maven project."));
        }
        List<DerivedFacts.Pin> pins = PomFacts.pins(pom, pluginRepos);
        if (pins.isEmpty()) {
            return List.of(Finding.error(path, "the pom declares no plugin the catalog lists; a bot declares at"
                    + " least one (the BotMaker SDK, for most)."));
        }
        return pins.stream().filter(p -> !CatalogBuilder.VERSION.matcher(p.version()).matches())
                .map(p -> Finding.error(path, p.repo() + " is pinned at \"" + p.version() + "\", not a released"
                        + " version (no -SNAPSHOT, no property the pom does not define)."))
                .toList();
    }

    /** Rule 8, on the compiled classes. Public for a host that compiles the bot its own way (Studio). */
    public static List<Finding> checkClasses(Path classes, String path) throws IOException {
        if (Files.isDirectory(classes)) {
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")
                        && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    if (hasMain(ClassFile.of().parse(file))) return List.of();
                }
            }
        }
        return List.of(Finding.error(path, "no compiled class holds public static void main(String[]), which is"
                + " how a bot is started."));
    }

    private static boolean hasMain(ClassModel model) {
        for (MethodModel method : model.methods()) {
            if (method.methodName().equalsString("main") && method.methodType().equalsString(MAIN_DESCRIPTOR)
                    && method.flags().has(AccessFlag.PUBLIC) && method.flags().has(AccessFlag.STATIC)) {
                return true;
            }
        }
        return false;
    }
}
