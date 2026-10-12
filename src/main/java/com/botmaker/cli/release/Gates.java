package com.botmaker.cli.release;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every gate a release runs, in the order {@link GatePlan} places them.
 *
 * <p><b>All of them run even after one refuses</b>, and the exit code is unchanged either way. The script
 * stops at its first {@code die}; this reports the whole list, because the operator is about to go and fix
 * something and a second refusal discovered on the next run is a second round trip. A refusal is still a
 * refusal — nothing is tagged.
 */
public final class Gates {

    private Gates() {
    }

    /**
     * @param plan the decide pass's answer. The whole plan rather than just {@link Plan#releasing()},
     *             because {@link ForcingGate} asks what was <i>requested</i> — a question a map of what is
     *             being cut cannot answer.
     * @return the refusals, empty when the release may proceed
     */
    public static List<GateVerdict> run(Runner runner, Path umbrella, Plan plan, boolean force) {
        Map<Module, Version> releasing = plan.releasing();
        Set<Module> modules = releasing.keySet();
        List<GateVerdict> refusals = new ArrayList<>();

        // First, because it is about the plan itself rather than about any module's contents, and because
        // an operator who has to add a flag would rather learn that before Maven runs a test.
        record(runner, refusals, ForcingGate.check(plan, force));
        // Before anything reads a file: a module red on main is the refusal the operator can least fix
        // from here, so it is the one worth hearing first.
        for (Module module : GatePlan.ci(modules)) {
            record(runner, refusals, CiGate.check(module, force));
        }
        // Beside CI and as cheap: a request per pin. A tag pinning a release JitPack never built is as broken
        // as one cut from a red main, and the fix is a flag (2026-10-12, shared v0.2.4).
        record(runner, refusals, PinsResolveGate.check(umbrella, releasing, force));
        if (GatePlan.fallbackVersions(modules)) {
            record(runner, refusals, FallbackVersionsGate.check(umbrella, releasing, force));
        }
        for (Module module : GatePlan.changelog(modules)) {
            record(runner, refusals, ChangelogGate.check(umbrella, module, releasing.get(module), force));
        }
        for (Module module : GatePlan.ciDeps(modules)) {
            record(runner, refusals, CiDepsGate.check(umbrella, module, force));
            record(runner, refusals, VersionsGate.check(umbrella, module, force));
        }
        // A build per module whose tag pins an upstream outside this release: slower than the reads above,
        // cheaper than the SDK's and the templates' below, and the one check of what the tag compiles against
        // (2026-10-12: SDK v1.4.3 needed a shared method no shared release had).
        for (GateVerdict verdict : BuildsAtPinsGate.check(umbrella, releasing, force)) {
            record(runner, refusals, verdict);
        }
        for (Module module : GatePlan.jitpackPlugins(modules)) {
            GateVerdict script = JitpackScriptGate.check(umbrella, module, force);
            record(runner, refusals, script);
            record(runner, refusals,
                    JitpackPluginsGate.check(umbrella, module, force, JitpackScriptGate.ceiling(script)));
        }
        if (GatePlan.sdkGates(modules)) {
            record(runner, refusals, SdkGates.apiPointers(umbrella, releasing.get(Module.SDK), force));
            record(runner, refusals, SdkGates.sdkPlugin(umbrella, force));
        }
        // After the SDK gates, because the SDK is what a template pins: asking whether the templates still
        // compile is worth more once the thing they compile against has answered for itself.
        for (String directory : GatePlan.templates(modules)) {
            // A template cut without the SDK is compiled at the SDK tag its own tag will pin, not at the pin it
            // replaces (TemplatePin.targets); one cut with the SDK waits for that SDK's tag (afterBump).
            boolean cut = modules.stream().anyMatch(m -> m.template() && m.directory().equals(directory));
            record(runner, refusals, GatePlan.pinMoves(directory, modules)
                    ? TemplateGate.deferred(directory)
                    : TemplateGate.check(umbrella, directory, force, cut
                            ? Optional.ofNullable(TemplatePin.targets(umbrella, releasing).get(Module.SDK))
                            : Optional.empty()));
        }
        return List.copyOf(refusals);
    }

    private static void record(Runner runner, List<GateVerdict> refusals, GateVerdict verdict) {
        if (verdict.stops()) {
            refusals.add(verdict);
        } else if (!verdict.line().isBlank()) {
            runner.say(verdict.line());
        }
    }
}
