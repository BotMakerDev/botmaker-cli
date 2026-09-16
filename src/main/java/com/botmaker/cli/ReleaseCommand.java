package com.botmaker.cli;

import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Release;
import com.botmaker.cli.release.ReleaseRefusal;
import com.botmaker.cli.release.ReleaseStatus;
import com.botmaker.cli.release.Requested;
import com.botmaker.cli.release.Runner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code botmaker release} — the third noun, and the one whose decisions live in a library.
 *
 * <p><b>This class is a command line and nothing else.</b> Which modules a release cuts, what version each
 * gets, what forces what, the tag order and every gate belong to {@code com.botmaker.cli.release}, because
 * that package has three callers — this command, {@code .github/workflows/release.yml} and
 * {@code botmaker-dashboard} — and CI cannot run a JavaFX app, so the owner of those decisions cannot be
 * either of the other two. Same shape, same reason, as {@code com.botmaker.cli.validate}.
 *
 * <p><b>It previews unless {@code --execute} is passed, which is the inverse of the script's default.</b>
 * {@code release.sh} releases unless {@code --dry-run} opts out; here the safe direction is the default,
 * because a wrong tag is permanent and no exit code recalls one. The port is verified by diffing this
 * command's preview against {@code ./release.sh --dry-run}'s for the same flags — see {@link #execute}.
 *
 * <p>The ten module options are spelled out one per field rather than collected into a map: they are the
 * script's own flags, one for one, and {@code --help} listing them is half of what makes this command
 * usable.
 */
@Command(name = "release",
        header = "Cut, or preview, a cross-module release.",
        description = "The decide pass, the gates and the tag order, from com.botmaker.cli.release — the "
                + "port of release.sh. Previews unless --execute is passed: it pushes nothing by default.",
        mixinStandardHelpOptions = true)
public final class ReleaseCommand implements Callable<Integer> {

    @ParentCommand
    private Main parent;

    private static final String SPEC = "<version|level>";
    private static final String SPEC_HELP = "x.y.z, or patch|minor|major (default: ${FALLBACK-VALUE}).";

    @Option(names = "--all", arity = "0..1", fallbackValue = "patch", paramLabel = "<level>",
            description = "Every module, at this level (default: ${FALLBACK-VALUE}).")
    private String all;

    @Option(names = "--studio-api", arity = "0..1", fallbackValue = "patch", paramLabel = SPEC,
            description = "The plugin contract. " + SPEC_HELP)
    private String studioApi;

    @Option(names = "--plugin-toolkit", arity = "0..1", fallbackValue = "patch", paramLabel = SPEC,
            description = "The plugin widget toolkit. " + SPEC_HELP)
    private String pluginToolkit;

    @Option(names = "--plugin-host", arity = "0..1", fallbackValue = "patch", paramLabel = SPEC,
            description = "The plugin loader. " + SPEC_HELP)
    private String pluginHost;

    @Option(names = "--plugin-archetype", arity = "0..1", fallbackValue = "patch", paramLabel = SPEC,
            description = "mvn archetype:generate. " + SPEC_HELP)
    private String pluginArchetype;

    @Option(names = "--plugin-basics", arity = "0..1", fallbackValue = "patch", paramLabel = SPEC,
            description = "Plugin #2: the value types, Settings and the project store. " + SPEC_HELP)
    private String pluginBasics;

    @Option(names = "--cli", arity = "0..1", fallbackValue = "patch", paramLabel = SPEC,
            description = "The botmaker command and the validator. " + SPEC_HELP)
    private String cli;

    @Option(names = "--shared", arity = "0..1", fallbackValue = "patch", paramLabel = SPEC,
            description = "The host platform layer. " + SPEC_HELP)
    private String shared;

    @Option(names = "--session", arity = "0..1", fallbackValue = "patch", paramLabel = SPEC,
            description = "Private display sessions. " + SPEC_HELP)
    private String session;

    @Option(names = "--sdk", arity = "0..1", fallbackValue = "patch", paramLabel = SPEC,
            description = "The bot runtime, and Studio's plugin #1. " + SPEC_HELP)
    private String sdk;

    @Option(names = "--studio", arity = "0..1", fallbackValue = "patch", paramLabel = SPEC,
            description = "The IDE. " + SPEC_HELP)
    private String studio;

    @Option(names = "--pilot", arity = "0..1", fallbackValue = "patch", paramLabel = SPEC,
            description = "The phone client. " + SPEC_HELP)
    private String pilot;

    @Option(names = "--force", description = "Release every requested module, changes or not.")
    private boolean force;

    /**
     * Off by default, and that is about the cutover rather than about taste.
     *
     * <p>The port is verified by diffing this command's output against {@code ./release.sh --dry-run}'s for
     * the same flags, and the diff has to be <b>empty</b>. Anything this prints that the script does not —
     * however useful — fails that test, so the one place the port improves on the script (a reason per
     * forcing edge, where the script keeps a shell comment) is opt-in until the script is gone.
     */
    @Option(names = "--why", description = "Also say why each forced module is in the release.")
    private boolean why;

    /**
     * The one flag that pushes anything, and it is off.
     *
     * <p>Inverted relative to {@code release.sh}, where a real release is the default and {@code --dry-run}
     * opts out. Here the port is what is on trial: until its {@code --dry-run} has agreed with the script's
     * across the flag matrix <i>and</i> one real single-module release has been watched end to end, the
     * safe default is the one that cannot burn a tag. A tag is permanent and no exit code recalls it.
     */
    @Option(names = "--execute",
            description = "Actually cut the release: commit, tag, push. Off by default.")
    private boolean execute;

    @Option(names = "--no-wait-jitpack",
            description = "Do not block on each JitPack build between tags.")
    private boolean noWaitJitpack;

    @Option(names = "--status", arity = "0..1", fallbackValue = "", paramLabel = "<file>",
            description = "Re-poll a releases/*.md instead of planning (default: the newest).")
    private String status;

    /**
     * The umbrella checkout, always absolute.
     *
     * <p>The setter is what makes the second half of that sentence true, and it is load-bearing rather than
     * tidy: half of a release runs a process in a MODULE's directory ({@code Proc.run(dir, …)}), so a
     * relative umbrella turns every path built from it into a path resolved against the wrong directory.
     * {@code --umbrella .} refused a changelog that was there, because the extractor was looked for at
     * {@code ./botmaker-plugin-basics/tools/…} from inside {@code botmaker-plugin-basics}.
     * {@code release.sh} cannot have that bug — its {@code ROOT} is computed absolute — so it was also a
     * silent divergence between two implementations that are verified by diffing their output.
     */
    private Path umbrella = Path.of("").toAbsolutePath();

    @Option(names = "--umbrella", paramLabel = "<dir>",
            description = "The umbrella checkout (default: the current directory).")
    private void setUmbrella(Path dir) {
        umbrella = dir.toAbsolutePath().normalize();
    }

    /** True unless {@code --no-wait-jitpack}: waiting costs minutes, losing the race burns a tag. */
    private boolean wait;

    @Override
    public Integer call() {
        Runner runner = execute ? Runner.real() : Runner.preview();
        wait = !noWaitJitpack;
        try {
            if (!Files.isRegularFile(umbrella.resolve("release.sh"))) {
                parent.console().error("not a botmaker umbrella checkout: " + umbrella);
                return 2;
            }
            if (status != null) {
                ReleaseStatus.repoll(runner, umbrella,
                        status.isBlank() ? Optional.empty() : Optional.of(Path.of(status)));
                return 0;
            }
            return plan(runner);
        } catch (ReleaseRefusal refused) {
            parent.console().error(refused.getMessage());
            return 1;
        }
    }

    private int plan(Runner runner) {
        Map<Module, String> requested = requested();
        if (requested.isEmpty()) {
            parent.console().error("nothing to release — pass --all or a module flag.");
            return 2;
        }
        Release.Outcome outcome = Release.run(runner, umbrella, requested, force, wait, why);
        if (outcome.refused()) {
            outcome.refusals().forEach(refused -> parent.console().error(refused.refusal()));
            return 1;
        }
        if (!outcome.pushesOk()) {
            // Reported, not fatal: by the time a branch push fails every tag is out and every CI job is
            // running, so a non-zero exit would call a finished release failed.
            parent.console().warn("a branch was not pushed — see the lines above.");
        }
        return 0;
    }

    /**
     * The flags, as module to spec.
     *
     * <p>This half — eleven fields to a map — is the command line's own shape. The rule that an explicit
     * module beats {@code --all} is {@link Requested}'s, because the Dashboard and the workflow ask the
     * same question from eleven table rows and eleven workflow inputs.
     */
    private Map<Module, String> requested() {
        Map<Module, String> explicit = new EnumMap<>(Module.class);
        put(explicit, Module.STUDIO_API, studioApi);
        put(explicit, Module.PLUGIN_TOOLKIT, pluginToolkit);
        put(explicit, Module.PLUGIN_HOST, pluginHost);
        put(explicit, Module.PLUGIN_ARCHETYPE, pluginArchetype);
        put(explicit, Module.PLUGIN_BASICS, pluginBasics);
        put(explicit, Module.CLI, cli);
        put(explicit, Module.SHARED, shared);
        put(explicit, Module.SESSION, session);
        put(explicit, Module.SDK, sdk);
        put(explicit, Module.STUDIO, studio);
        put(explicit, Module.PILOT, pilot);
        return Requested.of(Optional.ofNullable(all), explicit);
    }

    private static void put(Map<Module, String> out, Module module, String spec) {
        if (spec != null) {
            out.put(module, spec);
        }
    }
}
