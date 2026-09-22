package com.botmaker.cli.registry;

import com.botmaker.cli.Console;
import com.botmaker.cli.Subjects;
import com.botmaker.cli.validate.PluginSubject;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.host.PluginLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The ids a host's own bundled plugins already own, which the registry must reserve as surely as an id an
 * entry file claims.
 *
 * <p>Without this the gate has a hole with no symptom at submission time: {@code plugins/<id>.json} makes
 * entry-vs-entry uniqueness a property of git — but a plugin the host <em>ships</em> has no entry file, so
 * nothing claims its id, and a submission taking {@code com.botmaker.sdk} would pass every check.
 *
 * <p>It reserved value type ids as well until 2026-09-23. A type is a class now, named by its own package,
 * so there is no string for one plugin to take from another.
 *
 * <p><b>The ids are read from the bundled plugin itself, never listed here.</b> A hand-kept list would be a
 * second answer to a question the plugin already answers, and it would drift — the shape this repository
 * has rejected repeatedly (the frozen per-version catalogs, {@code api-surface.txt}). So the gate resolves
 * the coordinate and asks it, exactly as it resolves and asks a submission.
 *
 * <p><b>Which coordinates are bundled is an input, not a constant.</b> "Bundled" is a fact about a
 * <em>host</em> — the SDK is Studio's plugin #1 — and this module is a host among others, so hard-coding the
 * SDK here would make the CLI the owner of somebody else's list. The registry names them in
 * {@value #ENVIRONMENT_VARIABLE}, because the registry is the thing that has a host in mind.
 */
record Bundled(Set<String> pluginIds) {

    /**
     * Comma-separated {@code groupId:artifactId:version}. Unset means nothing is reserved and nobody said
     * so — {@link RegistryGate} warns. Set and <em>empty</em> is the deliberate statement that the host
     * bundles nothing, and is silent.
     */
    static final String ENVIRONMENT_VARIABLE = "BOTMAKER_BUNDLED_PLUGINS";

    static Bundled none() {
        return new Bundled(Set.of());
    }

    /**
     * Resolves every coordinate and reads the ids its plugins claim.
     *
     * @throws IOException a coordinate is malformed, does not resolve, or resolves to something with no
     *                     plugin in it. All three are failures of the gate's own configuration rather than
     *                     of a submission, and a gate that quietly stopped reserving would be worse than one
     *                     that stops.
     */
    static Bundled resolve(Console console, Subjects subjects, String coordinates) throws IOException {
        List<String> list = new ArrayList<>();
        for (String coordinate : coordinates.split(",")) {
            String trimmed = coordinate.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        if (list.isEmpty()) {
            return none();
        }
        Set<String> pluginIds = new LinkedHashSet<>();
        read(console, subjects, list, pluginIds);
        return new Bundled(Set.copyOf(pluginIds));
    }

    /**
     * All of them on <b>one</b> classpath, because that is what a host has.
     *
     * <p>Not one resolve per coordinate: a bundled plugin's own dependency may be {@code optional} and so
     * not transitive, and a classpath missing it is one the plugin cannot be constructed from. The worked
     * example was the SDK's own toolkit dependency, {@code optional} until SDK v1.1.5 — resolving
     * {@code botmaker-sdk} alone gave {@code NoClassDefFoundError:
     * com/botmaker/plugin/toolkit/AbstractStudioPlugin} inside {@code ServiceLoader}. That is fixed at the
     * source (a plugin brings its own toolkit, because the loader resolves it child-first), so it is
     * history rather than a live case — but the shape recurs with any bundled plugin's optional
     * dependency, and one classpath for all of them is what a host has anyway.
     */
    private static void read(Console console, Subjects subjects, List<String> coordinates,
                             Set<String> pluginIds) throws IOException {
        String coordinate = String.join(" + ", coordinates);
        PluginSubject subject = subjects.fromCoordinates(coordinates, Set.of());
        try (PluginLoader loaded = PluginLoader.open(
                subject.classpath().stream().map(Object::toString).toList())) {
            List<StudioPlugin> plugins = loaded == null ? List.of() : List.copyOf(loaded.plugins());
            if (plugins.isEmpty()) {
                throw new IOException(coordinate + " is listed as a bundled plugin but no StudioPlugin"
                        + " loaded from it");
            }
            for (StudioPlugin plugin : plugins) {
                // Defended rather than trusted: a bundled plugin is ours, but this runs before any check has
                // looked at it, so a null here would surface as an NPE inside Set.copyOf naming nothing.
                if (plugin.id() != null && !plugin.id().isBlank()) {
                    pluginIds.add(plugin.id());
                }
            }
        }
        console.out("reserved from " + coordinate + ": " + String.join(", ", pluginIds));
    }

    /** This set plus {@code others} — the ids an entry may not take, from both sources at once. */
    static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return Set.copyOf(union);
    }
}
