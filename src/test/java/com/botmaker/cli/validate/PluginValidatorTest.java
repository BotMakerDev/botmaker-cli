package com.botmaker.cli.validate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The eight checks, against plugins compiled here.
 *
 * <p><b>The fixtures are compiled rather than mocked, and that is the point of the file.</b> Every failure
 * these checks exist to catch is a failure of a real classloader over real bytecode — a services file naming
 * a class that is not there, a catalogued member that was renamed, two plugins declaring one class.
 * A mock {@code StudioPlugin} would pass through the same code and prove none of it, because it would never
 * have been loaded by {@code PluginLoader} in the first place.
 */
class PluginValidatorTest {

    // ------------------------------------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------------------------------------

    /**
     * The good case: a well-formed id, one catalogued facade with one hidden member, one declared type that
     * is also a component type.
     *
     * <p>{@code Greeting} has no {@code equals}, on purpose: most classes a plugin writes have none, and the
     * round trip compares parts so that such a type still passes.
     */
    private static final String GOOD_PLUGIN = """
            package p;
            import com.botmaker.plugin.api.StudioPlugin;
            import com.botmaker.plugin.api.catalog.PaletteCatalog;
            import com.botmaker.plugin.api.slot.ValueContext;
            import com.botmaker.plugin.api.value.ComponentType;
            import com.botmaker.plugin.api.value.PluginType;
            import java.util.List;
            public final class GoodPlugin implements StudioPlugin {
                @Override public String id() { return "com.example.good"; }
                @Override public String displayName() { return "Good"; }
                @Override public PaletteCatalog catalog() { return PaletteCatalog.of(Api.class); }
                @Override public List<PluginType<?>> types() { return List.of(new GreetingType()); }

                public static final class Greeting {
                    final String who;
                    final int times;
                    public Greeting(String who, int times) { this.who = who; this.times = times; }
                }

                public static final class GreetingType implements PluginType<Greeting>, ComponentType<Greeting> {
                    @Override public Class<Greeting> type() { return Greeting.class; }
                    @Override public Greeting fresh() { return new Greeting("world", 1); }
                    @Override public javafx.scene.Node editor(ValueContext ctx) { return null; }
                    @Override public List<Class<?>> componentTypes() { return List.of(String.class, int.class); }
                    @Override public List<Object> components(Greeting g) { return List.of(g.who, g.times); }
                    @Override public Greeting build(List<Object> parts) {
                        return new Greeting((String) parts.get(0), (Integer) parts.get(1));
                    }
                }
            }
            """;

    /** A second plugin declaring the good plugin's own class, which a host cannot load beside it. */
    private static final String OTHER_PLUGIN = """
            package p;
            import com.botmaker.plugin.api.StudioPlugin;
            import com.botmaker.plugin.api.value.PluginType;
            import java.util.List;
            public final class OtherPlugin implements StudioPlugin {
                @Override public String id() { return "com.example.other"; }
                @Override public List<PluginType<?>> types() { return List.of(new GoodPlugin.GreetingType()); }
            }
            """;

    private static final String GOOD_API = """
            package p;
            import com.botmaker.plugin.api.palette.Hidden;
            import com.botmaker.plugin.api.palette.Palette;
            @Palette(category = "util", categoryLabel = "Good", icon = "*", order = 100)
            public final class Api {
                public static String greet(String who) { return "Hello, " + who; }
                @Hidden public static String helper(String who) { return who; }
            }
            """;

