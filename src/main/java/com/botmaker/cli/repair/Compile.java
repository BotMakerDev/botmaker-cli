package com.botmaker.cli.repair;

import com.botmaker.cli.Console;
import com.botmaker.cli.Mvn;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the last compile of the project on disk said, as {@link Damage} reads it: a project that compiles keeps every
 * member it no longer has, as nothing shows that deleting one was a mistake.
 */
public record Compile(State state, List<Problem> problems) {

    public enum State { CLEAN, FAILED, NOT_RUN }

    /**
     * One compile error.
     *
     * @param path    relative to the project, with {@code /}
     * @param message javac's first line
     * @param symbol  the name javac could not find, or empty
     * @param owner   the simple name of the type it looked in, or empty
     */
    public record Problem(String path, int line, String message, String symbol, String owner) {

        public Problem(String path, int line, String message) {
            this(path, line, message, "", "");
        }

        /** Whether this error is a missing member of that name in that type; a missing type is looked for anywhere. */
        boolean asksFor(String name, String memberOwner, boolean isType) {
            return !name.isEmpty() && name.equals(symbol)
                    && (isType || owner.isEmpty() || owner.equals(memberOwner));
        }
    }

    /** The commits of a project that has no record are built by Maven; one build gets this long. */
    private static final Duration BUILD_LIMIT = Duration.ofMinutes(5);

    /** javac's own form. */
    private static final Pattern JAVAC = Pattern.compile("^(.+\\.java):(\\d+): error: (.*)$");

    /** Maven's report of the same. */
    private static final Pattern MAVEN = Pattern.compile("^\\[ERROR] (.+\\.java):\\[(\\d+),\\d+] (.*)$");

    private static final Pattern SYMBOL = Pattern.compile("^(?:\\[ERROR] )?\\s+symbol:\\s+\\w+\\s+([\\w$]+).*$");

    /** {@code location: class a.b.Bot}, or {@code location: variable x of type Bot<T>}. */
    private static final Pattern LOCATION = Pattern.compile("^(?:\\[ERROR] )?\\s+location:\\s+.*?([\\w$.]+)(?:<.*)?$");

    public Compile {
        problems = List.copyOf(problems);
    }

    public static Compile clean() {
        return new Compile(State.CLEAN, List.of());
    }

    public static Compile notRun() {
        return new Compile(State.NOT_RUN, List.of());
    }

    public static Compile failed(List<Problem> problems) {
        return new Compile(State.FAILED, problems);
    }

    /** A failed compile from what javac or Maven printed, with the paths made relative to {@code project}. */
    public static Compile failed(String output, Path project) {
        List<Problem> found = new ArrayList<>();
        for (String line : output.split("\\R")) {
            Matcher matcher = JAVAC.matcher(line);
            if (!matcher.matches()) matcher = MAVEN.matcher(line);
            if (matcher.matches()) {
                found.add(new Problem(relative(matcher.group(1), project), Integer.parseInt(matcher.group(2)),
                        matcher.group(3)));
            } else if (!found.isEmpty()) {
                Problem last = found.getLast();
                Matcher symbol = SYMBOL.matcher(line);
                Matcher location = LOCATION.matcher(line);
                if (symbol.matches()) {
                    found.set(found.size() - 1, new Problem(last.path(), last.line(), last.message(), symbol.group(1),
                            last.owner()));
                } else if (location.matches()) {
                    String type = location.group(1);
                    found.set(found.size() - 1, new Problem(last.path(), last.line(), last.message(), last.symbol(),
                            type.substring(type.lastIndexOf('.') + 1)));
                }
            }
        }
        return failed(List.copyOf(new LinkedHashSet<>(found))); // Maven reports each error twice.
    }

    /**
     * Whether the project in a folder compiles, by Maven alone: a commit put in a folder of its own has no state of
     * Studio's to compile with, and its pom may pin other versions than the project does now. An old pom's build
     * plugins are code nobody looked at, and may be another author's, so it runs as {@link Mvn#runUntrusted} does: no
     * token or settings by environment, and at most a time limit. It writes to a repository of its own, reading the
     * user's as a tail, so it can leave nothing there for a trusted build to load. This is no sandbox: the plugins
     * still run as the user, and can read what the user can.
     *
     * @throws UncheckedIOException when Maven could not be run, which says nothing about the commit
     */
    public static boolean compiles(Path folder) {
        Path home = Path.of(System.getProperty("user.home"));
        try {
            return new Mvn(new Console(true)).runUntrusted(folder, BUILD_LIMIT, "-q",
                    "-Dmaven.repo.local=" + home.resolve(".cache/botmaker/repair-repository"),
                    "-Dmaven.repo.local.tail=" + home.resolve(".m2/repository"), "compile").ok();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relative(String file, Path project) {
        Path path = Path.of(file.strip());
        return (path.isAbsolute() && path.startsWith(project) ? project.relativize(path) : path).toString()
                .replace('\\', '/');
    }
}
