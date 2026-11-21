// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli;

import com.botmaker.cli.catalog.EntryPath;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * {@code botmaker plugin yank} — opens the pull request pulling one release of the plugin (spec 2026-11-21 § 4): it
 * stays on record, Studio offers it to no new project and warns one already on it, and nothing is refused.
 * {@code --undo} lists it again. The catalog merges it when opened by whoever controls the entry, or an admin.
 */
@Command(name = "yank",
        header = "Pull one release of this plugin, with a reason.",
        description = "Opens the pull request that marks a release of plugins/<owner>__<repo>.json yanked, or with"
                + " --undo no longer yanked. Nothing else changes.",
        mixinStandardHelpOptions = true)
final class PluginYankCommand implements Callable<Integer> {

    @ParentCommand
    private PluginCommand parent;

    @Option(names = "--repo", paramLabel = "<owner/name>", required = true,
            description = "The plugin's repository.")
    private String repo;

    @Option(names = "--release", paramLabel = "<tag>", required = true, description = "The release to yank.")
    private String release;

    @Option(names = "--reason", paramLabel = "<text>",
            description = "Why, in 1 to 300 characters, shown to whoever is on the release.")
    private String reason;

    @Option(names = "--undo", description = "List the release again; takes no --reason.")
    private boolean undo;

    @Override
    public Integer call() throws IOException {
        String slug = BotNewCommand.slug(repo);
        EntryPath path = EntryPath.parse(EntryPath.plugin(slug)).orElseThrow(
                () -> new IOException(slug + " cannot be a catalog entry"));
        return PublishCommands.yank(parent.main(), path, release, reason, undo, "botmaker plugin yank");
    }
}
