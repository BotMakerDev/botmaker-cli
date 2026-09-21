package com.botmaker.cli.release;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A whole release, from the decide pass to the pushed branches — the top half of {@code release.sh}'s main
 * body, as one method.
 *
 * <p><b>There is one path, and {@code --dry-run} is a {@link Runner}, not a branch through it.</b> A preview
 * decides, gates and computes exactly what a real run does and echoes the commands instead of running them.
 * That is what makes the preview worth trusting: a separate preview implementation is free to drift from the
 * release, and the drift is discovered as a tag, which cannot be edited.
 *
 * <p>The order is the script's and every step of it is load-bearing:
 *
 * <ol>
 *   <li><b>Decide everything up front</b> ({@link Plan}), so the tag order is free of the decisions and a
 *       refusal happens while nothing is pushed.</li>
 *   <li><b>Gate</b>, still before the first tag.</li>
 *   <li><b>Write the log before the first tag</b>, every row pending, and keep it current as each module is
 *       tagged — a log that appears only at the end is missing in exactly the case worth recording.</li>
 *   <li><b>Tag in {@link Order#TAG}</b> — the pilot first, Studio last. A module that throws stops the chain
 *       and is recorded, with the tagged modules' pointers, before the exception leaves
 *       ({@link #tagChain}).</li>
 *   <li><b>Verify, then record the pointers and push the branches</b>, umbrella last.</li>
 * </ol>
 */
public final class Release {

    /**
     * @param plan      what was decided
     * @param refusals  the gates that said no; non-empty means nothing was tagged
     * @param log       the release log written, when one was
     * @param pushesOk  whether every branch push succeeded — reported, never fatal
     */
    public record Outcome(Plan plan, List<GateVerdict> refusals, Optional<Path> log, boolean pushesOk) {

        public boolean refused() {
            return !refusals.isEmpty();
        }
    }

    private Release() {
    }

    /**
     * @param wait whether to block on each JitPack build before tagging the next module. Costs a few
     *             minutes per link and is worth it: a build result is cached per tag, so losing the race
     *             burns a tag that cannot be reused (see {@link Jitpack}).
     */
    public static Outcome run(Runner runner, Path umbrella, Map<Module, String> requested,
                              boolean force, boolean wait, boolean why) {
        // From here rather than from the first tag: "how long does a release take" is asked by somebody
        // about to start one, and the decide pass and the gates are part of the wait.
        java.time.Instant started = java.time.Instant.now();
        Plan plan = Plan.decide(umbrella, requested, force);

        runner.say("Release plan:");
        plan.planLines().forEach(runner::say);
        runner.say("Deciding what to release:");
        plan.decisionLines().forEach(runner::say);
        if (why) {
            List<String> forcing = plan.forcingLines();
            if (!forcing.isEmpty()) {
                runner.say("Forced into this release:");
                forcing.forEach(runner::say);
            }
        }

        Map<Module, Version> releasing = plan.releasing();
        if (releasing.isEmpty()) {
            runner.say("Nothing to release.");
            return new Outcome(plan, List.of(), Optional.empty(), true);
        }

        runner.say("Gates:");
        List<GateVerdict> refusals = Gates.run(runner, umbrella, plan, force);
        if (!refusals.isEmpty()) {
            // Refused with nothing pushed, which is the only time a refusal is worth anything.
            return new Outcome(plan, refusals, Optional.empty(), true);
        }

        LocalDateTime when = LocalDateTime.now();
        Chain chain = tagChain(runner, umbrella, releasing, when,
                (module, version, at) -> release(runner, umbrella, module, version, releasing, wait, at));
        Path log = chain.log();

        if (log == null && runner.dryRun()) {
            // A dry run writes no log, so the verify loop below has nothing to fill in. Naming the artifacts
            // it would have resolved still matters: this is the pass that catches a published pom declaring
            // a dependency nobody can resolve, and a preview that simply stops here reads as if it does not
            // run at all.
            String artifacts = ReleaseLog.rows(releasing).stream()
                    .filter(row -> ReleaseLog.onJitpack(row.module()))
                    .map(row -> row.module().directory() + ":" + row.version().tag())
                    .collect(Collectors.joining(" "));
            runner.say("    (dry-run) would verify on JitPack: "
                    + (artifacts.isEmpty() ? "nothing (no Maven artifact in this release)" : artifacts));
        }
        if (log != null) {
            // Every tag is pushed by now, so this blocks nothing: it fills the log's columns in.
            java.time.Instant verifyStarted = java.time.Instant.now();
            List<ReleaseLog.Row> polled = VerifyPass.run(runner, chain.rows(),
                    (own, row) -> row.stage().tagged() ? VerifyPass.verify(own, row) : row);
            ReleaseLog.Timing timing = new ReleaseLog.Timing(
                    ReleaseLog.elapsed(java.time.Duration.between(verifyStarted, java.time.Instant.now())),
                    ReleaseLog.elapsed(java.time.Duration.between(started, java.time.Instant.now())));
            runner.write(log, ReleaseLog.render(when, polled, timing));
            runner.say("Timing: verify pass " + timing.verifyPass() + " · total " + timing.total());
        }

        String pointers = Umbrella.recordPointers(runner, umbrella, releasing, log != null);
        boolean pushed = Umbrella.pushBranches(runner, umbrella);

        runner.say("Done. " + (runner.dryRun() ? "(dry run) " : "") + "Released: " + pointers);
        return new Outcome(plan, List.of(), Optional.ofNullable(log), pushed);
    }

    /** What the tag chain left: the log it kept (null on a dry run) and every row's final stage. */
    record Chain(Path log, List<ReleaseLog.Row> rows) {
    }

    /** One module's release; says which step it is on through {@code at}, and answers how far it got. */
    @FunctionalInterface
    interface Step {
        ReleaseLog.Stage release(Module module, Version version, java.util.function.Consumer<String> at);
    }

    /**
     * Tags every module in {@link Order#TAG}, keeping the release log current after each one.
     *
     * <p><b>The log exists before the first tag</b>, every row {@code pending}, and is rewritten as each
     * module finishes. <b>If a module throws</b>, its row becomes {@code FAILED} with the step and the
     * message, every row after it {@code not reached}, and the log is committed with the pointers of the
     * modules that <i>were</i> tagged — then the exception goes on to the caller. Nothing is pushed on that
     * path: the operator is about to look at what went wrong, and the one commit they need to see is local.
     *
     * <p>That is the 2026-09-16 release, recorded: four tags pushed, the window gone, and no log and no
     * pointer commit, so the only evidence of what had been cut was each repository's tag list.
     */
    static Chain tagChain(Runner runner, Path umbrella, Map<Module, Version> releasing, LocalDateTime when,
                          Step step) {
        List<ReleaseLog.Row> rows = new ArrayList<>(ReleaseLog.rows(releasing));
        Path log = ReleaseLog.write(runner, umbrella, when, rows);
        Map<Module, Version> tagged = new java.util.EnumMap<>(Module.class);
        String[] at = {""};

        for (int i = 0; i < rows.size(); i++) {
            ReleaseLog.Row row = rows.get(i);
            at[0] = "start";
            java.time.Instant moduleStarted = java.time.Instant.now();
            try {
                ReleaseLog.Stage stage = step.release(row.module(), row.version(), current -> at[0] = current);
                rows.set(i, row.withStage(stage)
                        .withElapsed(java.time.Duration.between(moduleStarted, java.time.Instant.now())));
                if (stage.tagged()) {
                    tagged.put(row.module(), row.version());
                }
            } catch (RuntimeException e) {
                // Timed too: how long a module ran before it threw is the first thing asked about a release
                // that stopped, and it is gone the moment the terminal is closed.
                rows.set(i, row.failed(at[0], e.getMessage() == null ? e.getClass().getSimpleName()
                                : e.getMessage())
                        .withElapsed(java.time.Duration.between(moduleStarted, java.time.Instant.now())));
                for (int rest = i + 1; rest < rows.size(); rest++) {
                    rows.set(rest, rows.get(rest).withStage(ReleaseLog.Stage.NOT_REACHED));
                }
                runner.say("error: " + row.module().directory() + " failed at " + at[0]
                        + " — the release stopped here. " + tagged.size() + " of " + rows.size()
                        + " modules were tagged.");
                if (log != null) {
                    runner.write(log, ReleaseLog.render(when, rows));
                }
                Umbrella.recordStopped(runner, umbrella, tagged, log != null);
                throw e;
            }
            if (log != null) {
                runner.write(log, ReleaseLog.render(when, rows));
            }
        }
        return new Chain(log, List.copyOf(rows));
    }

    /**
     * One module: its pins, the constants it holds about other modules, its changelog heading, its tag, and
     * the wait for its JitPack build.
     *
     * <p>The two source edits between the pins and the stamp are the script's order and it is the only one
     * that works: both land in <i>this module's</i> release commit, so they have to happen before
     * {@link CommitTagPush} and after the {@code .deps.env} they sit beside.
     *
     * <p><b>A failed push stops the release</b>, since 2026-09-16. It used to be returned and ignored, which
     * is safe for nothing downstream: every module tagged after this one pins its tag, and a tag that is not
     * on origin is one no CI can check out.
     */
    private static ReleaseLog.Stage release(Runner runner, Path umbrella, Module module, Version version,
                                            Map<Module, Version> releasing, boolean wait,
                                            java.util.function.Consumer<String> at) {
        runner.say("Releasing " + module.directory() + " " + version.tag());
        if (DepsEnv.writes(module)) {
            at.accept(".deps.env");
            DepsEnv.write(runner, umbrella, module, releasing);
        }
        if (module == Module.STUDIO) {
            // What a freshly generated bot's pom pins, which is Studio's source and not Studio's dependency.
            at.accept("fallback versions");
            Fallback.bump(runner, umbrella, releasing);
        }
        if (module.template()) {
            // The other path into a project: what a bot copied FROM A TEMPLATE pins, which is the template's
            // own pom. Same sentence as the line above, about the other door.
            at.accept("template pins");
            TemplatePin.bump(runner, umbrella, module, releasing);
        }
        // Silent for every module without the property, which is nine of the eleven.
        at.accept("japicmp baseline");
        Japicmp.bump(runner, umbrella, module);
        at.accept("changelog stamp");
        Stamp.changelog(runner, umbrella, module, version);
        // An APK has no CHANGELOG.md and nothing else to commit, so it takes no message — as the pilot has
        // since the stamp arrived and the other three stopped passing an empty one. The question is what a
        // release COMMITS and not what it stamps: a template has no changelog either, and a pom pin to
        // rewrite, so conditioning this on hasChangelog() would have tagged the bump without committing it.
        String message = module.commitsOnRelease()
                ? "release: " + module.shortName() + " " + version.tag() : "";
        at.accept("commit, tag and push");
        if (!CommitTagPush.run(runner, umbrella, module, version, message)) {
            throw new ReleaseRefusal(module.directory() + ": pushing " + version.tag() + " failed. Every"
                    + " module after it pins that tag, so the release stops here.");
        }
        if (module == Module.STUDIO) {
            runner.say("botmaker-studio " + version.tag()
                    + " tagged — last, so every tag its package matrix checks out is already on origin.");
        }
        if (wait && ReleaseLog.onJitpack(module) && !Waits.owed(module, releasing.keySet())) {
            // Still TAGGED, which is true: the verify pass fills its JitPack cell exactly as before.
            runner.say(Waits.notWaiting(module, version));
        } else if (wait && ReleaseLog.onJitpack(module)) {
            at.accept("jitpack wait");
            return Jitpack.waitFor(runner, module, version, Jitpack.Sleeper.real())
                    ? ReleaseLog.Stage.BUILT : ReleaseLog.Stage.TIMEOUT;
        }
        return ReleaseLog.Stage.TAGGED;
    }
}
