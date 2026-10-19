package com.botmaker.cli;

import com.botmaker.cli.validate.Check;
import com.botmaker.cli.validate.CheckResult;
import com.botmaker.cli.validate.PluginSubject;
import com.botmaker.cli.validate.PluginValidator;
import com.botmaker.cli.validate.Status;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.host.PluginLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>A plugin that is not the SDK, made by {@code botmaker plugin new} and validated exactly as a submitted
 * one would be.</b>
 *
 * <p>The example plugin compiles and passes its own tests in {@code botmaker-plugin-archetype}; what only this
 * test sees is the copy. A rename that misses one spelling of the package, the id or the name leaves a project
 * that does not compile, or one that loads as the example. So the bundled template is written under another
 * name, compiled with {@code javac} against this JVM's classpath plus the toolkit's classes, loaded through the
 * real {@link PluginLoader} and run through every check the registry runs.
 *
 * <p>It replaced {@code ArchetypeSkeletonTest} (2026-09-06), which did Velocity's substitution by hand over
 * the archetype's text and found, on the day it was written, that the skeleton no longer compiled.
 */
class PluginTemplateTest {

    private static final String PACKAGE = "com.acme.demo";
    private static final String PLUGIN_ID = "com.acme.demo-plugin";
    /** With a quote, so the name is written as a Java string rather than pasted into one. */
    private static final String PLUGIN_NAME = "Demo \"Plugin\"";

    private static PluginTemplate.Answers answers(Optional<String> studioApi) {
        return new PluginTemplate.Answers("com.acme", "demo-plugin", "0.1.0-SNAPSHOT", PACKAGE, PLUGIN_ID,
                PLUGIN_NAME, studioApi, Optional.empty());
    }

    @Test
    void the_copy_is_renamed_everywhere(@TempDir Path work) throws IOException {
        Path project = work.resolve("demo-plugin");
        try (InputStream template = PluginTemplate.bundled()) {
            PluginTemplate.write(template, project, answers(Optional.of("v0.10.0")));
        }

        assertTrue(Files.isRegularFile(project.resolve(".gitignore")), "the .gitignore lost its dot");
        assertTrue(Files.isRegularFile(project.resolve("README.md")));
        assertTrue(Files.isRegularFile(project.resolve("src/main/java/com/acme/demo/plugin/ExamplePlugin.java")));
        assertTrue(Files.isRegularFile(project.resolve("src/test/java/com/acme/demo/plugin/ExamplePluginTest.java")));
        for (Path file : files(project)) {
            String text = Files.readString(file);
            assertFalse(text.contains(PluginTemplate.PACKAGE), file + " still names the example's package");
            assertFalse(text.contains(PluginTemplate.NAME), file + " still names the example plugin");
            assertFalse(text.contains("botmaker plugin new:"), file + " kept a template region's marker");
        }

        String pom = Files.readString(project.resolve("pom.xml"));
        assertTrue(pom.contains("<groupId>com.acme</groupId>\n    <artifactId>demo-plugin</artifactId>"), pom);
        assertTrue(pom.contains("<name>Demo \"Plugin\"</name>"), pom);
        assertFalse(pom.contains("botmaker-plugin-archetype"), pom);
        assertFalse(pom.contains("maven-assembly-plugin"), "the template's own build reached the copy");
        assertTrue(pom.contains("<botmaker.studioapi.version>v0.10.0</botmaker.studioapi.version>"), pom);
        String plugin = Files.readString(project.resolve("src/main/java/com/acme/demo/plugin/ExamplePlugin.java"));
        assertTrue(plugin.contains("ID = \"com.acme.demo-plugin\";"), plugin);
        assertTrue(plugin.contains("NAME = \"Demo \\\"Plugin\\\"\";"), plugin);
    }

    @Test
    void the_copy_compiles_loads_and_passes_every_check(@TempDir Path work) throws IOException {
        Path toolkitClasses = Path.of("..", "botmaker-plugin-toolkit", "target", "classes");
        assumeTrue(Files.isDirectory(toolkitClasses),
                "botmaker-plugin-toolkit is not built; run the reactor rather than this module alone");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assumeTrue(javac != null, "no javac in this JRE");

        Path project = work.resolve("demo-plugin");
        try (InputStream template = PluginTemplate.bundled()) {
            PluginTemplate.write(template, project, answers(Optional.empty()));
        }
        Path classes = Files.createDirectories(project.resolve("target/classes"));
        Path services = Files.createDirectories(classes.resolve("META-INF/services"));
        Files.copy(project.resolve("src/main/resources/META-INF/services/com.botmaker.plugin.api.StudioPlugin"),
                services.resolve("com.botmaker.plugin.api.StudioPlugin"));

        // ---- it compiles ------------------------------------------------------------------------------
        String classpath = System.getProperty("java.class.path") + File.pathSeparator + toolkitClasses;
        assertEquals(0, javac(javac, classpath, classes, project.resolve("src/main/java")),
                "the example plugin, renamed, no longer compiles against this toolkit");

        // ---- and so does its test, the first thing an author runs ------------------------------------
        // Compiled, not run: running it wants a JUnit launcher, and the example's own build runs it.
        Path testClasses = Files.createDirectories(project.resolve("target/test-classes"));
        assertEquals(0, javac(javac, classpath + File.pathSeparator + classes, testClasses,
                project.resolve("src/test/java")), "the example's own test, renamed, no longer compiles");

        // ---- a host loads it --------------------------------------------------------------------------
        List<Path> pluginClasspath = List.of(classes, toolkitClasses);
        try (PluginLoader loaded = PluginLoader.open(pluginClasspath.stream().map(Path::toString).toList())) {
            assertNotNull(loaded, "PluginLoader found no plugin on the copy's own classpath");
            assertEquals(1, loaded.plugins().size());
            StudioPlugin plugin = loaded.plugins().getFirst();
            assertEquals(PLUGIN_ID, plugin.id());
            assertEquals(PLUGIN_NAME, plugin.displayName());
        }

        // ---- and the gate that decides a pull request passes it ---------------------------------------
        List<CheckResult> results = PluginValidator.validate(
                PluginSubject.local(pluginClasspath, project.resolve("pom.xml")));

        assertTrue(PluginValidator.passed(results), () -> "a freshly made plugin fails the registry's own gate: "
                + results.stream().filter(CheckResult::failed).map(r -> r.check().id() + " " + r.detail()).toList());
        // Every check ran on something rather than skipping: a green run of skips proves nothing.
        assertEquals(Check.values().length, results.size(), "a check was not run at all");
        // The example's one type is what an author copies, so it has to be the round-tripping kind.
        CheckResult types = results.stream().filter(r -> r.check() == Check.TYPES).findFirst().orElseThrow();
        assertEquals(Status.PASS, types.status(), types::toString);
        assertTrue(types.detail().getFirst().contains("1 round-tripped"), types.detail()::toString);
        // So is its one managed value, level 3 of the example.
        CheckResult managed = results.stream().filter(r -> r.check() == Check.MANAGED).findFirst().orElseThrow();
        assertEquals(Status.PASS, managed.status(), managed::toString);
        assertTrue(managed.detail().getFirst().startsWith("1 managed value"), managed.detail()::toString);
    }

