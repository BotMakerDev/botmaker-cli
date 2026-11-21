// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

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

    /**
     * 6 (2026-11-21): a release's {@code publishedAt}, an entry's {@code updatedAt}, a bot's {@code downloads} and
     * {@code platforms}; added only, so a reader of 5 reads it. 5 (2026-10-27): a plugin has no {@code id}, its
     * {@code repo} names it, and a pin names the plugin by {@code repo}. 4 (2026-10-24): every approved release per
     * entry, newest first, each pinned.
     */
    public static final int SCHEMA_VERSION = 6;

    /** The file's name at the catalog's root. */
    public static final String FILE = "catalog.json";

    /**
     * A listed plugin.
     *
     * @param repo       {@code owner/name}, which names it: no two entries share one
     * @param coordinate {@code com.github.<owner>:<repo>}, no version: a release's {@code tag} is the version
     * @param name       its newest release's pom {@code <name>}
     * @param tags       the repository's GitHub topics
     * @param releases   every approved release still published, newest first; never empty
     * @param updatedAt  its newest release's {@code publishedAt}
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonPropertyOrder({"repo", "rank", "coordinate", "name", "description", "tags", "stars", "releases",
            "updatedAt"})
    public record Plugin(String repo, Rank rank, String coordinate, String name, String description,
                         List<String> tags, int stars, List<Release> releases, String updatedAt) {

        /**
         * One approved release, with its pins and what its pom says.
         *
         * @param minContractVersion the oldest contract its pom accepts
         * @param editorDependencies {@code groupId:artifactId:version} it needs on the editor's classpath
         * @param plugins            the other catalog plugins its pom declares, which a project adding it gets too
         *                           (2026-10-24: New Project locks them)
         * @param publishedAt        when its GitHub Release was published (ISO-8601), {@code null} when unknown
         * @param yanked             why and when it was pulled, {@code null} when it was not (spec 2026-11-21 § 4)
         */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonPropertyOrder({"tag", "commit", "jarSha256", "pomSha256", "minContractVersion", "editorDependencies",
                "plugins", "publishedAt", "yanked"})
        public record Release(String tag, String commit, String jarSha256, String pomSha256,
                              String minContractVersion, List<String> editorDependencies,
                              List<DerivedFacts.Pin> plugins, String publishedAt, ApprovedRelease.Yanked yanked) {
            public Release {
                editorDependencies = editorDependencies == null ? List.of() : List.copyOf(editorDependencies);
                plugins = plugins == null ? List.of() : List.copyOf(plugins);
            }

            /** A release not yanked. */
            public Release(String tag, String commit, String jarSha256, String pomSha256,
                           String minContractVersion, List<String> editorDependencies,
                           List<DerivedFacts.Pin> plugins, String publishedAt) {
                this(tag, commit, jarSha256, pomSha256, minContractVersion, editorDependencies, plugins, publishedAt,
                        null);
            }

            /** A release whose publishing time is not known. */
            public Release(String tag, String commit, String jarSha256, String pomSha256,
                           String minContractVersion, List<String> editorDependencies,
                           List<DerivedFacts.Pin> plugins) {
                this(tag, commit, jarSha256, pomSha256, minContractVersion, editorDependencies, plugins, null);
            }

            /** A release that brings no other catalog plugin. */
            public Release(String tag, String commit, String jarSha256, String pomSha256,
                           String minContractVersion, List<String> editorDependencies) {
                this(tag, commit, jarSha256, pomSha256, minContractVersion, editorDependencies, List.of());
            }
        }

        public Plugin {
            description = description == null ? "" : description;
            tags = tags == null ? List.of() : List.copyOf(tags);
            releases = releases == null ? List.of() : List.copyOf(releases);
        }

        /** A plugin whose update time is not known. */
        public Plugin(String repo, Rank rank, String coordinate, String name, String description,
                      List<String> tags, int stars, List<Release> releases) {
            this(repo, rank, coordinate, name, description, tags, stars, releases, null);
        }

        /** The newest approved release, what Studio offers first. */
        @JsonIgnore
        public Release newest() {
            return releases.getFirst();
        }

        /** The releases a picker offers: none yanked, newest first. */
        @JsonIgnore
        public List<Release> pickable() {
            return releases.stream().filter(r -> r.yanked() == null).toList();
        }
    }

    /**
     * A listed bot.
     *
     * @param forkOf   the repository it was forked from, absent when it is not a fork or stopped following
     * @param releases  every approved release still published, newest first; never empty
     * @param updatedAt its newest release's {@code publishedAt}
     * @param downloads the sum of its releases' {@code downloads}, {@code null} when none has a count
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonPropertyOrder({"repo", "rank", "description", "tags", "stars", "forkOf", "releases", "updatedAt",
            "downloads"})
    public record Bot(String repo, Rank rank, String description, List<String> tags, int stars, String forkOf,
                      List<Release> releases, String updatedAt, Integer downloads) {

        /**
         * One approved release.
         *
         * @param commit      what Studio installs
         * @param plugins     the catalog plugins its pom declares
         * @param bmkSha256   the sha256 of the release's {@code .bmk} (spec 2026-10-24 § 3), which Studio checks a
         *                    file against before it opens one; {@code null} for a release that has none (2026-10-25)
         * @param publishedAt when its GitHub Release was published (ISO-8601), {@code null} when unknown
         * @param downloads   its {@code .bmk}'s GitHub download count, {@code null} for a release with no {@code .bmk}
         * @param platforms   the systems its pom names ({@link Platform#PROPERTY}); empty means every known one
         * @param yanked      why and when it was pulled, {@code null} when it was not (spec 2026-11-21 § 4)
         */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonPropertyOrder({"tag", "commit", "plugins", "bmkSha256", "publishedAt", "downloads", "platforms",
                "yanked"})
        public record Release(String tag, String commit, List<DerivedFacts.Pin> plugins, String bmkSha256,
                              String publishedAt, Integer downloads, List<Platform> platforms,
                              ApprovedRelease.Yanked yanked) {
            public Release {
                plugins = plugins == null ? List.of() : List.copyOf(plugins);
                platforms = platforms == null ? List.of() : List.copyOf(platforms);
            }

            /** A release not yanked. */
            public Release(String tag, String commit, List<DerivedFacts.Pin> plugins, String bmkSha256,
                           String publishedAt, Integer downloads, List<Platform> platforms) {
                this(tag, commit, plugins, bmkSha256, publishedAt, downloads, platforms, null);
            }

            /** A release with no time, count or platforms. */
            public Release(String tag, String commit, List<DerivedFacts.Pin> plugins, String bmkSha256) {
                this(tag, commit, plugins, bmkSha256, null, null, List.of());
            }

            /** A release with no {@code .bmk}. */
            public Release(String tag, String commit, List<DerivedFacts.Pin> plugins) {
                this(tag, commit, plugins, null);
            }

            /** Whether it runs on {@code platform}: it names it, or names none. */
            public boolean runsOn(Platform platform) {
                return platforms.isEmpty() || platforms.contains(platform);
            }
        }

        public Bot {
            description = description == null ? "" : description;
            tags = tags == null ? List.of() : List.copyOf(tags);
            releases = releases == null ? List.of() : List.copyOf(releases);
        }

        /** A bot whose update time and downloads are not known. */
        public Bot(String repo, Rank rank, String description, List<String> tags, int stars, String forkOf,
                   List<Release> releases) {
            this(repo, rank, description, tags, stars, forkOf, releases, null, null);
        }

        /** The newest approved release, what New Project starts from. */
        @JsonIgnore
        public Release newest() {
            return releases.getFirst();
        }

        /** The releases a picker offers: none yanked, newest first. */
        @JsonIgnore
        public List<Release> pickable() {
            return releases.stream().filter(r -> r.yanked() == null).toList();
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
