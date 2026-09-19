package com.botmaker.cli.release;

import java.util.Collection;
import java.util.List;

/**
 * Which modules of a release are owed a JitPack wait — the question {@link Jitpack} exists to answer for,
 * asked per module rather than for all nine.
 *
 * <p><b>The wait has exactly one reason</b>, and {@link Jitpack}'s javadoc is it: a downstream tagged seconds
 * after its upstream starts a JitPack build that resolves an upstream still building, and a build result is
 * <b>cached per tag</b>, so the breakage is permanent. That reason applies to a module some <i>later</i>
 * JitPack build resolves, and to no other. Waiting ten minutes on a module nothing in the release resolves
 * buys nothing and is paid every release.
 *
 * <p><b>The rule is derived, never listed.</b> A hand-kept list of "modules worth waiting for" would be a
 * second statement of the dependency graph and would go stale the first time an edge was added — the failure
 * {@link DepsEnv} and {@link Forcing} are both written to avoid. So it is read off what the release already
 * knows: {@link Order#TAG} for who comes after, {@link DepsEnv#upstreams} for who pins whom, and
 * {@link Module#onJitpack()} for whether the consumer's build is a JitPack build at all.
 *
 * <p><b>{@code onJitpack()} on the <i>consumer</i> is the load-bearing half.</b> Studio's and the dashboard's
 * {@code package} jobs check their upstreams out and {@code mvn install} them from source, so what they need
 * is the <b>tag on origin</b> — which {@link CommitTagPush} has already guaranteed by the time the wait would
 * start — and not a JitPack build of it. A wait for them would block on a build nothing in the release reads.
 *
 * <p>For a full fourteen-module release that answers: owed — studio-api, plugin-toolkit, plugin-host, shared,
 * session, plugin-basics. Not owed — plugin-archetype (nothing of ours pins it), cli (only the dashboard
 * pins it, and its CI installs it from source) and sdk (nothing tagged after it resolves it; Studio's
 * {@code SDK_FALLBACK_VERSION} is text naming a tag, checked by {@link FallbackVersionsGate} as a tag).
 *
 * <p><b>A skipped wait is not a skipped check.</b> The clean-room verify pass still resolves all nine
 * artifacts after the chain, so a module whose build is broken is still reported — later, and without the
 * release having paid for the discovery.
 */
public final class Waits {

    private Waits() {
    }

    /**
     * Whether this release must wait for {@code module}'s JitPack build before tagging the next module.
     *
     * @param releasing every module in this release; order is irrelevant, {@link Order#TAG} supplies it
     */
    public static boolean owed(Module module, Collection<Module> releasing) {
        List<Module> tagOrder = Order.toTag(releasing);
        int at = tagOrder.indexOf(module);
        if (at < 0) {
            return false;
        }
        return tagOrder.subList(at + 1, tagOrder.size()).stream()
                .filter(Module::onJitpack)
                .anyMatch(consumer -> DepsEnv.upstreams(consumer).contains(module));
    }

    /** The sentence a skipped wait prints, so a shorter release is a fact in the log rather than a mystery. */
    public static String notWaiting(Module module, Version version) {
        return "not waiting on " + module.directory() + ":" + version.tag()
                + " — nothing in this release resolves it from JitPack";
    }
}
