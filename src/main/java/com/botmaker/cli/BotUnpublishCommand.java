package com.botmaker.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code botmaker bot unpublish} — takes the topic {@link BotPublishCommand#TOPIC} off the repository, so
 * Browse Bots stops listing it. The repository, its releases and its other topics stay, and projects already
 * started from it keep their link.
 */
@Command(name = "unpublish",
        header = "Stop listing this bot in Browse Bots.",
        description = "Removes the topic botmaker-bot from the repository. Nothing else changes.",
        mixinStandardHelpOptions = true)
final class BotUnpublishCommand implements Callable<Integer> {

    @ParentCommand
    private BotCommand parent;

    @Option(names = "--repo", paramLabel = "<owner/name>", required = true, description = "The bot's repository.")
    private String repo;

    @Override
    public Integer call() throws IOException {
        Console console = parent.main().console();
        String slug = BotNewCommand.slug(repo);
        if (Shell.gh(console, Path.of("."), "repo", "edit", slug, "--remove-topic", BotPublishCommand.TOPIC) != 0) {
            console.error("could not remove the topic from " + slug + ". Is `gh` authenticated as its owner?");
            return 1;
        }
        console.step(slug + " is no longer listed.");
        return 0;
    }
}
