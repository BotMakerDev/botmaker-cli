package com.botmaker.cli;

import com.botmaker.cli.bmk.BotFile;
import com.botmaker.cli.bmk.BotFiles;
import com.botmaker.cli.bmk.BotJson;
import com.botmaker.cli.publish.PublishTarget;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

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
                + "the JitPack-built jars it runs with; the Maven Central jars are listed and fetched on first open.",
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
        String tag = tags.getFirst();
        String commit = git(dir, "rev-parse", "HEAD").orElseThrow(() -> new BotFile.Refused("git rev-parse failed."));
        // origin from the command line, mine from Studio (its publish pushes there)
        String slug = git(dir, "remote", "get-url", "origin").or(() -> git(dir, "remote", "get-url", "mine"))
                .flatMap(PublishTarget::slugOf)
                .orElseThrow(() -> new BotFile.Refused("neither origin nor mine is a GitHub repository: a .bmk "
                        + "names the repository it was made from."));
        Path list = Files.createTempFile("botmaker-deps", ".txt");
        Path ids = Files.createTempFile("botmaker-blobs", ".txt");
        Path batch = Files.createTempFile("botmaker-blobs", ".bin");
        try {
            // the commit's bytes, not the checkout's: no line-ending conversion, filter or skipped file reaches the
            // .bmk, so the catalog's rebuild of this commit on another machine gives the same file
            Map<String, String> blobs = BotFiles.sources(git(dir, "ls-tree", "-r", "-z", "HEAD")
                    .orElseThrow(() -> new BotFile.Refused("git ls-tree failed.")));
            Files.writeString(ids, blobs.values().stream().map(id -> id + "\n").collect(Collectors.joining()));
            if (!Shell.pipe(dir, ids, batch, "git", "cat-file", "--batch")) {
                throw new BotFile.Refused("git cat-file failed.");
            }
            Map<String, byte[]> sources = BotFiles.contents(blobs, Files.readAllBytes(batch));

            BotJson.Head head = BotFiles.head(dir.resolve("pom.xml"), sources, tag, commit, slug);

            Mvn.Result listed = mvn.run(dir, "-q", "dependency:list", "-DoutputAbsoluteArtifactFilename=true",
                    "-DoutputFile=" + list, "-DappendOutput=false");
            if (!listed.ok()) {
                throw new BotFile.Refused("could not resolve the bot's dependencies:\n" + listed.tail(15));
            }
            List<BotFile.Jar> jars = BotFiles.jars(Files.readString(list));

            Files.createDirectories(outDir);
            Path target = outDir.resolve(BotFiles.fileName(head.name(), tag));
            Path next = Files.createTempFile(outDir, ".bmk", ".next");
            try {
                try (OutputStream stream = Files.newOutputStream(next)) {
                    BotFile.write(sources, head, jars, stream);
                }
                Files.move(next, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(next);
            }
            return target;
        } finally {
            Files.deleteIfExists(list);
            Files.deleteIfExists(ids);
            Files.deleteIfExists(batch);
        }
    }

    private static Optional<String> git(Path dir, String... args) {
        String[] argv = new String[args.length + 1];
        argv[0] = "git";
        System.arraycopy(args, 0, argv, 1, args.length);
        return Shell.capture(dir, argv);
    }
}
