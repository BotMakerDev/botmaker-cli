package com.botmaker.cli;

import com.botmaker.cli.catalog.Catalog;
import com.botmaker.cli.catalog.GitHubFacts;
import com.botmaker.cli.catalog.Rank;
import com.botmaker.cli.publish.CatalogPr;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker plugin list} — every plugin {@code catalog.json} lists, with its rank, as Studio reads it: one
 * row a plugin, {@code rank  id  coordinate:tag  name}.
 */
@Command(name = "list",
        header = "List the plugins the catalog lists, with their rank.",
        mixinStandardHelpOptions = true)
final class PluginListCommand implements Callable<Integer> {

    @ParentCommand
    private PluginCommand parent;

    @Override
    public Integer call() {
        Console console = parent.main().console();
        Optional<Catalog> catalog = CatalogPr.Reader.of(GitHubFacts.anonymousOrToken()).catalog();
        if (catalog.isEmpty()) {
            console.error("could not read " + Catalog.FILE + " from " + CatalogPr.REPO + ".");
            return 1;
        }
        for (Catalog.Plugin plugin : catalog.get().plugins()) {
            console.out(String.join("  ", Objects.requireNonNullElse(plugin.rank(), Rank.COMMUNITY).displayName(),
                    plugin.id(), plugin.coordinate() + ":" + plugin.newest().tag(),
                    Objects.requireNonNullElse(plugin.name(), "")).strip());
        }
        return 0;
    }
}
