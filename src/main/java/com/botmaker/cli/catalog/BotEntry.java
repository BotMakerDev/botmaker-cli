package com.botmaker.cli.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code bots/<owner>__<repo>.json}.
 *
 * @param detached the bot is a fork that stopped following its original (Studio's <i>Stop following</i>):
 *                 {@code catalog.json} then names no {@code forkOf}. Omitted from the file when false.
 */
@JsonPropertyOrder({"repo", "tag", "detached"})
public record BotEntry(String repo, String tag,
                       @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean detached) implements Entry {

    public BotEntry {
        Entry.required(repo, "repo");
        Entry.required(tag, "tag");
    }

    public static BotEntry read(Path file) throws IOException {
        return CatalogJson.strict().readValue(Files.readString(file), BotEntry.class);
    }
}
