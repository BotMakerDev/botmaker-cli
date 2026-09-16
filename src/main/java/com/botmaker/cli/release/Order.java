package com.botmaker.cli.release;

import java.util.Collection;
import java.util.List;

/**
 * The two orders a release runs in, and they are not the same order — nor either the order
 * {@link Module} declares.
 *
 * <p><b>{@link #DECIDE} is dependency order and has to be.</b> Each module's forced flag is computed from
 * the versions decided <i>so far</i> ({@link Forcing}), so an upstream must be answered before anything it
 * drags in. A skipped module has its version cleared here, which is what makes the downstream flags, the
 * {@code .deps.env} pins and the pointer commit all see the final answer.
 *
 * <p><b>{@link #TAG} is neither, and that freedom is the whole reason the decisions are taken up front.</b>
 * {@code should_release} used to be evaluated inline, immediately before each module was tagged, which
 * forced the tag order to equal the decision order. The pilot's APK build depends on nothing of ours, so it
 * is tagged first and runs while the JitPack chain is still going:
 *
 * <pre>
 *   pilot (APK, ~3m)
 *     → studio-api → plugin-toolkit → plugin-host → plugin-archetype → cli → shared → session
 *     → plugin-basics → sdk
 *     → studio (per-OS package matrix, ~6m)
 * </pre>
 *
 * <p><b>Studio was tagged second until 2026-09-16, and that was a race every release lost.</b> The argument
 * was that its {@code package} matrix builds its upstreams from source, so it needs no JitPack build to
 * exist. True, and beside the point: it checks those upstreams out <i>at the tags in its own
 * {@code .deps.env}</i>, and those tags are pushed minutes later, further down this list. On 2026-09-16
 * Studio v1.1.0's two package jobs failed fetching {@code botmaker-shared v0.1.0}, which did not exist yet.
 * So Studio goes last: every tag it pins is on origin before its CI starts. The cost is that its matrix no
 * longer overlaps the JitPack waits, which is minutes; the other way cost a Studio tag.
 *
 * <p>And {@link Module}'s own declaration order is a third thing again — the order {@code release.sh --help}
 * lists the flags. Three orders, three jobs; none of them is a preference, and collapsing any two would be
 * the kind of change that looks like tidying and costs a release.
 */
public final class Order {

    /** Dependency order: an upstream is decided before anything its release would force. */
    public static final List<Module> DECIDE = List.of(
            Module.PILOT,
            Module.STUDIO_API,
            Module.PLUGIN_TOOLKIT,
            Module.PLUGIN_HOST,
            Module.PLUGIN_ARCHETYPE,
            Module.PLUGIN_BASICS,
            Module.CLI,
            Module.SHARED,
            Module.SESSION,
            Module.SDK,
            Module.STUDIO);

    /** Tag order: the pilot, the JitPack chain in dependency order, then Studio once all its pins exist. */
    public static final List<Module> TAG = List.of(
            Module.PILOT,
            Module.STUDIO_API,
            Module.PLUGIN_TOOLKIT,
            Module.PLUGIN_HOST,
            Module.PLUGIN_ARCHETYPE,
            Module.CLI,
            Module.SHARED,
            Module.SESSION,
            // Plugin #2 is tagged immediately before the SDK, which resolves it. Nothing here waits on
            // shared or session — it pins the contract and the toolkit, both tagged far above — but
            // JitPack builds on demand and does not queue, so the SDK must not start building first.
            Module.PLUGIN_BASICS,
            Module.SDK,
            // Last: its package job checks out every upstream at the tag its .deps.env names.
            Module.STUDIO);

    private Order() {
    }

    /** The modules being released, in the order their tags are pushed. */
    public static List<Module> toTag(Collection<Module> releasing) {
        return TAG.stream().filter(releasing::contains).toList();
    }

    /** The modules requested, in the order their releases are decided. */
    public static List<Module> toDecide(Collection<Module> requested) {
        return DECIDE.stream().filter(requested::contains).toList();
    }
}
