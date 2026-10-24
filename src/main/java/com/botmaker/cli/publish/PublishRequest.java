package com.botmaker.cli.publish;

import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.release.Level;

import java.nio.file.Path;
import java.util.Optional;

/**
 * One publish, as the author asked for it.
 *
 * @param bump    how the newest {@code vX.Y.Z} is bumped when nothing else names the version
 * @param tag     {@code --tag}: the version named exactly
 * @param repo    {@code --repo}: overrides the folder's {@code origin}, and must agree with it
 * @param dryRun  stop before the first change (the repository, the tag), with what would be listed
 * @param command who asked, for the pull request's body ({@code botmaker plugin publish}, Studio)
 */
public record PublishRequest(Path dir, EntryPath.Kind kind, Level bump, Optional<String> tag, Optional<String> repo,
                             boolean dryRun, String command) {}
