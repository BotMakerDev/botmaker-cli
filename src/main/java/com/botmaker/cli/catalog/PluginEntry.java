package com.botmaker.cli.catalog;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code plugins/<id>.json}. The file name is the id, so git keeps one file — one owner — per id.
 *
 * @param id       the plugin's own {@code StudioPlugin.id()}; the check refuses a jar that says otherwise
 * @param releases each with its commit and its jar's and pom's sha256
 */
@JsonPropertyOrder({"id", "repo", "releases"})
public record PluginEntry(String id, String repo, List<ApprovedRelease> releases) implements Entry {

    /** A plugin id as a file name can hold it: {@code com.example.thing}. */
    public static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public PluginEntry {
        Entry.required(id, "id");
        Entry.required(repo, "repo");
        releases = Entry.releases(releases, true);
    }

    @Override
    public PluginEntry withReleases(List<ApprovedRelease> releases) {
        return new PluginEntry(id, repo, releases);
    }

    public static PluginEntry read(Path file) throws IOException {
        return CatalogJson.strict().readValue(Files.readString(file), PluginEntry.class);
    }
}
