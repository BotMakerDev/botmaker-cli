package com.botmaker.cli.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What each approved release's pom, read at its pinned commit, says: a plugin's name and pom facts, the catalog
 * plugins a bot declares. Read from the pom as data — no build, no entry's code — because the job that derives it
 * holds the write token, and one job running every plugin's code would let one plugin write the facts of another
 * (2026-10-23 security review). Still untrusted: the author controls the pom, so {@link CatalogBuilder} checks
 * every value again.
 *
 * @param plugins    by plugin {@code owner/repo}, then by release tag
 * @param botPlugins by bot {@code owner/repo}, then by release tag
 */
public record DerivedFacts(Map<String, Map<String, PluginFacts>> plugins,
                           Map<String, Map<String, List<Pin>>> botPlugins) {

    /**
     * A plugin as its pom at one release says.
     *
     * @param name               its pom's {@code <name>}, else the repository name
     * @param minContractVersion the oldest contract its pom accepts
     * @param editorDependencies {@code groupId:artifactId:version} it needs on the editor's classpath and does not
     *                           bring (its {@code optional} dependencies)
     * @param plugins            the catalog plugins its pom declares, at the versions it pins
     */
    public record PluginFacts(String name, String minContractVersion, List<String> editorDependencies,
                              List<Pin> plugins) {
        public PluginFacts {
            editorDependencies = editorDependencies == null ? List.of() : List.copyOf(editorDependencies);
            plugins = plugins == null ? List.of() : List.copyOf(plugins);
        }

        /** A plugin whose pom declares no other catalog plugin. */
        public PluginFacts(String name, String minContractVersion, List<String> editorDependencies) {
            this(name, minContractVersion, editorDependencies, List.of());
        }
    }

    /** A catalog plugin a pom declares, named by its entry's {@code owner/repo}, at the version it pins. */
    public record Pin(String repo, String version) {}

    public DerivedFacts {
        plugins = plugins == null ? Map.of() : Map.copyOf(plugins);
        botPlugins = botPlugins == null ? Map.of() : Map.copyOf(botPlugins);
    }

    /** The facts of the plugin at {@code repo} at {@code tag}, or {@code null}. */
    PluginFacts plugin(String repo, String tag) {
        return plugins.getOrDefault(repo, Map.of()).get(tag);
    }

    /** The plugins bot {@code repo} declares at {@code tag}, or {@code null}. */
    List<Pin> bot(String repo, String tag) {
        return botPlugins.getOrDefault(repo, Map.of()).get(tag);
    }

    public static DerivedFacts read(Path file) throws IOException {
        return CatalogJson.strict().readValue(Files.readString(file), DerivedFacts.class);
    }
}
