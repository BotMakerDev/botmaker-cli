package com.botmaker.cli.release;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code .deps.env} a release writes into each module that pins one — {@code release.sh}'s
 * {@code write_deps_env}.
 *
 * <p><b>It records exact refs, and that is the whole point.</b> Five of these values were once
 * {@code git ls-remote --tags | sort -V | tail -1} — "newest tag", which is only <i>usually</i> the tag this
 * release just cut. An exact ref makes a tag self-describing: checking out {@code v1.0.25} tells you which
 * upstreams it was built against.
 *
 * <p><b>The file is staged here, and the {@code git add} is load-bearing rather than tidy.</b>
 * {@link CommitTagPush} commits with {@code commit -am}, and both halves of that are blind to a file git has
 * never seen: {@code -a} stages tracked modifications only. So a module getting its <i>first</i>
 * {@code .deps.env} had it written, left on the floor, and the tag pushed without it. That is not
 * hypothetical — on 2026-09-02 the toolkit, plugin-host and the CLI were all released for the first time,
 * all three tags went out with no {@code .deps.env}, and all three JitPack builds died on
 * {@code ./.deps.env: No such file or directory} while the release reported success. A pushed tag cannot be
 * edited, so the repair was three more versions.
 */
public final class DepsEnv {

    /** The variable each upstream is recorded under, in the order the file lists them. */
    private static final Map<Module, String> VARIABLE = new EnumMap<>(Map.of(
            Module.SHARED, "SHARED_TAG",
            Module.SESSION, "SESSION_TAG",
            Module.SDK, "SDK_TAG",
            Module.STUDIO_API, "STUDIO_API_TAG",
            Module.PLUGIN_TOOLKIT, "PLUGIN_TOOLKIT_TAG",
            Module.PLUGIN_HOST, "PLUGIN_HOST_TAG",
            Module.PLUGIN_BASICS, "PLUGIN_BASICS_TAG",
            Module.CLI, "CLI_TAG"));

    private static final List<Module> ORDER = List.of(
            Module.SHARED, Module.SESSION, Module.SDK,
            Module.STUDIO_API, Module.PLUGIN_TOOLKIT, Module.PLUGIN_HOST, Module.PLUGIN_BASICS, Module.CLI);

    /**
     * Which modules write one, what they pin, and the sentence naming who reads it.
     *
     * <p><b>The empties are as deliberate as the entries.</b> Studio pins four and not six: it has had no
     * {@code botmaker-sdk} dependency since 2026-09-02, and the toolkit is a <i>plugin's</i> dependency,
     * never the host's. {@code botmaker-studio-api} and {@code botmaker-plugin-archetype} write none at all —
     * the first pins nothing of ours, the second ships text whose versions are generation-time properties.
     */
    public record Writer(String consumer, List<Module> pins) {
    }

