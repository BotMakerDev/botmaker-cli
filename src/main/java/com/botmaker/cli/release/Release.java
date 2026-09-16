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
 *   <li><b>Tag in {@link Order#TAG}</b> — the two longest CI jobs first, so they run while the JitPack chain
 *       is still going.</li>
 *   <li><b>Write the log the moment the last tag is pushed</b>, verdicts pending, <i>before</i> the polling
 *       — a log that only appears after the poll is missing in exactly the case worth recording.</li>
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

        for (Map.Entry<Module, Version> cut : releasing.entrySet()) {
            release(runner, umbrella, cut.getKey(), cut.getValue(), releasing, wait);
        }

        LocalDateTime when = LocalDateTime.now();
        Path log = ReleaseLog.write(runner, umbrella, when, ReleaseLog.rows(releasing));

        if (log == null && runner.dryRun()) {
            // A dry run writes no log, so the verify loop below has nothing to fill in. Naming the artifacts
            // it would have resolved still matters: this is the pass that catches a published pom declaring
            // a dependency nobody can resolve, and a preview that simply stops here reads as if it does not
            // run at all.
            runner.say("    (dry-run) would verify on JitPack: " + ReleaseLog.rows(releasing).stream()
                    .filter(row -> ReleaseLog.onJitpack(row.module()))
                    .map(row -> row.module().directory() + ":" + row.version().tag())
                    .collect(Collectors.joining(" ")));
        }
        if (log != null) {
            // Every tag is pushed by now, so this blocks nothing: it fills the log's columns in.
            List<ReleaseLog.Row> polled = new ArrayList<>();
            for (ReleaseLog.Row row : ReleaseLog.rows(releasing)) {
                ReleaseLog.Row done = row;
                if (ReleaseLog.onJitpack(row.module())) {
                    Optional<String> broken = CleanRoom.resolve(runner, row.module(), row.version());
                    done = broken.isPresent()
                            ? done.withJitpack("BROKEN", broken.get())
                            : done.withJitpack("ok (resolves clean)", "");
                }
                Actions.Poll actions = Actions.poll(row.module(), row.version());
                polled.add(done.withActions(actions.verdict(), actions.error()));
            }
            runner.write(log, ReleaseLog.render(when, polled));
        }

        String pointers = Umbrella.recordPointers(runner, umbrella, releasing, log != null);
        boolean pushed = Umbrella.pushBranches(runner, umbrella);

        runner.say("Done. " + (runner.dryRun() ? "(dry run) " : "") + "Released: " + pointers);
        return new Outcome(plan, List.of(), Optional.ofNullable(log), pushed);
    }

    /**
     * One module: its pins, the constants it holds about other modules, its changelog heading, its tag, and
     * the wait for its JitPack build.
     *
     * <p>The two source edits between the pins and the stamp are the script's order and it is the only one
     * that works: both land in <i>this module's</i> release commit, so they have to happen before
     * {@link CommitTagPush} and after the {@code .deps.env} they sit beside.
     */
    private static void release(Runner runner, Path umbrella, Module module, Version version,
                                Map<Module, Version> releasing, boolean wait) {
        runner.say("Releasing " + module.directory() + " " + version.tag());
        if (DepsEnv.writes(module)) {
            DepsEnv.write(runner, umbrella, module, releasing);
        }
        if (module == Module.STUDIO) {
            // What a freshly generated bot's pom pins, which is Studio's source and not Studio's dependency.
            Fallback.bump(runner, umbrella, releasing);
        }
        // Silent for every module without the property, which is nine of the eleven.
        Japicmp.bump(runner, umbrella, module);
        Stamp.changelog(runner, umbrella, module, version);
        // The pilot has no CHANGELOG.md and nothing else to commit, so it takes no message — as it has
        // since the stamp arrived and the other three stopped passing an empty one.
        String message = module == Module.PILOT ? ""
                : "release: " + module.shortName() + " " + version.tag();
        CommitTagPush.run(runner, umbrella, module, version, message);
        if (module == Module.STUDIO) {
            // Said because it is the one tag that buys time rather than costing it: Studio heads the order
            // precisely so its per-OS package matrix runs while the chain below is still being cut.
            runner.say("botmaker-studio " + version.tag()
                    + " tagged — its package matrix runs in parallel with the chain below.");
        }
        if (wait && ReleaseLog.onJitpack(module)) {
            Jitpack.waitFor(runner, module, version, Jitpack.Sleeper.real());
        }
    }
}
