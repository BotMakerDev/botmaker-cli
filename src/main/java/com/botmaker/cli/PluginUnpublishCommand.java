package com.botmaker.cli;

import com.botmaker.cli.catalog.EntryPath;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * {@code botmaker plugin unpublish} — opens the pull request removing {@code plugins/<id>.json}. Studio stops
 * offering the plugin; bots that pin it keep resolving it from JitPack, and the catalog drops a bot whose pom then
 * declares no listed plugin. The catalog merges a removal only when it is opened by whoever controls the entry.
 */
@Command(name = "unpublish",
        header = "Stop listing this plugin in the catalog.",
        description = "Opens the pull request that removes plugins/<id>.json. Nothing else changes.",
        mixinStandardHelpOptions = true)
final class PluginUnpublishCommand implements Callable<Integer> {

    @ParentCommand
    private PluginCommand parent;

    @Option(names = "--id", paramLabel = "<plugin-id>", required = true, description = "The plugin's id.")
    private String id;

    @Override
    public Integer call() throws IOException {
        EntryPath path = EntryPath.parse(EntryPath.PLUGINS + id.strip() + ".json").orElseThrow(
                () -> new IOException(id + " is not a plugin id"));
        return CatalogPr.unpublish(parent.main().console(), path, "botmaker plugin unpublish");
    }
}
