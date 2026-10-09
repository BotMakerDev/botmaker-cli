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
 *   <li><b>Open the registry's pull requests</b> moving each released plugin's {@code verifiedVersion}
 *       ({@link RegistryPin}).</li>
 * </ol>
 */
public final class Release {

    /**
     * @param plan      what was decided
     * @param refusals  the gates that said no; non-empty means nothing was tagged
     * @param log       the release log written, when one was
     * @param pushesOk  whether every branch push succeeded, the registry's pull requests included ({@link
     *                  RegistryPin}) — reported, never fatal
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

        if (runner.stopping()) {
            // Pressed during the gates: nothing is tagged, so there is nothing to log or record either.
            throw new ReleaseRefusal(STOPPED_BY_YOU + " before the first tag — nothing was tagged.");
        }
        LocalDateTime when = LocalDateTime.now();
        java.util.Set<Module> followed = java.util.EnumSet.noneOf(Module.class);
        Chain chain = tagChain(runner, umbrella, releasing, when, followed,
                (module, version, at) -> release(runner, umbrella, module, version, releasing, wait, followed,
                        at));
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

        String pointers = Umbrella.recordPointers(runner, umbrella, releasing, followed, log != null);
        boolean pushed = Umbrella.pushBranches(runner, umbrella);
        // After the verify pass, so the registry's gate resolves a tag JitPack has already built.
        pushed &= RegistryPin.bump(runner, tagged(chain.rows()), when.toLocalDate());

        runner.say("Done. " + (runner.dryRun() ? "(dry run) " : "") + "Released: " + pointers);
        return new Outcome(plan, List.of(), Optional.ofNullable(log), pushed);
    }

    /** The modules the chain tagged, with their versions. */
    static Map<Module, Version> tagged(List<ReleaseLog.Row> rows) {
        Map<Module, Version> tagged = new java.util.EnumMap<>(Module.class);
        rows.stream().filter(row -> row.stage().tagged()).forEach(row -> tagged.put(row.module(), row.version()));
        return tagged;
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
     *
     * <p><b>An {@link AfterTag} stops it the same way, with the row tagged</b>: an owed JitPack wait that
     * failed, timed out or was stopped. And <b>a stop the operator asked for</b> ({@link Runner#stopping})
     * is honoured before each module. Both since 2026-10-05, when the chain tagged plugin-toolkit and
     * plugin-host on top of a studio-api JitPack had failed to build, and could only be stopped by killing it.
     */
    static Chain tagChain(Runner runner, Path umbrella, Map<Module, Version> releasing, LocalDateTime when,
                          Step step) {
        return tagChain(runner, umbrella, releasing, when, java.util.Set.of(), step);
    }

    /**
     * @param followed the dependents outside the release whose pins the steps moved so far, read when the
     *                 chain stops so their pointers are recorded with the tagged modules'
     */
    static Chain tagChain(Runner runner, Path umbrella, Map<Module, Version> releasing, LocalDateTime when,
                          java.util.Set<Module> followed, Step step) {
        List<ReleaseLog.Row> rows = new ArrayList<>(ReleaseLog.rows(releasing));
        Path log = ReleaseLog.write(runner, umbrella, when, rows);
        Map<Module, Version> tagged = new java.util.EnumMap<>(Module.class);
        Map<Module, Version> unpushed = new java.util.EnumMap<>(Module.class);
        String[] at = {""};

        for (int i = 0; i < rows.size(); i++) {
            ReleaseLog.Row row = rows.get(i);
            if (runner.stopping()) {
                // Between two modules, which is the one moment a stop costs nothing: the last tag is out and
                // waited for, the next not started.
                rows.set(i, row.withStage(ReleaseLog.Stage.NOT_REACHED).stoppedAt("start", STOPPED_BY_YOU));
                notReachedAfter(rows, i);
                runner.say("error: " + STOPPED_BY_YOU + " before " + row.module().directory() + " — "
                        + tagged.size() + " of " + rows.size() + " modules were tagged.");
                recordStop(runner, umbrella, when, log, rows, tagged, unpushed, followed);
                throw new ReleaseRefusal(STOPPED_BY_YOU + " before " + row.module().directory() + ". "
                        + tagged.size() + " of " + rows.size() + " modules were tagged.");
            }
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
                java.time.Duration took = java.time.Duration.between(moduleStarted, java.time.Instant.now());
                if (e instanceof AfterTag after) {
                    // Its tag is out, so its row says how far it got and its pointer is recorded.
                    rows.set(i, after.record(row, at[0]).withElapsed(took));
                    tagged.put(row.module(), row.version());
                } else {
                    rows.set(i, row.failed(at[0], e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage()).withElapsed(took));
                    if (e instanceof NotPushed) {
                        unpushed.put(row.module(), row.version());
                    }
                }
                notReachedAfter(rows, i);
                runner.say("error: " + row.module().directory() + " failed at " + at[0]
                        + " — the release stopped here. " + tagged.size() + " of " + rows.size()
                        + " modules were tagged" + (unpushed.isEmpty() ? "." : ", and "
                        + row.module().directory() + " " + row.version().tag() + " is tagged locally only."));
                recordStop(runner, umbrella, when, log, rows, tagged, unpushed, followed);
                throw e;
            }
            if (log != null) {
                runner.write(log, ReleaseLog.render(when, rows));
            }
        }
        return new Chain(log, List.copyOf(rows));
    }

    private static void notReachedAfter(List<ReleaseLog.Row> rows, int i) {
        for (int rest = i + 1; rest < rows.size(); rest++) {
            rows.set(rest, rows.get(rest).withStage(ReleaseLog.Stage.NOT_REACHED));
        }
    }

    /**
     * The log as it stands and the pointers of what was tagged, committed locally; nothing is pushed.
     *
     * @param unpushed the module whose push failed ({@link NotPushed}), if any: tagged and back on its
     *                 snapshot locally, so its dependents follow it and its pointer is staged, while the
     *                 commit subject leaves it out — origin has no tag of it
     */
    private static void recordStop(Runner runner, Path umbrella, LocalDateTime when, Path log,
                                   List<ReleaseLog.Row> rows, Map<Module, Version> tagged,
                                   Map<Module, Version> unpushed, java.util.Set<Module> followed) {
        if (log != null) {
            runner.write(log, ReleaseLog.render(when, rows));
        }
        // A dependent the chain never reached still pins the snapshot its tagged upstream left: move it now,
        // or its main names a version nobody builds until somebody edits the pin by hand.
        // Skip only what was tagged (its own back-to-snapshot set its pins): a dependent one upstream already
        // moved still pins the next one's old snapshot, so each tagged upstream gets its own pin commit. Skipping
        // the moved set too left plugin-basics and the SDK at toolkit 0.3.3-SNAPSHOT on 2026-10-06.
        java.util.Set<Module> moved = java.util.EnumSet.noneOf(Module.class);
        moved.addAll(followed);
        moved.addAll(unpushed.keySet());
        Map<Module, Version> local = new java.util.EnumMap<>(tagged);
        local.putAll(unpushed);
        for (Map.Entry<Module, Version> done : local.entrySet()) {
            moved.addAll(PomVersions.follow(runner, umbrella, done.getKey(), done.getValue(), local.keySet()));
        }
        Umbrella.recordStopped(runner, umbrella, tagged, moved, log != null);
    }

    /**
     * One module: the release commit ({@link #prepare}), its tag, the back-to-snapshot commit, the push, its
     * dependents' pins, and the wait for its JitPack build.
     *
     * <p><b>The tag goes on the release commit and the push carries both</b>, so origin never sees
     * {@code main} at a release version, and a tag's pom names the versions it was built against
     * (umbrella {@code docs/refactor/43-real-versions.md}).
     *
     * <p><b>A failed push stops the release</b>, since 2026-09-16. It used to be returned and ignored, which
     * is safe for nothing downstream: every module tagged after this one pins its tag, and a tag that is not
     * on origin is one no CI can check out.
     *
     * @param followed collects the dependents outside this release whose pins moved, so the umbrella records
     *                 their pointers too
     */
    private static ReleaseLog.Stage release(Runner runner, Path umbrella, Module module, Version version,
                                            Map<Module, Version> releasing, boolean wait,
                                            java.util.Set<Module> followed,
                                            java.util.function.Consumer<String> at) {
        runner.say("Releasing " + module.directory() + " " + version.tag());
        // An APK has no CHANGELOG.md and nothing else to commit, so it takes no message — as the pilot has
        // since the stamp arrived and the other three stopped passing an empty one. The question is what a
        // release COMMITS and not what it stamps: a template has no changelog either, and a pom pin to
        // rewrite, so conditioning this on hasChangelog() would have tagged the bump without committing it.
        String message = module.commitsOnRelease()
                ? "release: " + module.shortName() + " " + version.tag() : "";
        try {
            prepare(runner, umbrella, module, version, releasing, at);
            at.accept("commit and tag");
            CommitTagPush.commit(runner, umbrella, module, message);
        } catch (RuntimeException e) {
            // Nothing is committed yet: put the snapshot back, so a stopped release leaves main building at
            // its -SNAPSHOT rather than at a version that was never published.
            PomVersions.restore(runner, umbrella, module, false);
            throw e;
        }
        try {
            CommitTagPush.tag(runner, umbrella, module, version);
            at.accept("back to snapshot");
            PomVersions.backToSnapshot(runner, umbrella, module, version, releasing);
        } catch (RuntimeException e) {
            // The release commit is made and nothing is pushed: the working tree goes back to the snapshot
            // that commit replaced, and the operator decides about the local commit and tag.
            PomVersions.restore(runner, umbrella, module, true);
            throw new ReleaseRefusal(module.directory() + ": " + e.getMessage() + "\n     The release commit"
                    + " is local and nothing was pushed; pom.xml is back at its -SNAPSHOT in the working tree.");
        }
        at.accept("push");
        Proc.Result pushed = CommitTagPush.push(runner, umbrella, module, version);
        if (!pushed.ok()) {
            List<String> said = pushed.lines();
            throw new NotPushed(module.directory() + ": pushing " + version.tag() + " failed:\n"
                    + said.subList(Math.max(0, said.size() - 5), said.size()).stream()
                    .map(line -> "       " + line).reduce((a, b) -> a + "\n" + b).orElse("       (git said nothing)")
                    + "\n     The tag and the back-to-snapshot commit are local; once git can push, run"
                    + " git -C " + module.directory() + " push origin HEAD " + version.tag() + ".\n"
                    + "     Every module after it pins that tag, so the release stops here.");
        }
        at.accept("dependents' pins");
        followed.addAll(PomVersions.follow(runner, umbrella, module, version, releasing.keySet()));
        if (module == Module.STUDIO) {
            runner.say("botmaker-studio " + version.tag()
                    + " tagged — last, so every tag its package matrix checks out is already on origin.");
        }
        return waitFor(runner, module, version, releasing, wait, at);
    }

    /**
     * The release commit's edits, in the script's order: the pom's versions first, then the constants a
     * module holds about other modules, then the changelog heading. Every one lands in <i>this module's</i>
     * release commit, so all of them happen before {@link CommitTagPush}.
     */
    private static void prepare(Runner runner, Path umbrella, Module module, Version version,
                                Map<Module, Version> releasing, java.util.function.Consumer<String> at) {
        at.accept("pom versions");
        PomVersions.release(runner, umbrella, module, version, releasing);
        if (module == Module.STUDIO) {
            // What a freshly generated bot's pom pins, which is Studio's source and not Studio's dependency.
            at.accept("fallback versions");
            Fallback.bump(runner, umbrella, releasing);
        }
        if (module == Module.PLUGIN_ARCHETYPE) {
            // The third door: what a generated PLUGIN pins, which is the archetype's descriptor defaults.
            at.accept("archetype defaults");
            ArchetypePin.bump(runner, umbrella, releasing);
        }
        if (module.template()) {
            // The other path into a project: what a bot copied FROM A TEMPLATE pins, which is the template's
            // own pom. Same sentence as the line above, about the other door.
            at.accept("template pins");
            TemplatePin.bump(runner, umbrella, module, releasing);
            Version sdk = releasing.get(Module.SDK);
            if (sdk != null) {
                // The pin moved, so the thing the decide pass compiled is not the thing about to be tagged.
                // This is the last moment a refusal costs nothing: Order.TAG puts the templates last.
                TemplateGate.afterBump(runner, umbrella, module, sdk);
            }
        }
        // Silent for every module without the property, which is nine of the eleven.
        at.accept("japicmp baseline");
        Japicmp.bump(runner, umbrella, module);
        at.accept("changelog stamp");
        Stamp.changelog(runner, umbrella, module, version);
    }

    /** The JitPack wait a later module is owed, or none. */
    private static ReleaseLog.Stage waitFor(Runner runner, Module module, Version version,
                                            Map<Module, Version> releasing, boolean wait,
                                            java.util.function.Consumer<String> at) {
        if (wait && ReleaseLog.onJitpack(module) && !Waits.owed(module, releasing.keySet())) {
            // Still TAGGED, which is true: the verify pass fills its JitPack cell exactly as before.
            runner.say(Waits.notWaiting(module, version));
        } else if (wait && ReleaseLog.onJitpack(module)) {
            at.accept("jitpack wait");
            Jitpack.Waited waited = Jitpack.waitFor(runner, module, version, Jitpack.Sleeper.real(runner::stopping));
            // The wait is owed, so a later module resolves this one from JitPack: tagging it now would start a
            // build that cannot find its upstream, and JitPack caches that result per tag.
            String tag = module.directory() + " " + version.tag();
            switch (waited.build()) {
                case BUILT -> {
                    return ReleaseLog.Stage.BUILT;
                }
                case FAILED -> throw new AfterTag(ReleaseLog.Stage.JITPACK_FAILED, waited.detail(),
                        "JitPack failed to build " + tag + ", and a later module resolves it, so the release"
                                + " stopped here. A new tag of " + module.directory() + " repairs it.");
                case TIMEOUT -> throw new AfterTag(ReleaseLog.Stage.TIMEOUT, "",
                        "JitPack had not built " + tag + " after 10 minutes, and a later module resolves it,"
                                + " so the release stopped here.");
                case STOPPED -> throw new AfterTag(ReleaseLog.Stage.TAGGED, "",
                        STOPPED_BY_YOU + " while waiting for JitPack to build " + tag + ".");
            }
        }
        return ReleaseLog.Stage.TAGGED;
    }

    static final String STOPPED_BY_YOU = "stopped by you";

    /**
     * A module whose release commit, tag and back-to-snapshot commit are made and whose push failed.
     *
     * <p>Its row says {@code FAILED} at {@code push}, which is what origin sees: no tag. But its {@code main}
     * is at the next snapshot already, so {@link #tagChain} still moves its dependents' pins and records its
     * pointer. On 2026-10-07 it did neither: cli's {@code main} went to 0.2.2-SNAPSHOT, the dashboard kept
     * pinning 0.2.1-SNAPSHOT, and {@link VersionsGate} refused the dashboard from then on.
     */
    static final class NotPushed extends ReleaseRefusal {

        NotPushed(String message) {
            super(message);
        }
    }

    /**
     * A module that was tagged and pushed, and still stops the chain — its JitPack build failed, timed out, or
     * the operator stopped the wait.
     *
     * <p>Its own type because the tag is out: {@link #tagChain} records this row with its {@code stage} and its
     * pointer, where any other exception means the module never reached its tag.
     *
     * @param stage   what the row says
     * @param jitpack JitPack's own message, for the row's JitPack error; empty when there is none
     */
    static final class AfterTag extends ReleaseRefusal {

        private final ReleaseLog.Stage stage;
        private final String jitpack;

        AfterTag(ReleaseLog.Stage stage, String jitpack, String message) {
            super(message);
            this.stage = stage;
            this.jitpack = jitpack;
        }

        ReleaseLog.Row record(ReleaseLog.Row row, String step) {
            ReleaseLog.Row stopped = row.withStage(stage).stoppedAt(step, getMessage());
            return jitpack.isBlank() ? stopped : stopped.withJitpack("BROKEN", jitpack);
        }
    }
}
