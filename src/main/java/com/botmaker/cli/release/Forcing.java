package com.botmaker.cli.release;

import java.util.List;
import java.util.Set;

/**
 * One reason a module being released drags another one with it — {@code release.sh}'s {@code forced} flags,
 * as data.
 *
 * <p><b>The script spells these as an expression per module</b>
 * ({@code [[ -n "$SHARED_VER$SESSION_VER$STUDIOAPI_VER$TOOLKIT_VER" ]]}), with the <i>why</i> in a comment
 * above it. That is the one thing worth changing in the port rather than transcribing: the reasons are the
 * hard-won part — every one of them records a bug that shipped — and a comment cannot be printed to the
 * operator who is asking why a module they did not name is in the plan. So an edge carries its own sentence
 * and {@link #forcedBy} hands it back.
 *
 * <p><b>The set of edges is transcribed exactly, comment for comment.</b> Adding or dropping one here is a
 * release that differs from the script's, which is what the slice diff exists to catch.
 *
 * <p>Two modules are forced by <b>nothing</b> and it is deliberate in both cases. {@code botmaker-shared}
 * and {@code botmaker-studio-api} sit at the top of the graph and pin nothing of ours; and
 * {@code botmaker-plugin-archetype} <i>ships text</i>, whose BotMaker versions are archetype
 * {@code requiredProperties} defaulting to {@code main-SNAPSHOT} — there is no pin in it for a contract
 * release to invalidate. {@code botmaker-pilot} is likewise independent: no pom pin, no JitPack.
 *
 * @param upstream   the module whose release triggers this
 * @param downstream the module dragged in
 * @param reason     why, in the present tense, as the operator should read it
 */
public record Forcing(Module upstream, Module downstream, String reason) {

    /** The pin that flatten bakes into a published pom — the shape behind most of these edges. */
    private static final String BAKED =
            "compiles against the contract, and flatten bakes that pin into its published pom, "
                    + "so a contract release with no release here leaves every consumer resolving the old one";

