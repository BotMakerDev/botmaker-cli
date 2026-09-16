package com.botmaker.cli.gallery;

import com.botmaker.cli.project.Poms;
import com.botmaker.cli.registry.RegistryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What {@code bot publish} writes into {@code requires}: the pom's dependencies the registry knows as plugins. */
class RequirementsTest {

    private static RegistryEntry plugin(String id, String coordinate) {
        return new RegistryEntry(id, id, coordinate, "", "", List.of(), "", List.of(), List.of(), "v1", "");
    }

    @Test
    void only_registered_plugins_are_required_and_their_versions_are_resolved() {
        List<Poms.Dependency> declared = List.of(
                new Poms.Dependency("com.github.LiQiyeDev", "botmaker-sdk", "${botmaker.sdk.version}", ""),
                new Poms.Dependency("org.junit.jupiter", "junit-jupiter", "5.11.0", "test"));

        assertEquals(List.of(new GalleryEntry.Requirement("com.botmaker.sdk", "v1.1.7")),
                Requirements.of(declared, Map.of("botmaker.sdk.version", "v1.1.7"),
                        List.of(plugin("com.botmaker.sdk", "com.github.LiQiyeDev:botmaker-sdk"))));
    }

    @Test
    void a_project_with_no_plugins_requires_nothing() {
        assertEquals(List.of(), Requirements.of(List.of(), Map.of(),
                List.of(plugin("com.botmaker.sdk", "com.github.LiQiyeDev:botmaker-sdk"))));
    }
}
