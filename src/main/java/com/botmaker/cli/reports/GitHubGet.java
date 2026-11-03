package com.botmaker.cli.reports;

import com.botmaker.cli.publish.StepRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * One read of GitHub's REST API, by path ({@code repos/o/r/issues?state=all}): its JSON text. Its host says as whom:
 * Studio with its signed-in token, the CLI through {@code gh api} as the account {@code gh} is signed in to — no
 * token passes through here.
 */
@FunctionalInterface
public interface GitHubGet {

    /** The JSON GitHub answered {@code path} with; an {@link IOException} when it could not be read. */
    String get(String path) throws IOException;

    /** Through {@code gh api}, as {@code runner} runs it. */
    static GitHubGet gh(StepRunner runner) {
        return path -> runner.read(Path.of("."), List.of("gh", "api", "-H", "Accept: application/vnd.github+json",
                        path))
                .orElseThrow(() -> new IOException("`gh api " + path + "` failed: is gh installed and signed in "
                        + "(gh auth status)?"));
    }
}
