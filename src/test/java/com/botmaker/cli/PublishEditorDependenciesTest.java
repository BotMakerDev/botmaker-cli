package com.botmaker.cli;

import com.botmaker.cli.project.Poms;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What {@code botmaker plugin publish} writes into an entry's {@code editorDependencies}.
 *
 * <p><b>The list is read from the pom, never asked for</b>, and what it reads is the one thing resolving the
 * plugin cannot tell a host: {@code optional} means <i>not transitive</i>, so these are exactly the
 * dependencies a project adding this plugin's coordinate does not get. Until 2026-09-06 the list was
 * {@code MavenService.pluginCompanions()} in Studio's own source, spelled out for plugin #1.
 */
class PublishEditorDependenciesTest {

    private static final Console CONSOLE = new Console(false);

    private static Poms.Dependency dep(String groupId, String artifactId, String version, boolean optional) {
        return new Poms.Dependency(groupId, artifactId, version, null, optional);
    }

    @Test
    void only_the_optional_ones_are_listed() {
        List<String> companions = PluginPublishCommand.editorDependencies(CONSOLE, List.of(
                dep("com.github.BotMakerDev", "botmaker-plugin-toolkit", "0.0.6", false),
                dep("io.javalin", "javalin", "6.7.0", true),
                dep("org.junit.jupiter", "junit-jupiter", "5.9.3", false)));

        assertEquals(List.of("io.javalin:javalin:6.7.0"), companions);
    }

    /**
     * JavaFX is parent-first in {@code PluginLoader}, so the host's own copy is what every plugin links —
     * and a headless machine with no JavaFX distribution for its platform cannot resolve it at all.
     */
    @Test
    void javafx_is_never_listed_however_optional_it_is() {
        List<String> companions = PluginPublishCommand.editorDependencies(CONSOLE, List.of(
                dep("org.openjfx", "javafx-controls", "21", true),
                dep("org.openjfx", "javafx-graphics", "21", true)));

        assertEquals(List.of(), companions);
    }

    /** A bot's pom must declare neither, whatever a plugin's pom says about them. */
    @Test
    void the_contract_and_the_toolkit_are_never_listed() {
        List<String> companions = PluginPublishCommand.editorDependencies(CONSOLE, List.of(
                dep("com.github.BotMakerDev", "botmaker-studio-api", "0.0.4", true),
                dep("com.github.BotMakerDev", "botmaker-plugin-toolkit", "0.0.6", true)));

        assertEquals(List.of(), companions);
    }

    /** A version nobody can resolve is left out with a warning, not written as text. */
    @Test
    void an_unresolved_property_version_is_skipped() {
        List<String> companions = PluginPublishCommand.editorDependencies(CONSOLE, List.of(
                dep("io.javalin", "javalin", "${javalin.version}", true),
                dep("com.google.zxing", "core", "3.5.3", true)));

        assertEquals(List.of("com.google.zxing:core:3.5.3"), companions);
    }

    /** The SDK's own shape, which is the list the registry entry holds today. */
    @Test
    void the_sdks_pom_composes_the_entry_it_has() {
        List<String> companions = PluginPublishCommand.editorDependencies(CONSOLE, List.of(
                dep("com.github.BotMakerDev", "botmaker-studio-api", "0.0.4", false),
                dep("com.github.BotMakerDev", "botmaker-plugin-toolkit", "0.0.6", false),
                dep("org.openjfx", "javafx-controls", "21", true),
                dep("org.openjfx", "javafx-graphics", "21", true),
                dep("io.javalin", "javalin", "6.7.0", true),
                dep("com.google.zxing", "core", "3.5.3", true)));

        assertEquals(List.of("io.javalin:javalin:6.7.0", "com.google.zxing:core:3.5.3"), companions);
    }
}