    /** A template that lost an anchor is refused, not copied half-renamed. */
    @Test
    void a_template_without_its_anchors_is_refused(@TempDir Path work) throws IOException {
        String plugin = "package com.example.greetings.plugin;\nclass ExamplePlugin { String ID = \"com.example.greetings\"; }\n";
        String pom = "<project>\n    <!-- botmaker plugin new: coordinates -->\n    <groupId>x</groupId>\n"
                + "    <!-- /botmaker plugin new -->\n    <properties>\n"
                + "        <botmaker.studioapi.version>1</botmaker.studioapi.version>\n"
                + "        <botmaker.plugintoolkit.version>1</botmaker.plugintoolkit.version>\n"
                + "    </properties>\n</project>\n";

        IllegalStateException noDrop = assertThrows(IllegalStateException.class, () -> PluginTemplate.write(
                zip("pom.xml", pom, "src/main/java/com/example/greetings/plugin/ExamplePlugin.java", plugin),
                work.resolve("a"), answers(Optional.empty())));
        assertTrue(noDrop.getMessage().contains("no region marked drop"), noDrop.getMessage());

        String complete = pom.replace("</project>",
                "    <!-- botmaker plugin new: drop -->\n    <!-- /botmaker plugin new -->\n</project>");
        IllegalStateException noId = assertThrows(IllegalStateException.class, () -> PluginTemplate.write(
                zip("pom.xml", complete, "src/main/java/com/example/greetings/plugin/ExamplePlugin.java",
                        plugin.replace("\"com.example.greetings\"", "\"elsewhere\"")),
                work.resolve("b"), answers(Optional.empty())));
        assertTrue(noId.getMessage().contains("0 plugin ids"), noId.getMessage());
    }

    @Test
    void a_package_that_is_not_java_is_refused() {
        assertThrows(IllegalArgumentException.class, () -> new PluginTemplate.Answers("com.acme", "2fa",
                "0.1.0", "com.acme.2fa", "com.acme.2fa", "2FA", Optional.empty(), Optional.empty()));
    }

    /** The name lands in string literals and a Javadoc comment. */
    @Test
    void a_name_that_would_break_the_source_is_refused() {
        for (String name : new String[]{"Two\nLines", "Ends */ here"}) {
            assertThrows(IllegalArgumentException.class, () -> new PluginTemplate.Answers("com.acme", "demo",
                    "0.1.0", PACKAGE, PLUGIN_ID, name, Optional.empty(), Optional.empty()), name);
        }
    }

    /** An id containing the example's package is written once, not renamed again by the package. */
    @Test
    void an_id_containing_the_examples_package_survives(@TempDir Path work) throws IOException {
        Path project = work.resolve("demo-plugin");
        try (InputStream template = PluginTemplate.bundled()) {
            PluginTemplate.write(template, project, new PluginTemplate.Answers("com.acme", "demo-plugin",
                    "0.1.0-SNAPSHOT", PACKAGE, "com.example.greetings2", PLUGIN_NAME, Optional.empty(),
                    Optional.empty()));
        }
        String plugin = Files.readString(project.resolve("src/main/java/com/acme/demo/plugin/ExamplePlugin.java"));
        assertTrue(plugin.contains("ID = \"com.example.greetings2\";"), plugin);
    }

    private static int javac(JavaCompiler javac, String classpath, Path out, Path sources) throws IOException {
        List<String> arguments = new ArrayList<>(List.of("-cp", classpath, "-d", out.toString()));
        files(sources).stream().filter(p -> p.toString().endsWith(".java")).map(Path::toString)
                .forEach(arguments::add);
        return javac.run(null, null, null, arguments.toArray(String[]::new));
    }

    private static List<Path> files(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    private static InputStream zip(String... namesAndTexts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndTexts.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndTexts[i]));
                zip.write(namesAndTexts[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }
}
