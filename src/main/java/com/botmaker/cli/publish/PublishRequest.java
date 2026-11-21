// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.publish;

import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.catalog.Platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * One publish, as the author asked for it.
 *
 * @param bump    how the newest {@code vX.Y.Z} is bumped when nothing else names the version
 * @param tag     {@code --tag}: the version named exactly
 * @param repo    {@code --repo}: overrides the folder's {@code origin}, and must agree with it
 * @param dryRun  stop before the first change (the repository, the tag), with what would be listed
 * @param command   who asked, for the pull request's body ({@code botmaker plugin publish}, Studio)
 * @param platforms a bot's systems, written to its pom's {@link Platform#PROPERTY} and committed before the tag
 *                  (2026-11-21); empty leaves the pom as it is
 */
public record PublishRequest(Path dir, EntryPath.Kind kind, Level bump, Optional<String> tag, Optional<String> repo,
                             boolean dryRun, String command, List<Platform> platforms) {

    public PublishRequest {
        platforms = platforms == null ? List.of() : List.copyOf(platforms);
    }

    /** A publish that leaves the pom's platforms as they are. */
    public PublishRequest(Path dir, EntryPath.Kind kind, Level bump, Optional<String> tag, Optional<String> repo,
                          boolean dryRun, String command) {
        this(dir, kind, bump, tag, repo, dryRun, command, List.of());
    }
}
