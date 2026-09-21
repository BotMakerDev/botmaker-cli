package com.botmaker.cli.release;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The versions a bot template's own pom pins, and the release's bump of them — {@link Fallback}'s argument
 * applied to the other bot pom this project owns.
 *
 * <p><b>A pin a template <i>has</i> is a pin nobody else moves.</b> {@code Fallback} rewrites
 * {@code SDK_FALLBACK_VERSION}, the SDK a <i>freshly generated</i> bot declares, and it exists because
 * without it every bot created after an SDK release keeps pinning the previous SDK. A template is the same
 * sentence about the other path into a project — <i>New project from a template</i> copies
 * {@code botmaker-gamebot} as it stands — and it had no such bump at all.
 *
 * <p><b>What that cost, on 2026-09-21.</b> The template's source was migrated to the flow-as-Java design and
 * its pom was left pinning SDK 1.1.9, which has neither {@code @Managed} nor {@code Flow}. So the worked bot
 * — the one thing a new user copies, and the only bot this project maintains — did not compile at its own
 * pin for a day, and nothing in the release had an opinion about it. {@code TemplateGate} is the half that
 * notices; this is the half that moves.
 *
 * <p><b>An anchored regex over the pom text, and it must never become a computed value</b>, which is
 * {@code Fallback}'s rule and it is load-bearing for the same reason: the bump is a text edit, so anything
 * derived would let it silently stop matching while continuing to report success. The anchor is the
 * {@code artifactId}, never the version, so the pattern is indifferent to what the pin says today; and
 * {@link Runner#replace} throws when nothing matches, so a template that stops declaring the dependency is
 * a refusal rather than a release that believes it moved a pin it did not move.
 */
public final class TemplatePin {

    /**
     * Module to the Maven {@code artifactId} a template pins it as.
     *
     * <p>A map of one, and it stays a map for {@link Fallback#CONSTANTS}'s reason: any released module a
     * template's pom names belongs here, and belongs here <i>once</i>. {@code botmaker-plugin-toolkit} is
     * deliberately not a second entry — the SDK declares it itself at {@code compile}, so it arrives
     * transitively at the version that SDK was built against, and a template naming it again would pin it by
     * Maven's nearest-wins to whatever number the file happened to say. That is exactly how a template ended
     * up linked against a contract that had moved.
     */
    static final Map<Module, String> PINS =
            new EnumMap<>(Map.of(Module.SDK, "botmaker-sdk"));

    private TemplatePin() {
    }

    /**
     * {@code <artifactId>botmaker-sdk</artifactId> … <version>1.1.14</version>} — the declaration, with the
     * version as the middle group.
     *
     * <p>The whitespace between the two elements is whatever the file has, so the pattern reads a pom
     * formatted either way and touches one version: the one belonging to the artifact it names. A bare
     * {@code <version>} pattern would match the project's own version, which is the first one in the file.
     */
    static Pattern declaration(String artifactId) {
        return Pattern.compile("(<artifactId>" + Pattern.quote(artifactId)
                + "</artifactId>\\s*<version>)[^<]*(</version>)");
    }

    /**
     * Rewrites every pin naming a module this release is cutting.
     *
     * <p>Called from a template's own release and only from there. A run that cuts the SDK without the
     * template never reaches this and is not refused for it — a template is forced by nothing, on purpose,
     * so that a patch release of the SDK does not demand a template version. What keeps the template honest
     * instead is {@code TemplateGate}, which refuses a release whose template will not compile.
     *
     * <p>The version is written <b>without</b> the {@code v} the tag carries, which is the spelling the
     * template's pom already uses. JitPack resolves a coordinate to the tag either way; what matters is that
     * one spelling is written every time, rather than the file drifting between two.
     */
    public static void bump(Runner runner, Path umbrella, Module module, Map<Module, Version> releasing) {
        Path pom = umbrella.resolve(module.directory()).resolve("pom.xml");
        PINS.forEach((pinned, artifactId) -> {
            Version version = releasing.get(pinned);
            if (version != null) {
                runner.replace(pom, declaration(artifactId), "$1" + version + "$2");
            }
        });
    }
}
