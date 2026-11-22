// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.UnaryOperator;

/**
 * Where this computer's Studio is, as {@code botmaker studio set} saved it: the umbrella checkout {@code studio dev}
 * builds and runs, and the command of a released Studio. Every command that starts Studio ({@code studio},
 * {@code plugin run}, {@code mcp-bridge}) takes its flag first, then its environment variable, then these.
 *
 * @param umbrella the checkout, a folder holding {@code botmaker-studio/pom.xml}
 * @param command  a released Studio's command: an executable's path, spaces and all, or a command line
 */
public record StudioPlaces(Optional<Path> umbrella, Optional<String> command) {

    /** The file in BotMaker's config folder, beside Studio's own settings. */
    static final String FILE = "studio-places.properties";
    /** Where the rpm and deb install Studio's launcher, a path jpackage fixes. */
    static final Path PACKAGED = Path.of("/opt/botmaker-studio/bin/BotMaker Studio");
    private static final String UMBRELLA_KEY = "umbrella";
    private static final String COMMAND_KEY = "command";

    /** None saved. */
    static final StudioPlaces NONE = new StudioPlaces(Optional.empty(), Optional.empty());

    /** What {@code botmaker studio set} saved on this computer; none when it saved nothing or the file is unread. */
    public static StudioPlaces saved() {
        return read(file());
    }

    static StudioPlaces read(Path file) {
        if (!Files.isRegularFile(file)) return NONE;
        Properties saved = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            saved.load(in);
        } catch (IOException | IllegalArgumentException unread) {
            return NONE;
        }
        Optional<Path> umbrella;
        try {
            umbrella = text(saved.getProperty(UMBRELLA_KEY)).map(Path::of);
        } catch (InvalidPathException edited) {
            umbrella = Optional.empty();
        }
        return new StudioPlaces(umbrella, text(saved.getProperty(COMMAND_KEY)));
    }

    /** Writes these to this computer's {@link #FILE}. */
    void save() throws IOException {
        write(file());
    }

    void write(Path file) throws IOException {
        Properties saved = new Properties();
        umbrella.ifPresent(dir -> saved.setProperty(UMBRELLA_KEY, dir.toString()));
        command.ifPresent(line -> saved.setProperty(COMMAND_KEY, line));
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file)) {
            saved.store(out, "Where Studio is: botmaker studio set");
        }
    }

    /** The checkout: {@code flag}, else {@code $BOTMAKER_UMBRELLA}, else the one saved while it is still a checkout. */
    public static Optional<Path> umbrella(String flag) {
        return text(flag).or(() -> text(System.getenv("BOTMAKER_UMBRELLA"))).map(Path::of)
                .or(() -> saved().umbrella().filter(StudioPlaces::isCheckout));
    }

    /**
     * The checkout a command that starts either Studio runs ({@code plugin run}, {@code mcp-bridge}): one given by flag
     * or environment first, then none when a released Studio is given so, and only then the saved checkout. What is
     * typed or set for this run outranks what was saved once.
     */
    public static Optional<Path> umbrellaFirst(String umbrellaFlag, String studioFlag) {
        Optional<Path> given = text(umbrellaFlag).or(() -> text(System.getenv("BOTMAKER_UMBRELLA"))).map(Path::of);
        if (given.isPresent()) return given;
        if (text(studioFlag).or(() -> text(System.getenv("BOTMAKER_STUDIO"))).isPresent()) return Optional.empty();
        return saved().umbrella().filter(StudioPlaces::isCheckout);
    }

    private static boolean isCheckout(Path dir) {
        try {
            checkout(dir);
            return true;
        } catch (IllegalArgumentException notOne) {
            return false;
        }
    }

    /**
     * A released Studio's command line: {@code flag}, else {@code $BOTMAKER_STUDIO}, else the one saved, else the
     * package's launcher when it is installed; empty when none.
     */
    public static Optional<List<String>> released(String flag) {
        return text(flag).or(() -> text(System.getenv("BOTMAKER_STUDIO"))).or(() -> saved().command())
                .map(StudioPlaces::argv)
                .or(() -> Files.isExecutable(PACKAGED) ? Optional.of(List.of(PACKAGED.toString())) : Optional.empty());
    }

    /** {@code line} as a command: one executable's path when it names one, spaces and all, else split at spaces. */
    static List<String> argv(String line) {
        String command = line.strip();
        try {
            if (Files.isRegularFile(Path.of(command))) return List.of(command);
        } catch (InvalidPathException notAPath) {
            // A command line with arguments.
        }
        return List.of(command.split("\\s+"));
    }

    /**
     * The checkout {@code dir} names: itself when it holds {@code botmaker-studio/pom.xml}, its parent when it is that
     * {@code botmaker-studio} folder.
     *
     * @throws IllegalArgumentException when it is neither
     */
    static Path checkout(Path dir) {
        Path at = dir.toAbsolutePath().normalize();
        if (Files.isRegularFile(at.resolve("botmaker-studio/pom.xml"))) return at;
        if (Files.isRegularFile(at.resolve("pom.xml")) && at.getParent() != null
                && Files.isRegularFile(at.getParent().resolve("botmaker-studio/pom.xml"))) {
            return at.getParent();
        }
        throw new IllegalArgumentException(dir + " is not a BotMaker checkout: it holds no botmaker-studio/pom.xml, "
                + "and is not that botmaker-studio folder.");
    }

    /** This computer's {@link #FILE}. */
    static Path file() {
        String override = System.getProperty("botmaker.config.dir");
        if (override != null && !override.isBlank()) return Path.of(override).resolve(FILE);
        return config(System.getProperty("os.name", ""), System::getenv, System.getProperty("user.home", "."))
                .resolve(FILE);
    }

    /**
     * BotMaker's config folder, as shared's {@code UserDirs.config()} names it (its {@code botmaker.config.dir} override
     * in {@link #file}): a path a user can see, copied because this program does not depend on shared.
     */
    static Path config(String osName, UnaryOperator<String> env, String home) {
        String os = osName.toLowerCase();
        if (os.contains("win")) {
            String local = env.apply("LOCALAPPDATA");
            return local == null || local.isBlank() ? Path.of(home, "BotMaker") : Path.of(local, "BotMaker");
        }
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "botmaker");
        String xdg = env.apply("XDG_CONFIG_HOME");
        return xdg == null || xdg.isBlank() ? Path.of(home, ".config", "botmaker") : Path.of(xdg, "botmaker");
    }

    private static Optional<String> text(String value) {
        return Optional.ofNullable(value).map(String::strip).filter(s -> !s.isEmpty());
    }
}
