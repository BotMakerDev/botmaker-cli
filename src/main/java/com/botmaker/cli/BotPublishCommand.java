package com.botmaker.cli;

import com.botmaker.cli.gallery.GalleryEntry;
import com.botmaker.cli.gallery.GalleryFacts;
import com.botmaker.cli.gallery.GalleryGate;
import com.botmaker.cli.gallery.ListingPolicy;
import com.botmaker.cli.gallery.Requirements;
import com.botmaker.cli.gallery.Templates;
import com.botmaker.cli.gallery.Tier;
import com.botmaker.cli.project.Poms;
import com.botmaker.cli.registry.Registry;
import com.botmaker.cli.registry.RegistryEntry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker bot publish} — repository, release, and the gallery pull request.
 *
 * <h2>Four steps, in one order, each refusing before the next</h2>
 *
 * <ol>
 *   <li><b>The repository</b> — {@code gh repo create --source . --push}, or a push when the working copy's
 *       {@code origin} already is that repository. A dirty tree is refused: what gets published is a commit,
 *       and a commit that omits the file the author is looking at is the confusing kind of wrong.</li>
 *   <li><b>The release</b> — {@code gh release create}. This is the step that matters and the one nobody
 *       remembers: a bot is installed from its <em>release archive</em>, so a gallery entry with no release
 *       behind it is a 404 on somebody else's machine.</li>
 *   <li><b>The archive is fetched before anything points at it.</b> Same principle {@code botmaker plugin publish}
 *       follows for a coordinate: do not publish a pointer nobody has followed.</li>
 *   <li><b>The gallery pull request</b> — the gallery's own {@code GalleryGate} entry checks run first, on
 *       this machine; then fork, one file at {@code bots/<owner>-<repo>.json}, open it. One file per entry,
 *       never the generated index, so two authors publishing on the same day open two pull requests with no
 *       line in common. A passing pull request is merged by the gallery's CI and listed as Community.</li>
 * </ol>
 *
 * <p><b>{@code --template} is the whole reason this exists.</b> It puts {@code "template"} in the entry's
 * tags, which is what makes the bot a <em>starting point</em> in Studio's New Project rather than something
 * to install and run. Nothing else about a template is different — same repository, same release, same
 * archive — and that is the design: anybody can write one, and it needs no Studio release to be offered.
 *
 * <p>{@code --dry-run} prints the entry on stdout and does nothing else, so
 * {@code botmaker bot publish --dry-run > bots/x.json} is a file a parser reads. Progress is on stderr,
 * which is the same split {@code Console} opens with.
 */
@Command(name = "publish",
        header = "Publish this bot: repository, release, and the gallery entry.",
        description = "Creates the repository, pushes, cuts the release, checks the archive can actually be "
                + "downloaded, then opens the gallery pull request.",
        mixinStandardHelpOptions = true)
final class BotPublishCommand implements Callable<Integer> {

    private static final String GALLERY_REPO = "BotMakerDev/botmaker-gallery";

    private final GalleryFacts facts = GalleryFacts.github();

    @ParentCommand
    private BotCommand parent;

    @Option(names = "--dir", defaultValue = ".", description = "The bot project. Default: ${DEFAULT-VALUE}")
    private String directory;

    @Option(names = "--repo", paramLabel = "<owner/name>",
            description = "Where the bot's source will live. Required for a real run; without it this is a "
                    + "dry run, because an entry needs somewhere for a reader to go and look.")
    private String repo;

    @Option(names = "--name", description = "The project name in the entry. Default: the pom's artifactId.")
    private String projectName;

    @Option(names = "--description", defaultValue = "", description = "One sentence, for the gallery.")
    private String description;

    @Option(names = "--tags", defaultValue = "", paramLabel = "<a,b,c>", description = "Comma separated.")
    private String tags;

    @Option(names = "--template",
            description = "Publish as a starting template: adds the reserved \"template\" tag, which is what "
                    + "offers it in New Project rather than as a bot to install.")
    private boolean template;

    @Option(names = "--tag", defaultValue = "v0.1.0", paramLabel = "<tag>",
            description = "The release to cut and point the entry at. Default: ${DEFAULT-VALUE}")
    private String tag;

    @Option(names = "--dry-run", description = "Print the entry and open nothing.")
    private boolean dryRun;

    @Override
    public Integer call() throws IOException {
        Console console = parent.main().console();
        Path dir = Path.of(directory).toAbsolutePath().normalize();
        Path pom = dir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new IOException("no pom.xml in " + dir + " — point `botmaker bot publish` at a bot project");
        }

        GalleryEntry entry = compose(dir, pom);
        String json = Registry.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(entry);

        if (dryRun || repo == null) {
            if (repo == null) {
                console.warn("--repo <owner/name> was not given, so this is a dry run: a gallery entry needs"
                        + " somewhere for a reader to go and look at the source.");
            }
            console.out(json);
            return 0;
        }

