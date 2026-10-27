package com.botmaker.cli.catalog;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code plugins/<owner>__<repo>.json}, named like a bot's. A plugin is its pom's coordinate, which JitPack serves
 * as {@code com.github.<owner>:<repo>}, so the repository is the whole of its identity: the {@code id} the entry
 * held until 2026-10-27 is gone with {@code StudioPlugin.id()}.
 *
 * @param releases each with its commit and its jar's and pom's sha256
 */
@JsonPropertyOrder({"repo", "releases"})
public record PluginEntry(String repo, List<ApprovedRelease> releases) implements Entry {

    public PluginEntry {
        Entry.required(repo, "repo");
        releases = Entry.releases(releases, true);
    }

    @Override
    public PluginEntry withReleases(List<ApprovedRelease> releases) {
        return new PluginEntry(repo, releases);
    }

    public static PluginEntry read(Path file) throws IOException {
        return CatalogJson.strict().readValue(Files.readString(file), PluginEntry.class);
    }
}
