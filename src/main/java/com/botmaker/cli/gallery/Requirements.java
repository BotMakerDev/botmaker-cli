package com.botmaker.cli.gallery;

import com.botmaker.cli.project.Poms;
import com.botmaker.cli.registry.RegistryEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An entry's {@code requires}: the dependencies a bot's pom declares that the plugin registry knows as plugins.
 *
 * <p><b>Keyed by the registry, not by a naming rule.</b> What makes a dependency a plugin is having an entry
 * there — the same answer Studio's Manage Plugins gives — so a plugin nobody registered is simply not listed,
 * which is the honest outcome for a reader who could not install it from Studio anyway.
 *
 * <p>The version is the pom's, with its properties applied: a stranger reading the entry cannot resolve
 * {@code ${botmaker.sdk.version}}.
 */
public final class Requirements {

    private Requirements() {
    }

    public static List<GalleryEntry.Requirement> of(List<Poms.Dependency> declared, Map<String, String> properties,
                                                    List<RegistryEntry> registry) {
        List<GalleryEntry.Requirement> out = new ArrayList<>();
        for (Poms.Dependency dependency : declared) {
            for (RegistryEntry plugin : registry) {
                if (dependency.coordinate().equals(plugin.coordinate())) {
                    out.add(new GalleryEntry.Requirement(plugin.id(),
                            Poms.interpolate(dependency.version(), properties)));
                    break;
                }
            }
        }
        return List.copyOf(out);
    }
}
