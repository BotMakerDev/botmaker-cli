package com.botmaker.cli.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What only building or loading an entry tells: a plugin's own name and pom facts, the catalog plugins a bot's pom
 * declares. index.yml's {@code derive} job computes it with a read-only token and uploads it as
 * {@code facts.json}; its {@code publish} job, which holds the write token, only reads it — through
 * {@link CatalogJson#strict()}, so a field the schema lacks is refused rather than carried into
 * {@code catalog.json}.
 *
 * @param plugins    by plugin id
 * @param botPlugins by bot {@code owner/repo}
 */
public record DerivedFacts(Map<String, PluginFacts> plugins, Map<String, List<Pin>> botPlugins) {

    /**
     * A plugin as its jar and pom at the entry's tag say.
     *
     * @param name               its {@code StudioPlugin.displayName()}
     * @param minContractVersion the oldest contract its pom accepts
     * @param editorDependencies {@code groupId:artifactId:version} it needs on the editor's classpath and does not
     *                           bring (its {@code optional} dependencies)
     */
    public record PluginFacts(String name, String minContractVersion, List<String> editorDependencies) {
        public PluginFacts {
            editorDependencies = editorDependencies == null ? List.of() : List.copyOf(editorDependencies);
        }
    }

    /** A catalog plugin a bot's pom declares, at the version it pins. */
    public record Pin(String id, String version) {}

    public DerivedFacts {
        plugins = plugins == null ? Map.of() : Map.copyOf(plugins);
        botPlugins = botPlugins == null ? Map.of() : Map.copyOf(botPlugins);
    }

    public static DerivedFacts read(Path file) throws IOException {
        return CatalogJson.strict().readValue(Files.readString(file), DerivedFacts.class);
    }
}
