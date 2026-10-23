package com.botmaker.cli.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.regex.Pattern;

/**
 * What an author writes into the catalog: the repository and the release the check verified. Everything else
 * Studio shows is derived ({@link CatalogBuilder}), so nothing written by hand can drift.
 */
public sealed interface Entry permits BotEntry, PluginEntry {

    /** {@code owner/name}: a GitHub login (letters, digits, hyphens) and a repository name. */
    Pattern SLUG = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}");

    /** {@code owner/name} on GitHub. */
    String repo();

    /** The GitHub Release the check verified; what Studio installs. */
    String tag();

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
}
