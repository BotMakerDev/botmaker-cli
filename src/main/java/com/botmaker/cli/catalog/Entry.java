package com.botmaker.cli.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What an author writes into the catalog: the repository and every release the check approved, oldest first, each
 * pinned ({@link ApprovedRelease}). A pull request adds one release at the end or removes some, never edits one
 * ({@link EntryGate.Change}). Everything else Studio shows is derived ({@link CatalogBuilder}), so nothing written by
 * hand can drift.
 */
public sealed interface Entry permits BotEntry, PluginEntry {

    /** {@code owner/name}: a GitHub login (letters, digits, hyphens) and a repository name. */
    Pattern SLUG = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}");

    /** {@code owner/name} on GitHub. */
    String repo();

    /** Every approved release, oldest first; never empty. */
    List<ApprovedRelease> releases();

    /** The same entry holding {@code releases} instead. */
    Entry withReleases(List<ApprovedRelease> releases);

    /** The newest approved release, what Studio offers first. */
    @JsonIgnore
    default ApprovedRelease newest() {
        return releases().getLast();
    }

    /** The approved release at {@code tag}, or empty. */
    default Optional<ApprovedRelease> release(String tag) {
        return releases().stream().filter(r -> r.tag().equals(tag)).findFirst();
    }

    /** The same entry with {@code release} approved after the others. */
    default Entry appended(ApprovedRelease release) {
        List<ApprovedRelease> more = new ArrayList<>(releases());
        more.add(release);
        return withReleases(more);
    }

    /** The owner half of {@link #repo()}. */
    @JsonIgnore
    default String owner() {
        int slash = repo().indexOf('/');
        return slash < 0 ? repo() : repo().substring(0, slash);
    }

    /** The repository-name half of {@link #repo()}. */
    @JsonIgnore
    default String name() {
        int slash = repo().indexOf('/');
        return slash < 0 ? "" : repo().substring(slash + 1);
    }

    /** Whether {@code repo} is an {@code owner/name} GitHub accepts ({@code .}, {@code ..} and {@code *.git} it refuses). */
    static boolean isSlug(String repo) {
        if (repo == null || !SLUG.matcher(repo).matches()) return false;
        String name = repo.substring(repo.indexOf('/') + 1);
        return !name.equals(".") && !name.equals("..") && !name.endsWith(".git");
    }

    /** Refuses a missing field while the file is read, so a broken entry is an unreadable file, never a null. */
    static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing \"" + field + "\"");
        return value;
    }

    /**
     * {@code releases} as an entry holds them: at least one, no tag twice, each with a plugin's hashes when
     * {@code hashed} and with none when not.
     */
    static List<ApprovedRelease> releases(List<ApprovedRelease> releases, boolean hashed) {
        if (releases == null || releases.isEmpty()) throw new IllegalArgumentException("missing \"releases\"");
        Set<String> tags = new HashSet<>();
        for (ApprovedRelease release : releases) {
            if (release == null) throw new IllegalArgumentException("a null release");
            if (!tags.add(release.tag())) throw new IllegalArgumentException("tag " + release.tag() + " twice");
            boolean any = release.jarSha256() != null || release.pomSha256() != null;
            if (hashed ? !release.hashed() : any) {
                throw new IllegalArgumentException(hashed ? "a plugin release carries \"jarSha256\" and \"pomSha256\""
                        : "a bot release carries no hashes");
            }
        }
        return List.copyOf(releases);
    }
}
