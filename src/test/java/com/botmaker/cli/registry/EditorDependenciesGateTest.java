package com.botmaker.cli.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate's reading of {@code editorDependencies}.
 *
 * <p><b>Every line in that field becomes a {@code provided} dependency in a stranger's project pom</b>,
 * written by Studio when somebody installs the plugin — so the shape is checked here rather than trusted.
 * {@code botmaker plugin publish} composes the field from the plugin's own pom and cannot get it wrong; a
 * pull request may be hand-written, and this is the reader that can tell somebody to fix it.
 */
class EditorDependenciesGateTest {

    @Test
    void an_empty_list_is_the_ordinary_case() {
        assertNull(RegistryGate.editorDependenciesRefusal(List.of()));
    }

    @Test
    void a_full_coordinate_passes() {
        assertNull(RegistryGate.editorDependenciesRefusal(
                List.of("io.javalin:javalin:6.7.0", "com.google.zxing:core:3.5.3")));
    }

    @Test
    void a_coordinate_with_no_version_is_refused() {
        String refusal = RegistryGate.editorDependenciesRefusal(List.of("io.javalin:javalin"));

        assertNotNull(refusal);
        assertTrue(refusal.contains("groupId:artifactId:version"), refusal);
    }

    @Test
    void the_contract_under_its_retired_group_is_refused_too() {
        String refusal = RegistryGate.editorDependenciesRefusal(
                List.of("com.github.LiQiyeDev:botmaker-studio-api:v0.4.0"));

        assertNotNull(refusal);
        assertTrue(refusal.contains("must never declare"), refusal);
    }

    @Test
    void two_versions_of_one_artifact_are_refused() {
        String refusal = RegistryGate.editorDependenciesRefusal(
                List.of("io.javalin:javalin:6.7.0", "io.javalin:javalin:6.6.0"));

        assertNotNull(refusal);
        assertTrue(refusal.contains("twice"), refusal);
    }

    /**
     * The {@code pom-scopes} failure one level removed: a plugin declaring the contract or the toolkit in a
     * <em>bot's</em> pom, arriving in a project whose owner never saw the entry.
     */
    @Test
    void the_contract_and_the_toolkit_are_refused() {
        for (String coordinate : List.of("com.github.BotMakerDev:botmaker-studio-api:0.0.4",
                "com.github.BotMakerDev:botmaker-plugin-toolkit:0.0.6")) {
            String refusal = RegistryGate.editorDependenciesRefusal(List.of(coordinate));
            assertNotNull(refusal, coordinate + " must be refused");
            assertTrue(refusal.contains("must never declare"), refusal);
        }
    }
}
