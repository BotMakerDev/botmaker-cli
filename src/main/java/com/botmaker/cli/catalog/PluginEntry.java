package com.botmaker.cli.catalog;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * {@code plugins/<id>.json}. The file name is the id, so git keeps one file — one owner — per id.
 *
 * @param id the plugin's own {@code StudioPlugin.id()}; the check refuses a jar that says otherwise
 */
@JsonPropertyOrder({"id", "repo", "tag"})
public record PluginEntry(String id, String repo, String tag) implements Entry {

    /** A plugin id as a file name can hold it: {@code com.example.thing}. */
    public static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public PluginEntry {
        Entry.required(id, "id");
        Entry.required(repo, "repo");
        Entry.required(tag, "tag");
    }

    public static PluginEntry read(Path file) throws IOException {
        return CatalogJson.strict().readValue(Files.readString(file), PluginEntry.class);
    }
}