    private static final Map<Module, Writer> WRITERS = new EnumMap<>(Map.of(
            Module.PLUGIN_TOOLKIT, new Writer(
                    "Consumer: jitpack.yml, which sources this file and injects it as\n"
                            + "# -Dbotmaker.studioapi.version at build time.",
                    List.of(Module.STUDIO_API)),
            Module.PLUGIN_HOST, new Writer(
                    "Consumer: jitpack.yml, which sources this file and injects it as\n"
                            + "# -Dbotmaker.studioapi.version at build time.",
                    List.of(Module.STUDIO_API)),
            // Plugin #2 pins two: the contract at `provided` and the toolkit at `compile`. Both are baked
            // into the published pom by flatten, and that pom is what another plugin — the SDK — resolves.
            Module.PLUGIN_BASICS, new Writer(
                    "Consumer: jitpack.yml, which sources this file and injects these as\n"
                            + "# -Dbotmaker.studioapi.version / -Dbotmaker.plugintoolkit.version at build"
                            + " time.",
                    List.of(Module.STUDIO_API, Module.PLUGIN_TOOLKIT)),
            Module.CLI, new Writer(
                    "Consumer: jitpack.yml, which sources this file and injects these as\n"
                            + "# -Dbotmaker.studioapi.version / -Dbotmaker.pluginhost.version at build"
                            + " time.",
                    List.of(Module.STUDIO_API, Module.PLUGIN_HOST)),
            Module.SESSION, new Writer(
                    "Consumer: jitpack.yml, which sources this file and injects SHARED_TAG as\n"
                            + "# -Dbotmaker.shared.version at build time.",
                    List.of(Module.SHARED)),
            Module.SDK, new Writer(
                    "Consumer: jitpack.yml, which sources this file and injects these as\n"
                            + "# -Dbotmaker.shared.version / -Dbotmaker.session.version /"
                            + " -Dbotmaker.studioapi.version /\n"
                            + "# -Dbotmaker.plugintoolkit.version /"
                            + " -Dbotmaker.pluginbasics.version at build time.",
                    List.of(Module.SHARED, Module.SESSION, Module.STUDIO_API, Module.PLUGIN_TOOLKIT,
                            Module.PLUGIN_BASICS)),
            Module.STUDIO, new Writer(
                    "Consumer: the `package` job of .github/workflows/ci.yml. It checks these four repos"
                            + " out at\n"
                            + "# these refs and `mvn install`s them at 0.0.0-SNAPSHOT, so the release build"
                            + " resolves them from\n"
                            + "# source and never touches JitPack at all.",
                    List.of(Module.SHARED, Module.SESSION, Module.STUDIO_API, Module.PLUGIN_HOST)),
            // The dashboard depends on shared and the cli's main jar, and its package job installs the cli
            // from source — which needs the cli's own two pins installed first. So it records four: the two
            // it resolves and the two the cli does. The cli's tag is also what the installed app compares
            // the checkout against (UmbrellaBar's notice), so the pin is read at run time too.
            Module.DASHBOARD, new Writer(
                    "Consumer: the `package` job of .github/workflows/ci.yml. It checks these four repos"
                            + " out at\n"
                            + "# these refs and `mvn install`s them at 0.0.0-SNAPSHOT, so the release build"
                            + " resolves them from\n"
                            + "# source and never touches JitPack at all. The file is also baked into the"
                            + " packaged app, which\n"
                            + "# compares CLI_TAG against the checkout's botmaker-cli at start-up.",
                    List.of(Module.SHARED, Module.STUDIO_API, Module.PLUGIN_HOST, Module.CLI))));

    private DepsEnv() {
    }

    /** Whether this module records its upstreams at all. */
    public static boolean writes(Module module) {
        return WRITERS.containsKey(module);
    }

    /** What this module pins, or empty for one that pins nothing of ours. */
    public static List<Module> upstreams(Module module) {
        Writer writer = WRITERS.get(module);
        return writer == null ? List.of() : writer.pins();
    }

    /**
     * The whole file, for a module and the refs its upstreams resolved to.
     *
     * @param refs upstream to git ref, as {@link DepTag} answered — the version this run is cutting when it
     *             is cutting one, otherwise that module's newest existing tag
     */
    public static String text(Module module, Map<Module, String> refs) {
        Writer writer = WRITERS.get(module);
        if (writer == null) {
            throw new ReleaseRefusal(module.directory() + ": records no upstream refs");
        }
        StringBuilder pins = new StringBuilder();
        for (Module upstream : ORDER) {
            String ref = refs.get(upstream);
            if (writer.pins().contains(upstream) && ref != null && !ref.isBlank()) {
                pins.append(VARIABLE.get(upstream)).append('=').append(ref).append('\n');
            }
        }
        return """
                # Generated by the umbrella release.sh — do not hand-edit except to test an unreleased \
                upstream
                # locally (any git ref works: a tag, a branch, a SHA). The next release overwrites it.
                #
                # The upstream refs this %s release is built against.
                # %s
                #
                # Why a file rather than a version in the pom, and why an exact ref rather than the newest \
                tag: the
                # botmaker umbrella's CLAUDE.md, section "Every released module records its upstream refs \
                in a .deps.env".

                %s
                """.formatted(module.directory(), writer.consumer(), pins.toString().stripTrailing());
    }

    /**
     * Writes it and stages it.
     *
     * @param cutting the versions this run is releasing, used to resolve each pin through {@link DepTag}
     */
    public static void write(Runner runner, Path umbrella, Module module, Map<Module, Version> cutting) {
        Map<Module, String> refs = new LinkedHashMap<>();
        for (Module upstream : upstreams(module)) {
            refs.put(upstream, DepTag.of(umbrella, upstream,
                    java.util.Optional.ofNullable(cutting.get(upstream))));
        }
        runner.say("  pinning " + module.directory() + " to " + refs.entrySet().stream()
                .map(pin -> VARIABLE.get(pin.getKey()) + "=" + pin.getValue())
                .reduce((a, b) -> a + " " + b).orElse(""));
        Path dir = umbrella.resolve(module.directory());
        runner.write(dir.resolve(".deps.env"), text(module, refs));
        // See the class javadoc: without this, a module's FIRST .deps.env is never committed.
        runner.git(dir, "add", ".deps.env");
    }
}
