package com.botmaker.cli.catalog;

import com.botmaker.cli.project.Poms;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the catalog derives into a plugin's {@code editorDependencies} from its pom ({@link PomFacts}).
 *
 * <p><b>The list is read from the pom, never asked for</b>, and what it reads is the one thing resolving the
 * plugin cannot tell a host: {@code optional} means <i>not transitive</i>, so these are exactly the
 * dependencies a project adding this plugin's coordinate does not get. Until 2026-10-23 {@code plugin publish}
 * wrote this list into the entry; the catalog derives it now.
 */
class PomFactsEditorDependenciesTest {

    private static Poms.Dependency dep(String groupId, String artifactId, String version, boolean optional) {
        return new Poms.Dependency(groupId, artifactId, version, null, optional);
    }

    private static List<String> of(Poms.Dependency... declared) {
        return PomFacts.editorDependencies(List.of(declared), Map.of("javalin.version", "6.7.0"));
    }

    @Test
    void only_the_optional_ones_are_listed() {
        assertEquals(List.of("io.javalin:javalin:6.7.0"), of(
                dep("com.github.BotMakerDev", "botmaker-plugin-toolkit", "0.0.6", false),
                dep("io.javalin", "javalin", "6.7.0", true),
                dep("org.junit.jupiter", "junit-jupiter", "5.9.3", false)));
    }

    /**
     * JavaFX is parent-first in {@code PluginLoader}, so the host's own copy is what every plugin links —
     * and a headless machine with no JavaFX distribution for its platform cannot resolve it at all.
     */
    @Test
    void javafx_is_never_listed_however_optional_it_is() {
        assertEquals(List.of(), of(
                dep("org.openjfx", "javafx-controls", "21", true),
                dep("org.openjfx", "javafx-graphics", "21", true)));
    }

    /** A bot's pom must declare neither, whatever a plugin's pom says about them. */
    @Test
    void the_contract_and_the_toolkit_are_never_listed() {
        assertEquals(List.of(), of(
                dep("com.github.BotMakerDev", "botmaker-studio-api", "0.0.4", true),
                dep("com.github.BotMakerDev", "botmaker-plugin-toolkit", "0.0.6", true)));
    }

    /** A property the pom defines is substituted; one it does not is left out, never written as text. */
    @Test
    void a_property_version_is_resolved_or_skipped() {
        assertEquals(List.of("io.javalin:javalin:6.7.0", "com.google.zxing:core:3.5.3"), of(
                dep("io.javalin", "javalin", "${javalin.version}", true),
                dep("org.example", "nowhere", "${nowhere.version}", true),
                dep("com.google.zxing", "core", "3.5.3", true)));
    }
}
