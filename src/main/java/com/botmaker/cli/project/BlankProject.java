package com.botmaker.cli.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The blank bot project {@code botmaker bot new} writes: a pom, one {@code main}, and the template
 * declaration.
 *
 * <h2>It names no plugin, and that is the whole point</h2>
 *
 * <p>Since 2026-09-04 a project Studio creates names no plugin either — no SDK, no toolkit, no JavaFX. The
 * platform's rule is that the SDK is one plugin among any number, so a starting point that names it is a
 * starting point that has already chosen one, which is a choice belonging to whoever is starting. The
 * repositories are declared all the same, so the project can <em>become</em> a bot without anybody
 * hand-editing XML: <b>Project ▸ Manage Plugins</b> adds the SDK as an ordinary dependency and it resolves.
 *
 * <h2>This duplicates Studio's {@code MavenService.blankPomXml} and {@code StarterSources}, deliberately</h2>
 *
 * <p>{@code botmaker-studio} is an application, not a library: there is nothing to depend on, and depending
 * on it would mean pulling JavaFX, OpenCV and JNA into a command whose promise is a single jar. The
 * precedent is {@code validate/StubContexts}, which re-writes the toolkit's {@code TestContexts} for the
 * same reason and says so in its own javadoc. Both copies write a project whose whole point is that nothing
 * maintains it afterwards — there is no ongoing agreement to keep, only the shape of a first commit — and if
 * the two drift, the cost is that two blank projects differ in a comment.
 *
 * <h2>Text, not {@code maven-model}</h2>
 *
 * <p>Studio composes its pom through the Maven Model API so a project name containing an {@code &} cannot
 * produce a file that does not parse. Here the name is a command-line argument and is <b>refused</b> unless
 * it is a Maven-safe identifier, which answers the same question one step earlier and costs no dependency.
 */
public final class BlankProject {

    private BlankProject() {
    }

    /**
     * The files, keyed by path relative to the project root.
     *
     * <p>Built in memory before anything is written, which is {@code ProjectCreator.writeProject}'s rule
     * carried over: everything that can refuse refuses while there is nothing to clean up.
     *
     * @param name        the artifact id and the entry class's name
     * @param packageName the Java package every source sits in
     */
    public static Map<String, String> files(String name, String packageName) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pom.xml", pomXml(name, packageName));
        files.put("src/main/java/" + packageName.replace('.', '/') + "/" + className(name) + ".java",
                mainClass(packageName, className(name)));
        // No template declaration: `botmaker bot publish --template` reads the package off the class holding
        // main (2026-09-27).
        return files;
    }

    /** Writes {@link #files} under {@code projectDir}, refusing rather than merging into an existing tree. */
    public static void write(Path projectDir, String name, String packageName) throws IOException {
        Map<String, String> files = files(name, packageName);
        if (Files.isDirectory(projectDir)) {
            try (var existing = Files.list(projectDir)) {
                if (existing.findAny().isPresent()) {
                    throw new IOException(projectDir + " is not empty — `botmaker bot new` writes a whole"
                            + " project and will not merge into one that already exists");
                }
            }
        }
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = projectDir.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
    }

    /**
     * The entry class's name: the project name in PascalCase.
     *
     * <p>{@code clicker-bot} becomes {@code ClickerBot}, matching what Studio's {@code sanitizeName} does to
     * a repository name on install — so a project created here and one installed from the gallery are named
     * the same way.
     */
    public static String className(String name) {
        StringBuilder out = new StringBuilder();
        boolean upNext = true;
        for (char c : name.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(upNext ? Character.toUpperCase(c) : c);
                upNext = false;
            } else {
                upNext = true;
            }
        }
        return out.isEmpty() ? "Bot" : out.toString();
    }

    /** The default package: {@code com.} plus the name, lowercased with everything else dropped. */
    public static String defaultPackage(String name) {
        StringBuilder out = new StringBuilder("com.");
        for (char c : name.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.length() == "com.".length() ? "com.bot" : out.toString();
    }

    /**
     * Refuses a name or package that would produce a file nobody can build.
     *
     * @throws IOException with the sentence to act on — this is a command-line mistake, caught where the
     *                     person who typed it is still watching
     */
    public static void requireUsable(String name, String packageName) throws IOException {
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IOException("'" + name + "' is not a usable project name: it becomes the Maven"
                    + " artifactId, so it may hold letters, digits, '.', '_' and '-' only");
        }
        if (!packageName.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")) {
            throw new IOException("'" + packageName + "' is not a usable Java package: lowercase segments"
                    + " of letters and digits, separated by dots");
        }
    }

    /**
     * The {@code maven.compiler.release} a generated pom declares, and a fixed number rather than
     * {@link Runtime.Version#feature()}.
     *
     * <p>Studio's {@code MavenService} writes the running feature version, and there that is right: it
     * creates a project on the machine that will build it. A project created <em>here</em> travels — it is
     * one {@code bot publish} away from being a template somebody else downloads — so the running JVM is
     * the one thing that must not reach the file. The first template published from this command was
     * composed on a Java 27 box and would have declared {@code release 27} to every author on 25, whose
     * build answers {@code release version 27 not supported} for a project containing one
     * {@code println}.
     *
     * <p>25 because it is the platform's own baseline: the SDK, the contract and Studio all compile with
     * it, so nothing a bot can reach needs a newer one, and a newer JDK compiles it happily.
     */
    static final int PLATFORM_RELEASE = 25;

    private static String pomXml(String name, String packageName) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 \
                https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <groupId>%1$s</groupId>
                    <artifactId>%2$s</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <packaging>jar</packaging>

                    <properties>
                        <maven.compiler.release>%3$s</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>

                    <!--
                        Declared even though nothing here resolves from them yet: this is what lets the
                        project become a bot without anybody hand-editing XML. Project ▸ Manage Plugins in
                        BotMaker Studio adds a plugin as an ordinary dependency, and it resolves from JitPack.
                    -->
                    <repositories>
                        <repository>
                            <id>central</id>
                            <url>https://repo.maven.apache.org/maven2/</url>
                        </repository>
                        <repository>
                            <id>jitpack</id>
                            <url>https://jitpack.io</url>
                        </repository>
                        <repository>
                            <id>google</id>
                            <url>https://dl.google.com/dl/android/maven2/</url>
                        </repository>
                    </repositories>

                    <dependencies>
                        <!-- The one entry that is not about BotMaker: what a Maven project comes with. -->
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.9.3</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(packageName, name, PLATFORM_RELEASE);
    }

    private static String mainClass(String packageName, String className) {
        return """
                package %1$s;

                /**
                 * Your project.
                 *
                 * <p>BotMaker wrote this file once, when the project was created, and will never touch it
                 * again. Every line of it is yours — rename it, split it up, throw it away.
                 *
                 * <p>This is a plain Java project: it has a pom, a source folder and this main(). To make it
                 * a bot, add the BotMaker SDK from <b>Project ▸ Manage Plugins</b> — it is a plugin like any
                 * other, and installing it brings the palette, the pictures, the capture tools and the rest.
                 */
                public class %2$s {

                    public static void main(String[] args) {
                        System.out.println("Hello from %2$s!");
                    }
                }
                """.formatted(packageName, className);
    }
}
