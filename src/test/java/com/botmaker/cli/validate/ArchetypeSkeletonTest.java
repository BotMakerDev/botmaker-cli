package com.botmaker.cli.validate;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.host.PluginLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>A plugin that is not the SDK, loaded and validated exactly as a submitted one would be.</b>
 *
 * <p>Every architectural claim in this project rests on one plugin, and that plugin is built from the same
 * reactor as the host: it cannot be too old, cannot miss a dependency the reactor supplies, and cannot
 * disagree with the toolkit it was compiled against, because it is always compiled against the toolkit in
 * the same working copy. `botmaker-plugin-registry/plugins/` holds exactly one entry. So the second plugin
 * has to be built by a test if it is to exist at all.
 *
 * <p><b>It found something on the day it was written (2026-09-06)</b>, which is the argument for it: the
 * skeleton did not compile. {@code Source.string} had grown a return type — {@code Expr} rather than
 * {@code String} — and the template still passed {@code Source::string} where a codec wants a
 * {@code Function<T, String>}. The archetype's README says the generated project "builds and passes seven
 * tests unedited"; nothing had checked that since the toolkit moved, because nobody generates a plugin in
 * this build.
 *
 * <p><b>What it does not do is run Maven.</b> The archetype's own machinery (velocity, `archetype:generate`,
 * a child Maven build) needs a network or a warm local repository, and a test that is skipped on the machine
 * that would have caught the regression is not a test. So the substitution is done here — the archetype's
 * placeholders are three names — and the compile is {@code javac} over this JVM's own classpath plus the
 * toolkit's classes. What that costs is honest and worth stating: <b>this exercises the skeleton's source,
 * not the archetype's descriptor</b>. A `archetype-metadata.xml` that stops copying a file is invisible
 * here.
 */
class ArchetypeSkeletonTest {

    private static final String PACKAGE = "com.example.demo";
    private static final String PLUGIN_ID = "com.example.demo";

    /** The archetype's placeholders, in the one place they are spelled. */
    private static final Map<String, String> SUBSTITUTIONS = Map.of(
            "${package}", PACKAGE,
            "${pluginId}", PLUGIN_ID,
            "${pluginName}", "Demo Plugin",
            "${groupId}", "com.example",
            "${artifactId}", "demo-plugin",
            "${version}", "0.1.0-SNAPSHOT",
            "${studioApiVersion}", "0.0.0-SNAPSHOT",
            "${toolkitVersion}", "0.0.0-SNAPSHOT");

    @Test
    void the_archetypes_skeleton_compiles_loads_and_passes_every_check(@TempDir Path work) throws IOException {
        Path resources = Path.of("..", "botmaker-plugin-archetype", "src", "main", "resources",
                "archetype-resources");
        assumeTrue(Files.isDirectory(resources), "not run from the umbrella checkout");
        Path toolkitClasses = Path.of("..", "botmaker-plugin-toolkit", "target", "classes");
        assumeTrue(Files.isDirectory(toolkitClasses),
                "botmaker-plugin-toolkit is not built; run the reactor rather than this module alone");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assumeTrue(javac != null, "no javac in this JRE");

        // ---- the generated project, minus the archetype's own machinery --------------------------------
        Path sources = Files.createDirectories(work.resolve("src/main/java/" + PACKAGE.replace('.', '/')));
        // Every source the archetype ships, rather than a list: a file added to the skeleton and missing
        // here would be a class the plugin names and this test never compiles.
        try (Stream<Path> shipped = Files.list(resources.resolve("src/main/java"))) {
            for (Path source : shipped.toList()) {
                copySubstituted(source, sources.resolve(source.getFileName().toString()));
            }
        }
        Path pom = work.resolve("pom.xml");
        copySubstituted(resources.resolve("pom.xml"), pom);

        Path classes = Files.createDirectories(work.resolve("target/classes"));
        Path services = Files.createDirectories(classes.resolve("META-INF/services"));
        copySubstituted(
                resources.resolve("src/main/resources/META-INF/services/com.botmaker.plugin.api.StudioPlugin"),
                services.resolve("com.botmaker.plugin.api.StudioPlugin"));

        // ---- it compiles ------------------------------------------------------------------------------
        String classpath = System.getProperty("java.class.path")
                + java.io.File.pathSeparator + toolkitClasses;
        List<String> arguments = new ArrayList<>(List.of("-cp", classpath, "-d", classes.toString()));
        try (Stream<Path> files = Files.list(sources)) {
            files.map(Path::toString).forEach(arguments::add);
        }
        int status = javac.run(null, null, null, arguments.toArray(String[]::new));
        assertEquals(0, status, "the archetype's skeleton no longer compiles against this toolkit");

        // ---- and so does its test, the first thing an author runs ------------------------------------
        // Compiled, not run: running it wants a JUnit launcher. Compiling is what caught the 2026-09-23
        // drift — the test still called catalog(null) and valueTypes(), neither of which exists.
        Path testSource = Files.createDirectories(work.resolve("src/test/java")).resolve("ExamplePluginTest.java");
        copySubstituted(resources.resolve("src/test/java/ExamplePluginTest.java"), testSource);
        Path testClasses = Files.createDirectories(work.resolve("target/test-classes"));
        assertEquals(0, javac.run(null, null, null, "-cp", classpath + java.io.File.pathSeparator + classes,
                        "-d", testClasses.toString(), testSource.toString()),
                "the archetype's own test no longer compiles against its skeleton");

        // ---- a host loads it --------------------------------------------------------------------------
        List<Path> pluginClasspath = List.of(classes, toolkitClasses);
        try (PluginLoader loaded = PluginLoader.open(
                pluginClasspath.stream().map(Path::toString).toList())) {
            assertNotNull(loaded, "PluginLoader found no plugin on the skeleton's own classpath");
            assertEquals(1, loaded.plugins().size());
            StudioPlugin plugin = loaded.plugins().getFirst();
            assertEquals(PLUGIN_ID, plugin.id());
            // Constructed with no JavaFX toolkit started and no host services: a headless host is a real
            // host, and a plugin whose constructor links a widget is the shape that shipped as SDK v1.1.5.
            assertNotNull(plugin.displayName());
        }

        // ---- and the gate that decides a pull request passes it ---------------------------------------
        List<CheckResult> results = PluginValidator.validate(
                PluginSubject.local(pluginClasspath, pom));

        assertTrue(PluginValidator.passed(results), () -> "a freshly generated plugin fails the registry's"
                + " own gate: " + results.stream().filter(CheckResult::failed)
                .map(r -> r.check().id() + " " + r.detail()).toList());
        // Every check ran on something rather than skipping: a green run of eight skips proves nothing.
        assertTrue(results.stream().filter(CheckResult::failed).findAny().isEmpty());
        assertEquals(Check.values().length, results.size(), "a check was not run at all");
        // The skeleton's one type is what an author copies, so it has to be the round-tripping kind.
        CheckResult types = results.stream().filter(r -> r.check() == Check.TYPES).findFirst().orElseThrow();
        assertEquals(Status.PASS, types.status(), types::toString);
        assertTrue(types.detail().getFirst().contains("1 round-tripped"), types.detail()::toString);
    }

    private static void copySubstituted(Path from, Path to) throws IOException {
        String text = Files.readString(from);
        for (Map.Entry<String, String> substitution : SUBSTITUTIONS.entrySet()) {
            text = text.replace(substitution.getKey(), substitution.getValue());
        }
        Files.writeString(to, text);
    }
}
