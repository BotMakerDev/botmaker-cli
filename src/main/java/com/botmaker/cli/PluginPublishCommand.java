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
 * {@code botmaker plugin publish [patch|minor|major]} — from the plugin's folder: check it, tag and release the next
 * version, wait for JitPack, then open the catalog pull request listing it ({@link Publish}).
 *
 * <p><b>The author names nothing and hand-edits nothing</b> (2026-10-24). The repository is the folder's
 * {@code origin}, the version the newest {@code vX.Y.Z} bumped ({@code patch} by default), the id the plugin's own
 * read off the jar; the catalog derives the rest (name, contract version, editor dependencies) from the pom at the
 * pinned commit. {@code --tag} names the version exactly, and one already released is listed as it is: how a release
 * made elsewhere is listed once {@code HEAD} has moved past it.
 */
@Command(name = "publish",
        header = "Check, release the next version, and open the catalog pull request.",
        description = "Run in the plugin's folder. Bumps the newest vX.Y.Z tag (patch by default), checks the plugin, "
                + "tags and releases it, waits for JitPack, checks the jar and opens the pull request listing it. "
                + "The catalog's checks merge it when they pass.",
        mixinStandardHelpOptions = true)
final class PluginPublishCommand implements Callable<Integer> {

    @ParentCommand
    private PluginCommand parent;

    @Parameters(index = "0", arity = "0..1", paramLabel = "patch|minor|major",
            converter = PublishCommands.LevelConverter.class,
            description = "How the newest vX.Y.Z tag is bumped. Default: patch.")
    private Level bump;

    @Option(names = "--dir", defaultValue = ".", description = "The plugin project. Default: ${DEFAULT-VALUE}")
    private String directory;

    /**
     * <b>{@code --tag}, not {@code --version}, and the name is forced rather than chosen.</b>
     * {@code mixinStandardHelpOptions} already declares {@code -V, --version} on every command here, so a
     * second one would be a duplicate picocli refuses at construction. It reads better anyway: what JitPack
     * serves an artifact under is a git tag.
     */
    @Option(names = "--tag", paramLabel = "<tag>",
            description = "The version exactly, instead of a bump. One already released is listed as it is.")
    private String tag;

    @Option(names = "--repo", paramLabel = "<owner/name>",
            description = "The plugin's GitHub repository. Default: the folder's origin, which it must agree with.")
    private String repo;

    @Option(names = "--dry-run", description = "Check, print what would be listed, and tag nothing.")
    private boolean dryRun;

    @Override
    public Integer call() {
        return PublishCommands.publish(parent.main(), new PublishRequest(Path.of(directory).toAbsolutePath().normalize(),
                EntryPath.Kind.PLUGIN, bump == null ? Level.DEFAULT : bump, Optional.ofNullable(tag),
                Optional.ofNullable(repo), dryRun, "botmaker plugin publish"));
    }
}
