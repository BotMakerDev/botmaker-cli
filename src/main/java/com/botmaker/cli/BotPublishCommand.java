package com.botmaker.cli;

import com.botmaker.cli.catalog.ApprovedRelease;
import com.botmaker.cli.catalog.BotEntry;
import com.botmaker.cli.catalog.Entry;
import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.catalog.GitHubFacts;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker bot publish} — repository, release, and the catalog entry that lists it.
 *
 * <h2>Three steps, in one order, each refusing before the next</h2>
 *
 * <ol>
 *   <li><b>The repository</b> — {@code gh repo create --source . --push}, or a push when the working copy's
 *       {@code origin} already is that repository. A dirty tree is refused: what gets published is a commit,
 *       and a commit that omits the file the author is looking at is the confusing kind of wrong.</li>
 *   <li><b>The release</b> — {@code gh release create}, made as the signed-in user, which is what the catalog
 *       reads as control of the repository.</li>
 *   <li><b>The listing</b> — the entry {@code bots/<owner>__<repo>.json} ({@code {repo, tag}}), checked here by
 *       the catalog's own rules 2–5 and then opened as a pull request on {@code botmaker-catalog}
 *       ({@link CatalogPr}), which the catalog's checks merge when they pass (2026-10-23). The description, the
 *       topics and the stars are the repository's own, read by the catalog.</li>
 * </ol>
 *
 * <p>{@code botmaker bot unpublish} opens the pull request removing the entry; the repository and its releases
 * stay.
 */
@Command(name = "publish",
        header = "Publish this bot: repository, release, and its catalog entry.",
        description = "Creates the repository, pushes, cuts the release, then opens the pull request that lists "
                + "it in the catalog. The catalog's checks merge it when the release's pom declares a catalog "
                + "plugin, the bot compiles and a class holds main.",
        mixinStandardHelpOptions = true)
final class BotPublishCommand implements Callable<Integer> {

    @ParentCommand
    private BotCommand parent;

    @Option(names = "--dir", defaultValue = ".", description = "The bot project. Default: ${DEFAULT-VALUE}")
    private String directory;

    @Option(names = "--repo", paramLabel = "<owner/name>", required = true,
            description = "Where the bot's source lives on GitHub.")
    private String repo;

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
        Optional<EntryPath> path = EntryPath.parse(EntryPath.bot(slug));
        if (path.isEmpty()) {
            console.error(slug + " cannot be listed: the catalog names a bot's file after owner/name, and this one"
                    + " does not fit (letters, digits, '.', '-' and '_').");
            return 1;
        }
        Optional<String> author = CatalogPr.login();
        if (author.isEmpty()) {
            console.error("`gh` is not signed in; run `gh auth login` first.");
            return 1;
        }
        if (!publishRepository(console, dir, slug) || !cutRelease(console, dir, slug)) {
            return 1;
        }
        GitHubFacts facts = GitHubFacts.anonymousOrToken();
        Optional<Entry> previous = CatalogPr.listed(facts, path.get());
        Optional<ApprovedRelease> release = ApprovedRelease.pinBot(facts, slug, tag);
        if (release.isEmpty()) {
            console.error("released " + tag + ", but GitHub does not show the tag's commit yet; re-run to list it.");
            return 1;
        }
        if (previous.flatMap(e -> e.release(tag)).isPresent()) {
            console.step(slug + " " + tag + " is already listed in the catalog.");
            return 0;
        }
        // Appended to what the catalog holds: a fork that stopped following its original stays detached.
        Entry entry = previous.map(e -> e.appended(release.get()))
                .orElse(new BotEntry(slug, List.of(release.get()), false));
        if (!CatalogPr.passes(console, entry, author.get(), previous, facts)) {
            console.error("released " + tag + ", but the catalog would refuse the entry; no pull request opened.");
            return 1;
        }
        if (CatalogPr.open(console, CatalogPr.Change.put(entry, "botmaker bot publish"), author.get()) != 0) {
            return 1;
        }
        console.step(slug + " " + tag + " is listed once the catalog's checks pass on the pull request.");
        return 0;
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
