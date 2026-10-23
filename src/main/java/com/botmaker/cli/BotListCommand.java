package com.botmaker.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code botmaker bot list} — every repository carrying the topic {@link BotPublishCommand#TOPIC}, forks
 * included, as {@code gh search repos} prints them. Studio's Browse Bots makes the same search and also
 * drops a repository whose release's pom declares no registry plugin; this list does not read poms, so it
 * says so.
 */
@Command(name = "list",
        header = "List published bots: the repositories with the topic botmaker-bot.",
        mixinStandardHelpOptions = true)
final class BotListCommand implements Callable<Integer> {

    @ParentCommand
    private BotCommand parent;

    @Option(names = "--limit", defaultValue = "100", description = "At most this many. Default: ${DEFAULT-VALUE}")
    private int limit;

    @Override
    public Integer call() throws IOException {
        Console console = parent.main().console();
        console.step("Studio also leaves out a repository whose release's pom declares no registry plugin.");
        return Shell.gh(console, Path.of("."), "search", "repos", "--topic", BotPublishCommand.TOPIC,
                "--include-forks", "true", "--sort", "updated", "--limit", Integer.toString(limit)) == 0 ? 0 : 1;
    }
}
