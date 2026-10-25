package com.botmaker.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * {@code botmaker bot} — the second noun, and the half of the platform that had no command.
 *
 * <p>The four verbs beside it are all about a <b>plugin</b> and live under {@link PluginCommand} since
 * 2026-09-05: {@code new}, {@code validate}, {@code run},
 * {@code publish}. Every one of them automates something that already had a Studio UI or an obvious
 * {@code mvn} incantation. What had neither is the other half — a <b>bot</b>, and in particular a bot
 * published for others to start from. Since 2026-10-23 every published bot is a starting point: a
 * repository with a release and an entry in the catalog ({@code BotMakerDev/botmaker-catalog}), checked by
 * machine and merged automatically, with no template kind.
 *
 * <p>A noun rather than four more verbs because {@code new} and {@code publish} already mean something here
 * and mean it about a plugin. {@code botmaker bot new} and {@code botmaker plugin new} being different
 * commands is the honest reading of two different things that happen to share an English word — and this
 * noun is why the plugin verbs eventually grew one of their own.
 */
@Command(name = "bot",
        header = "Create, publish, list and export bot projects.",
        description = "Start blank or from a published bot, then publish the result — repository, release and "
                + "its catalog entry — for anybody else to start from.",
        mixinStandardHelpOptions = true,
        synopsisSubcommandLabel = "<command>",
        subcommands = {BotNewCommand.class, BotPublishCommand.class, BotUnpublishCommand.class,
                BotListCommand.class, BotExportCommand.class})
final class BotCommand implements Callable<Integer> {

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
