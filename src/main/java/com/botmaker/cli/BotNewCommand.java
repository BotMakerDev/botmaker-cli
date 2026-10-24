package com.botmaker.cli;

import com.botmaker.cli.project.BlankProject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code botmaker bot new <name>} — a bot project, blank or started from somebody else's published bot.
 *
 * <p><b>Blank means blank: no SDK, no plugin, no BotMaker API.</b> That is not a smaller version of a bot
 * project, it is what a project with no plugins installed looks like, and it is one step from being a bot
 * (<b>Project ▸ Manage Plugins</b>). The platform's rule is that the SDK is one plugin among any number, so
 * a starting point naming it has already made a choice belonging to the person starting.
 *
 * <p><b>{@code --from} is a linked clone</b> (2026-10-23, the same path as Studio's Browse Bots and New
 * Project): the author's repository cloned at a release tag, branch {@code main} started there, and the
 * remote renamed {@code original}. Nothing is renamed, so the author's later releases merge in (Studio's
 * <i>Get vX.Y</i>); the remote is the whole record of where the bot came from.
 */
@Command(name = "new",
        header = "Create a bot project — blank, or from a published bot.",
        description = "With no --from this writes a blank project: a pom and one main(). With --from it clones "
                + "that bot at its newest release, linked to its author by the remote `original`.",
        mixinStandardHelpOptions = true)
final class BotNewCommand implements Callable<Integer> {

    /** The remote a project started from a bot names its author's repository by, as Studio does. */
    static final String ORIGINAL = "original";

    @ParentCommand
    private BotCommand parent;

    @Parameters(index = "0", paramLabel = "<name>",
            description = "The project name. Becomes the folder; for a blank project, the artifactId and the "
                    + "entry class's name too.")
    private String name;

    @Option(names = "--from", paramLabel = "<owner/repo>",
            description = "Start from a published bot instead of a blank project.")
    private String from;

    @Option(names = "--tag", paramLabel = "<tag>",
            description = "Which release of --from to start at. Default: its newest.")
    private String tag;

    @Option(names = "--package", paramLabel = "<pkg>",
            description = "The Java package of a blank project. Default: com.<name>, lowercased.")
    private String packageName;

    @Option(names = "--dir", defaultValue = ".", paramLabel = "<path>",
            description = "Where to create it. The project lands in <path>/<name>. Default: ${DEFAULT-VALUE}")
    private String directory;

    @Override
    public Integer call() throws IOException {
        Console console = parent.main().console();
        boolean blank = from == null || from.isBlank();
        String pkg = packageName == null || packageName.isBlank()
                ? BlankProject.defaultPackage(name) : packageName.trim();
        BlankProject.requireUsable(name, pkg);
        if (!blank && packageName != null) {
            throw new IOException("--package names a blank project's package; a bot started --from keeps its"
                    + " author's, so its updates still merge");
        }

        Path projectDir = Path.of(directory).toAbsolutePath().normalize().resolve(name);
        if (Files.isDirectory(projectDir)) {
            try (var existing = Files.list(projectDir)) {
                if (existing.findAny().isPresent()) {
                    throw new IOException(projectDir + " already exists and is not empty");
                }
            }
        }

        if (blank) {
            BlankProject.write(projectDir, name, pkg);
            console.step("Wrote a blank project in " + projectDir);
        } else if (fromBot(console, projectDir) != 0) {
            return 1;
        }

        console.out(projectDir.toString());
        return 0;
    }

    private int fromBot(Console console, Path projectDir) throws IOException {
        String slug = slug(from);
        String release = tag == null || tag.isBlank() ? newestRelease(slug) : tag.trim();
        Path parentDir = projectDir.getParent();
        Files.createDirectories(parentDir);
        if (Shell.run(console, parentDir, "git", "clone", "-q", "--branch", release,
                "https://github.com/" + slug + ".git", projectDir.toString()) != 0) {
            console.error("could not clone " + slug + " at " + release + ". Is the repository public, and is "
                    + release + " one of its tags?");
            return 1;
        }
        // -B: a --tag naming the branch main has the clone on main already.
        if (Shell.run(console, projectDir, "git", "checkout", "-q", "-B", "main") != 0
                || Shell.run(console, projectDir, "git", "remote", "rename", "origin", ORIGINAL) != 0) {
            console.error("cloned into " + projectDir + ", but could not start branch main or name the remote "
                    + ORIGINAL);
            return 1;
        }
        console.step("Started from " + slug + "@" + release + " in " + projectDir + ", linked to its author as "
                + "the remote `" + ORIGINAL + "`.");
        console.step("A bot runs its author's code on your computer: read it before you run it.");
        return 0;
    }

    /** The newest GitHub Release of {@code slug}, as Studio installs it: never a bare tag. */
    private static String newestRelease(String slug) throws IOException {
        return Shell.capture(Path.of("."), "gh", "release", "view", "--repo", slug, "--json", "tagName",
                        "--jq", ".tagName")
                .filter(found -> !found.isBlank())
                .orElseThrow(() -> new IOException(slug + " has no release `gh` could read. Name one with --tag,"
                        + " or check that `gh` is installed."));
    }

    /**
     * {@code owner/repo}, refused in any other shape — and never starting with a dash, since it is passed to
     * {@code gh} as a positional argument.
     */
    static String slug(String source) throws IOException {
        String trimmed = source.trim();
        if (!trimmed.matches("[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9._][A-Za-z0-9._-]*")
                || trimmed.endsWith("/.") || trimmed.endsWith("/..")) {
            throw new IOException("--from wants owner/repo, got '" + source + "'");
        }
        return trimmed;
    }
}
