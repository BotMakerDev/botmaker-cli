package com.botmaker.cli.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Derives {@code catalog.json} from the entry files, {@code publishers.json}, GitHub's facts and the poms'
 * {@link DerivedFacts}. Runs no entry's code: the job calling it holds the write token.
 *
 * <ul>
 *   <li>Every approved release is a row of its entry, newest first, with its pins and the facts of its pom read at
 *       its pinned commit. A release whose GitHub Release is gone (or became a draft or a prerelease) is left
 *       out; an entry with none left is left out.</li>
 *   <li>Every derived value is checked again — a plugin's name, its {@code minContractVersion}, its
 *       {@code editorDependencies} ({@link EditorDependencies}), a bot's pinned versions — because the author
 *       controls the pom. A release that fails is left out.</li>
 *   <li>Only regular files are read: a symbolic link could point the read anywhere on the runner.</li>
 *   <li>An entry file whose name is not what its content claims, or a second entry for a plugin id or (ignoring
 *       case, as GitHub does) a repository, is skipped and said: the check refuses both, and this is the
 *       backstop for anything that reached {@code main} without it.</li>
 *   <li>A repository that is gone or private is left out; its entry file stays until its owner removes it.</li>
 *   <li>A release whose pom could not be read this time keeps its previous facts while its repository, tag and
 *       commit are unchanged, so one failed request never unlists the SDK.</li>
 *   <li>A bot's release is listed only while every catalog plugin it declares is listed and it declares at least
 *       one.</li>
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
            Catalog.Plugin before = previousPlugins.get(entry.id());
            List<Catalog.Plugin.Release> releases = new ArrayList<>();
            String name = null;
            for (ApprovedRelease release : entry.releases().reversed()) {
                if (!published(facts, entry.repo(), release.tag())) continue;
                DerivedFacts.PluginFacts jar = derived.plugin(entry.id(), release.tag());
                Optional<Catalog.Plugin.Release> kept = before != null && before.repo().equalsIgnoreCase(entry.repo())
                        ? previous(before.releases(), release, Catalog.Plugin.Release::tag, Catalog.Plugin.Release::commit)
                        : Optional.empty();
                if (jar == null && kept.isPresent()) {
                    jar = new DerivedFacts.PluginFacts(name == null ? before.name() : name,
                            kept.get().minContractVersion(), kept.get().editorDependencies());
                }
                if (jar == null) continue;
                String refusal = refusal(jar);
                if (refusal != null) {
                    System.err.println("left out plugins/" + entry.id() + ".json " + release.tag() + ": " + refusal);
                    continue;
                }
                if (name == null) name = jar.name();
                releases.add(new Catalog.Plugin.Release(release.tag(), release.commit(), release.jarSha256(),
                        release.pomSha256(), jar.minContractVersion(), jar.editorDependencies()));
            }
            if (releases.isEmpty()) continue;
            plugins.add(new Catalog.Plugin(entry.id(), entry.repo(), publishers.rankOf(repo.get().ownerId()),
                    repo.get().coordinate(), name, repo.get().description(), repo.get().topics(),
                    repo.get().stars(), releases));
        }
        plugins.sort(Comparator.comparing(Catalog.Plugin::id));
        Set<String> listed = plugins.stream().map(Catalog.Plugin::id).collect(Collectors.toSet());

        List<Catalog.Bot> bots = new ArrayList<>();
        for (BotEntry entry : read(root, EntryPath.BOTS, BotEntry::read, b -> b.repo().toLowerCase(Locale.ROOT))) {
            Optional<EntryFacts.Repo> repo = facts.repo(entry.repo());
            if (repo.isEmpty()) continue;
            Catalog.Bot before = previousBots.get(entry.repo());
            List<Catalog.Bot.Release> releases = new ArrayList<>();
            for (ApprovedRelease release : entry.releases().reversed()) {
                if (!published(facts, entry.repo(), release.tag())) continue;
                List<DerivedFacts.Pin> pins = derived.bot(entry.repo(), release.tag());
                if (pins == null && before != null && before.repo().equalsIgnoreCase(entry.repo())) {
                    pins = previous(before.releases(), release, Catalog.Bot.Release::tag, Catalog.Bot.Release::commit)
                            .map(Catalog.Bot.Release::plugins).orElse(null);
                }
                if (pins == null || pins.isEmpty() || !pins.stream().allMatch(p -> listed.contains(p.id())
                        && VERSION.matcher(p.version() == null ? "" : p.version()).matches())) continue;
                releases.add(new Catalog.Bot.Release(release.tag(), release.commit(), pins));
            }
            if (releases.isEmpty()) continue;
            bots.add(new Catalog.Bot(entry.repo(), publishers.rankOf(repo.get().ownerId()),
                    repo.get().description(), repo.get().topics(), repo.get().stars(),
                    entry.detached() ? null : repo.get().parent().orElse(null), releases));
        }
        bots.sort(Comparator.comparing(b -> b.repo().toLowerCase(Locale.ROOT)));

        Catalog built = new Catalog(Catalog.SCHEMA_VERSION, now, plugins, bots);
        return previous.filter(p -> p.builtAt(now).equals(built)).map(p -> built.builtAt(p.builtAt()))
                .orElse(built);
    }

    /** Every entry {@link #build} reads: plugins first, then bots. */
    static List<Entry> entries(Path root) throws IOException {
        List<Entry> entries = new ArrayList<>(read(root, EntryPath.PLUGINS, PluginEntry::read, PluginEntry::id));
        entries.addAll(read(root, EntryPath.BOTS, BotEntry::read, b -> b.repo().toLowerCase(Locale.ROOT)));
        return entries;
    }

    /** A released version as a pom pins it: digits and dots, an optional {@code v}, no {@code -SNAPSHOT}. */
    static final Pattern VERSION =Pattern.compile("v?\\d+(\\.\\d+){0,3}");

    /** At most this long, no control characters: a name Studio shows as a label. */
    private static final Pattern NAME = Pattern.compile("[^\\p{Cntrl}]{1,80}");

    /** The previous row's release with {@code release}'s tag and pinned commit: its facts still hold. */
    private static <R> Optional<R> previous(List<R> rows, ApprovedRelease release, Function<R, String> tag,
                                            Function<R, String> commit) {
        return rows.stream().filter(r -> release.tag().equals(tag.apply(r)) && release.commit().equals(commit.apply(r)))
                .findFirst();
    }

    /** Whether {@code repo}'s release at {@code tag} is still a published GitHub Release. */
    private static boolean published(EntryFacts facts, String repo, String tag) {
        return facts.release(repo, tag).filter(r -> !r.draft() && !r.prerelease()).isPresent();
    }

    /** Why a plugin's derived facts may not be listed, or {@code null}. */
    private static String refusal(DerivedFacts.PluginFacts jar) {
        if (jar.name() == null || !NAME.matcher(jar.name()).matches()) return "its name is empty, too long or holds control characters";
        if (jar.minContractVersion() != null && !VERSION.matcher(jar.minContractVersion()).matches()) {
            return "minContractVersion \"" + jar.minContractVersion() + "\" is not a released version";
        }
        return EditorDependencies.refusal(jar.editorDependencies());
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
                // A symbolic link could point the read at any file on the runner, and the log is public.
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    System.err.println("skipped " + path + ": not a regular file");
                    continue;
                }
                T entry;
                try {
                    entry = reader.read(file);
                } catch (IOException | IllegalArgumentException e) {
                    System.err.println("skipped " + path + ": not an entry of this schema");
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
