package com.botmaker.cli.release;

import java.util.List;
import java.util.Set;

/**
 * Which gate runs for which module — the placement half of {@code release.sh}'s gates, and the half that is
 * a decision rather than a process.
 *
 * <p><b>All of them belong to the decide pass.</b> A gate beside a module's tag command runs when pilot and
 * studio are already tagged and their CI is already going; a refusal then has nothing to undo, because a
 * pushed tag cannot be edited. So everything here answers before the first push.
 *
 * <p>The exemptions are each a fact about a module rather than a convenience:
 *
 * <ul>
 *   <li>{@code botmaker-pilot} takes no gate at all. It has no {@code CHANGELOG.md} (an APK, released by its
 *       own CI, and nothing reads notes out of it), no pom pin and no JitPack build.</li>
 *   <li>{@code botmaker-studio} is exempt from the JitPack plugin gate only: JitPack never builds it — it
 *       ships installers from its own per-OS matrix — so its plugin pins are bounded by its own CI.</li>
 *   <li>The SDK-only gates are SDK-only because their subject is: {@code check_api_pointers} runs
 *       {@code ApiPointersTest} against the version being cut, and {@code check_sdk_plugin} runs the plugin
 *       registry's own validator over the SDK, which is Studio's plugin #1 with no exemption — a rule the
 *       host's own plugin breaks is a rule the gate cannot enforce on anybody else.</li>
 * </ul>
 */
public final class GatePlan {

    /** The Maven JitPack's builder runs, and therefore the ceiling on every plugin prerequisite. */
    public static final String JITPACK_MAVEN = "3.6.1";

    private GatePlan() {
    }

    /** Modules whose {@code CHANGELOG.md} must describe the version being cut. */
    public static List<Module> changelog(Set<Module> releasing) {
        return Order.DECIDE.stream()
                .filter(releasing::contains)
                .filter(module -> !ChangelogGate.exempt(module))
                .toList();
    }

    /** Modules whose newest CI run on {@code main} must not be red — every one being cut. */
    public static List<Module> ci(Set<Module> releasing) {
        return Order.TAG.stream().filter(releasing::contains).toList();
    }

    /** Modules whose own CI must be able to build them standalone. */
    public static List<Module> ciDeps(Set<Module> releasing) {
        return Order.DECIDE.stream()
                .filter(releasing::contains)
                .filter(module -> module != Module.PILOT)
                .toList();
    }

    /** Modules JitPack builds, and whose pinned Maven plugins must therefore run on its Maven. */
    public static List<Module> jitpackPlugins(Set<Module> releasing) {
        return ciDeps(releasing).stream()
                .filter(module -> module != Module.STUDIO)
                .toList();
    }

    /** Whether the two SDK-only gates run: only when this release cuts the SDK. */
    public static boolean sdkGates(Set<Module> releasing) {
        return releasing.contains(Module.SDK);
    }

    /**
     * Whether the fallback-constant gate runs: only when this release cuts Studio, because the constants
     * live in Studio's source and only a Studio release publishes a change to them.
     */
    public static boolean fallbackVersions(Set<Module> releasing) {
        return releasing.contains(Module.STUDIO);
    }
}
