package com.botmaker.cli.release;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The version constants Studio's own source holds, and the release's bump of them — {@code release.sh}'s
 * {@code sed} over {@code MavenService.SDK_FALLBACK_VERSION}.
 *
 * <p><b>A pin Studio <i>writes</i> is not a pin Studio <i>has</i>.</b> {@code SDK_FALLBACK_VERSION} is a
 * string literal naming the SDK a <i>freshly generated bot's</i> pom declares; Studio itself has not
 * depended on the SDK since 2026-09-02. That distinction is the whole reason the constant survived the
 * dependency and the reason {@code --sdk} still forces {@code --studio}: the SDK release changes Studio's
 * source, so a Studio release is owed, and without one every bot created afterwards keeps pinning the
 * previous SDK. Studio v1.0.37 is what that looks like when the forcing edge is missing.
 *
 * <p><b>It is a literal on purpose and must never become a computed value.</b> The bump is a regex over the
 * text of a {@code .java} file, so anything derived — {@code SdkVersion.latest()} was proposed — would make
 * the bump silently stop working while continuing to report success.
 *
 * <p><b>This class owns the list and {@link FallbackVersionsGate} reads it</b>, rather than each keeping its
 * own. The gate refuses a constant naming a tag nobody published and this moves it; two lists would let a
 * release move a constant the gate does not check, which is exactly the state
 * {@code TOOLKIT_FALLBACK_VERSION} was in before it was deleted.
 */
public final class Fallback {

    /**
     * Constant name to the module whose tags it must name.
     *
     * <p>A map of one since 2026-09-06, and it stays a map: the shape that outlived
     * {@code TOOLKIT_FALLBACK_VERSION} is the general one — any constant in Studio's source naming another
     * module's released version belongs here, and belongs here <i>once</i>.
     */
    static final Map<Module, String> CONSTANTS =
            new EnumMap<>(Map.of(Module.SDK, "SDK_FALLBACK_VERSION"));

    /** Studio's file holding them, relative to that module's directory. */
    static final String SOURCE = "src/main/java/com/botmaker/studio/services/MavenService.java";

    private Fallback() {
    }

    /**
     * {@code SDK_FALLBACK_VERSION = "1.1.6"} — the assignment, with the literal as the middle group.
     *
     * <p>The name is interpolated unquoted, because a constant name is {@code [A-Z_]+} and
     * {@link Pattern#quote} would put {@code \Q…\E} into the {@code sed} {@link Runner#replace} echoes,
     * which is not a pattern {@code sed} understands.
     */
    static Pattern assignment(String constant) {
        return Pattern.compile("(" + constant + " = \")[^\"]*(\")");
    }

    /**
     * Rewrites every constant naming a module this release is cutting.
     *
     * <p>Called from the Studio release and only from there, because the file is Studio's. A run that cuts
     * the SDK without Studio never reaches this — {@link ForcingGate} refuses that run by name rather than
     * letting it pass silently, which is the failure this pair exists for.
     */
    public static void bump(Runner runner, Path umbrella, Map<Module, Version> releasing) {
        Path source = umbrella.resolve(Module.STUDIO.directory()).resolve(SOURCE);
        CONSTANTS.forEach((module, constant) -> {
            Version version = releasing.get(module);
            if (version != null) {
                runner.replace(source, assignment(constant), "$1" + version + "$2");
            }
        });
    }
}
