package com.botmaker.cli.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code catalog.json}, the one file Studio reads: every listed plugin and bot with its rank and what was derived
 * about it. Written only by {@link CatalogBuilder}.
 *
 * @param builtAt when its content last changed (ISO-8601), not when the builder last ran
 */
@JsonPropertyOrder({"schemaVersion", "builtAt", "plugins", "bots"})
public record Catalog(int schemaVersion, String builtAt, List<Plugin> plugins, List<Bot> bots) {

    public static final int SCHEMA_VERSION = 3;

    /** The file's name at the catalog's root. */
    public static final String FILE = "catalog.json";

    /**
     * A listed plugin.
     *
     * @param coordinate {@code com.github.<owner>:<repo>}, no version: the version is {@code tag}
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonPropertyOrder({"id", "repo", "tag", "rank", "coordinate", "name", "description", "tags", "stars",
            "minContractVersion", "editorDependencies"})
    public record Plugin(String id, String repo, String tag, Rank rank, String coordinate, String name,
                         String description, List<String> tags, int stars, String minContractVersion,
                         List<String> editorDependencies) {
        public Plugin {
            description = description == null ? "" : description;
            tags = tags == null ? List.of() : List.copyOf(tags);
            editorDependencies = editorDependencies == null ? List.of() : List.copyOf(editorDependencies);
        }
    }

    /**
     * A listed bot.
     *
     * @param forkOf  the repository it was forked from, absent when it is not a fork or stopped following
     * @param plugins the catalog plugins its pom declares at {@code tag}
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonPropertyOrder({"repo", "tag", "rank", "description", "tags", "stars", "forkOf", "plugins"})
    public record Bot(String repo, String tag, Rank rank, String description, List<String> tags, int stars,
                      String forkOf, List<DerivedFacts.Pin> plugins) {
        public Bot {
            description = description == null ? "" : description;
            tags = tags == null ? List.of() : List.copyOf(tags);
            plugins = plugins == null ? List.of() : List.copyOf(plugins);
        }
    }

    public Catalog {
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
        bots = bots == null ? List.of() : List.copyOf(bots);
    }

    /** The same catalog stamped {@code builtAt}. */
    public Catalog builtAt(String when) {
        return new Catalog(schemaVersion, when, plugins, bots);
    }

    public String toJson() {
        try {
            return CatalogJson.strict().writeValueAsString(this) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("catalog.json could not be written", e);
        }
    }

    /** The catalog at {@code file}, or empty when there is none or it does not read. */
    public static Optional<Catalog> read(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(CatalogJson.lenient().readValue(Files.readString(file), Catalog.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