    /**
     * Every edge, transcribed from the {@code decide} calls.
     *
     * <p>Order is the script's, which is also dependency order — it has to be, because each flag is computed
     * from the versions decided <i>so far</i>. That is why the decisions are all taken up front and the tag
     * order is then free to differ (see {@link Order}).
     */
    public static final List<Forcing> EDGES = List.of(
            new Forcing(Module.STUDIO_API, Module.PLUGIN_TOOLKIT, "the toolkit " + BAKED),
            new Forcing(Module.STUDIO_API, Module.PLUGIN_HOST, "the loader " + BAKED),

            // Plugin #2 pins the contract at `provided` and the toolkit at `compile`, and flatten bakes
            // both into the pom another plugin — the SDK — resolves.
            new Forcing(Module.STUDIO_API, Module.PLUGIN_BASICS, "plugin #2 " + BAKED),
            new Forcing(Module.PLUGIN_TOOLKIT, Module.PLUGIN_BASICS,
                    "plugin #2 compiles against the toolkit at compile scope, and flatten bakes that pin "
                            + "into its published pom"),

            // The CLI pins BOTH, and flatten bakes both into the pom the plugin registry's CI resolves to
            // run `validate` as a library. A gate loading plugins with a different loader than Studio's is a
            // gate that admits plugins Studio then refuses.
            new Forcing(Module.STUDIO_API, Module.CLI,
                    "the registry's CI resolves the CLI's published pom to run the validator, and a gate "
                            + "compiled against a different contract admits plugins Studio then refuses"),
            new Forcing(Module.PLUGIN_HOST, Module.CLI,
                    "the registry's CI resolves the CLI's published pom to run the validator, and a gate "
                            + "loading plugins with a different loader than Studio's admits plugins Studio "
                            + "then refuses"),

            // JitPack's build cache is per-tag, so a downstream must be re-tagged to be rebuilt against the
            // new upstream at all.
            new Forcing(Module.SHARED, Module.SESSION,
                    "session pins shared, and JitPack's build cache is per-tag: without a new tag nothing "
                            + "rebuilds against the new shared"),

            new Forcing(Module.SHARED, Module.SDK, "the SDK pins shared, and JitPack rebuilds per tag"),
            new Forcing(Module.SESSION, Module.SDK, "the SDK pins session, and JitPack rebuilds per tag"),
            new Forcing(Module.STUDIO_API, Module.SDK,
                    "the SDK is Studio's plugin #1 and pins the contract, and JitPack rebuilds per tag"),
            // Since phase 12a the SDK's slot editors are built on the toolkit: an ordinary (optional)
            // <dependencies> entry that flatten bakes into the SDK's published pom.
            new Forcing(Module.PLUGIN_TOOLKIT, Module.SDK,
                    "the SDK's slot editors are built on the toolkit, and flatten bakes that pin into the "
                            + "SDK's published pom"),
            // A plugin compiling against another plugin, as an ordinary Maven dependency rather than
            // through the contract or a service lookup: PluginLoader builds one URLClassLoader over the
            // whole resolved project classpath, so plugin A sees plugin B's classes and Maven has already
            // mediated B to one version. The price is that plugin-basics stops being freely breakable.
            //
            // THIS EDGE IS DELIBERATELY ONE PHASE AHEAD OF THE DEPENDENCY IT DESCRIBES (2026-09-09, the
            // maintainer's call). botmaker-sdk does not declare botmaker-plugin-basics yet — phase 2 of
            // ~/.claude/plans/settings-becomes-a-plugin.md is what adds it — so until then this refuses a
            // release that is not strictly owed. That is the safe direction and it costs nothing: the
            // refusal names the flag to add, --force overrides it, and neither module is released before
            // phase 2 lands. The other direction is the one that has actually hurt here — the missing
            // sdk->studio edge left studio v1.0.37 pinning an SDK its own changelog disowned.
            new Forcing(Module.PLUGIN_BASICS, Module.SDK,
                    "the SDK compiles against plugin #2 as an ordinary Maven dependency, and flatten bakes "
                            + "that pin into the SDK's published pom"),

            new Forcing(Module.SHARED, Module.STUDIO, "Studio builds shared from source at the ref in its "
                    + ".deps.env, which this release moves"),
            new Forcing(Module.SESSION, Module.STUDIO, "Studio builds session from source at the ref in its "
                    + ".deps.env, which this release moves"),
            new Forcing(Module.STUDIO_API, Module.STUDIO, "Studio builds the contract from source at the "
                    + "ref in its .deps.env, which this release moves"),
            new Forcing(Module.PLUGIN_HOST, Module.STUDIO, "Studio builds plugin-host from source at the "
                    + "ref in its .deps.env, which this release moves"),

            // This last one is the interesting case, and the reason is NOT the old one. The SDK is not a
            // Studio dependency any more — it left botmaker-studio/pom.xml on 2026-09-02 and is not in
            // studio's .deps.env. What it is is a STRING CONSTANT IN STUDIO'S SOURCE, sed-bumped by the
            // release: a pin Studio WRITES, not a pin Studio HAS.
            //
            // It had a twin, PLUGIN_TOOLKIT -> STUDIO over MavenService.TOOLKIT_FALLBACK_VERSION, deleted
            // 2026-09-06 with the constant. A generated bot's pom stopped declaring the toolkit at all: the
            // SDK brings it at compile scope, and a direct entry in the bot's pom outranked that by
            // nearest-wins, pinning a bot to a toolkit its own SDK was never built against. With nothing in
            // Studio's source naming a toolkit version, a toolkit release owes Studio nothing.
            new Forcing(Module.SDK, Module.STUDIO,
                    "the release bumps MavenService.SDK_FALLBACK_VERSION, so Studio's own source changes — "
                            + "without a Studio release, freshly created bots keep pinning the previous SDK"));

    /** Every reason this module is dragged in by what is already being cut. Empty means it is not forced. */
    public static List<Forcing> forcedBy(Module downstream, Set<Module> cutting) {
        return EDGES.stream()
                .filter(edge -> edge.downstream() == downstream && cutting.contains(edge.upstream()))
                .toList();
    }

    /** The script's flag: is this module forced at all. */
    public static boolean forced(Module downstream, Set<Module> cutting) {
        return !forcedBy(downstream, cutting).isEmpty();
    }

    /** How the operator reads one edge: {@code forced by botmaker-shared — session pins shared, and …}. */
    public String sentence() {
        return "forced by " + upstream.directory() + " — " + reason;
    }
}
