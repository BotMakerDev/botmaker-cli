package com.botmaker.cli.catalog;

import com.botmaker.java.grammar.ParamsFitter;
import com.botmaker.java.grammar.SourceNames;
import com.botmaker.java.grammar.SourceParser;
import com.botmaker.java.parse.JavaLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A bot's {@code params/} package filled from its committed {@code starting-params/}, as Studio fills a fresh copy on
 * open: the package is never in a tag, so a checkout compiles only once it is there.
 */
final class StartingParams {

    private StartingParams() {}

    /**
     * Fills {@code bot}'s {@code params/} from {@code starting-params/} ({@link ParamsFitter#onDisk}); nothing when the
     * bot has no starting copy. The package is found from a starting file's own package declaration.
     */
    static void fill(Path bot) throws IOException {
        Path starting = bot.resolve(ParamsFitter.STARTING);
        Optional<Path> root = paramsRoot(bot, starting);
        if (root.isEmpty()) return;
        ParamsFitter.onDisk(JavaLevel.LATEST, root.get(), starting, bot.resolve(".botmaker/values-removed"));
    }

    /** {@code src/main/java/<package of params>}, read off the first starting file; empty without one. */
    static Optional<Path> paramsRoot(Path bot, Path starting) throws IOException {
        if (!Files.isDirectory(starting)) return Optional.empty();
        Optional<Path> first;
        try (Stream<Path> walk = Files.walk(starting)) {
            first = walk.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".java")).sorted().findFirst();
        }
        if (first.isEmpty()) return Optional.empty();
        String pkg = SourceNames.packageOf(SourceParser.parse(JavaLevel.LATEST, Files.readString(first.get())));
        Path packageDir = bot.resolve("src/main/java").resolve(pkg.replace('.', '/'));
        Path relative = starting.relativize(first.get().getParent());
        // The file sits as deep under params/ as under starting-params/: climb that many folders.
        Path root = packageDir;
        for (int i = 0; i < relative.getNameCount() && !relative.toString().isEmpty(); i++) root = root.getParent();
        if (root == null || !root.getFileName().toString().equals(ParamsFitter.PARAMS)) return Optional.empty();
        return Optional.of(root);
    }
}
