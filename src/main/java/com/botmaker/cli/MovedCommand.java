package com.botmaker.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The four verbs that moved under {@link PluginCommand} on 2026-09-05, and {@code release}, which went to sluice
 * on 2026-11-13, kept only to say where they went.
 *
 * <p><b>It does not delegate, and that is the point.</b> An alias is a second spelling to carry forever and
 * a second thing every example has to choose between; this runs nothing and exits 2 — the command line was
 * wrong — with one line naming the replacement. What it buys over deleting the verbs outright is the
 * difference between picocli's {@code Unmatched argument: 'validate'} and {@code moved: use `botmaker plugin
 * validate`}, for a tool whose install base arrives through dnf and apt and updates on its own schedule.
 *
 * <p>Delete this class at 1.0.0.
 *
 * <p>One class rather than four: the verbs are registered as aliases of one command, so the replacement is a
 * lookup rather than four near-identical files. Which alias was typed is read back off the root's original
 * arguments — picocli reports the command's primary name, not the alias, and the message has to name the
 * verb the person actually wrote.
 */
@Command(name = "new", aliases = {"validate", "run", "publish", "release"}, hidden = true)
final class MovedCommand implements Callable<Integer> {

    /**
     * Old verb to its replacement: the four plugin verbs are {@code plugin} sub-verbs, and {@code release} is
     * sluice's (2026-11-13), the umbrella's {@code ./release.sh}.
     */
    private static final Map<String, String> MOVED = Map.of(
            "new", "botmaker plugin new",
            "validate", "botmaker plugin validate",
            "run", "botmaker plugin run",
            "publish", "botmaker plugin publish",
            "release", "sluice");

    @Spec
    private CommandSpec spec;

    @picocli.CommandLine.ParentCommand
    private Main parent;

    /** Swallows whatever followed the verb, so the message is the moved notice and not a parse error. */
    @Parameters(hidden = true)
    private List<String> ignored;

    @Override
    public Integer call() {
        String typed = typedVerb();
        Console console = parent.console();
        console.error("`botmaker " + typed + "` moved: use `" + MOVED.get(typed) + "`. " + (typed.equals("release")
                ? "BotMaker is released by sluice, from the umbrella's ./release.sh."
                : "The four plugin verbs are under `botmaker plugin`; a bot's are under `botmaker bot`."));
        return picocli.CommandLine.ExitCode.USAGE;
    }

    /**
     * The verb as it was typed.
     *
     * <p>The root's original arguments are the only place an alias survives: {@code spec.name()} is the
     * primary name — {@code new} — whichever of the four was written. The first argument that is one of the
     * moved verbs is that verb; nothing before it can be one, since only options precede a subcommand.
     */
    private String typedVerb() {
        List<String> args = spec.root().commandLine().getParseResult().originalArgs();
        return args.stream().filter(MOVED::containsKey).findFirst().orElse(spec.name());
    }
}
