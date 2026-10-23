package com.botmaker.cli.catalog;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** {@link EntryFacts} and poms from maps the test fills. */
final class FakeFacts implements EntryFacts, PomFacts.Source {

    final Map<String, Repo> repos = new HashMap<>();
    final Map<String, Release> releases = new HashMap<>();
    final Set<String> jitpack = new HashSet<>();
    final Map<String, String> poms = new HashMap<>();

    FakeFacts pom(String slug, String tag, String xml) {
        poms.put(slug + "@" + tag, xml);
        return this;
    }

    @Override
    public Optional<String> pom(String repo, String tag) {
        return Optional.ofNullable(poms.get(repo + "@" + tag));
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

    FakeFacts release(String slug, String tag, String author) {
        releases.put(slug + "@" + tag, new Release(author, false, false));
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
}
