package com.botmaker.cli.gallery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A maintainer's statement that one release of one bot is listed as {@link Tier#VETTED} —
 * {@code vetted/<owner>-<repo>.json}.
 *
 * <p><b>A file of its own, beside the author's entry rather than inside it.</b> The entry is the author's
 * file and an author re-publishes it whenever they like; a field in it would be a field every submission can
 * set, so the gate would have to police one key inside a file it otherwise lets the author own. A separate
 * directory makes the rule a path rule — nobody but a maintainer changes {@code vetted/} — which
 * {@link GalleryGate} can state in one line.
 *
 * <p><b>It pins a release.</b> {@link #vettedVersion} is the tag a maintainer looked at, the same idea as the
 * plugin registry's {@code verifiedVersion}: a badge that followed the repository would carry over to a
 * release nobody has seen. A newer release is still installable; it is simply not the vetted one.
 *
 * <p>{@link #owner} and {@link #repo} are stated rather than parsed back out of the filename, because both
 * may contain a hyphen and {@code a-b-c.json} does not say where one ends.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"schemaVersion", "owner", "repo", "vettedVersion", "vettedAt", "vettedBy"})
public record VettedRecord(int schemaVersion, String owner, String repo, String vettedVersion,
                           String vettedAt, String vettedBy) {

    /** Where these live, relative to the gallery root. Maintainer-only. */
    public static final String DIRECTORY = "vetted";

    public static final int CURRENT_SCHEMA = 1;

    public VettedRecord {
        schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA : schemaVersion;
        owner = owner == null ? "" : owner.trim();
        repo = repo == null ? "" : repo.trim();
        vettedVersion = vettedVersion == null ? "" : vettedVersion.trim();
        vettedAt = vettedAt == null ? "" : vettedAt.trim();
        vettedBy = vettedBy == null ? "" : vettedBy.trim();
    }

    /** {@code vetted/<owner>-<repo>.json} — the same identity rule as {@link GalleryEntry#path()}. */
    public String path() {
        return DIRECTORY + "/" + owner + "-" + repo + ".json";
    }

    public String slug() {
        return owner + "/" + repo;
    }
}
