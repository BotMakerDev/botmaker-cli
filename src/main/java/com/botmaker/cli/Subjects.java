package com.botmaker.cli;

import com.botmaker.cli.validate.PluginSubject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turning "this directory" or "this coordinate" into the resolved facts {@code PluginValidator} needs.
 *
 * <p>Every process spawn and every download in validation happens here, which is what lets the validator
 * itself be a pure library with two callers in two repositories. The two entry points are deliberately
 * different questions: {@link #fromDirectory} validates the build in front of you, and
 * {@link #fromCoordinate} validates what the world can actually download — the one the registry's CI asks,
 * and the only one that catches a plugin that works locally and publishes a pom nobody can resolve.
 *
 * <p><b>Public, and only {@link #fromCoordinate} is.</b> The registry's gate
 * ({@link com.botmaker.cli.registry.RegistryGate}) resolves a published coordinate through this same class,
 * so that "the gate downloads what a bot project would download" is one implementation rather than two.
 * {@link #fromDirectory} stays package-private: a working copy is the author's question, and the registry
 * has no working copy to ask about.
 */
public final class Subjects {

    private final Console console;
    private final Mvn mvn;

    public Subjects(Console console, Mvn mvn) {
        this.console = console;
        this.mvn = mvn;
    }

    /**
     * A working copy: its own {@code target/classes} first, then its resolved runtime classpath.
     *
     * <p>{@code target/classes} leads because it is the plugin being validated and everything after it is
     * background; the loader is child-first for non-contract names, so an entry earlier on this list is the
     * one a plugin's own class resolves from.
     *
     * @param build whether to compile first. Compiling is the default because validating a stale
     *              {@code target/classes} answers a question about a build the author has already replaced
     */
    PluginSubject fromDirectory(Path dir, boolean build) throws IOException {
        Path pom = dir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new IOException("no pom.xml in " + dir.toAbsolutePath()
                    + " — point `botmaker plugin validate` at a plugin project, or pass --coordinate G:A:V");
        }
        if (build) {
            console.step("Compiling…");
            Mvn.Result compiled = mvn.run(dir, "-q", "-DskipTests", "compile");
            if (!compiled.ok()) {
                throw new IOException("the plugin does not compile:\n" + compiled.tail(20));
            }
        }
        Path classes = dir.resolve("target/classes");
        if (!Files.isDirectory(classes)) {
            throw new IOException("no target/classes in " + dir.toAbsolutePath()
                    + " — drop --no-build, or run `mvn compile` first");
        }
        List<Path> classpath = new ArrayList<>();
        classpath.add(classes);
        classpath.addAll(runtimeClasspath(dir, pom));
        return PluginSubject.local(classpath, pom);
    }

    /**
     * A published coordinate, resolved exactly as a bot project would resolve it.
     *
     * <p>The pom is fetched as well as the jar, because {@link com.botmaker.cli.validate.Check#POM_SCOPES}
     * asks what the <em>published</em> pom declares — and a plugin whose working copy is right and whose
     * published pom says {@code 0.0.0-SNAPSHOT} is a real failure mode this project has shipped before.
     */
    public PluginSubject fromCoordinate(String coordinate, Set<String> claimedIds) throws IOException {
        return fromCoordinates(List.of(coordinate), claimedIds);
    }

    /**
     * Several coordinates on <b>one</b> classpath, which is what a host's own bundled set is.
     *
     * <p>Needed because a plugin's dependency may be {@code optional} and therefore not transitive: the SDK
     * declares {@code botmaker-plugin-toolkit} that way, so resolving {@code botmaker-sdk} alone yields a
     * classpath its own plugin cannot be constructed from. Whoever puts a plugin on a classpath supplies
     * what that plugin needs — Studio does it with a {@code runtime} dependency, and this does it by naming
     * both coordinates.
     *
     * <p>The first coordinate is the subject: its published pom is the one the scope check reads.
     */
    public PluginSubject fromCoordinates(List<String> coordinates, Set<String> claimedIds) throws IOException {
        if (coordinates.isEmpty()) {
            throw new IOException("no coordinate to resolve");
        }
        List<String[]> split = new ArrayList<>();
        for (String coordinate : coordinates) {
            String[] parts = coordinate.split(":");
            if (parts.length != 3) {
                throw new IOException("expected a coordinate of the form groupId:artifactId:version, got '"
                        + coordinate + "'");
            }
            split.add(parts);
        }
        String coordinate = String.join(" + ", coordinates);
        String[] parts = split.getFirst();
        Path work = Files.createTempDirectory("botmaker-validate");
        Path pom = work.resolve("pom.xml");
        Files.writeString(pom, resolverPom(split));

        console.step("Resolving " + coordinate + "…");
        List<Path> classpath = new ArrayList<>(runtimeClasspath(work, pom));
        if (classpath.isEmpty()) {
            throw new IOException(coordinate + " resolved to an empty classpath");
        }

        Mvn.Result copied = mvn.run(work, "-q", "dependency:copy",
                "-Dartifact=" + String.join(":", parts) + ":pom", "-DoutputDirectory=" + work);
        Path published = work.resolve(parts[1] + "-" + parts[2] + ".pom");
        if (!copied.ok() || !Files.isRegularFile(published)) {
            console.warn("could not fetch the published pom for " + coordinate
                    + "; the dependency-scope check will be skipped");
            published = null;
        }
        return new PluginSubject(classpath, published, claimedIds);
    }

    /**
     * {@code dependency:build-classpath} at runtime scope.
     *
     * <p>Runtime rather than test, and runtime rather than compile: it is exactly the set a host would put
     * on the loader. A {@code compile} contract does appear in it, as it does on a bot's classpath, and is
     * harmless for the same reason there: {@code PluginLoader} is parent-first for the contract's package,
     * so the copy on this list is never the one a plugin links against.
     */
    private List<Path> runtimeClasspath(Path dir, Path pom) throws IOException {
        Path out = dir.resolve("target/botmaker-classpath.txt");
        // mdep.outputFile does not create its parent, and a pom-packaged throwaway has no target/ at all.
        Files.createDirectories(out.getParent());
        Mvn.Result result = mvn.run(dir, "-q", "-f", pom.toString(), "dependency:build-classpath",
                "-DincludeScope=runtime", "-Dmdep.outputFile=" + out);
        if (!result.ok()) {
            throw new IOException("could not resolve the plugin's dependencies:\n" + result.tail(20));
        }
        if (!Files.isRegularFile(out)) {
            return List.of();
        }
        String text = Files.readString(out).trim();
        if (text.isEmpty()) {
            return List.of();
        }
        List<Path> classpath = new ArrayList<>();
        for (String entry : text.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                classpath.add(Path.of(entry.trim()));
            }
        }
        return classpath;
    }

    /**
     * A throwaway pom whose only job is to have one dependency Maven can resolve.
     *
     * <p>JitPack is declared because that is where every BotMaker coordinate lives and where a plugin built
     * from a GitHub tag will live too. Central needs no declaration.
     */
    private static String resolverPom(List<String[]> coordinates) {
        StringBuilder dependencies = new StringBuilder();
        for (String[] parts : coordinates) {
            dependencies.append("""
                                <dependency>
                                    <groupId>%s</groupId>
                                    <artifactId>%s</artifactId>
                                    <version>%s</version>
                                </dependency>
                    """.formatted(parts[0], parts[1], parts[2]));
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.botmaker.cli</groupId>
                    <artifactId>validate-subject</artifactId>
                    <version>0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <repositories>
                        <repository>
                            <id>jitpack.io</id>
                            <url>https://jitpack.io</url>
                        </repository>
                    </repositories>
                    <dependencies>
                %s    </dependencies>
                </project>
                """.formatted(dependencies);
    }
}
