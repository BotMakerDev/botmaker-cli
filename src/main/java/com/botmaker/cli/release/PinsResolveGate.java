package com.botmaker.cli.release;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Every pin a module's tag will carry, on an upstream <b>outside</b> this release, must be a tag JitPack serves.
 *
 * <p><b>Added 2026-10-12.</b> botmaker-shared v0.2.4 never built on JitPack (Maven Central's 429, before
 * {@code tools/jitpack/build.sh}), and nothing looked: the {@code --studio-api 0.9.0} release then cut SDK
 * v1.4.3, Studio v1.4.3 and dashboard v0.3.1 pinning it, and all three were broken for good. A pin on an upstream
 * <i>in</i> this release is the release's own wait ({@link Waits}); this is the one it never checked.
 *
 * <p>The question is the pom's presence, as {@link Jitpack#waitFor} asks it: a tag whose build failed is cached
 * that way, and asking again rebuilds nothing. JitPack unreachable is {@link GateVerdict.Status#SKIPPED}, never a
 * refusal of every pin; {@code --force} overrides a pin it does not serve, and says so.
 */
public final class PinsResolveGate {

    private PinsResolveGate() {
    }

    /** One upstream a module's tag would pin: the upstream and the release it would name. */
    record Pin(Module module, Module upstream, Version version) {
    }

    public static GateVerdict check(Path umbrella, Map<Module, Version> releasing, boolean force) {
        Jitpack.Probe probe = Jitpack.Probe.real();
        return verdict(pins(umbrella, releasing), pin -> probe.built(Jitpack.pomUrl(pin.upstream(), pin.version())),
                () -> probe.built(Jitpack.SITE), force);
    }

    /**
     * The pins of every module this release cuts that resolves them from JitPack, on upstreams it does not cut.
     * An upstream never released has nothing to pin, and {@link DepTag} refuses that elsewhere; it is left out.
     */
    static List<Pin> pins(Path umbrella, Map<Module, Version> releasing) {
        List<Pin> pins = new ArrayList<>();
        for (Module module : Order.toDecide(releasing.keySet())) {
            if (!module.resolvesFromJitpack()) {
                continue;
            }
            for (Module upstream : module.upstreams()) {
                if (releasing.containsKey(upstream)) {
                    continue;
                }
                Optional<Version> latest = Tags.latest(umbrella, upstream);
                latest.ifPresent(version -> pins.add(new Pin(module, upstream, version)));
            }
        }
        return List.copyOf(pins);
    }

    /**
     * The rule over pins and two probes — pure, so every arm is testable offline.
     *
     * @param served whether JitPack serves the pom of a pin's upstream at its version
     * @param online whether JitPack answers at all; asked only when some pin is not served
     */
    static GateVerdict verdict(List<Pin> pins, Predicate<Pin> served, BooleanSupplier online, boolean force) {
        if (pins.isEmpty()) {
            return GateVerdict.ok("");
        }
        List<Pin> missing = pins.stream().filter(pin -> !served.test(pin)).toList();
        if (missing.isEmpty()) {
            return GateVerdict.ok("  every pin outside this release resolves on JitPack (" + pins.size() + ") — ok");
        }
        if (!online.getAsBoolean()) {
            return GateVerdict.skipped("  JitPack did not answer — " + pins.size() + " pin(s) not checked");
        }
        if (force) {
            return GateVerdict.forced("  " + listed(missing) + " — not served by JitPack, FORCED");
        }
        StringBuilder refusal = new StringBuilder("these tags would pin a release JitPack does not serve:\n");
        for (Pin pin : missing) {
            refusal.append("     ").append(pin.module().directory()).append(" would pin ")
                    .append(pin.upstream().directory()).append(' ').append(pin.version().tag()).append('\n')
                    .append("       ").append(Jitpack.pomUrl(pin.upstream(), pin.version())).append('\n');
        }
        refusal.append("     A tag built against it fails, and a pushed tag cannot be edited. Release the upstream "
                + "too, so the tag pins a build that exists: add ")
                .append(String.join(" ", missing.stream().map(pin -> pin.upstream().flag()).distinct().toList()))
                .append(". --force overrides.");
        return GateVerdict.refused(refusal.toString());
    }

    private static String listed(List<Pin> pins) {
        return String.join(", ", pins.stream()
                .map(pin -> pin.module().shortName() + " → " + pin.upstream().shortName() + " " + pin.version().tag())
                .toList());
    }
}
