package com.botmaker.cli;

import com.botmaker.cli.catalog.EntryPath;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker bot unpublish} — opens the pull request removing the bot's catalog entry (or with
 * {@code --release} one of its releases; the only one left removes the entry), so Studio stops
 * listing it. The repository and its releases stay, and projects already started from it keep their link. The
 * catalog merges a removal only when it is opened by whoever controls the entry.
 */
@Command(name = "unpublish",
        header = "Stop listing this bot in the catalog.",
        description = "Opens the pull request that removes bots/<owner>__<repo>.json. Nothing else changes.",
        mixinStandardHelpOptions = true)
final class BotUnpublishCommand implements Callable<Integer> {

    @ParentCommand
    private BotCommand parent;

    @Option(names = "--repo", paramLabel = "<owner/name>", required = true, description = "The bot's repository.")
    private String repo;

    @Option(names = "--release", paramLabel = "<tag>",
            description = "Remove only this release; the others stay listed. Default: the whole entry.")
    private String release;

    @Override
    public Integer call() throws IOException {
        String slug = BotNewCommand.slug(repo);
        EntryPath path = EntryPath.parse(EntryPath.bot(slug)).orElseThrow(
                () -> new IOException(slug + " cannot be a catalog entry"));
        return PublishCommands.unpublish(parent.main(), path, Optional.ofNullable(release), "botmaker bot unpublish");
    }
}
