// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.catalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * index.yml's one step: {@code catalog.json} from the checkout at {@code <root>}.
 *
 * <p>It runs no entry's code: GitHub's facts through the API, each pom at its tag read as data ({@link PomFacts}),
 * then {@link CatalogBuilder}, which checks every derived value again. The file is rewritten only when its content
 * changed, so the workflow commits only then. A failed GitHub request stops the run with exit {@code 1} and writes
 * nothing, rather than unlisting what it could not see.
 *
 * <p>First it follows GitHub's renames and transfers ({@link #moves}, spec 2026-11-21 § 3): an entry whose
 * repository GitHub names otherwise now moves to the new file, its {@code repo} the new slug and the old one
 * appended to {@code formerly}, which the workflow commits with the catalog. No pull request: one opened with the
 * workflow's token starts no check (2026-11-21, the user's call), and the move is proven here by the repository's
 * id, the same fact {@link EntryGate}'s {@link EntryGate.Change.Move} asks of one opened by hand.
 *
 * <pre>java -cp … com.botmaker.cli.catalog.IndexCommand &lt;root&gt;</pre>
 */
public final class IndexCommand {

    /** One entry GitHub renamed or transferred: {@code from} as listed, {@code to} at its new slug. */
    public record Move(Entry from, Entry to) {}

    private IndexCommand() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: IndexCommand <root>");
            System.exit(2);
        }
        try {
            System.out.println(write(Path.of(args[0]), GitHubFacts.anonymousOrToken(),
                    Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), System.out::println)
                    ? "catalog.json updated" : "catalog.json unchanged");
        } catch (UncheckedIOException e) {
            System.err.println("could not reach GitHub: " + e.getCause().getMessage());
            System.exit(1);
        }
    }

    /**
     * The entries whose repository GitHub names otherwise now, ignoring case (a case-only difference is the same
     * slug to GitHub, and the file keeps its name), each with the entry it moves to. One gone or private is left.
     */
    public static List<Move> moves(List<Entry> entries, EntryFacts facts) {
        List<Move> moves = new ArrayList<>();
        for (Entry entry : entries) {
            Optional<EntryFacts.Repo> repo = facts.repo(entry.repo());
            if (repo.isEmpty() || repo.get().slug().equalsIgnoreCase(entry.repo())) continue;
            moves.add(new Move(entry, entry.moved(repo.get().slug())));
        }
        return moves;
    }

    /** Builds and writes the catalog; whether the file changed. */
    static <F extends EntryFacts & PomFacts.Source> boolean write(Path root, F facts, String now) throws IOException {
        return write(root, facts, now, line -> {});
    }

    /** {@link #write(Path, EntryFacts, String)}, saying each move made or left to {@code say}. */
    static <F extends EntryFacts & PomFacts.Source> boolean write(Path root, F facts, String now, Consumer<String> say)
            throws IOException {
        List<Entry> entries = CatalogBuilder.entries(root);
        if (move(root, moves(entries, facts), facts, say)) entries = CatalogBuilder.entries(root);
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

    /**
     * Each move made in the checkout: the new file written, the old one deleted. One whose new path, or a path
     * differing from it only by case, is already an entry is left, and said: two entries then claim one repository,
     * which the admin settles. Whether any was made.
     */
    private static boolean move(Path root, List<Move> moves, EntryFacts facts, Consumer<String> say)
            throws IOException {
        boolean any = false;
        for (Move move : moves) {
            String to = EntryPath.of(move.to());
            Optional<EntryFacts.Repo> was = facts.repo(move.from().repo());
            Optional<EntryFacts.Repo> is = facts.repo(move.to().repo());
            if (was.isEmpty() || is.isEmpty() || !was.get().sameAs(is.get())) {
                say.accept("not moved: " + move.from().repo() + " and " + move.to().repo()
                        + " are not one repository by GitHub's id.");
                continue;
            }
            if (taken(root, to)) {
                say.accept("not moved: " + move.from().repo() + " to " + to + ", which is already an entry.");
                continue;
            }
            Files.writeString(root.resolve(to), CatalogJson.strict().writeValueAsString(move.to()) + "\n");
            Files.delete(root.resolve(EntryPath.of(move.from())));
            say.accept("moved " + move.from().repo() + " to " + move.to().repo());
            any = true;
        }
        return any;
    }

    /** Whether {@code path}, or a file differing from it only by case, is in its folder already. */
    private static boolean taken(Path root, String path) throws IOException {
        Path file = root.resolve(path);
        if (Files.exists(file)) return true;
        String name = file.getFileName().toString();
        try (Stream<Path> files = Files.list(file.getParent())) {
            return files.anyMatch(f -> f.getFileName().toString().equalsIgnoreCase(name));
        }
    }
}
