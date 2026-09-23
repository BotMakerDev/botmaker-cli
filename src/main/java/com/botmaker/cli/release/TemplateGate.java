package com.botmaker.cli.release;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Does the project a user copies still compile — asked of every template this repository ships.
 *
 * <p><b>It exists because the two ways a template rots are both invisible from a version number.</b> On
 * 2026-09-21 {@code botmaker-gamebot} was migrated to {@code @Param}, {@code @Managed} and a flow written in
 * Java while its pom still pinned SDK {@code 1.1.9} — the template did not compile at its own pin for a day,
 * and nothing said so. A working copy of it also declared {@code botmaker-plugin-toolkit} beside the SDK that
 * brings it, so Maven's nearest-wins pinned the toolkit four contract releases back and opening the project
 * died on {@code com/botmaker/plugin/api/ValueContext}. <b>A pin comparison would have caught neither.</b>
 * Compiling catches both, because both are exactly a compile failure.
 *
 * <p><b>Two templates, one of them not a {@link Module}.</b> {@code botmaker-base} names no SDK and no
 * plugin — that is the point of the blank — so it has no pin to move and no release flag. It still has a pom
 * and a {@code main()}, so it can still stop compiling, and this is the one thing it gets: the gate is about
 * a directory rather than about a module.
 *
 * <p><b>When it runs</b> is {@link GatePlan#templates}: whenever the release cuts the SDK <i>or</i> a
 * template. A template is not forced by an SDK release — a small SDK patch must not demand a template
 * version — so this gate is what keeps the template honest instead of forcing.
 *
 * <p><b>What the decide pass proves is that the template is coherent today</b>, at the pin it has now. A
 * {@code --gamebot} run then moves that pin, so {@link #afterBump} compiles it again, after
 * {@link TemplatePin#bump} and before {@link CommitTagPush} — the one place a refusal is still free, since
 * {@link Order#TAG} puts the templates last and nothing is tagged for this module yet. <b>A run that moves
 * the pin skips the first compile</b> ({@link #deferred}): a template migrated ahead of its SDK compiles only
 * at the pin it is about to get, and refusing it at the old one would refuse the very release that fixes it.
 *
 * <p>Skips with a line when {@code mvn} is absent, like every other gate that shells out: a gate that cannot
 * run must say so, and {@code --force} overrides a gate that failed, never one that could not run.
 */
public final class TemplateGate {

    /** The blank bot: a pom, one {@code main()} and nothing else. Not in the enum — it has no flag. */
    public static final String BASE = "botmaker-base";

    /** Lines of Maven's output a refusal quotes back, as {@link SdkGates} caps them. */
    private static final int QUOTED = 20;

    private TemplateGate() {
    }

    /** Every template directory, whether or not the release knows it as a module. */
    public static List<String> directories() {
        List<String> all = new ArrayList<>(java.util.Arrays.stream(Module.values())
                .filter(Module::template)
                .map(Module::directory)
                .toList());
        all.add(BASE);
        return List.copyOf(all);
    }

    /**
     * Compiles one template where it sits.
     *
     * <p>A directory with no {@code pom.xml} is {@code SKIPPED} rather than refused: the templates are git
     * submodules, and an umbrella checked out without them is a checkout that cannot answer the question —
     * not a template that fails it.
     */
    public static GateVerdict check(Path umbrella, String directory, boolean force) {
        Path pom = umbrella.resolve(directory).resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return GateVerdict.skipped("  " + directory + ": no pom.xml in this checkout — template gate"
                    + " skipped");
        }
        if (!Proc.onPath("mvn")) {
            return GateVerdict.skipped("  " + directory + ": mvn not on PATH — template gate skipped");
        }
        return verdict(directory, compile(umbrella, pom), force);
    }

    /**
     * The decide pass's line for a template whose pin this release moves ({@link GatePlan#pinMoves}): the
     * compile waits for {@link #afterBump}, which runs before the template is tagged. A dry run never gets
     * there, so its preview says the question is still open rather than answered.
     */
    public static GateVerdict deferred(String directory) {
        return GateVerdict.ok("  " + directory + ": its pin moves in this release — compiled after the bump");
    }

    /** The reading of one compile, split out so the refusal's wording is tested without forking Maven. */
    static GateVerdict verdict(String directory, Proc.Result run, boolean force) {
        if (run.ok()) {
            return GateVerdict.ok("  " + directory + ": compiles at its own pin — ok");
        }
        if (force) {
            return GateVerdict.forced("  " + directory + ": will not compile — FORCED");
        }
        return GateVerdict.refused(directory + ": the template does not compile at the version it pins.\n"
                + "     This is the project a user copies, so a template that will not build is a new"
                + " project that will not build.\n"
                + "     Fix it, or --force.\n\n"
                + errors(run));
    }

    /**
     * The second compile: the same question asked of the pin {@link TemplatePin#bump} just wrote.
     *
     * <p><b>A dry run asks nothing here</b>, and that is not an exemption from the gate — the pom was never
     * edited, so this would compile the old pin and report on a file that does not exist yet. The decide
     * pass already compiled that pin, and a preview's whole promise is that it changes nothing.
     *
     * @throws ReleaseRefusal when the bumped template will not compile
     */
    public static void afterBump(Runner runner, Path umbrella, Module module, Version sdk) {
        if (runner.dryRun()) {
            return;
        }
        Path pom = umbrella.resolve(module.directory()).resolve("pom.xml");
        if (!Files.isRegularFile(pom) || !Proc.onPath("mvn")) {
            runner.say("    " + module.directory() + ": mvn not on PATH — the bumped pin is not compiled");
            return;
        }
        Proc.Result run = compile(umbrella, pom);
        if (run.ok()) {
            runner.say("    " + module.directory() + " compiles against " + sdk.tag() + " — ok");
            return;
        }
        throw new ReleaseRefusal(module.directory() + ": the template does not compile against the SDK"
                + " this release just cut (" + sdk.tag() + ").\n"
                + "     The pin was moved and nothing is tagged for it yet. Fix the template, or release it"
                + " without the SDK.\n\n"
                + errors(run));
    }

    /**
     * {@code mvn -B -q compile}, from the umbrella, on the template's own pom.
     *
     * <p><b>{@code -f <pom>} rather than the reactor</b>, unlike {@link SdkGates#sdkPlugin}: a template is
     * not a module of the umbrella's aggregator and resolves its SDK as a published artifact, which is
     * exactly what a user's copy does. Building it from the reactor would test a bot nobody has.
     */
    private static Proc.Result compile(Path umbrella, Path pom) {
        return Proc.run(umbrella, "mvn", "-B", "-q", "-f", pom.toString(), "compile");
    }

    /**
     * The {@code [ERROR]} lines, or the tail when there are none.
     *
     * <p>The fallback is the part that matters: {@code mvn -q} hands javac's own diagnostics through
     * unprefixed for some failures, and a refusal quoting nothing at all is the one output worse than a long
     * one — it reads as the gate being broken rather than the template.
     */
    private static String errors(Proc.Result run) {
        List<String> flagged = run.out().lines()
                .filter(line -> line.contains("[ERROR]"))
                .limit(QUOTED)
                .toList();
        if (!flagged.isEmpty()) {
            return String.join("\n", flagged);
        }
        List<String> lines = run.lines();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - QUOTED), lines.size()));
    }
}
