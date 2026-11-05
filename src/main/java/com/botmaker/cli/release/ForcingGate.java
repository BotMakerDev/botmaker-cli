package com.botmaker.cli.release;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Refuse a release that forces a module the operator did not name — {@code release.sh}'s
 * {@code check_forced_but_unrequested}.
 *
 * <p><b>The forcing edges were documentation, not enforcement, until 2026-09-06.</b> Both implementations
 * evaluate a module's {@code forced} flag only for modules that were requested — {@code release.sh}'s
 * {@code decide} returns early on an empty {@code *_VER}, and {@link Plan#decide} skips a {@code null}
 * spec — so an edge whose downstream is absent from the command line does nothing at all. Every
 * <i>"a {@code --studio-api} in the same run forces it"</i> in this project's documentation quietly rests
 * on the operator having typed both flags.
 *
 * <p><b>What that cost, on 2026-09-05.</b> {@code ./release.sh --sdk 1.1.6} cut the SDK and nothing else
 * ({@code releases/2026-09-05-0128.md} has one row). The {@code SDK → STUDIO} edge ({@code SDK → CORE} since
 * 2026-11-05, when the constant moved to the core) exists precisely
 * because that release {@code sed}s {@code MavenService.SDK_FALLBACK_VERSION}, and with no Studio release
 * the {@code sed} never ran: Studio v1.0.37, cut eleven hours later, still pinned {@code 1.1.5} while its
 * own changelog announced {@code 1.1.6}. Every project created by that build pins the one SDK whose plugin
 * half cannot be constructed on a host without JavaFX.
 *
 * <p><b>It refuses rather than widening, and that asymmetry is the whole design.</b> Pulling the
 * downstream in automatically would cut a tag nobody named — and a pushed tag cannot be edited, which is
 * the constraint every decision in this package answers to. A refusal costs one more flag on the next
 * invocation; a wrong tag costs a release chain. So the operator is told which flag to add, with the
 * edge's own sentence as the reason, and types it.
 *
 * <p><b>{@code --force} overrides it</b>, like every other gate here, because deliberately cutting an
 * upstream and bumping the downstream by hand afterwards is a real thing to want. What the flag cannot do
 * is make the omission silent: a {@link GateVerdict.Status#FORCED} line still names every edge.
 */
public final class ForcingGate {

    private ForcingGate() {
    }

    /**
     * @param plan the decide pass's own answer — {@link Plan.Decision}s carry both what was asked for and
     *             what was decided, which is what makes <i>requested</i> a question this can ask
     */
    public static GateVerdict check(Plan plan, boolean force) {
        return check(plan.requested(), plan.releasing().keySet(), force);
    }

    /**
     * The two sets the answer actually depends on, split out so it can be tested without a checkout.
     *
     * @param requested every module the operator named, released or skipped
     * @param cutting   every module this run is actually tagging
     */
    static GateVerdict check(Set<Module> requested, Set<Module> cutting, boolean force) {
        List<String> lines = new ArrayList<>();
        List<Module> missing = new ArrayList<>();
        for (Module module : Order.DECIDE) {
            if (requested.contains(module)) {
                continue;
            }
            List<Forcing> edges = Forcing.forcedBy(module, cutting);
            if (edges.isEmpty()) {
                continue;
            }
            missing.add(module);
            for (Forcing edge : edges) {
                lines.add("     " + module.directory() + ": " + edge.sentence());
            }
        }

        if (missing.isEmpty()) {
            return GateVerdict.ok("  forcing: every module this release drags in was requested — ok");
        }
        if (force) {
            return GateVerdict.forced("  forcing: " + flags(missing) + " forced but not requested — FORCED");
        }
        return GateVerdict.refused("this release forces modules that were not requested.\n"
                + String.join("\n", lines) + "\n"
                + "     Add " + flags(missing) + ", or drop the flag that forces them. A forcing edge\n"
                + "     never cuts a tag nobody named, because a pushed tag cannot be edited.  --force overrides.");
    }

    /**
     * {@code --studio}, or {@code --sdk, --studio} — in {@link Order#DECIDE}, which is the order they would
     * be typed and the order {@code release.sh}'s own list accumulates them in.
     */
    private static String flags(List<Module> modules) {
        return modules.stream().map(Module::flag).collect(Collectors.joining(", "));
    }
}
