package com.botmaker.cli.project;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The newest git tag on a working copy.
 *
 * <p>Here rather than in {@code Poms} because it answers a different question about the same directory, and
 * the difference is the whole point of asking it: <b>JitPack serves an artifact under the git tag, whatever
 * the pom's {@code <version>} says</b> (this project's own poms agree with their tags only since 2026-10-06,
 * umbrella doc 43; a plugin author's need not). A pom saying {@code 1.0.0} under a tag {@code v1.0.0} resolves by the accident of the two
 * matching; a pom saying {@code 0.1.0-SNAPSHOT} — which is what {@code botmaker new} generates — resolves
 * for nobody.
 *
 * <p>Every failure answers {@link Optional#empty()} and none of them is exceptional: not a git repository,
 * no tag yet, no {@code git} on the PATH. The caller has a pom version to fall back to and a refusal to make
 * if that turns out to be a snapshot, so a missing tag is a fact rather than an error.
 */
public final class Tags {

    private Tags() {
    }

    /** The newest tag reachable from {@code HEAD}, or empty when there is not one to have. */
    public static Optional<String> newest(Path dir) {
        List<String> argv = List.of("git", "describe", "--tags", "--abbrev=0");
        try {
            Process process = new ProcessBuilder(argv)
                    .directory(dir.toFile())
                    .redirectErrorStream(false)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String out;
            try (var stdout = process.getInputStream()) {
                out = new String(stdout.readAllBytes()).trim();
            }
            if (process.waitFor() != 0 || out.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(out);
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
