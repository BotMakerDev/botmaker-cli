package com.botmaker.cli.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    /** 4 (2026-10-24): every approved release per entry, newest first, each pinned. */
    public static final int SCHEMA_VERSION = 4;

    /** The file's name at the catalog's root. */
    public static final String FILE = "catalog.json";

    /**
     * A listed plugin.
     *
     * @param coordinate {@code com.github.<owner>:<repo>}, no version: a release's {@code tag} is the version
     * @param name       its newest release's pom {@code <name>}
     * @param tags       the repository's GitHub topics
     * @param releases   every approved release still published, newest first; never empty
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonPropertyOrder({"id", "repo", "rank", "coordinate", "name", "description", "tags", "stars", "releases"})
    public record Plugin(String id, String repo, Rank rank, String coordinate, String name, String description,
                         List<String> tags, int stars, List<Release> releases) {

        /**
         * One approved release, with its pins and what its pom says.
         *
         * @param minContractVersion the oldest contract its pom accepts
         * @param editorDependencies {@code groupId:artifactId:version} it needs on the editor's classpath
         */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonPropertyOrder({"tag", "commit", "jarSha256", "pomSha256", "minContractVersion", "editorDependencies"})
        public record Release(String tag, String commit, String jarSha256, String pomSha256,
                              String minContractVersion, List<String> editorDependencies) {
            public Release {
                editorDependencies = editorDependencies == null ? List.of() : List.copyOf(editorDependencies);
            }
        }

        public Plugin {
            description = description == null ? "" : description;
            tags = tags == null ? List.of() : List.copyOf(tags);
            releases = releases == null ? List.of() : List.copyOf(releases);
        }

        /** The newest approved release, what Studio offers first. */
        @JsonIgnore
        public Release newest() {
            return releases.getFirst();
        }
    }

    /**
     * A listed bot.
     *
     * @param forkOf   the repository it was forked from, absent when it is not a fork or stopped following
     * @param releases every approved release still published, newest first; never empty
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonPropertyOrder({"repo", "rank", "description", "tags", "stars", "forkOf", "releases"})
    public record Bot(String repo, Rank rank, String description, List<String> tags, int stars, String forkOf,
                      List<Release> releases) {

        /**
         * One approved release.
         *
         * @param commit  what Studio installs
         * @param plugins the catalog plugins its pom declares
         */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonPropertyOrder({"tag", "commit", "plugins"})
        public record Release(String tag, String commit, List<DerivedFacts.Pin> plugins) {
            public Release {
                plugins = plugins == null ? List.of() : List.copyOf(plugins);
            }
        }

        public Bot {
            description = description == null ? "" : description;
            tags = tags == null ? List.of() : List.copyOf(tags);
            releases = releases == null ? List.of() : List.copyOf(releases);
        }

        /** The newest approved release, what New Project starts from. */
        @JsonIgnore
        public Release newest() {
            return releases.getFirst();
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
