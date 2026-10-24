package com.botmaker.cli;

import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.publish.Publish;
import com.botmaker.cli.publish.PublishRequest;
import com.botmaker.cli.release.Level;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker bot publish [patch|minor|major]} — from the bot's folder: check it, release the next version and
 * open the catalog pull request listing it ({@link Publish}).
 *
 * <p>The repository is the folder's {@code origin}. A folder with none has its repository created under the
 * signed-in account and named after the folder ({@code --repo} names another), and one that is not a git
 * repository yet is initialised and committed first, because that is mechanical and exactly the part this command
 * exists to remove. A working copy with <em>uncommitted changes</em> is refused instead: guessing that the author
 * meant to publish them is guessing about the one thing they can see and this command cannot.
 *
 * <p>{@code botmaker bot unpublish} opens the pull request removing the entry, or one release of it; the repository
 * and its releases stay.
 */
@Command(name = "publish",
        header = "Check this bot, release the next version, and open its catalog pull request.",
        description = "Run in the bot's folder. Bumps the newest vX.Y.Z tag (patch by default; v0.1.0 first), checks "
                + "that a catalog plugin is declared, the bot compiles and a class holds main, then tags, releases "
                + "and opens the pull request listing it. The catalog's checks merge it when they pass.",
        mixinStandardHelpOptions = true)
final class BotPublishCommand implements Callable<Integer> {

    @ParentCommand
    private BotCommand parent;

    @Parameters(index = "0", arity = "0..1", paramLabel = "patch|minor|major",
            converter = PublishCommands.LevelConverter.class,
            description = "How the newest vX.Y.Z tag is bumped. Default: patch.")
    private Level bump;

    @Option(names = "--dir", defaultValue = ".", description = "The bot project. Default: ${DEFAULT-VALUE}")
    private String directory;

    @Option(names = "--tag", paramLabel = "<tag>",
            description = "The version exactly, instead of a bump. One already released is listed as it is.")
    private String tag;

    @Option(names = "--repo", paramLabel = "<owner/name>",
            description = "The bot's GitHub repository. Default: the folder's origin, or a new one named after the "
                    + "folder when it has none.")
    private String repo;

    @Option(names = "--dry-run", description = "Check, print what would be listed, and create or tag nothing.")
    private boolean dryRun;

    @Override
    public Integer call() {
        return PublishCommands.publish(parent.main(), new PublishRequest(Path.of(directory).toAbsolutePath().normalize(),
                EntryPath.Kind.BOT, bump == null ? Level.DEFAULT : bump, Optional.ofNullable(tag),
                Optional.ofNullable(repo), dryRun, "botmaker bot publish"));
    }
}
