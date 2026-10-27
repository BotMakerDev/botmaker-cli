package com.botmaker.cli;

import com.botmaker.cli.catalog.EntryPath;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker plugin unpublish} — opens the pull request removing {@code plugins/<id>.json}, or with
 * {@code --release} one of its releases (the only one left removes the entry). Studio stops
 * offering the plugin; bots that pin it keep resolving it from JitPack, and the catalog drops a bot whose pom then
 * declares no listed plugin. The catalog merges a removal only when it is opened by whoever controls the entry.
 */
@Command(name = "unpublish",
        header = "Stop listing this plugin in the catalog.",
        description = "Opens the pull request that removes plugins/<owner>__<repo>.json. Nothing else changes.",
        mixinStandardHelpOptions = true)
final class PluginUnpublishCommand implements Callable<Integer> {

    @ParentCommand
    private PluginCommand parent;

    @Option(names = "--repo", paramLabel = "<owner/name>", required = true,
            description = "The plugin's repository.")
    private String repo;

    @Option(names = "--release", paramLabel = "<tag>",
            description = "Remove only this release; the others stay listed. Default: the whole entry.")
    private String release;

    @Override
    public Integer call() throws IOException {
        String slug = BotNewCommand.slug(repo);
        EntryPath path = EntryPath.parse(EntryPath.plugin(slug)).orElseThrow(
                () -> new IOException(slug + " cannot be a catalog entry"));
        return PublishCommands.unpublish(parent.main(), path, Optional.ofNullable(release),
                "botmaker plugin unpublish");
    }
}
