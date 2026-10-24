package com.botmaker.cli.publish;

import com.botmaker.cli.catalog.ApprovedRelease;
import com.botmaker.cli.catalog.BotEntry;
import com.botmaker.cli.catalog.Catalog;
import com.botmaker.cli.catalog.Entry;
import com.botmaker.cli.catalog.EntryFacts;
import com.botmaker.cli.catalog.EntryGate;
import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.catalog.Finding;
import com.botmaker.cli.catalog.PluginEntry;
import com.botmaker.cli.release.Jitpack;
import com.botmaker.cli.release.Level;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A plugin or a bot published from its folder and listed in the catalog: the one implementation the cli's
 * {@code plugin publish} and {@code bot publish}, the release library and Studio's Publish call (2026-10-24).
 *
 * <h2>The steps, each refusing before the next</h2>
 * <ol>
 *   <li><b>Repository</b>: the folder's {@code origin} ({@link PublishTarget}).</li>
 *   <li><b>Version</b>: {@link NextVersion}. One the catalog lists already ends the run, listing nothing.</li>
 *   <li><b>Local check</b>, for a {@link NextVersion.Listing#NEW NEW} version only: a clean tree, the kind's own
 *       {@link Checks#folder checks}, and the entry's owner (a listed entry belongs to whoever controls it). A
 *       failure tags nothing. {@code --dry-run} stops here.</li>
 *   <li><b>Release</b>: {@code HEAD} pushed (the repository created first for a bot with no {@code origin}), the
 *       tag made and pushed, {@code gh release create} as the signed-in user. Each part is skipped when already
 *       done, so a re-run after a failure finishes it.</li>
 *   <li><b>A plugin waits for JitPack</b> (the release library's poll; a build that failed is broken for good) and
 *       its jar is {@link Checks#published checked} as the catalog's check will build it.</li>
 *   <li><b>Pull request</b>: the release pinned ({@link ApprovedRelease}), appended to the entry or a new one,
 *       the catalog's rules 2–5 run on it here, then {@link CatalogPr#open}.</li>
 * </ol>
 *
 * <p>Prints nothing: progress goes to the {@link Listener}, every program and file write through the
 * {@link StepRunner}, and a refusal is the {@link Outcome} returned.
 */
public final class Publish {

    /** How a publish ended. */
    public sealed interface Outcome {

        /** The pull request is open (or already read so: then no URL); the catalog's checks merge it. */
        record Opened(Entry entry, String tag, Optional<String> pullRequest) implements Outcome {}

        /** The catalog approved this tag already; nothing was done. */
        record AlreadyListed(String repo, String tag) implements Outcome {}

        /** {@code --dry-run}: what a real run would list, after the local check passed. */
        record WouldList(String repo, String tag, NextVersion.Listing listing, Optional<String> pluginId)
                implements Outcome {}

        /** Stopped, saying why and what to do. */
        record Refused(String reason) implements Outcome {}
    }

    private static final Duration PIN_EVERY = Duration.ofSeconds(5);
    private static final int PIN_TRIES = 6;

    private final EntryFacts facts;
    private final CatalogPr.Reader catalog;
    private final Checks checks;
    private final Jitpack.Probe jitpack;
    private final Jitpack.Sleeper sleeper;

    /**
     * @param sleeper how the JitPack wait and the pin's retries pass time; one that answers {@code false} stops
     *                the publish there
     */
    public Publish(EntryFacts facts, CatalogPr.Reader catalog, Checks checks, Jitpack.Probe jitpack,
                   Jitpack.Sleeper sleeper) {
        this.facts = facts;
        this.catalog = catalog;
        this.checks = checks;
        this.jitpack = jitpack;
        this.sleeper = sleeper;
    }

    public Outcome run(PublishRequest request, Listener listener, StepRunner runner) {
        try {
            return steps(request, listener, runner);
        } catch (Refusal e) {
            return new Outcome.Refused(e.getMessage());
        } catch (UncheckedIOException e) {
            return new Outcome.Refused("could not reach GitHub: " + e.getCause().getMessage());
        }
    }

    /**
     * The release library's step ({@link ReleaseListing}): {@code tag}, which the release just pushed, listed in the
     * entry {@code catalog.json} holds for {@code repo} — {@link #run} with {@code --tag}, once the tag's GitHub
     * Release is out (its own CI makes it, minutes after the tag). The folder is past the tag, so the listing is
     * {@link NextVersion.Listing#EXISTING EXISTING}: nothing tagged, the jar checked from JitPack.
     *
     * @return empty when the catalog lists no plugin of {@code repo}: a release lists no plugin by itself
     */
    public Optional<Outcome> listRelease(Path dir, String repo, String tag, String command, Listener listener,
                                         StepRunner runner) {
        Optional<Catalog> index = catalog.catalog();
        if (index.isEmpty()) {
            return Optional.of(new Outcome.Refused("the catalog's " + Catalog.FILE + " did not read, so whether it"
                    + " lists " + repo + " cannot be told."));
        }
        if (index.get().plugins().stream().noneMatch(p -> p.repo().equalsIgnoreCase(repo))) return Optional.empty();
        Optional<String> unreleased = awaitRelease(repo, tag, listener);
        if (unreleased.isPresent()) {
            return Optional.of(new Outcome.Refused(unreleased.get() + " Once it is out, run `botmaker plugin publish"
                    + " --tag " + tag + "` in " + dir + "."));
        }
        return Optional.of(run(new PublishRequest(dir, EntryPath.Kind.PLUGIN, Level.DEFAULT, Optional.of(tag),
                Optional.of(repo), false, command), listener, runner));
    }

    private static final Duration RELEASE_WAIT = Duration.ofMinutes(20);
    private static final Duration RELEASE_EVERY = Duration.ofSeconds(30);

    /** Waits a while for {@code tag}'s GitHub Release to be published: empty once it is, else why it is not. */
    private Optional<String> awaitRelease(String repo, String tag, Listener listener) {
        String release = "the GitHub Release of " + repo + " " + tag;
        for (Duration waited = Duration.ZERO; ; waited = waited.plus(RELEASE_EVERY)) {
            if (facts.release(repo, tag).filter(r -> !r.draft()).isPresent()) return Optional.empty();
            if (waited.isZero()) listener.step("Waiting for " + release + ".");
            if (waited.compareTo(RELEASE_WAIT) >= 0) {
                return Optional.of(release + " was not out after " + RELEASE_WAIT.toMinutes() + " minutes (the tag's"
                        + " CI makes it).");
            }
            if (!sleeper.sleep(RELEASE_EVERY)) return Optional.of("stopped while waiting for " + release + ".");
        }
    }

    private Outcome steps(PublishRequest request, Listener listener, StepRunner runner) throws Refusal {
        String login = CatalogPr.login(runner)
                .orElseThrow(() -> new Refusal("`gh` is not signed in; run `gh auth login` first."));
        PublishTarget target = PublishTarget.of(request.dir(), request.kind(), request.repo(), login, runner);
        String repo = target.repo();
        if (!target.create() && facts.repo(repo).isEmpty()) {
            throw new Refusal(repo + " is not a public repository on GitHub, and the catalog lists public ones.");
        }
        if (target.create() && runner.read(request.dir(), List.of("gh", "repo", "view", repo, "--json", "name"))
                .isPresent()) {
            throw new Refusal(repo + " already exists, and this folder has no origin pointing at it. Set origin to it"
                    + " if it is yours (`git remote add origin …`), or pass another --repo.");
        }
        Optional<EntryPath> path = knownPath(target);
        Optional<Entry> listed = path.flatMap(catalog::listed);
        NextVersion version = NextVersion.decide(request.dir(), request.bump(), request.tag(), listed, repo, facts,
                runner);
        String tag = version.tag();
        if (lists(listed, tag)) return new Outcome.AlreadyListed(repo, tag);
        boolean fresh = version.listing() == NextVersion.Listing.NEW;
        listener.step(fresh ? "Publishing " + repo + " " + tag + "." : repo + " " + tag + " is released already;"
                + " listing it.");

        if (fresh) {
            if (!target.fresh() && !runner.read(request.dir(), List.of("git", "status", "--porcelain"))
                    .map(String::isBlank).orElse(false)) {
                throw new Refusal("the working tree has uncommitted changes, and what gets published is a commit."
                        + " Commit or stash them, then re-run.");
            }
            Checks.Result folder = checks.folder(target, listener);
            if (folder.refusal().isPresent()) {
                throw new Refusal(folder.refusal().get() + " Nothing was tagged: the catalog runs these same checks.");
            }
            if (target.kind() == EntryPath.Kind.PLUGIN) {
                EntryPath own = pluginPath(folder.pluginId());
                if (!path.equals(Optional.of(own))) {
                    path = Optional.of(own);
                    listed = catalog.listed(own);
                    if (lists(listed, tag)) return new Outcome.AlreadyListed(repo, tag);
                }
            }
            owner(path.orElseThrow(), listed, repo, login, runner);
        }
        if (request.dryRun()) {
            return new Outcome.WouldList(repo, tag, version.listing(),
                    path.filter(p -> p.kind() == EntryPath.Kind.PLUGIN).map(EntryPath::key));
        }
        if (fresh) release(target, tag, runner);

        Optional<ApprovedRelease> pinned;
        if (target.kind() == EntryPath.Kind.PLUGIN) {
            String coordinate = facts.repo(repo).map(EntryFacts.Repo::coordinate)
                    .orElseThrow(() -> new Refusal(repo + " is not a public repository on GitHub."));
            built(coordinate, tag, listener);
            Checks.Result jar = checks.published(coordinate + ":" + tag, listener);
            if (jar.refusal().isPresent()) {
                throw new Refusal("the jar JitPack built for " + tag + " does not pass the checks the catalog runs: "
                        + jar.refusal().get() + " Fix it and publish the next version.");
            }
            // No id when the checks are the catalog's alone (Checks.LEFT_TO_THE_CATALOG): the known path stands.
            EntryPath own = jar.pluginId().isEmpty() && path.isPresent() ? path.get() : pluginPath(jar.pluginId());
            if (!path.equals(Optional.of(own))) {
                path = Optional.of(own);
                listed = catalog.listed(own);
                if (lists(listed, tag)) return new Outcome.AlreadyListed(repo, tag);
            }
            pinned = pin(() -> ApprovedRelease.pinPlugin(facts, repo, tag));
        } else {
            pinned = pin(() -> ApprovedRelease.pinBot(facts, repo, tag));
        }
        ApprovedRelease release = pinned.orElseThrow(() -> new Refusal("released " + tag + ", but GitHub or JitPack"
                + " does not show it yet, so it cannot be pinned. Re-run in a minute: the run lists " + tag + "."));
        String key = path.orElseThrow().key();
        Entry entry = listed.map(e -> e.appended(release)).orElseGet(() -> target.kind() == EntryPath.Kind.PLUGIN
                ? new PluginEntry(key, repo, List.of(release))
                : new BotEntry(repo, List.of(release), false));

        List<Finding> findings = EntryGate.checkEntry(entry, EntryPath.of(entry), login,
                listed.isPresent() && CatalogPr.admin(runner, catalog), listed, facts);
        findings.stream().filter(f -> f.severity() != Finding.Severity.ERROR).forEach(f -> listener.warn(f.message()));
        List<String> errors = findings.stream().filter(f -> f.severity() == Finding.Severity.ERROR)
                .map(Finding::message).toList();
        if (!errors.isEmpty()) {
            throw new Refusal("released " + tag + ", but the catalog would refuse the entry; no pull request opened:\n"
                    + String.join("\n", errors));
        }
        Optional<String> url = CatalogPr.open(CatalogPr.Change.put(entry, request.command()), login, runner, listener);
        return new Outcome.Opened(entry, tag, url);
    }

    /**
     * The entry's path when it can be known before the folder is checked: a bot's from its repository, a plugin's
     * from {@code catalog.json}'s row for its repository (its id is read off the jar otherwise).
     */
    private Optional<EntryPath> knownPath(PublishTarget target) throws Refusal {
        if (target.kind() == EntryPath.Kind.BOT) {
            return Optional.of(EntryPath.parse(EntryPath.bot(target.repo())).orElseThrow(() -> new Refusal(
                    target.repo() + " cannot be listed: the catalog names a bot's file after owner/name, and this"
                            + " one does not fit (letters, digits, '.', '-' and '_').")));
        }
        return catalog.catalog().flatMap(c -> c.plugins().stream()
                        .filter(p -> p.repo().equalsIgnoreCase(target.repo())).findFirst())
                .flatMap(p -> EntryPath.parse(EntryPath.PLUGINS + p.id() + ".json"));
    }

    private static EntryPath pluginPath(Optional<String> id) throws Refusal {
        String named = id.orElseThrow(() -> new Refusal("the check loaded no plugin, so there is no id to list."));
        return EntryPath.parse(EntryPath.PLUGINS + named + ".json").orElseThrow(() -> new Refusal("the plugin's id "
                + named + " cannot name a catalog file: letters, digits, '.', '-' and '_', at most 128."));
    }

    private static boolean lists(Optional<Entry> listed, String tag) {
        return listed.flatMap(e -> e.release(tag)).isPresent();
    }

    /**
     * Rule 4 before anything is tagged: a listed entry keeps its repository and belongs to whoever controls it, or to
     * an admin (the catalog's own exception).
     */
    private void owner(EntryPath path, Optional<Entry> listed, String repo, String login, StepRunner runner)
            throws Refusal {
        if (listed.isEmpty()) return;
        Entry held = listed.get();
        if (!held.repo().equalsIgnoreCase(repo)) {
            throw new Refusal("the catalog lists " + path.key() + " for " + held.repo() + ", not " + repo + ", and a"
                    + " listed entry keeps its repository. Nothing was tagged.");
        }
        if (!EntryGate.controls(login, held.repo(), held.newest().tag(), facts) && !CatalogPr.admin(runner, catalog)) {
            throw new Refusal(path.key() + "'s entry belongs to whoever controls " + held.repo() + ", and " + login
                    + " neither made its release " + held.newest().tag() + " nor owns it. Nothing was tagged.");
        }
    }

    /** Step 4. Each part skipped when it is done already, so a re-run after a failure finishes the release. */
    private static void release(PublishTarget target, String tag, StepRunner runner) throws Refusal {
        Path dir = target.dir();
        String repo = target.repo();
        if (target.fresh()) {
            // The local check just built target/, and the repository is public: never commit the build.
            Path ignore = dir.resolve(".gitignore");
            if (!Files.exists(ignore)) {
                try {
                    runner.write(ignore, "target/\n");
                } catch (IOException e) {
                    throw new Refusal("could not write " + ignore + ": " + e.getMessage());
                }
            }
            must(runner, dir, "could not make the first commit in " + dir + ".",
                    List.of("git", "init", "-q", "-b", "main"), List.of("git", "add", "-A"),
                    List.of("git", "commit", "-qm", "Initial commit"));
        }
        if (target.create()) {
            must(runner, dir, "could not create " + repo + ".",
                    List.of("gh", "repo", "create", repo, "--source", ".", "--push", "--public"));
        } else {
            must(runner, dir, "could not push HEAD to origin.", List.of("git", "push", "origin", "HEAD"));
        }
        if (runner.read(dir, List.of("git", "rev-parse", "-q", "--verify", "refs/tags/" + tag)).isEmpty()) {
            must(runner, dir, "could not tag HEAD " + tag + ".", List.of("git", "tag", tag));
        }
        must(runner, dir, "could not push the tag " + tag + ".", List.of("git", "push", "origin", "refs/tags/" + tag));
        if (runner.read(dir, List.of("gh", "release", "view", tag, "--repo", repo)).isEmpty()) {
            must(runner, dir, "could not create the release " + tag + " on " + repo + ".",
                    List.of("gh", "release", "create", tag, "--repo", repo, "--title", tag, "--notes",
                            repo + " " + tag + ".", "--verify-tag"));
        }
    }

    @SafeVarargs
    private static void must(StepRunner runner, Path dir, String failure, List<String>... commands) throws Refusal {
        for (List<String> command : commands) {
            if (!runner.change(dir, command).ok()) throw new Refusal(failure + " Its output is above.");
        }
    }

    /** Step 5's wait: only a built jar goes on. A build that failed is broken for good; its log says why. */
    private void built(String coordinate, String tag, Listener listener) throws Refusal {
        int colon = coordinate.indexOf(':');
        Jitpack.Waited waited = Jitpack.waitFor(coordinate.substring(0, colon), coordinate.substring(colon + 1), tag,
                listener::step, () -> false, sleeper, jitpack);
        switch (waited.build()) {
            case BUILT -> { }
            case FAILED -> throw new Refusal("JitPack failed to build " + tag + ": " + waited.detail()
                    + waited.logUrl().map(url -> " The build log: " + url).orElse("")
                    + ". A tag JitPack built Error stays broken: fix it, commit, and publish the next version."
                    + " No pull request was opened.");
            case TIMEOUT -> throw new Refusal("JitPack has not built " + tag + " after 10 minutes. Re-run once it"
                    + " has: the run lists " + tag + " without tagging again.");
            case STOPPED -> throw new Refusal("stopped while JitPack built " + tag + ". Re-run to list it.");
        }
    }

    /** The pins, retried a little: GitHub's API can answer a few seconds behind a tag just pushed. */
    private Optional<ApprovedRelease> pin(Supplier<Optional<ApprovedRelease>> pins) {
        for (int attempt = 0; ; attempt++) {
            Optional<ApprovedRelease> pinned = pins.get();
            if (pinned.isPresent() || attempt + 1 >= PIN_TRIES || !sleeper.sleep(PIN_EVERY)) return pinned;
        }
    }
}
