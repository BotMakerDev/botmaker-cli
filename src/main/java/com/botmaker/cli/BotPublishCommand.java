package com.botmaker.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code botmaker bot publish} — repository, release, and the topic that lists it.
 *
 * <h2>Three steps, in one order, each refusing before the next</h2>
 *
 * <ol>
 *   <li><b>The repository</b> — {@code gh repo create --source . --push}, or a push when the working copy's
 *       {@code origin} already is that repository. A dirty tree is refused: what gets published is a commit,
 *       and a commit that omits the file the author is looking at is the confusing kind of wrong.</li>
 *   <li><b>The release</b> — {@code gh release create}. A bot is started from its newest release, so a
 *       listed bot with none behind it is something nobody can start from.</li>
 *   <li><b>The listing</b> — the topic {@link #TOPIC} on the repository, beside the author's tags as topics,
 *       and the description. Studio's Browse Bots finds bots by that topic (2026-10-23: no gallery repository,
 *       nothing stored but the repository's own metadata) and lists one whose release's pom declares a
 *       registry plugin.</li>
 * </ol>
 *
 * <p>{@code botmaker bot unpublish} takes the topic off; the repository and its releases stay.
 */
@Command(name = "publish",
        header = "Publish this bot: repository, release, and the botmaker-bot topic.",
        description = "Creates the repository, pushes, cuts the release, then adds the topic botmaker-bot that "
                + "lists it in Studio's Browse Bots. A bot is listed when its release's pom declares a plugin "
                + "from the registry.",
        mixinStandardHelpOptions = true)
final class BotPublishCommand implements Callable<Integer> {

    /** The topic Studio's Browse Bots searches for. */
    static final String TOPIC = "botmaker-bot";

    /** GitHub's own limits on a repository's topics. */
    static final int MAX_TOPICS = 20;
    static final int MAX_TOPIC_LENGTH = 50;

    @ParentCommand
    private BotCommand parent;

    @Option(names = "--dir", defaultValue = ".", description = "The bot project. Default: ${DEFAULT-VALUE}")
    private String directory;

    @Option(names = "--repo", paramLabel = "<owner/name>", required = true,
            description = "Where the bot's source lives on GitHub.")
    private String repo;

    @Option(names = "--description", defaultValue = "",
            description = "One sentence, shown in Browse Bots. Empty keeps the repository's.")
    private String description;

    @Option(names = "--tags", defaultValue = "", paramLabel = "<a,b,c>",
            description = "Comma separated; each becomes a topic.")
    private String tags;

    @Option(names = "--tag", defaultValue = "v0.1.0", paramLabel = "<tag>",
            description = "The release to cut. Default: ${DEFAULT-VALUE}")
    private String tag;

    @Override
    public Integer call() throws IOException {
        Console console = parent.main().console();
        Path dir = Path.of(directory).toAbsolutePath().normalize();
        if (!Files.isRegularFile(dir.resolve("pom.xml"))) {
            throw new IOException("no pom.xml in " + dir + " — point `botmaker bot publish` at a bot project");
        }
        String slug = BotNewCommand.slug(repo);
        if (!publishRepository(console, dir, slug) || !cutRelease(console, dir, slug)) {
            return 1;
        }
        List<String> args = new ArrayList<>(List.of("repo", "edit", slug));
        for (String topic : topics(List.of(tags.split(",")))) {
            args.addAll(List.of("--add-topic", topic));
        }
        if (!description.isBlank()) {
            args.addAll(List.of("--description", description.strip()));
        }
        if (Shell.gh(console, dir, args.toArray(String[]::new)) != 0) {
            console.error("released " + tag + ", but could not add the topic " + TOPIC + " to " + slug
                    + ", so it is not listed. GitHub takes at most " + MAX_TOPICS + " topics, counting the ones the"
                    + " repository already has; re-run once `gh` can edit it.");
            return 1;
        }
        console.step(slug + " is listed in Browse Bots once its release's pom declares a registry plugin.");
        return 0;
    }

    /**
     * The topics a publish adds: {@link #TOPIC} first, then each tag as GitHub takes a topic, at most
     * {@link #MAX_TOPICS}. A tag spelling a {@code botmaker-} topic is dropped: those are the listing's own.
     */
    static List<String> topics(List<String> tags) {
        Set<String> out = new LinkedHashSet<>(List.of(TOPIC));
        for (String tag : tags) {
            String topic = asTopic(tag);
            if (!topic.isEmpty() && !topic.startsWith("botmaker-") && out.size() < MAX_TOPICS) {
                out.add(topic);
            }
        }
        return List.copyOf(out);
    }

    /** A tag as GitHub accepts a topic: lowercase letters, digits and single dashes, at most 50 long. */
    static String asTopic(String tag) {
        String topic = tag.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return topic.length() <= MAX_TOPIC_LENGTH ? topic
                : topic.substring(0, MAX_TOPIC_LENGTH).replaceAll("-+$", "");
    }

    /**
     * Step one: the repository holds this commit.
     *
     * <p>A working copy that is not a git repository yet is initialised and committed, because that is
     * mechanical and is exactly the part this command exists to remove. A working copy with <em>uncommitted
     * changes</em> is refused instead: guessing that the author meant to publish them is guessing about the
     * one thing they can see and this command cannot.
     */
    private static boolean publishRepository(Console console, Path dir, String slug) throws IOException {
        if (Shell.capture(dir, "git", "rev-parse", "--git-dir").isEmpty()) {
            console.step("Not a git repository yet — initialising one.");
            // -b main, not the built-in default: a machine with no init.defaultBranch set gets `master`,
            // which is what the first real run of this command produced.
            if (Shell.run(console, dir, "git", "init", "-q", "-b", "main") != 0
                    || Shell.run(console, dir, "git", "add", "-A") != 0
                    || Shell.run(console, dir, "git", "commit", "-qm", "Initial commit") != 0) {
                console.error("could not make the first commit in " + dir);
                return false;
            }
        } else if (!Shell.capture(dir, "git", "status", "--porcelain").orElse("").isBlank()) {
            console.error("the working tree has uncommitted changes, and what gets published is a commit."
                    + " Commit or stash them, then re-run.");
            return false;
        }

        boolean exists = Shell.capture(dir, "gh", "repo", "view", slug, "--json", "name").isPresent();
        if (!exists) {
            if (Shell.gh(console, dir, "repo", "create", slug, "--source", ".", "--push", "--public") != 0) {
                console.error("could not create " + slug + ". Is `gh` installed and authenticated?");
                return false;
            }
            return true;
        }

        // It exists. Publishing into it is right when it is this working copy's own origin — a second
        // release of the author's own bot — and wrong when it is somebody else's repository that happens
        // to have the name, which is a mistake no push should be the first to discover.
        Optional<String> origin = Shell.capture(dir, "git", "remote", "get-url", "origin");
        if (origin.filter(url -> BotNewCommand.names(url, slug)).isEmpty()) {
            console.error(slug + " already exists and is not this working copy's origin. Pick another --repo,"
                    + " or set origin to it if it is yours.");
            return false;
        }
        console.step(slug + " already exists and is this project's origin — pushing.");
        if (Shell.run(console, dir, "git", "push", "-u", "origin", "HEAD") != 0) {
            console.error("could not push to " + slug);
            return false;
        }
        return true;
    }

    /** Step two: the release, which is what a bot is started from. */
    private boolean cutRelease(Console console, Path dir, String slug) throws IOException {
        if (Shell.capture(dir, "gh", "release", "view", tag, "--repo", slug).isPresent()) {
            console.step("Release " + tag + " already exists — leaving it alone.");
            return true;
        }
        if (Shell.gh(console, dir, "release", "create", tag, "--repo", slug, "--title", tag,
                "--notes", slug + " " + tag + ".") != 0) {
            console.error("could not create release " + tag + " on " + slug
                    + ". A bot is started from its newest release, so it was not listed.");
            return false;
        }
        return true;
    }
}
