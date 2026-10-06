package com.botmaker.cli.release;

import java.nio.file.Path;

/**
 * The writes that end a module's release — {@code release.sh}'s {@code commit_tag_push}, split in three
 * since 2026-10-06 so the back-to-snapshot commit ({@link PomVersions}) goes between the tag and the push.
 *
 * <p>Commit what the release edited, tag the version, push both. <b>Nothing here can be undone</b>, which is
 * why every gate runs in the decide pass and why {@link Runner} is the only way this package touches a
 * repository.
 *
 * <p>Two details are scars rather than style:
 *
 * <ul>
 *   <li><b>Both the worktree and the index are checked</b> before committing, because
 *       {@code git diff --quiet} cannot see a staged addition. Checking only the worktree is what let the
 *       2026-09-02 release tag three modules without the pin file it had just written.</li>
 *   <li><b>Tagging is idempotent</b> — an existing {@code v<version>} is left alone rather than failing —
 *       because a run that died halfway is re-run, and the second attempt must get past the module that
 *       already succeeded.</li>
 * </ul>
 *
 * <p>An empty message means <i>commit nothing</i>: {@code botmaker-pilot} and {@code botmaker-remote} pass
 * none, having no pom or changelog to edit.
 */
public final class CommitTagPush {

    private CommitTagPush() {
    }

    /**
     * Commit, tag and push in one go — for a module with nothing to do between the tag and the push.
     *
     * @return false when a push failed, as {@link #push} answers
     */
    public static boolean run(Runner runner, Path umbrella, Module module, Version version, String message) {
        commit(runner, umbrella, module, message);
        tag(runner, umbrella, module, version);
        return push(runner, umbrella, module, version);
    }

    /**
     * The release commit, when there is anything to commit.
     *
     * @param message the release commit's subject, or empty to commit nothing
     * @throws ReleaseRefusal when the commit fails: tagging then would tag the commit <i>before</i> the
     *                        release, whose pom names {@code -SNAPSHOT} upstreams nobody can resolve
     */
    public static void commit(Runner runner, Path umbrella, Module module, String message) {
        Path dir = umbrella.resolve(module.directory());
        if (!message.isBlank() && dirty(runner, dir) && !runner.git(dir, "commit", "-am", message).ok()) {
            throw new ReleaseRefusal(module.directory() + ": the release commit failed, so nothing was tagged");
        }
    }

    /** Tags {@code HEAD}, unless the tag exists already. */
    public static void tag(Runner runner, Path umbrella, Module module, Version version) {
        Path dir = umbrella.resolve(module.directory());
        if (!tagged(runner, dir, version)) {
            runner.git(dir, "tag", version.tag());
        }
    }

    /**
     * Pushes the branch and the tag.
     *
     * @return false when a push failed — reported by the caller rather than thrown, because by the time a
     *         push runs the tag may already exist and aborting would leave a release that is done but looks
     *         failed
     */
    public static boolean push(Runner runner, Path umbrella, Module module, Version version) {
        Path dir = umbrella.resolve(module.directory());
        boolean head = runner.git(dir, "push", "origin", "HEAD").ok();
        boolean tag = runner.git(dir, "push", "origin", version.tag()).ok();
        return head && tag;
    }

    /** Whether there is anything to commit — worktree modifications <b>or</b> a staged addition. */
    static boolean dirty(Runner runner, Path dir) {
        if (runner.dryRun()) {
            // A dry run has staged nothing and edited nothing, so asking git would answer "clean" and the
            // commit line would vanish from the plan — the one thing a preview must not do.
            return true;
        }
        return !Git.run(dir, "diff", "--quiet").ok() || !Git.run(dir, "diff", "--cached", "--quiet").ok();
    }

    static boolean tagged(Runner runner, Path dir, Version version) {
        if (runner.dryRun()) {
            return false;
        }
        return Git.run(dir, "rev-parse", "-q", "--verify", "refs/tags/" + version.tag()).ok();
    }
}
