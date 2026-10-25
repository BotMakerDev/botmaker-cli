package com.botmaker.cli;

import com.botmaker.cli.bmk.BotFile;
import com.botmaker.cli.bmk.BotFiles;
import com.botmaker.cli.bmk.Central;
import com.botmaker.cli.publish.PublishTarget;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * {@code botmaker bot export} — writes the bot at its tagged {@code HEAD} as a {@code .bmk} ({@link BotFile}), the
 * file someone who only runs bots opens in BotMaker, and prints its path and sha256.
 *
 * <p>Made from a commit, never from a working copy: a tree with uncommitted changes, or a {@code HEAD} no tag names,
 * is refused, because the file says which tag and commit it is and the catalog rebuilds it from that commit.
 */
@Command(name = "export",
        header = "Write this bot as a .bmk file, for people who only run bots.",
        description = "Run in the bot's folder, at a tagged commit. Packs the pom, icon, sources and resources, and "
                + "every jar it runs with that Maven Central does not serve byte for byte; Central's jars are listed "
                + "and fetched on first open.",
        mixinStandardHelpOptions = true)
final class BotExportCommand implements Callable<Integer> {

    @ParentCommand
    private BotCommand parent;

    @Option(names = "--dir", defaultValue = ".", description = "The bot project. Default: ${DEFAULT-VALUE}")
    private String directory;

    @Option(names = "--out", paramLabel = "<dir>", description = "Where the file is written. Default: the bot's folder.")
    private String out;

    @Override
    public Integer call() throws IOException {
        Console console = parent.main().console();
        Path dir = Path.of(directory).toAbsolutePath().normalize();
        try {
            Path written = export(dir, out == null ? dir : Path.of(out).toAbsolutePath().normalize(), new Mvn(console));
            console.out(written.toString());
            console.out("sha256 " + BotFiles.sha256(written));
            return 0;
        } catch (BotFile.Refused e) {
            console.error(e.getMessage());
            return 1;
        }
    }

    private static Path export(Path dir, Path outDir, Mvn mvn) throws IOException {
        if (!Files.isRegularFile(dir.resolve("pom.xml"))) throw new BotFile.Refused(dir + " has no pom.xml.");
        if (!git(dir, "status", "--porcelain").map(String::isBlank).orElse(false)) {
            throw new BotFile.Refused("Commit first: a .bmk is made from a commit, and " + dir
                    + " has uncommitted changes (or is not a git repository).");
        }
        List<String> tags = git(dir, "tag", "--points-at", "HEAD").map(s -> s.lines().filter(t -> !t.isBlank())
                .sorted().toList()).orElse(List.of());
        if (tags.isEmpty()) {
            throw new BotFile.Refused("HEAD has no tag. Tag the commit (or publish it) first: a .bmk names the "
                    + "version it is.");
        }
        if (tags.size() > 1) {
            throw new BotFile.Refused("HEAD has several tags (" + String.join(", ", tags) + "); a .bmk names one "
                    + "version. Delete the others.");
        }
        String slug = slug(remote -> git(dir, "remote", "get-url", remote))
                .orElseThrow(() -> new BotFile.Refused("none of the remotes " + String.join(", ", REMOTES)
                        + " is a GitHub repository: a .bmk names the repository it was made from."));
        return BotExport.write(dir, tags.getFirst(), slug, outDir, BotExport.maven(mvn).of(dir), Central.maven());
    }

    /**
     * The remotes a .bmk's repository is read from, in order: yours ({@code origin}; {@code mine} in a project Studio
     * has not renamed yet), then the author's ({@code upstream}; {@code original} before the rename), for a clone
     * never published (2026-10-25, finding N2).
     */
    static final List<String> REMOTES = List.of("origin", "mine", "upstream", "original");

    /** The first of {@link #REMOTES} whose url is a GitHub repository, as {@code owner/repo}. */
    static Optional<String> slug(Function<String, Optional<String>> url) {
        return REMOTES.stream().map(url).flatMap(Optional::stream).map(PublishTarget::slugOf)
                .flatMap(Optional::stream).findFirst();
    }

    private static Optional<String> git(Path dir, String... args) {
        String[] argv = new String[args.length + 1];
        argv[0] = "git";
        System.arraycopy(args, 0, argv, 1, args.length);
        return Shell.capture(dir, argv);
    }
}
