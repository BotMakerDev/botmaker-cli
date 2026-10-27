package com.botmaker.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * {@code botmaker plugin} — the first of the two nouns.
 *
 * <p>These four verbs were the top level until 2026-09-05: {@code botmaker new}, {@code validate},
 * {@code run}, {@code publish}. They meant <em>plugin</em>, and nothing in the command line said so — while
 * the other half of the platform already had to spell its noun ({@code botmaker bot new}). So the most-typed
 * verb in the program belonged to one of the two things a user creates, and {@code --help} was the only
 * place that fact was written down.
 *
 * <p><b>The fix is symmetry, and it was taken as a break rather than as an alias.</b> The install base is a
 * dnf/apt repository days old and the tool is v0.x; a permanent second spelling of every verb is a worse
 * thing to carry than one rename. The four old paths survive only as {@link MovedCommand} — hidden, running
 * nothing, printing where the verb went and exiting 2 — because the difference between
 * {@code Unmatched argument: 'validate'} and {@code moved: use `botmaker plugin validate`} is the whole
 * value of a break somebody has to notice.
 *
 * <p>A noun holds no state of its own: the three collaborators are {@link Main}'s, built once there because
 * {@code --quiet} is not known until parsing is done.
 */
@Command(name = "plugin",
        header = "Create, check, try, publish and list Studio plugins.",
        description = "The loop a plugin author lives in: start from the example plugin, run the catalog's "
                + "own checks, try it inside Studio, list it in the catalog.",
        mixinStandardHelpOptions = true,
        synopsisSubcommandLabel = "<command>",
        subcommands = {PluginNewCommand.class, PluginDeclareCommand.class, PluginUpgradeCommand.class,
                PluginValidateCommand.class, PluginRunCommand.class,
                PluginPublishCommand.class, PluginUnpublishCommand.class, PluginListCommand.class})
final class PluginCommand implements Callable<Integer> {

    @ParentCommand
    private Main parent;

    Main main() {
        return parent;
    }

    /** No sub-verb: the usage text, and exit 2 — the same contract {@link Main#call()} keeps. */
    @Override
    public Integer call() {
        picocli.CommandLine.usage(this, System.out);
        return picocli.CommandLine.ExitCode.USAGE;
    }
}
