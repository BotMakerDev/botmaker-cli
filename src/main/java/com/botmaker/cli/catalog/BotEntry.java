// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code bots/<owner>__<repo>.json}.
 *
 * @param releases each with the commit Studio installs
 * @param detached the bot is a fork that stopped following its original (Studio's <i>Stop following</i>):
 *                 {@code catalog.json} then names no {@code forkOf}. Omitted from the file when false.
 * @param formerly {@link Entry#formerly()}; omitted from the file when empty
 */
@JsonPropertyOrder({"repo", "releases", "detached", "formerly"})
public record BotEntry(String repo, List<ApprovedRelease> releases,
                       @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean detached,
                       @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> formerly) implements Entry {

    public BotEntry {
        Entry.required(repo, "repo");
        releases = Entry.releases(releases, false);
        formerly = Entry.formerly(formerly, repo);
    }

    /** A bot never moved. */
    public BotEntry(String repo, List<ApprovedRelease> releases, boolean detached) {
        this(repo, releases, detached, List.of());
    }

    @Override
    public BotEntry withReleases(List<ApprovedRelease> releases) {
        return new BotEntry(repo, releases, detached, formerly);
    }

    @Override
    public BotEntry moved(String slug) {
        return new BotEntry(slug, releases, detached, formerlyAnd(repo, slug));
    }

    public static BotEntry read(Path file) throws IOException {
        return CatalogJson.strict().readValue(Files.readString(file), BotEntry.class);
    }
}
