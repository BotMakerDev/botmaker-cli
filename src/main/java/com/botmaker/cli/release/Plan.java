package com.botmaker.cli.release;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The decide pass: what a set of flags would actually release — {@code release.sh}'s {@code resolve_version}
 * loop, its {@code Release plan:} block and its {@code decide} calls, assembled.
 *
 * <p><b>Every decision is taken here, before anything is tagged, and that ordering is the point.</b>
 * {@code should_release} used to be evaluated inline, immediately before each module was tagged, which
 * forced the tag order to equal the decision order and put Studio — the longest job in the release — last,
 * behind three JitPack waits. Deciding up front frees {@link Order#TAG}. It also means a refusal happens
 * while nothing has been pushed, which is the only time a refusal is worth anything: a pushed tag cannot be
 * edited.
 *
 * <p><b>A skipped module's version is cleared</b>, so the forcing rules, the {@code .deps.env} pins and the
 * pointer commit all see the final answer rather than the requested one.
 *
 * <p>This class computes and prints; it writes nothing. Handing its {@link #releasing()} to the writers is
 * the caller's job, and it is what keeps a preview honest — the plan is produced by the same pass either
 * way.
 */
public final class Plan {

    /** The label and parenthetical each module gets in the plan block, spaced as the script spaces them. */
    // Map.ofEntries rather than Map.of: there are fifteen modules and Map.of stops at ten pairs.
    // Package-private for the one test that asserts it is total: a module missing here printed `null:
    // 0.3.0 -> v0.3.0` in the plan block, which is the kind of defect only the next new module finds.
    static final Map<Module, String> LABEL = new EnumMap<>(Map.ofEntries(
            Map.entry(Module.STUDIO_API, "studio-api"),
            Map.entry(Module.PLUGIN_TOOLKIT, "toolkit   "),
            Map.entry(Module.PLUGIN_HOST, "host      "),
            Map.entry(Module.PLUGIN_ARCHETYPE, "archetype "),
            Map.entry(Module.PLUGIN_BASICS, "basics    "),
            Map.entry(Module.CLI, "cli       "),
            Map.entry(Module.SHARED, "shared "),
            Map.entry(Module.SESSION, "session"),
            Map.entry(Module.SDK, "sdk    "),
            Map.entry(Module.STUDIO, "studio "),
            Map.entry(Module.PILOT, "pilot  "),
            Map.entry(Module.REMOTE_SERVER, "remote-server"),
            Map.entry(Module.REMOTE, "remote       "),
            Map.entry(Module.DASHBOARD, "dashboard    "),
            Map.entry(Module.GAMEBOT, "gamebot      ")));

    // Map.ofEntries here too, since 2026-09-21: the worked bot made it eleven.
    private static final Map<Module, String> NOTE = new EnumMap<>(Map.ofEntries(
            Map.entry(Module.STUDIO_API, "  (the plugin contract)"),
            Map.entry(Module.PLUGIN_TOOLKIT, "  (the plugin widget toolkit)"),
            Map.entry(Module.PLUGIN_HOST, "  (the plugin loader)"),
            Map.entry(Module.PLUGIN_ARCHETYPE, "  (mvn archetype:generate)"),
            Map.entry(Module.PLUGIN_BASICS, "  (plugin #2: values, Settings, the store)"),
            Map.entry(Module.CLI, "  (the botmaker command + the validator)"),
            Map.entry(Module.PILOT, "  (tags -> APK GitHub Release)"),
            Map.entry(Module.REMOTE_SERVER, "  (tags -> jar GitHub Release)"),
            Map.entry(Module.REMOTE, "  (tags -> APK GitHub Release)"),
            Map.entry(Module.DASHBOARD, "  (tags -> rpm + deb GitHub Release)"),
            Map.entry(Module.GAMEBOT, "  (the worked bot a new project copies)")));

    /** What one module was asked for, what that resolves to, and what was decided about it. */
    public record Decision(Module module, VersionSpec spec, Version version,
                           ReleaseDecision verdict, List<Forcing> forcedBy) {

        public boolean releasing() {
            return verdict.releasing();
        }
    }

    private final List<Decision> decisions;

    private Plan(List<Decision> decisions) {
        this.decisions = List.copyOf(decisions);
    }

    /**
     * Runs the pass.
     *
     * @param requested module to the spec typed on the command line, in any order — the pass walks
     *                  {@link Order#DECIDE} regardless, because each forced flag reads the versions decided
     *                  so far
     */
    public static Plan decide(Path umbrella, Map<Module, String> requested, boolean force) {
        List<Decision> decisions = new ArrayList<>();
        Map<Module, Version> cutting = new EnumMap<>(Module.class);

        for (Module module : Order.DECIDE) {
            String spec = requested.get(module);
            if (spec == null) {
                continue;
            }
            VersionSpec parsed = VersionSpec.parse(module, spec);
            Optional<Version> latest = Tags.latest(umbrella, module);
            Version version = parsed.against(latest);

            List<Forcing> forcedBy = Forcing.forcedBy(module, cutting.keySet());
            ReleaseDecision verdict = ReleaseDecision.read(umbrella, module, force,
                    !forcedBy.isEmpty(), parsed, latest);

            if (verdict.releasing()) {
                cutting.put(module, version);
            }
            decisions.add(new Decision(module, parsed, version, verdict, forcedBy));
        }
        return new Plan(decisions);
    }

    /** Every module asked for, decided or not, in decide order. */
    public List<Decision> decisions() {
        return decisions;
    }

    /**
     * The modules the operator actually named — every one that reached the pass, released or skipped.
     *
     * <p>Distinct from {@link #releasing()} on purpose, and {@link ForcingGate} is why: a module skipped
     * for having no changes <b>was</b> requested, so no forcing edge is being ignored on its account, while
     * a module absent from this set was never considered at all.
     */
    public Set<Module> requested() {
        return decisions.stream().map(Decision::module)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Module.class)));
    }

    /** What is actually being cut, in tag order — the map every writer downstream takes. */
    public Map<Module, Version> releasing() {
        Map<Module, Version> out = new LinkedHashMap<>();
        for (Module module : Order.TAG) {
            decisions.stream()
                    .filter(decision -> decision.module() == module && decision.releasing())
                    .findFirst()
                    .ifPresent(decision -> out.put(module, decision.version()));
        }
        return out;
    }

    /**
     * The {@code Release plan:} block — what was asked for and what it resolves to.
     *
     * <p>In <b>flag</b> order, which is {@link Module}'s own and neither of the other two: this block
     * mirrors the command line back at the operator, so it reads in the order they typed it.
     */
    public List<String> planLines() {
        List<String> lines = new ArrayList<>();
        for (Module module : Module.values()) {
            decisions.stream().filter(decision -> decision.module() == module).findFirst()
                    .ifPresent(decision -> lines.add("    " + LABEL.get(module) + ": "
                            + spelling(decision.spec()) + " -> v" + decision.version()
                            + NOTE.getOrDefault(module, "")));
        }
        return lines;
    }

    /** The {@code Deciding what to release:} block, in the order the decisions were taken. */
    public List<String> decisionLines() {
        return decisions.stream()
                .map(decision -> decision.verdict().line(decision.module(), decision.version()))
                .toList();
    }

    /** Why each module nobody asked for directly is in the plan — the reasons the script keeps as comments. */
    public List<String> forcingLines() {
        List<String> lines = new ArrayList<>();
        for (Decision decision : decisions) {
            for (Forcing edge : decision.forcedBy()) {
                lines.add("    " + decision.module().directory() + ": " + edge.sentence());
            }
        }
        return lines;
    }

    /** The spec as it was typed, which is what the plan block echoes. */
    private static String spelling(VersionSpec spec) {
        return spec instanceof VersionSpec.Bump bump ? bump.level().spelling() : spec.against(
                Optional.empty()).toString();
    }
}
