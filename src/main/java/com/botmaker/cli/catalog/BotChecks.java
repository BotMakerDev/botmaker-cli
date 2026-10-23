package com.botmaker.cli.catalog;

import com.botmaker.cli.Mvn;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A bot's own rules, on a clone at the approved release's pinned commit:
 *
 * <ol start="6">
 *   <li>its {@code pom.xml} declares at least one catalog plugin, each at a released version;</li>
 *   <li>{@code mvn -B compile -Dmaven.test.skip} succeeds;</li>
 *   <li>a compiled class holds {@code public static void main(String[])}, read from the class files.</li>
 * </ol>
 *
 * <p>The build runs the bot's Maven plugins, which is acceptable only because validate.yml holds no secret and a
 * read-only token, and the build gets neither: {@link Mvn#runUntrusted} clears its environment.
 */
public final class BotChecks {

    private static final String MAIN_DESCRIPTOR = "([Ljava/lang/String;)V";

    private BotChecks() {}

    /** Clones {@code repo} at {@code release}'s pinned commit into a temporary directory, then {@link #check}. */
    public static List<Finding> checkRelease(String repo, ApprovedRelease release, String path,
                                             Map<String, String> pluginIds, Mvn mvn) throws IOException {
        Path clone = Files.createDirectories(Files.createTempDirectory("catalog-bot").resolve("bot"));
        // No shell: the repository and the commit passed the entry's patterns, and reach git as arguments. The
        // commit is fetched itself, not the tag: it is what Studio installs, whatever the tag points at later.
        for (List<String> step : List.of(List.of("git", "init", "--quiet"),
                List.of("git", "fetch", "--quiet", "--depth", "1", "https://github.com/" + repo + ".git",
                        release.commit()),
                List.of("git", "checkout", "--quiet", "--detach", "FETCH_HEAD"))) {
            String failure = git(clone, step);
            if (failure != null) {
                return List.of(Finding.error(path, "could not fetch " + repo + " at " + release.tag() + " ("
                        + release.commit() + "): " + failure));
            }
        }
        return check(clone, path, pluginIds, mvn);
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

    /** Rules 6–8 on a working copy. */
    public static List<Finding> check(Path clone, String path, Map<String, String> pluginIds, Mvn mvn)
            throws IOException {
        List<Finding> pom = checkPom(clone.resolve("pom.xml"), path, pluginIds);
        if (Finding.refused(pom)) return pom;
        Mvn.Result compiled = mvn.runUntrusted(clone, Duration.ofMinutes(20), "compile", "-Dmaven.test.skip");
        if (!compiled.ok()) {
            return List.of(Finding.error(path, "the bot does not compile at this tag:\n" + compiled.tail(15)));
        }
        return checkClasses(clone.resolve("target/classes"), path);
    }

    /** Rule 6. */
    static List<Finding> checkPom(Path pom, String path, Map<String, String> pluginIds) throws IOException {
        if (!Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)) {
            return List.of(Finding.error(path, "no pom.xml at this tag: a bot is a Maven project."));
        }
        List<DerivedFacts.Pin> pins = PomFacts.pins(pom, pluginIds);
        if (pins.isEmpty()) {
            return List.of(Finding.error(path, "the pom declares no plugin the catalog lists; a bot declares at"
                    + " least one (the BotMaker SDK, for most)."));
        }
        return pins.stream().filter(p -> !CatalogBuilder.VERSION.matcher(p.version()).matches())
                .map(p -> Finding.error(path, p.id() + " is pinned at \"" + p.version() + "\", not a released"
                        + " version (no -SNAPSHOT, no property the pom does not define)."))
                .toList();
    }

    /** Rule 8. */
    static List<Finding> checkClasses(Path classes, String path) throws IOException {
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