        if (!publishRepository(console, dir, entry)) {
            return 1;
        }
        if (!cutRelease(console, dir, entry)) {
            return 1;
        }
        if (!Templates.archiveExists(entry.owner(), entry.repo(), tag)) {
            console.error("the release archive for " + entry.slug() + "@" + tag + " cannot be downloaded"
                    + " yet, and that archive is what an install actually fetches. Nothing was submitted.");
            return 1;
        }

        // The gallery's own gate, on this machine, before a pull request exists — so a refusal is met here
        // rather than as a red check on somebody else's repository.
        Path work = Files.createTempDirectory("botmaker-bot-publish");
        String login = Shell.capture(work, "gh", "api", "user", "--jq", ".login").orElse("").trim();
        boolean push = Shell.capture(work, "gh", "api", "repos/" + GALLERY_REPO, "--jq",
                ".permissions.push").orElse("").trim().equals("true");
        List<GalleryGate.Finding> findings = GalleryGate.checkEntry(entry, entry.path(), login, push,
                facts.listedEntry(entry.path()), facts);
        boolean refused = false;
        for (GalleryGate.Finding finding : findings) {
            if (finding.severity() == GalleryGate.Severity.ERROR) {
                console.error(finding.message());
                refused = true;
            } else {
                console.warn(finding.message());
            }
        }
        if (refused) {
            console.error("the gallery would refuse this entry, so nothing was submitted.");
            return 1;
        }
        int opened = openPullRequest(console, work, push, entry, json);
        if (opened == 0) {
            console.step(push
                    ? "Opened by a maintainer: it is merged as soon as the gallery's checks pass."
                    : "Once the gallery's checks pass it is merged automatically and listed as "
                            + Tier.COMMUNITY.displayName() + " (at most " + ListingPolicy.NEW_LISTINGS_PER_DAY
                            + " new bots a day per author). " + Tier.VETTED.displayName()
                            + " is granted by a maintainer, per release.");
        }
        return opened;
    }

    private GalleryEntry compose(Path dir, Path pom) throws IOException {
        String[] target = repo == null ? new String[]{"", ""} : ownerAndName(repo);
        String name = projectName != null && !projectName.isBlank()
                ? projectName.trim() : Poms.coordinate(pom).artifactId();
        List<String> entryTags = new ArrayList<>(List.of(tags.split("\\s*,\\s*")).stream()
                .filter(t -> !t.isBlank()).toList());
        if (template && entryTags.stream().noneMatch(GalleryEntry.TEMPLATE_TAG::equalsIgnoreCase)) {
            entryTags.add(GalleryEntry.TEMPLATE_TAG);
        }
        if (template && !Files.exists(dir.resolve(Templates.TEMPLATE_FILE))) {
            throw new IOException("a template must declare its package in " + Templates.TEMPLATE_FILE
                    + ", and " + dir + " has none. `botmaker bot new` writes one; a project created in"
                    + " Studio needs a line saying `package=<your package>`.");
        }
        GalleryEntry entry = new GalleryEntry(name, target[0], target[1], description, entryTags);
        // What the bot needs, by registry id. An unreachable registry composes none rather than failing the
        // publish: the entry is still correct, only less informative.
        Optional<List<RegistryEntry>> registry = facts.registry();
        if (registry.isEmpty()) {
            parent.main().console().warn("the plugin registry could not be read, so the entry names no required"
                    + " plugins.");
            return entry;
        }
        return entry.withRequires(Requirements.of(Poms.dependencies(pom), Poms.properties(pom), registry.get()));
    }

    /**
     * Step one: the repository holds this commit.
     *
     * <p>A working copy that is not a git repository yet is initialised and committed, because that is
     * mechanical and is exactly the part this command exists to remove. A working copy with <em>uncommitted
     * changes</em> is refused instead: guessing that the author meant to publish them is guessing about the
     * one thing they can see and this command cannot.
     */
    private boolean publishRepository(Console console, Path dir, GalleryEntry entry) throws IOException {
        if (Shell.capture(dir, "git", "rev-parse", "--git-dir").isEmpty()) {
            console.step("Not a git repository yet — initialising one.");
            // -b main, not the built-in default: a machine with no init.defaultBranch set gets `master`,
            // which is what the first real run of this command produced. The branch name is in every URL
            // the entry's readers follow.
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

        boolean exists = Shell.capture(dir, "gh", "repo", "view", entry.slug(), "--json", "name").isPresent();
        if (!exists) {
            if (Shell.gh(console, dir, "repo", "create", entry.slug(), "--source", ".", "--push",
                    "--public") != 0) {
                console.error("could not create " + entry.slug() + ". Is `gh` installed and authenticated?");
                return false;
            }
            return true;
        }

        // It exists. Publishing into it is right when it is this working copy's own origin — a second
        // release of the author's own bot — and wrong when it is somebody else's repository that happens
        // to have the name, which is a mistake no push should be the first to discover.
        Optional<String> origin = Shell.capture(dir, "git", "remote", "get-url", "origin");
        if (origin.filter(url -> url.contains(entry.slug())).isEmpty()) {
            console.error(entry.slug() + " already exists and is not this working copy's origin. Pick"
                    + " another --repo, or set origin to it if it is yours.");
            return false;
        }
        console.step(entry.slug() + " already exists and is this project's origin — pushing.");
        if (Shell.run(console, dir, "git", "push", "-u", "origin", "HEAD") != 0) {
            console.error("could not push to " + entry.slug());
            return false;
        }
        return true;
    }

    /** Step two: the release, which is the thing an install actually downloads. */
    private boolean cutRelease(Console console, Path dir, GalleryEntry entry) throws IOException {
        if (Shell.capture(dir, "gh", "release", "view", tag, "--repo", entry.slug()).isPresent()) {
            console.step("Release " + tag + " already exists — leaving it alone.");
            return true;
        }
        int created = Shell.gh(console, dir, "release", "create", tag, "--repo", entry.slug(),
                "--title", tag, "--notes", entry.name() + " " + tag + ".");
        if (created != 0) {
            console.error("could not create release " + tag + " on " + entry.slug()
                    + ". A bot is installed from its release archive, so nothing was submitted.");
            return false;
        }
        return true;
    }

    /**
     * Step four: one file, on a branch, as a pull request.
     *
     * <p><b>A fork only when a fork is needed.</b> The first real run of this command was the gallery's
     * own maintainer publishing a template, and GitHub does not fork a repository into the account that
     * already owns it — so the branch goes straight onto the gallery whenever the authenticated user can
     * push there, and through a fork otherwise. That is the same pull request either way; what changes is
     * only where its head branch lives.
     */
    private int openPullRequest(Console console, Path work, boolean push, GalleryEntry entry, String json)
            throws IOException {
        if (push) {
            console.step("You can push to " + GALLERY_REPO + " — branching on it directly rather than"
                    + " forking.");
            if (Shell.gh(console, work, "repo", "clone", GALLERY_REPO, "botmaker-gallery") != 0) {
                console.error("could not clone " + GALLERY_REPO + ". Is `gh` installed and authenticated?");
                return 1;
            }
            // `gh repo fork` takes no --remote when it is given a repository argument, which is the flag
            // that failed the first real run; the clone arm never wanted one.
        } else if (Shell.gh(console, work, "repo", "fork", GALLERY_REPO, "--clone=true",
                "--fork-name=botmaker-gallery") != 0) {
            console.error("could not fork " + GALLERY_REPO + ". Is `gh` installed and authenticated?");
            return 1;
        }
        Path clone = work.resolve("botmaker-gallery");
        if (!Files.isDirectory(clone)) {
            console.error("the " + (push ? "clone" : "fork") + " of " + GALLERY_REPO
                    + " did not land in " + work);
            return 1;
        }
        Path entries = clone.resolve(GalleryEntry.ENTRIES_DIRECTORY);
        if (!Files.isDirectory(entries)) {
            console.error("no " + GalleryEntry.ENTRIES_DIRECTORY + "/ in " + GALLERY_REPO + " — re-run with"
                    + " --dry-run and open the pull request by hand.");
            return 1;
        }
        String branch = "add-" + (entry.owner() + "-" + entry.repo()).replaceAll("[^a-zA-Z0-9]+", "-");
        Shell.run(console, clone, "git", "checkout", "-b", branch);
        Files.writeString(clone.resolve(entry.path()), json + "\n");
        Shell.run(console, clone, "git", "add", entry.path());
        Shell.run(console, clone, "git", "commit", "-m", "gallery: add " + entry.slug());
        if (Shell.run(console, clone, "git", "push", "-u", "origin", branch) != 0) {
            console.error("could not push the branch");
            return 1;
        }
        String kind = entry.isTemplate() ? "template" : "bot";
        int opened = Shell.gh(console, clone, "pr", "create", "--repo", GALLERY_REPO,
                "--title", "Add " + entry.name() + " (" + kind + ")",
                "--body", "Adds `" + entry.path() + "` — [" + entry.slug()
                        + "](https://github.com/" + entry.slug() + "), released as `" + tag + "`.\n\n"
                        + "Composed by `botmaker bot publish`, which downloaded the release archive and ran"
                        + " the gallery's checks before writing this entry.\n\n```json\n" + json + "\n```");
        return opened == 0 ? 0 : 1;
    }

    private static String[] ownerAndName(String slug) throws IOException {
        int slash = slug.indexOf('/');
        if (slash <= 0 || slash == slug.length() - 1) {
            throw new IOException("--repo wants owner/name, got '" + slug + "'");
        }
        return new String[]{slug.substring(0, slash), slug.substring(slash + 1)};
    }
}
