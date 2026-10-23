package com.botmaker.cli;

import com.botmaker.cli.catalog.Catalog;
import com.botmaker.cli.catalog.GitHubFacts;
import com.botmaker.cli.catalog.Rank;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker bot list} — every bot {@code catalog.json} lists, with its rank, as Studio reads it: one row a
 * bot, {@code rank  owner/repo  tag  description}.
 */
@Command(name = "list",
        header = "List the bots the catalog lists, with their rank.",
        mixinStandardHelpOptions = true)
final class BotListCommand implements Callable<Integer> {

    @ParentCommand
    private BotCommand parent;

    @Override
    public Integer call() {
        Console console = parent.main().console();
        Optional<Catalog> catalog = CatalogPr.catalog(GitHubFacts.anonymousOrToken());
        if (catalog.isEmpty()) {
            console.error("could not read " + Catalog.FILE + " from " + CatalogPr.REPO + ".");
            return 1;
        }
        for (Catalog.Bot bot : catalog.get().bots()) {
            console.out(String.join("  ", Objects.requireNonNullElse(bot.rank(), Rank.COMMUNITY).displayName(),
                    bot.repo(), bot.newest().tag(), bot.description()).strip());
        }
        return 0;
    }
}
