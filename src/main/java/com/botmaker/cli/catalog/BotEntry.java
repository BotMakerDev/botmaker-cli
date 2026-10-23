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
 */
@JsonPropertyOrder({"repo", "releases", "detached"})
public record BotEntry(String repo, List<ApprovedRelease> releases,
                       @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean detached) implements Entry {

    public BotEntry {
        Entry.required(repo, "repo");
        releases = Entry.releases(releases, false);
    }

    @Override
    public BotEntry withReleases(List<ApprovedRelease> releases) {
        return new BotEntry(repo, releases, detached);
    }

    public static BotEntry read(Path file) throws IOException {
        return CatalogJson.strict().readValue(Files.readString(file), BotEntry.class);
    }
}
