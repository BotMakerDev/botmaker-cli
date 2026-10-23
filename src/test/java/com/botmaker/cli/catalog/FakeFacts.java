package com.botmaker.cli.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link EntryFacts} and poms from maps the test fills. A release's tag points at {@link #commitOf}{@code (tag)} and
 * JitPack serves {@link #shaOf}{@code ("jar:" + tag)} unless the test says otherwise, which is what
 * {@link #botRelease} and {@link #pluginRelease} pin.
 */
final class FakeFacts implements EntryFacts, PomFacts.Source {

    final Map<String, Repo> repos = new HashMap<>();
    final Map<String, Release> releases = new HashMap<>();
    final Map<String, String> commits = new HashMap<>();
    final Set<String> jitpack = new HashSet<>();
    final Map<String, String> hashes = new HashMap<>();
    final Map<String, String> poms = new HashMap<>();

    /** The pom of {@code slug} at {@code tag}'s commit. */
    FakeFacts pom(String slug, String tag, String xml) {
        poms.put(slug + "@" + commitOf(tag), xml);
        return this;
    }

    @Override
    public Optional<String> pom(String repo, String ref) {
        return Optional.ofNullable(poms.get(repo + "@" + ref));
    }

    FakeFacts repo(String slug, long ownerId, String description, String parent) {
        return repo(slug, ownerId, slug, description, parent);
    }

    /** {@code slug} as the entry names it, described by GitHub as {@code now} (after a rename or a transfer). */
    FakeFacts repo(String slug, long ownerId, String now, String description, String parent) {
        int slash = now.indexOf('/');
        repos.put(slug, new Repo(ownerId, now.substring(0, slash), now.substring(slash + 1), description,
                List.of("idle"), 3, Optional.ofNullable(parent)));
        return this;
    }

    /** A published release made by {@code author}, its tag at {@link #commitOf}{@code (tag)}. */
    FakeFacts release(String slug, String tag, String author) {
        releases.put(slug + "@" + tag, new Release(author, false, false));
        commits.put(slug + "@" + tag, commitOf(tag));
        return this;
    }

    @Override
    public Optional<Release> release(String repo, String tag) {
        return Optional.ofNullable(releases.get(repo + "@" + tag));
    }

    @Override
    public Optional<Repo> repo(String repo) {
        return Optional.ofNullable(repos.get(repo));
    }

    @Override
    public boolean jitpackServes(String coordinate, String tag) {
        return jitpack.contains(coordinate + ":" + tag);
    }

    @Override
    public Optional<String> tagCommit(String repo, String tag) {
        return Optional.ofNullable(commits.get(repo + "@" + tag));
    }

    @Override
    public Optional<String> jitpackSha256(String coordinate, String tag, Artifact artifact) {
        if (!jitpackServes(coordinate, tag)) return Optional.empty();
        String key = artifact.extension() + ":" + tag;
        return Optional.of(hashes.getOrDefault(coordinate + ":" + key, shaOf(key)));
    }

    /** A commit hash for {@code tag}: 40 hex digits. */
    static String commitOf(String tag) {
        return shaOf("commit:" + tag).substring(0, 40);
    }

    static String shaOf(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static ApprovedRelease botRelease(String tag) {
        return ApprovedRelease.ofBot(tag, commitOf(tag));
    }

    static ApprovedRelease pluginRelease(String tag) {
        return new ApprovedRelease(tag, commitOf(tag), shaOf("jar:" + tag), shaOf("pom:" + tag));
    }

    /** A bot entry's file text, its releases pinned as {@link #botRelease}. */
    static String botJson(String repo, String... tags) {
        return json(new BotEntry(repo, Arrays.stream(tags).map(FakeFacts::botRelease).toList(), false));
    }

    /** A plugin entry's file text, its releases pinned as {@link #pluginRelease}. */
    static String pluginJson(String id, String repo, String... tags) {
        return json(new PluginEntry(id, repo, Arrays.stream(tags).map(FakeFacts::pluginRelease).toList()));
    }

    /** An entry or a release as the entry file writes it. */
    static String json(Object value) {
        try {
            return CatalogJson.strict().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
