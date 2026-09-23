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
 *   <li>A <b>template</b> ({@link Module#template}) takes neither CI gate, and for the plainest possible
 *       reason: {@code botmaker-gamebot} has no {@code .github/workflows} at all. A CI verdict on a
 *       repository with no CI is not a pass and not a failure, and {@code CiDepsGate}'s question — can this
 *       module's own CI build it standalone — has no subject. It is exempt from the JitPack gates through
 *       {@link Module#onJitpack} already, nobody resolving a template as an artifact. What it does take is
 *       the template gate, which is the one question worth asking a project a user copies: does it
 *       compile.</li>
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

    /** Modules whose newest CI run on {@code main} must not be red — every one being cut that has CI. */
    public static List<Module> ci(Set<Module> releasing) {
        return Order.TAG.stream()
                .filter(releasing::contains)
                .filter(module -> !module.template())
                .toList();
    }

    /** Modules whose own CI must be able to build them standalone. */
    public static List<Module> ciDeps(Set<Module> releasing) {
        return Order.DECIDE.stream()
                .filter(releasing::contains)
                .filter(Module::mavenBuild)
                .filter(module -> !module.template())
                .toList();
    }

    /** Modules JitPack builds, and whose pinned Maven plugins must therefore run on its Maven. */
    public static List<Module> jitpackPlugins(Set<Module> releasing) {
        return ciDeps(releasing).stream()
                .filter(Module::onJitpack)
                .toList();
    }

    /**
     * The template directories to compile — {@link TemplateGate}.
     *
     * <p>Every template whenever the release cuts the SDK <b>or</b> a template, and none otherwise. Not
     * "the template being cut": the SDK is what a template pins, so an SDK release is the moment a template
     * that has not been touched can stop compiling, which is the failure this gate was added for. Templates
     * are not forced by an SDK release — a small SDK patch must not demand a template version — so the gate
     * is what a forcing edge would otherwise have been.
     */
    public static List<String> templates(Set<Module> releasing) {
        boolean relevant = releasing.contains(Module.SDK) || releasing.stream().anyMatch(Module::template);
        return relevant ? TemplateGate.directories() : List.of();
    }

    /**
     * Whether this release moves {@code directory}'s pin: it cuts that template <b>and</b> a module the
     * template pins ({@link TemplatePin#PINS}).
     *
     * <p>Then the decide pass has nothing honest to compile. The pin it would read is the one the release
     * replaces, and a template migrated ahead of its SDK — {@code botmaker-gamebot} on SDK 2.0.0, 2026-09-23 —
     * compiles only at the pin it is about to get. {@link TemplateGate#afterBump} asks the question of the pin
     * actually written, before the template is tagged.
     */
    public static boolean pinMoves(String directory, Set<Module> releasing) {
        return releasing.stream().anyMatch(module -> module.template() && module.directory().equals(directory))
                && TemplatePin.PINS.keySet().stream().anyMatch(releasing::contains);
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
