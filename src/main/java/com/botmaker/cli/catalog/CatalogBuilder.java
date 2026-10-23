package com.botmaker.cli.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Derives {@code catalog.json} from the entry files, {@code publishers.json}, GitHub's facts and the
 * {@code derive} job's {@link DerivedFacts}. Runs no entry's code: the job calling it holds the write token.
 *
 * <ul>
 *   <li>An entry file whose name is not what its content claims, or a second entry for a plugin id or (ignoring
 *       case, as GitHub does) a repository, is skipped and said: the check refuses both, and this is the
 *       backstop for anything that reached {@code main} without it.</li>
 *   <li>A repository that is gone or private is left out; its entry file stays until its owner removes it.</li>
 *   <li>A plugin or bot the {@code derive} job could not read this time keeps its previous row while its tag is
 *       unchanged, so one flaky build never unlists the SDK.</li>
 *   <li>A bot is listed only while every catalog plugin it declares is listed and it declares at least one.</li>
 *   <li>The coordinate is GitHub's current owner and name, so it follows a transfer or a rename.</li>
 *   <li>{@code builtAt} moves only when the content changed, so the file is committed only when it did.</li>
 * </ul>
 */
public final class CatalogBuilder {

    private CatalogBuilder() {}

    /** The catalog for the checkout at {@code root}, stamped {@code now} only if it differs from the one there. */
    public static Catalog build(Path root, Publishers publishers, EntryFacts facts, DerivedFacts derived,
                                String now) throws IOException {
        Optional<Catalog> previous = Catalog.read(root.resolve(Catalog.FILE));
        Map<String, Catalog.Plugin> previousPlugins = previous.map(c -> c.plugins().stream()
                .collect(Collectors.toMap(Catalog.Plugin::id, Function.identity(), (a, b) -> a))).orElse(Map.of());
        Map<String, Catalog.Bot> previousBots = previous.map(c -> c.bots().stream()
                .collect(Collectors.toMap(Catalog.Bot::repo, Function.identity(), (a, b) -> a))).orElse(Map.of());

        List<Catalog.Plugin> plugins = new ArrayList<>();
        for (PluginEntry entry : read(root, EntryPath.PLUGINS, PluginEntry::read, PluginEntry::id)) {
            Optional<EntryFacts.Repo> repo = facts.repo(entry.repo());
            if (repo.isEmpty()) continue;
            DerivedFacts.PluginFacts jar = derived.plugins().get(entry.id());
            Catalog.Plugin before = previousPlugins.get(entry.id());
            if (jar == null && before != null && before.tag().equals(entry.tag())) {
                jar = new DerivedFacts.PluginFacts(before.name(), before.minContractVersion(),
                        before.editorDependencies());
            }
            if (jar == null) continue;
            plugins.add(new Catalog.Plugin(entry.id(), entry.repo(), entry.tag(),
                    publishers.rankOf(repo.get().ownerId()), repo.get().coordinate(), jar.name(),
                    repo.get().description(), repo.get().topics(), repo.get().stars(),
                    jar.minContractVersion(), jar.editorDependencies()));
        }
        plugins.sort(Comparator.comparing(Catalog.Plugin::id));
        Set<String> listed = plugins.stream().map(Catalog.Plugin::id).collect(Collectors.toSet());

        List<Catalog.Bot> bots = new ArrayList<>();
        for (BotEntry entry : read(root, EntryPath.BOTS, BotEntry::read, b -> b.repo().toLowerCase(Locale.ROOT))) {
            Optional<EntryFacts.Repo> repo = facts.repo(entry.repo());
            if (repo.isEmpty()) continue;
            List<DerivedFacts.Pin> pins = derived.botPlugins().get(entry.repo());
            Catalog.Bot before = previousBots.get(entry.repo());
            if (pins == null && before != null && before.tag().equals(entry.tag())) pins = before.plugins();
            if (pins == null || pins.isEmpty() || !pins.stream().allMatch(p -> listed.contains(p.id()))) continue;
            bots.add(new Catalog.Bot(entry.repo(), entry.tag(), publishers.rankOf(repo.get().ownerId()),
                    repo.get().description(), repo.get().topics(), repo.get().stars(),
                    entry.detached() ? null : repo.get().parent().orElse(null), pins));
        }
        bots.sort(Comparator.comparing(b -> b.repo().toLowerCase(Locale.ROOT)));

        Catalog built = new Catalog(Catalog.SCHEMA_VERSION, now, plugins, bots);
        return previous.filter(p -> p.builtAt(now).equals(built)).map(p -> built.builtAt(p.builtAt()))
                .orElse(built);
    }

    /**
     * Every {@code *.json} entry under {@code root/directory} whose name matches its content, first per
     * {@code identity}; any other file is skipped and said.
     */
    private static <T extends Entry> List<T> read(Path root, String directory, Reader<T> reader,
                                                  Function<T, String> identity) throws IOException {
        Path dir = root.resolve(directory);
        if (!Files.isDirectory(dir)) return List.of();
        List<T> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String path = directory + file.getFileName();
                T entry;
                try {
                    entry = reader.read(file);
                } catch (IOException e) {
                    System.err.println("skipped " + path + ": " + e.getMessage());
                    continue;
                }
                if (!EntryPath.parse(path).map(p -> p.matches(entry)).orElse(false)) {
                    System.err.println("skipped " + path + ": its name is not what its content claims");
                } else if (!seen.add(identity.apply(entry))) {
                    System.err.println("skipped " + path + ": a second entry for " + identity.apply(entry));
                } else {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(Path file) throws IOException;
    }
}
