package com.botmaker.cli.catalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * index.yml's one step: {@code catalog.json} from the checkout at {@code <root>}.
 *
 * <p>It runs no entry's code: GitHub's facts through the API, each pom at its tag read as data ({@link PomFacts}),
 * then {@link CatalogBuilder}, which checks every derived value again. The file is rewritten only when its content
 * changed, so the workflow commits only then. A failed GitHub request stops the run with exit {@code 1} and writes
 * nothing, rather than unlisting what it could not see.
 *
 * <pre>java -cp … com.botmaker.cli.catalog.IndexCommand &lt;root&gt;</pre>
 */
public final class IndexCommand {

    private IndexCommand() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: IndexCommand <root>");
            System.exit(2);
        }
        try {
            System.out.println(write(Path.of(args[0]), GitHubFacts.anonymousOrToken(),
                    Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()) ? "catalog.json updated"
                    : "catalog.json unchanged");
        } catch (UncheckedIOException e) {
            System.err.println("could not reach GitHub: " + e.getCause().getMessage());
            System.exit(1);
        }
    }

    /** Builds and writes the catalog; whether the file changed. */
    static <F extends EntryFacts & PomFacts.Source> boolean write(Path root, F facts, String now) throws IOException {
        List<Entry> entries = CatalogBuilder.entries(root);
        Map<String, String> pluginRepos = new HashMap<>();
        for (Entry entry : entries) {
            if (entry instanceof PluginEntry plugin) {
                facts.repo(plugin.repo()).ifPresent(repo -> pluginRepos.putIfAbsent(
                        PomFacts.key("com.github." + repo.ownerLogin(), repo.name()), plugin.repo()));
            }
        }
        DerivedFacts derived = PomFacts.read(entries, facts, pluginRepos);
        Catalog catalog = CatalogBuilder.build(root, Publishers.read(root.resolve(Publishers.FILE)), facts,
                derived, now);
        Path file = root.resolve(Catalog.FILE);
        String json = catalog.toJson();
        if (Files.isRegularFile(file) && Files.readString(file).equals(json)) return false;
        Files.writeString(file, json);
        return true;
    }
}
