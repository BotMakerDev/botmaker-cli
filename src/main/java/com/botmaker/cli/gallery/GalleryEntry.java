package com.botmaker.cli.gallery;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * One bot's entry in the gallery — {@code bots/<owner>-<repo>.json}.
 *
 * <p><b>A mirror of {@code com.botmaker.studio.sharing.GalleryEntry}, and the mirroring is the same decision
 * {@code registry/RegistryEntry} records.</b> {@code botmaker-studio} is an application rather than a
 * library: depending on it to reuse one record would mean pulling JavaFX, OpenCV and JNA into a command
 * whose whole promise is a single jar. What the two copies must agree on is the <em>file</em> — field names
 * and the path — and that is checked by the thing that reads it, which is Studio.
 *
 * <p><b>{@code launchTargets} is read and carried, never composed here.</b> Studio writes it and reads its
 * absence as {@code SupportedTargets.any()} — <i>the author never said</i>, never <i>works on nothing</i>.
 * Saying which launchers a bot was tested on is something only the person who ran it can know, and the
 * honest answer from a command that has run nothing is silence, so {@code bot publish} leaves it empty. It is
 * a component at all because {@link GalleryCatalog} rebuilds the generated files from these records, and a
 * copy that dropped the field would delete Studio's declaration from every index it wrote.
 *
 * <p><b>{@code schemaVersion} is the migration seam.</b> A file with no such field is version 1 — every entry
 * written before 2026-09-16 — and is read as it stands: version 2 only <em>adds</em> fields, so there is
 * nothing to convert and no file is rewritten. {@link #CURRENT_SCHEMA} is what a writer stamps; a reader
 * meeting a number above it knows it is the one that lags, which is how the gate refuses an entry it does not
 * understand instead of silently dropping fields from it.
 *
 * <p>Field order is fixed so a pull request adding an entry is one block rather than a reshuffle, and empty
 * values are omitted so an entry with no tags carries no line saying so.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"schemaVersion", "name", "owner", "repo", "description", "tags", "launchTargets",
        "requires"})
public record GalleryEntry(
        /** {@code 1} for a file that does not say — see the class note. */
        int schemaVersion,
        /** The project name, PascalCase — also the directory the bot installs into. */
        String name,
        String owner,
        String repo,
        String description,
        List<String> tags,
        /** Studio's launch-kind wire ids, carried verbatim. */
        List<String> launchTargets,
        /** The plugins the bot's pom declares, by registry id — so a browser can say what it needs. */
        List<Requirement> requires) {

    /** The schema a writer stamps today. */
    public static final int CURRENT_SCHEMA = 2;

    /** What a file without {@code schemaVersion} is. */
    public static final int LEGACY_SCHEMA = 1;

    /**
     * The one reserved tag: an entry carrying it is a <b>starting template</b> rather than a bot to install.
     *
     * <p>A template is a published bot — same repo, same release, same install path. That is what lets
     * {@code botmaker bot new --from} hold no template content of its own, and it is why this command can
     * publish one at all: there is no second kind of thing to publish.
     */
    public static final String TEMPLATE_TAG = "template";

    /** Where one entry lives in the gallery repository. The filename is the bot's identity. */
    public static final String ENTRIES_DIRECTORY = "bots";

    /**
     * One plugin a bot needs: the registry id and the version the bot's pom pins.
     *
     * <p>The id rather than the coordinate because the id is what a user reads and what the registry is keyed
     * by; the version because "needs the SDK" and "needs SDK 1.1.7" are different promises to somebody deciding
     * whether their Studio can open it.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Requirement(String id, String version) {
        public Requirement {
            id = id == null ? "" : id.trim();
            version = version == null ? "" : version.trim();
        }
    }

    public GalleryEntry {
        schemaVersion = schemaVersion <= 0 ? LEGACY_SCHEMA : schemaVersion;
        name = name == null ? "" : name.trim();
        owner = owner == null ? "" : owner.trim();
        repo = repo == null ? "" : repo.trim();
        description = description == null ? "" : description;
        tags = tags == null ? List.of() : List.copyOf(tags);
        launchTargets = launchTargets == null ? List.of() : List.copyOf(launchTargets);
        requires = requires == null ? List.of() : List.copyOf(requires);
    }

    /** A freshly composed entry: current schema, nothing declared beyond the five fields. */
    public GalleryEntry(String name, String owner, String repo, String description, List<String> tags) {
        this(CURRENT_SCHEMA, name, owner, repo, description, tags, List.of(), List.of());
    }

    /** This entry with {@code requires} replaced. */
    public GalleryEntry withRequires(List<Requirement> newRequires) {
        return new GalleryEntry(schemaVersion, name, owner, repo, description, tags, launchTargets, newRequires);
    }

    /** This entry read as the current schema. Version 2 only added fields, so only the number moves. */
    public GalleryEntry migrated() {
        return schemaVersion >= CURRENT_SCHEMA ? this
                : new GalleryEntry(CURRENT_SCHEMA, name, owner, repo, description, tags, launchTargets, requires);
    }

    /** {@code bots/<owner>-<repo>.json} — {@code GitHubConfig.entryPath} in Studio, and it must match. */
    public String path() {
        return ENTRIES_DIRECTORY + "/" + owner + "-" + repo + ".json";
    }

    public String slug() {
        return owner + "/" + repo;
    }

    /** The bot's own repository — where a reader goes to look at the source. */
    @JsonIgnore
    public String htmlUrl() {
        return "https://github.com/" + slug();
    }

    /**
     * <b>{@code @JsonIgnore}, and it is load-bearing.</b> Jackson reads an {@code isX()} method as a bean
     * property, so without this the entry file carried a {@code "template": true} field of its own beside
     * the tag it is derived from — a second statement of one fact, in the file two repositories agree on.
     */
    @JsonIgnore
    public boolean isTemplate() {
        return tags.stream().anyMatch(TEMPLATE_TAG::equalsIgnoreCase);
    }
}
