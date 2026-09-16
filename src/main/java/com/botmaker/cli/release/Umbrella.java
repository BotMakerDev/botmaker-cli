package com.botmaker.cli.release;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The two writes that belong to the umbrella rather than to any module — {@code release.sh}'s pointer
 * commit and {@code push_branch}.
 *
 * <p><b>The pointer commit carries the release log in the same commit</b>, and for the same reason the
 * pointers are there at all: <i>what was released</i> and <i>whether it landed</i> are one fact, and a log
 * committed separately is a log somebody forgets to commit.
 *
 * <p><b>{@code push_branch} exists because a release that tags remote refs while leaving the branch behind
 * them local is half a release.</b> {@link CommitTagPush} pushes only the modules being released; the
 * umbrella's own pointer commit was never pushed at all, which is how the umbrella ended up 33 commits
 * ahead of {@code origin/main} with every pointer bump since 2026-08 sitting locally.
 *
 * <p><b>Order is the caller's job and it is not decorative</b>: submodules first, umbrella last, because the
 * pointer commit names submodule commits the remote must already have.
 *
 * <p><b>Nothing here is fatal.</b> By the time it runs every tag is pushed and every CI job is going, so
 * aborting would leave a release that is done and looks failed. It reports, exactly as
 * {@link CleanRoom} does.
 */
public final class Umbrella {

    /** {@code botmaker-gallery} is deliberately absent: data-only, never released, not a release's business. */
    private static final List<Module> PUSHABLE = Order.DECIDE;

    private Umbrella() {
    }

    /**
     * Stages every released module's new pointer plus {@code releases/}, and commits them together.
     *
     * @return the commit subject's module list, which is also what the run's last line announces
     */
    public static String recordPointers(Runner runner, Path umbrella, Map<Module, Version> released,
                                        boolean withLog) {
        runner.say("Recording submodule pointers in the umbrella");
        StringBuilder pointers = new StringBuilder();
        for (Module module : Order.DECIDE) {
            Version version = released.get(module);
            if (version != null) {
                runner.git(umbrella, "add", module.directory());
                pointers.append(module.pointerName()).append(' ').append(version.tag()).append(' ');
            }
        }
        if (withLog) {
            runner.git(umbrella, "add", "releases");
        }
        String subject = pointers.toString().stripTrailing();
        if (!subject.isEmpty() && staged(runner, umbrella)) {
            runner.git(umbrella, "commit", "-m", "release: " + subject);
        }
        return subject;
    }

    /**
     * Pushes every branch that is ahead of its upstream — submodules first, then the umbrella.
     *
     * @return false when a push failed, so the caller can say so without stopping
     */
    public static boolean pushBranches(Runner runner, Path umbrella) {
        runner.say("Pushing branches that are ahead of origin");
        boolean ok = true;
        for (Module module : PUSHABLE) {
            ok &= pushBranch(runner, umbrella.resolve(module.directory()), module.directory());
        }
        // Last, because the pointer commit names submodule commits that must be on origin first.
        return ok & pushBranch(runner, umbrella, "umbrella");
    }

    /**
     * <b>A dry run inspects for real and pushes nothing.</b> Every step down to the push is a read — the
     * branch, its upstream, the commit count — so answering {@code would push X if it is ahead} instead, as
     * this did until 2026-09-16, threw away the one number the operator wants from a preview and made the
     * port's output differ from the script's over a question neither had to guess at. The push itself goes
     * through {@link Runner#git}, which echoes it and runs nothing.
     */
    static boolean pushBranch(Runner runner, Path dir, String label) {
        String branch = Git.capture(dir, "symbolic-ref", "--quiet", "--short", "HEAD").orElse("");
        if (branch.isBlank()) {
            runner.say("  " + label + ": detached HEAD — nothing pushed");
            return true;
        }
        // The configured upstream first, then origin/<branch>. With neither, this is a branch origin has
        // never seen, and pushing it mid-release would be a surprise.
        String against = Git.capture(dir, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}")
                .orElseGet(() -> Git.run(dir, "rev-parse", "-q", "--verify",
                        "refs/remotes/origin/" + branch).ok() ? "origin/" + branch : "");
        if (against.isBlank()) {
            runner.say("  " + label + ": '" + branch + "' is not on origin — not pushed");
            return true;
        }
        // No fetch: the remote-tracking ref is already fresh for anything this run touched, and a stale one
        // can only over-report — the push then no-ops.
        long ahead = Git.capture(dir, "rev-list", "--count", against + "..HEAD")
                .map(String::strip).filter(count -> !count.isEmpty())
                .map(Long::parseLong).orElse(0L);
        if (ahead == 0) {
            return true;
        }
        runner.say("  " + label + ": '" + branch + "' is " + ahead + " commit(s) ahead of " + against);
        if (runner.git(dir, "push", "origin", branch).ok()) {
            return true;
        }
        runner.say("warn: " + label + ": pushing '" + branch + "' failed (a non-fast-forward means origin"
                + " moved under us).");
        runner.say("warn: everything else in this release is already published — resolve it by hand:");
        runner.say("warn:     git -C " + dir + " pull --rebase && git -C " + dir + " push origin " + branch);
        return false;
    }

    private static boolean staged(Runner runner, Path umbrella) {
        // A dry run has staged nothing, so asking git would drop the commit line from the plan.
        return runner.dryRun() || !Git.run(umbrella, "diff", "--cached", "--quiet").ok();
    }
}
