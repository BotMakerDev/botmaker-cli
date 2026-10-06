package com.botmaker.cli.release;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What a module's diff against its own latest tag amounts to — {@code release.sh}'s {@code change_kind}.
 *
 * <p>Three answers, and the middle one is the reason this is not a boolean:
 *
 * <ul>
 *   <li>{@link #REAL} — something publishable moved, or the module has never been released. Cut a tag.</li>
 *   <li>{@link #DOCS} — commits exist, but every file in them is {@link Relevance#irrelevant}. The tag would
 *       publish a byte-identical artifact, so it is skipped <b>and the reason is said</b>: "no changes"
 *       would be a lie about a module that visibly has commits in it.</li>
 *   <li>{@link #NONE} — HEAD is the tag.</li>
 * </ul>
 *
 * <p><b>Every failure to read the checkout answers {@link #REAL}.</b> A version that cannot be resolved to a
 * ref, a git that would not run: none of that is evidence that nothing changed, and the harmless direction
 * to be wrong in is the one that publishes a duplicate rather than the one that silently omits a change.
 */
public enum ChangeKind {

    REAL, DOCS, NONE;

    /**
     * Reads the module's diff against {@code latest} and classifies it.
     *
     * @param latest the module's newest released version, or empty for a module never released — which is
     *               {@link #REAL} without looking at anything, since there is no tag to diff against.
     */
    public static ChangeKind read(Path umbrella, Module module, Optional<Version> latest) {
        if (latest.isEmpty()) {
            return REAL;                                    // never released -> release it
        }
        Optional<String> ref = Tags.existingRef(umbrella, module, latest.get());
        if (ref.isEmpty()) {
            return REAL;                                    // a tag we cannot resolve is not evidence of nothing
        }
        Git.Result diff = Git.run(umbrella.resolve(module.directory()),
                "diff", "--name-only", ref.get(), "HEAD", "--");
        if (!diff.ok()) {
            return REAL;                                    // same rule: unreadable is not unchanged
        }
        return classify(withoutVersionOnlyPom(umbrella.resolve(module.directory()), ref.get(), diff.lines()));
    }

    /**
     * The changed files, minus {@code pom.xml} when all that moved in it is versions.
     *
     * <p>Every release leaves a back-to-snapshot commit on top of its tag, and a release of an upstream moves
     * the pins of its dependents ({@link PomVersions}). Neither changes what the module publishes — the next
     * release commit writes its own versions anyway — so without this every module ever released would read
     * as changed and be cut again by {@code --all}.
     */
    static List<String> withoutVersionOnlyPom(Path dir, String ref, List<String> changed) {
        if (!changed.contains("pom.xml")) {
            return changed;
        }
        Optional<String> before = Git.capture(dir, "show", ref + ":pom.xml");
        Optional<String> after = Git.capture(dir, "show", "HEAD:pom.xml");
        if (before.isEmpty() || after.isEmpty() || !PomVersions.versionsOnly(before.get(), after.get())) {
            return changed;
        }
        return changed.stream().filter(path -> !path.equals("pom.xml")).toList();
    }

    /**
     * The rule itself, over the file list a diff produced — pure, so the deny-list is testable without a
     * checkout to tag.
     */
    public static ChangeKind classify(List<String> changedFiles) {
        if (changedFiles.isEmpty()) {
            return NONE;
        }
        return changedFiles.stream().allMatch(Relevance::irrelevant) ? DOCS : REAL;
    }
}
