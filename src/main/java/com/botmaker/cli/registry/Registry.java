package com.botmaker.cli.registry;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The registry as it exists on disk: {@code plugins/&lt;plugin-id&gt;.json}, one entry per file.
 *
 * <p><b>The filename is the id, and that is the layout doing a check's job.</b> A single {@code index.json}
 * array made "is this id already claimed?" a scan somebody had to remember to run, and made two concurrent
 * submissions a merge conflict on the same lines. With one file per entry, git itself refuses a second
 * {@code plugins/com.example.discord.json}, and two pull requests adding two plugins touch no common line.
 * The index is generated from these files and is nobody's source of truth.
 *
 * <p>Value type ids were the one claim that needed a scan, and they went on 2026-09-23: a plugin declares
 * its types as classes now, named by their own package. An entry file written before that date still
 * carries {@code valueTypeIds}; it is read, ignored and dropped from the next generated index, and nothing
 * rewrites the file.
 */
public final class Registry {

    /** Where entries live, relative to the registry repository's root. */
    public static final String ENTRIES_DIRECTORY = "plugins";

    /** The generated file. Named here so the gate can refuse a pull request that edits it. */
    public static final String INDEX = "index.json";

    private final List<Entry> entries;

    /**
     * One entry and the file it was read from, because every refusal this gate can produce has to name a
     * path — a reviewer is looking at a diff, not at a model.
     */
    public record Entry(Path file, RegistryEntry entry) {

        /** The id the layout claims for this file, which must equal the id the plugin claims for itself. */
        public String idFromFilename() {
            String name = file.getFileName().toString();
            return name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
        }
    }

    private Registry(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** Reads every {@code *.json} under a {@code plugins} directory. A missing directory is an empty one. */
    public static Registry read(Path entriesDirectory) throws IOException {
        List<Entry> read = new ArrayList<>();
        if (Files.isDirectory(entriesDirectory)) {
            try (Stream<Path> files = Files.list(entriesDirectory)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted()
                        .toList()) {
                    read.add(new Entry(file, mapper().readValue(Files.readString(file),
                            RegistryEntry.class)));
                }
            }
        }
        return new Registry(read);
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Every plugin id the registry already holds, except the one being submitted. */
    public Set<String> claimedPluginIds(String except) {
        Set<String> ids = new LinkedHashSet<>();
        for (Entry held : entries) {
            if (!held.entry().id().equals(except)) {
                ids.add(held.entry().id());
            }
        }
        return ids;
    }

    /** The generated index: every entry, id order, as an array — the shape a reader already expects. */
    public String index() throws IOException {
        List<RegistryEntry> ordered = new ArrayList<>(entries.stream().map(Entry::entry).toList());
        ordered.sort((a, b) -> a.id().compareTo(b.id()));
        return mapper().writerWithDefaultPrettyPrinter().writeValueAsString(ordered) + "\n";
    }

    /**
     * The mapper both halves use.
     *
     * <p>Unknown properties are ignored on purpose: an entry file written by a newer {@code botmaker
     * publish} than the gate resolves must be readable, or the registry stops accepting submissions every
     * time a field is added and nobody finds out until a pull request is refused for a reason its author
     * cannot act on.
     */
    public static ObjectMapper mapper() {
        return new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