    private static final String GOOD_POM = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId><artifactId>good</artifactId><version>0.1.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.github.LiQiyeDev</groupId>
                        <artifactId>botmaker-studio-api</artifactId>
                        <version>main-SNAPSHOT</version>
                        <scope>provided</scope>
                    </dependency>
                    <dependency>
                        <groupId>com.github.LiQiyeDev</groupId>
                        <artifactId>botmaker-plugin-toolkit</artifactId>
                        <version>main-SNAPSHOT</version>
                    </dependency>
                </dependencies>
            </project>
            """;

    // ------------------------------------------------------------------------------------------------
    // the good case
    // ------------------------------------------------------------------------------------------------

    @Test
    void a_well_formed_plugin_passes(@TempDir Path dir) throws IOException {
        PluginSubject subject = subject(dir, GOOD_POM);
        List<CheckResult> results = PluginValidator.validate(subject);
        assertTrue(PluginValidator.passed(results), () -> render(results));
    }

    /**
     * All eight, always, whatever happened. A report that shrinks when things go wrong leaves the reader
     * unable to tell a check that passed from one that never ran — which is why {@link Status#SKIP} exists
     * at all rather than a check simply being absent.
     */
    @Test
    void every_check_is_reported_even_when_the_first_one_fails() {
        List<CheckResult> results = PluginValidator.validate(
                PluginSubject.local(List.of(), null));
        assertEquals(List.of(Check.values()), results.stream().map(CheckResult::check).toList());
        assertTrue(results.getFirst().failed());
        // POM_SCOPES and PLUGIN_DEPS still run — both read a file and need nothing loaded, so a broken
        // classpath is no reason to leave the author's likeliest mistakes unmentioned.
        assertEquals(Status.SKIP, result(results, Check.POM_SCOPES).status());
        assertEquals(Status.SKIP, last(results).status());
    }

    // ------------------------------------------------------------------------------------------------
    // loading
    // ------------------------------------------------------------------------------------------------

    @Test
    void a_classpath_entry_that_does_not_exist_fails_the_first_check(@TempDir Path dir) {
        List<CheckResult> results = PluginValidator.validate(
                PluginSubject.local(List.of(dir.resolve("nope")), null));
        assertEquals(Status.FAIL, result(results, Check.CLASSPATH).status());
        assertEquals(Status.SKIP, result(results, Check.LOADS).status());
    }

    /**
     * The single most likely first-run failure, and the one whose message has to name the file: a plugin
     * that compiles, packages and contributes nothing, because {@code META-INF/services} is missing.
     */
    @Test
    void a_build_with_no_services_file_fails_the_loads_check(@TempDir Path dir) throws IOException {
        Path classes = compile(dir, GOOD_PLUGIN, GOOD_API);
        // deliberately no services file
        List<CheckResult> results = PluginValidator.validate(
                PluginSubject.local(List.of(classes), null));
        CheckResult loads = result(results, Check.LOADS);
        assertEquals(Status.FAIL, loads.status());
        assertTrue(loads.detail().stream().anyMatch(d -> d.contains("META-INF/services")), loads::toString);
        for (Check check : List.of(Check.ID, Check.PALETTE, Check.TYPES, Check.EDITORS)) {
            assertEquals(Status.SKIP, result(results, check).status(), check::name);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // ids
    // ------------------------------------------------------------------------------------------------

    @Test
    void an_id_that_is_not_well_formed_fails(@TempDir Path dir) throws IOException {
        // Both sources, always: overriding one means supplying the other, because the facade the plugin
        // catalogues has to be on the same javac invocation.
        PluginSubject subject = subject(dir, GOOD_POM,
                GOOD_PLUGIN.replace("\"com.example.good\"", "\"Com Example\""), GOOD_API);
        CheckResult id = result(PluginValidator.validate(subject), Check.ID);
        assertEquals(Status.FAIL, id.status());
        assertTrue(id.detail().getFirst().contains("well-formed"), id.detail()::toString);
    }

    @Test
    void an_id_the_registry_already_holds_fails(@TempDir Path dir) throws IOException {
        Path classes = compile(dir, GOOD_PLUGIN, GOOD_API);
        services(classes, "p.GoodPlugin");
        PluginSubject subject = new PluginSubject(List.of(classes), null, Set.of("com.example.good"));
        CheckResult id = result(PluginValidator.validate(subject), Check.ID);
        assertEquals(Status.FAIL, id.status());
        assertTrue(id.detail().getFirst().contains("already registered"), id.detail()::toString);
    }

    // ------------------------------------------------------------------------------------------------
    // palette
    // ------------------------------------------------------------------------------------------------

    /**
     * A class catalogued without {@code @Palette} is a {@code problems()} entry rather than an exception —
     * the contract collects malformed-catalog complaints instead of throwing, because no malformed catalog
     * may be the reason a project will not open. This check is what turns that into a refusal at the one
     * moment refusing is useful.
     */
    @Test
    void a_catalogued_class_with_no_palette_annotation_fails(@TempDir Path dir) throws IOException {
        PluginSubject subject = subject(dir, GOOD_POM, GOOD_PLUGIN,
                GOOD_API.replace("@Palette(category = \"util\", categoryLabel = \"Good\", icon = \"*\","
                        + " order = 100)", ""));
        CheckResult palette = result(PluginValidator.validate(subject), Check.PALETTE);
        assertEquals(Status.FAIL, palette.status());
        assertTrue(palette.detail().getFirst().contains("@Palette"), palette.detail()::toString);
    }

    /** A plugin that leaves {@code catalog()} alone is judged on the palette the host discovers from its jar. */
    @Test
    void a_plugin_with_no_catalog_is_judged_on_the_discovered_palette(@TempDir Path dir) throws IOException {
        PluginSubject subject = subject(dir, GOOD_POM, GOOD_PLUGIN.replace(
                "@Override public PaletteCatalog catalog() { return PaletteCatalog.of(Api.class); }", ""),
                GOOD_API);
        CheckResult palette = result(PluginValidator.validate(subject), Check.PALETTE);
        assertEquals(Status.PASS, palette.status(), palette.detail()::toString);
        assertTrue(palette.detail().toString().contains("1 facade"), palette.detail()::toString);
    }

    // ------------------------------------------------------------------------------------------------
    // types
    // ------------------------------------------------------------------------------------------------

    @Test
    void the_good_plugins_type_round_trips(@TempDir Path dir) throws IOException {
        CheckResult types = result(PluginValidator.validate(subject(dir, GOOD_POM)), Check.TYPES);
        assertEquals(Status.PASS, types.status(), types::toString);
        assertTrue(types.detail().getFirst().contains("1 round-tripped"), types.detail()::toString);
    }

    /** The host's own rule, asked before anyone installs the pair: one class, one owner. */
    @Test
    void two_plugins_may_not_own_one_type(@TempDir Path dir) throws IOException {
        Path classes = compile(dir, GOOD_PLUGIN, GOOD_API, OTHER_PLUGIN);
        services(classes, "p.GoodPlugin", "p.OtherPlugin");
        CheckResult types = result(PluginValidator.validate(PluginSubject.local(List.of(classes), null)),
                Check.TYPES);
        assertEquals(Status.FAIL, types.status());
        assertTrue(types.detail().getFirst().contains("already declares"), types.detail()::toString);
    }

    /** A value that changes on the way through {@code build} is a value rewritten on every save. */
    @Test
    void a_component_type_must_round_trip(@TempDir Path dir) throws IOException {
        PluginSubject subject = subject(dir, GOOD_POM,
                GOOD_PLUGIN.replace("new Greeting((String) parts.get(0),", "new Greeting(\"someone else\","),
                GOOD_API);
        CheckResult types = result(PluginValidator.validate(subject), Check.TYPES);
        assertEquals(Status.FAIL, types.status());
        assertTrue(types.detail().getFirst().contains("does not survive"), types.detail()::toString);
    }

    @Test
    void a_type_with_no_fresh_value_fails(@TempDir Path dir) throws IOException {
        PluginSubject subject = subject(dir, GOOD_POM,
                GOOD_PLUGIN.replace("return new Greeting(\"world\", 1);", "return null;"), GOOD_API);
        CheckResult types = result(PluginValidator.validate(subject), Check.TYPES);
        assertEquals(Status.FAIL, types.status());
        assertTrue(types.detail().getFirst().contains("neither fresh() nor freshCall()"),
                types.detail()::toString);
    }

    /** {@code GreetingType} answering a null {@code fresh()} and {@code freshCall()} as {@code body}. */
    private static String startingAsCall(String body) {
        return GOOD_PLUGIN.replace(
                "@Override public Greeting fresh() { return new Greeting(\"world\", 1); }",
                "@Override public Greeting fresh() { return null; }"
                        + " @Override public java.lang.reflect.Method freshCall() {"
                        + " try { " + body + " } catch (NoSuchMethodException e) { throw new IllegalStateException(e); } }"
                        + " public static Greeting last() { return new Greeting(\"last\", 1); }"
                        + " public Greeting mine() { return null; }");
    }

    /**
     * The contract's exception, and the SDK's case: a type whose fresh form is a call the bot evaluates
     * answers {@code freshCall()} and a null {@code fresh()}. The first real SDK validation refused six of
     * its types for exactly this before the check knew it.
     */
    @Test
    void a_type_that_starts_as_a_call_passes(@TempDir Path dir) throws IOException {
        PluginSubject subject = subject(dir, GOOD_POM,
                startingAsCall("return GreetingType.class.getMethod(\"last\");"), GOOD_API);
        CheckResult types = result(PluginValidator.validate(subject), Check.TYPES);
        assertEquals(Status.PASS, types.status(), types::toString);
    }

    /** A fresh call the host could not write — here an instance method — is refused, never thrown. */
    @Test
    void a_fresh_call_of_the_wrong_shape_fails(@TempDir Path dir) throws IOException {
        PluginSubject subject = subject(dir, GOOD_POM,
                startingAsCall("return GreetingType.class.getMethod(\"mine\");"), GOOD_API);
        CheckResult types = result(PluginValidator.validate(subject), Check.TYPES);
        assertEquals(Status.FAIL, types.status());
        assertTrue(types.detail().getFirst().contains("is not public static"), types.detail()::toString);
    }

    @Test
    void a_fresh_call_with_arguments_or_another_type_is_named() throws NoSuchMethodException {
        assertEquals("takes parameters; the host writes the call with no arguments",
                PluginValidator.freshCallProblem(String.class.getMethod("valueOf", int.class), "java.lang.String"));
        assertEquals("returns java.lang.String, not p.Greeting",
                PluginValidator.freshCallProblem(System.class.getMethod("lineSeparator"), "p.Greeting"));
    }

    /**
     * A plugin carried only because the judged one depends on it answers for its own soundness under its
     * own entry — the SDK must not be refused for plugin-basics' types.
     */
    @Test
    void a_plugin_that_is_not_judged_is_not_asked_for_a_fresh_value(@TempDir Path dir) throws IOException {
        Path classes = compile(dir,
                GOOD_PLUGIN.replace("return new Greeting(\"world\", 1);", "return null;"), GOOD_API);
        services(classes, "p.GoodPlugin");
        PluginSubject subject = PluginSubject.local(List.of(classes), null).about("com.example.someone.else");
        assertEquals(Status.PASS, result(PluginValidator.validate(subject), Check.TYPES).status());
    }

    // ------------------------------------------------------------------------------------------------
    // pom scopes
    // ------------------------------------------------------------------------------------------------

    /**
     * A {@code compile} contract passes since 2026-09-23: a bot writes {@code @Param} and {@code @Managed},
     * so the SDK hands the contract on, and the loader is parent-first for it whatever the scope.
     */
    @Test
    void a_contract_at_compile_scope_passes(@TempDir Path dir) throws IOException {
        PluginSubject subject = subject(dir, GOOD_POM.replace("<scope>provided</scope>", ""));
        CheckResult scopes = result(PluginValidator.validate(subject), Check.POM_SCOPES);
        assertEquals(Status.PASS, scopes.status(), scopes::toString);
        assertTrue(scopes.detail().getFirst().contains("contract compile"), scopes.detail()::toString);
    }

    @Test
    void a_contract_at_runtime_scope_fails(@TempDir Path dir) throws IOException {
        PluginSubject subject = subject(dir, GOOD_POM.replace("<scope>provided</scope>", "<scope>runtime</scope>"));
        CheckResult scopes = result(PluginValidator.validate(subject), Check.POM_SCOPES);
        assertEquals(Status.FAIL, scopes.status());
        assertTrue(scopes.detail().getFirst().contains("`provided` or `compile`"), scopes.detail()::toString);
    }

    @Test
    void a_toolkit_that_is_provided_fails(@TempDir Path dir) throws IOException {
        PluginSubject subject = subject(dir, GOOD_POM.replace(
                "<artifactId>botmaker-plugin-toolkit</artifactId>",
                "<artifactId>botmaker-plugin-toolkit</artifactId><scope>provided</scope>"));
        CheckResult scopes = result(PluginValidator.validate(subject), Check.POM_SCOPES);
        assertEquals(Status.FAIL, scopes.status());
        assertTrue(scopes.detail().getFirst().contains("must not"), scopes.detail()::toString);
    }

    @Test
    void a_pom_that_does_not_declare_the_contract_at_all_fails(@TempDir Path dir) throws IOException {
        PluginSubject subject = subject(dir, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>g</groupId><artifactId>a</artifactId><version>1</version>
                </project>
                """);
        assertEquals(Status.FAIL, result(PluginValidator.validate(subject), Check.POM_SCOPES).status());
    }

    @Test
    void no_pom_at_all_is_a_skip_rather_than_a_failure(@TempDir Path dir) throws IOException {
        Path classes = compile(dir, GOOD_PLUGIN, GOOD_API);
        services(classes, "p.GoodPlugin");
        List<CheckResult> results = PluginValidator.validate(
                PluginSubject.local(List.of(classes), null));
        assertEquals(Status.SKIP, result(results, Check.POM_SCOPES).status());
        assertFalse(results.stream().anyMatch(CheckResult::failed), () -> render(results));
    }

    // ------------------------------------------------------------------------------------------------
    // plugin deps
    // ------------------------------------------------------------------------------------------------

    /**
     * The mistake that has shipped three times: {@code optional} on the toolkit.
     *
     * <p>It is not a scope, which is why it is not {@link Check#POM_SCOPES}. The jar builds, every test in
     * the plugin's own build passes, and this very validator passes over a working copy — because an
     * {@code optional} dependency <em>is</em> on its own project's classpath. What it is not is on any
     * <em>consumer's</em>, and the first consumer is the host that loads the plugin.
     */
    @Test
    void a_toolkit_that_is_optional_fails(@TempDir Path dir) throws IOException {
        PluginSubject subject = subject(dir, GOOD_POM.replace(
                "<artifactId>botmaker-plugin-toolkit</artifactId>",
                "<artifactId>botmaker-plugin-toolkit</artifactId><optional>true</optional>"));
        List<CheckResult> results = PluginValidator.validate(subject);
        CheckResult deps = result(results, Check.PLUGIN_DEPS);
        assertEquals(Status.FAIL, deps.status());
        assertTrue(deps.detail().getFirst().contains("optional"), deps.detail()::toString);
        // And the scope check is unmoved by it — the two ask different questions of the same element.
        assertEquals(Status.PASS, result(results, Check.POM_SCOPES).status());
    }

    /** A plugin that uses no toolkit widget has nothing here to get wrong. */
    @Test
    void a_pom_with_no_toolkit_passes_plugin_deps(@TempDir Path dir) throws IOException {
        PluginSubject subject = subject(dir, GOOD_POM.replaceAll(
                "(?s)<dependency>\\s*<groupId>com.github.LiQiyeDev</groupId>\\s*"
                        + "<artifactId>botmaker-plugin-toolkit</artifactId>.*?</dependency>", ""));
        assertEquals(Status.PASS, result(PluginValidator.validate(subject), Check.PLUGIN_DEPS).status());
    }

    // ------------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------------

    private static PluginSubject subject(Path dir, String pom, String... sources) throws IOException {
        Path classes = compile(dir, sources.length == 0 ? new String[]{GOOD_PLUGIN, GOOD_API} : sources);
        services(classes, "p.GoodPlugin");
        Path pomFile = dir.resolve("pom.xml");
        Files.writeString(pomFile, pom);
        return PluginSubject.local(List.of(classes), pomFile);
    }

    /**
     * Compiles the given sources against this test's own classpath.
     *
     * <p>{@code java.class.path} rather than a hand-built list: the fixture needs the contract, and the
     * contract is whatever version this build resolved. Naming a jar would make the test true of a build
     * nobody is running.
     */
    private static Path compile(Path dir, String... sources) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assumeTrue(javac != null, "no javac in this JRE");
        Path src = Files.createDirectories(dir.resolve("src/p"));
        List<String> files = new ArrayList<>();
        for (String source : sources) {
            String name = source.replaceFirst("(?s).*public final class (\\w+).*", "$1");
            Path file = src.resolve(name + ".java");
            Files.writeString(file, source);
            files.add(file.toString());
        }
        Path classes = Files.createDirectories(dir.resolve("classes"));
        List<String> argv = new ArrayList<>(List.of(
                "-cp", System.getProperty("java.class.path"), "-d", classes.toString()));
        argv.addAll(files);
        // A failure, not an assumption: a fixture that stops compiling against the contract is this file
        // drifting from it, and skipping would report every check in it as green.
        assertEquals(0, javac.run(null, null, null, argv.toArray(String[]::new)),
                "the fixture does not compile against this build's contract");
        return classes;
    }

    private static void services(Path classes, String... pluginClasses) throws IOException {
        Path dir = Files.createDirectories(classes.resolve("META-INF/services"));
        Files.writeString(dir.resolve("com.botmaker.plugin.api.StudioPlugin"),
                String.join("\n", pluginClasses) + "\n");
    }

    private static CheckResult result(List<CheckResult> results, Check check) {
        return results.stream().filter(r -> r.check() == check).findFirst().orElseThrow();
    }

    private static CheckResult last(List<CheckResult> results) {
        return results.getLast();
    }

    private static String render(List<CheckResult> results) {
        StringBuilder out = new StringBuilder();
        results.forEach(r -> out.append(r).append(' ').append(r.detail()).append('\n'));
        return out.toString();
    }
}
