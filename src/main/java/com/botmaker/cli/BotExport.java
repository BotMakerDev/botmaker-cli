package com.botmaker.cli;

import com.botmaker.cli.bmk.BotFile;
import com.botmaker.cli.bmk.BotFiles;
import com.botmaker.cli.bmk.BotJson;
import com.botmaker.cli.bmk.Central;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A bot folder's {@code HEAD} written as a {@code .bmk} ({@link BotFile}) with the programs the cli runs: {@code git}
 * for the commit's files, Maven for the jars. One writer for {@code botmaker bot export}, the cli's publish
 * ({@code PublishHost.bmk}) and the catalog's check, which rebuilds the file from the pinned commit and compares
 * (2026-10-25, plan phase 7).
 */
public final class BotExport {

    /** The jars a bot runs with, resolved in its folder: {@code dependency:list}'s, or a test's. */
    @FunctionalInterface
    public interface Jars {
        List<BotFile.Jar> of(Path dir) throws IOException;
    }

    private BotExport() {}

    /** The author's own Maven ({@link Mvn#run}): their settings and repository, their machine. */
    public static Jars maven(Mvn mvn) {
        return dir -> {
            Path list = Files.createTempFile("botmaker-deps", ".txt");
            try {
                Mvn.Result listed = mvn.run(dir, "-q", "dependency:list", "-DoutputAbsoluteArtifactFilename=true",
                        "-DoutputFile=" + list, "-DappendOutput=false");
                if (!listed.ok()) {
                    throw new BotFile.Refused("could not resolve the bot's dependencies:\n" + listed.tail(15));
                }
                return BotFiles.jars(Files.readString(list));
            } finally {
                Files.deleteIfExists(list);
            }
        };
    }

    /**
     * {@code dir}'s {@code HEAD}, released as {@code tag} of the GitHub repository {@code slug}, written into
     * {@code outDir} under {@link BotFiles#fileName}; its path. The files are the commit's bytes, not the checkout's:
     * no line-ending conversion, filter or skipped file reaches the {@code .bmk}, so the catalog's rebuild of the
     * commit on another machine gives the same file. {@code central} says which jars are listed, not packed.
     */
    public static Path write(Path dir, String tag, String slug, Path outDir, List<BotFile.Jar> jars, Central central)
            throws IOException {
        String commit = git(dir, "rev-parse", "HEAD").orElseThrow(() -> new BotFile.Refused("git rev-parse failed."));
        Path ids = Files.createTempFile("botmaker-blobs", ".txt");
        Path batch = Files.createTempFile("botmaker-blobs", ".bin");
        try {
            Map<String, String> blobs = BotFiles.sources(git(dir, "ls-tree", "-r", "-z", "HEAD")
                    .orElseThrow(() -> new BotFile.Refused("git ls-tree failed.")));
            Files.writeString(ids, blobs.values().stream().map(id -> id + "\n").collect(Collectors.joining()));
            if (!Shell.pipe(dir, ids, batch, "git", "cat-file", "--batch")) {
                throw new BotFile.Refused("git cat-file failed.");
            }
            Map<String, byte[]> sources = BotFiles.contents(blobs, Files.readAllBytes(batch));
            BotJson.Head head = BotFiles.head(dir.resolve("pom.xml"), sources, tag, commit, slug);

            Files.createDirectories(outDir);
            Path target = outDir.resolve(BotFiles.fileName(head.name(), tag));
            Path next = Files.createTempFile(outDir, ".bmk", ".next");
            try {
                try (OutputStream stream = Files.newOutputStream(next)) {
                    BotFile.write(sources, head, jars, central, stream);
                }
                Files.move(next, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(next);
            }
            return target;
        } finally {
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
