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
import com.botmaker.cli.bmk.BotFiles;
import com.botmaker.cli.project.Poms;
import com.botmaker.cli.release.Jitpack;
import com.botmaker.cli.release.Level;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 *       {@link Checks#folder checks}, the entry's owner (a listed entry belongs to whoever controls it), and a
 *       plugin's {@code <parent>} at a release. A failure tags nothing. {@code --dry-run} stops here.</li>
 *   <li><b>Release</b>: a plugin's JitPack build ({@link JitpackFiles}) written and committed when the folder's
 *       differs from this jar's, {@code HEAD} pushed (the repository created first for a bot with no {@code origin}), the
 *       tag made and pushed, the GitHub Release made as the signed-in user. Each part is skipped when already
 *       done, so a re-run after a failure finishes it.</li>
 *   <li><b>A plugin waits for JitPack</b> (the release library's poll; a build that failed is broken for good) and
 *       its jar is {@link Checks#published checked} as the catalog's check will build it. <b>A bot attaches its
 *       {@code .bmk}</b> to the release ({@link PublishHost#bmk}, {@link PublishHost#uploadAsset}), pinned as
 *       {@code bmkSha256}; the catalog's check rebuilds it from the commit.</li>
 *   <li><b>Pull request</b>: the release pinned ({@link ApprovedRelease}), appended to the entry or a new one,
 *       the catalog's rules 2–5 run on it here, then {@link CatalogPr#open}.</li>
 * </ol>
 *
 * <p>Prints nothing: progress goes to the {@link Listener}, every side effect through the {@link PublishHost} (the
 * cli's runs {@code git} and {@code gh}, Studio's JGit and its own sign-in), and a refusal is the {@link Outcome}
 * returned.
 */
public final class Publish {

    /** How a publish ended. */
    public sealed interface Outcome {

        /** The pull request is open (or already read so: then no URL); the catalog's checks merge it. */
        record Opened(Entry entry, String tag, Optional<String> pullRequest) implements Outcome {}

        /** The catalog approved this tag already; nothing was done. */
        record AlreadyListed(String repo, String tag) implements Outcome {}

        /** {@code --dry-run}: what a real run would list, after the local check passed. */
        record WouldList(String repo, String tag, NextVersion.Listing listing) implements Outcome {}

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

    public Outcome run(PublishRequest request, Listener listener, PublishHost host) {
        try {
            return steps(request, listener, host);
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
                                         PublishHost host) {
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
                Optional.of(repo), false, command), listener, host));
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

    private Outcome steps(PublishRequest request, Listener listener, PublishHost host) throws Refusal {
        PublishHost.Account account = host.account()
                .orElseThrow(() -> new Refusal("nobody is signed in to GitHub (`gh auth login`, or Studio's Sign in)."));
        String login = account.login();
        PublishTarget target = PublishTarget.of(request.dir(), request.kind(), request.repo(), login, host);
        String repo = target.repo();
        if (!target.create() && facts.repo(repo).isEmpty()) {
            throw new Refusal(repo + " is not a public repository on GitHub, and the catalog lists public ones.");
        }
        if (target.create() && host.repositoryExists(repo)) {
            throw new Refusal(repo + " already exists, and this folder has no origin pointing at it. Set origin to it"
                    + " if it is yours (`git remote add origin …`), or pass another --repo.");
        }
        EntryPath path = knownPath(target);
        Optional<Entry> listed = catalog.listed(path);
        NextVersion version = NextVersion.decide(request.bump(), request.tag(), listed, repo, facts, host);
        String tag = version.tag();
        if (lists(listed, tag)) return new Outcome.AlreadyListed(repo, tag);
        boolean fresh = version.listing() == NextVersion.Listing.NEW;
        listener.step(fresh ? "Publishing " + repo + " " + tag + "." : repo + " " + tag + " is released already;"
                + " listing it.");

        if (fresh) {
            if (!target.fresh() && !host.clean()) {
                throw new Refusal("the working tree has uncommitted changes, and what gets published is a commit."
                        + " Commit or stash them, then re-run.");
            }
            Checks.Result folder = checks.folder(target, listener);
            if (folder.refusal().isPresent()) {
                throw new Refusal(folder.refusal().get() + " Nothing was tagged: the catalog runs these same checks.");
            }
            owner(path, listed, repo, account);
            if (target.kind() == EntryPath.Kind.PLUGIN) releasedParent(target.dir());
        }
        Map<String, String> build = fresh && target.kind() == EntryPath.Kind.PLUGIN
                ? JitpackFiles.stale(target.dir()) : Map.of();
        if (request.dryRun()) {
            if (!build.isEmpty()) listener.step("Would write and commit " + String.join(", ", build.keySet()) + ".");
            return new Outcome.WouldList(repo, tag, version.listing());
        }
        if (!build.isEmpty()) {
            listener.step("Writing " + String.join(", ", build.keySet()) + ", the JitPack build this botmaker carries.");
            host.commitFiles("Build on JitPack with BotMaker's build script", build);
        }
        if (fresh) release(target, tag, host);

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
            pinned = pin(() -> ApprovedRelease.pinPlugin(facts, repo, tag));
        } else {
            Optional<String> bmk = attachBmk(repo, tag, listener, host);
            pinned = pin(() -> ApprovedRelease.pinBot(facts, repo, tag, bmk));
        }
        ApprovedRelease release = pinned.orElseThrow(() -> new Refusal("released " + tag + ", but GitHub or JitPack"
                + " does not show it yet, so it cannot be pinned. Re-run in a minute: the run lists " + tag + "."));
        Entry entry = listed.map(e -> e.appended(release)).orElseGet(() -> target.kind() == EntryPath.Kind.PLUGIN
                ? new PluginEntry(repo, List.of(release))
                : new BotEntry(repo, List.of(release), false));

        List<Finding> findings = EntryGate.checkEntry(entry, EntryPath.of(entry), login,
                listed.isPresent() && CatalogPr.admin(account, catalog), listed, facts);
        findings.stream().filter(f -> f.severity() != Finding.Severity.ERROR).forEach(f -> listener.warn(f.message()));
        List<String> errors = findings.stream().filter(f -> f.severity() == Finding.Severity.ERROR)
                .map(Finding::message).toList();
        if (!errors.isEmpty()) {
            throw new Refusal("released " + tag + ", but the catalog would refuse the entry; no pull request opened:\n"
                    + String.join("\n", errors));
        }
        Optional<String> url = host.pullRequest(CatalogPr.Change.put(entry, request.command()), account, listener);
        return new Outcome.Opened(entry, tag, url);
    }

    /**
     * The entry's path, from its repository: a plugin's file is named like a bot's (2026-10-27). A plugin the catalog
     * lists already is spelled as its row is, since GitHub ignores a repository's case and a file name does not.
     */
    private EntryPath knownPath(PublishTarget target) throws Refusal {
        String repo = target.kind() == EntryPath.Kind.BOT ? target.repo() : catalog.catalog()
                .flatMap(c -> c.plugins().stream().map(Catalog.Plugin::repo)
                        .filter(listed -> listed.equalsIgnoreCase(target.repo())).findFirst())
                .orElse(target.repo());
        String file = target.kind() == EntryPath.Kind.BOT ? EntryPath.bot(repo) : EntryPath.plugin(repo);
        return EntryPath.parse(file).orElseThrow(() -> new Refusal(target.repo() + " cannot be listed: the catalog"
                + " names an entry's file after owner/name, and this one does not fit (letters, digits, '.', '-' and"
                + " '_')."));
    }

    /**
     * A plugin is tagged on a released parent only: JitPack serves no {@code -SNAPSHOT}, so the tag would build
     * {@code Error}, and a tag that did is broken for good. A pom that does not read is left to the folder's checks.
     */
    private static void releasedParent(Path dir) throws Refusal {
        Optional<Poms.Dependency> parent;
        try {
            parent = Poms.parent(dir.resolve("pom.xml"));
        } catch (IOException unreadable) {
            return;
        }
        if (parent.isPresent() && parent.get().version().endsWith("-SNAPSHOT")) {
            throw new Refusal("the pom's <parent> is " + parent.get().coordinate() + ":" + parent.get().version()
                    + ", which JitPack never serves. Run `botmaker plugin upgrade` to name its newest release, commit,"
                    + " then re-run. Nothing was tagged.");
        }
    }

    private static boolean lists(Optional<Entry> listed, String tag) {
        return listed.flatMap(e -> e.release(tag)).isPresent();
    }

    /**
     * Rule 4 before anything is tagged: a listed entry keeps its repository and belongs to whoever controls it, or to
     * an admin (the catalog's own exception).
     */
    private void owner(EntryPath path, Optional<Entry> listed, String repo, PublishHost.Account account)
            throws Refusal {
        if (listed.isEmpty()) return;
        String login = account.login();
        Entry held = listed.get();
        if (!held.repo().equalsIgnoreCase(repo)) {
            throw new Refusal("the catalog lists " + path.key() + " for " + held.repo() + ", not " + repo + ", and a"
                    + " listed entry keeps its repository. Nothing was tagged.");
        }
        if (!EntryGate.controls(login, held.repo(), held.newest().tag(), facts) && !CatalogPr.admin(account, catalog)) {
            throw new Refusal(path.key() + "'s entry belongs to whoever controls " + held.repo() + ", and " + login
                    + " neither made its release " + held.newest().tag() + " nor owns it. Nothing was tagged.");
        }
    }

    /** Step 4. Each part skipped when it is done already, so a re-run after a failure finishes the release. */
    private static void release(PublishTarget target, String tag, PublishHost host) throws Refusal {
        String repo = target.repo();
        if (target.fresh()) host.firstCommit();
        if (target.create()) {
            host.createRepository(repo);
        } else {
            host.pushHead();
        }
        host.tag(tag);
        if (!host.releaseExists(repo, tag)) host.release(repo, tag);
    }

    /**
     * Step 5 for a bot (2026-10-25): its {@code .bmk} written from {@code HEAD} and attached to the release, its
     * sha256 what the release pins. Replaced when a re-run attaches it again: the same commit writes the same bytes.
     * A folder past the tag lists it without one, since a {@code .bmk} is made from the tagged commit.
     */
    private static Optional<String> attachBmk(String repo, String tag, Listener listener, PublishHost host)
            throws Refusal {
        if (!host.tagsAtHead().contains(tag)) {
            listener.warn(tag + " is listed without a .bmk: the folder is past that tag, and a .bmk is made from the"
                    + " tagged commit.");
            return Optional.empty();
        }
        // Its jars are resolved from the folder's pom, its files read from the commit: a changed pom would give a
        // file the catalog's rebuild of the commit refuses.
        if (!host.clean()) {
            throw new Refusal("released " + tag + ", but the working tree has uncommitted changes, and the .bmk is"
                    + " made from the commit. Commit or stash them, then re-run: the run lists " + tag + ".");
        }
        listener.step("Attaching the bot's .bmk to " + tag + ".");
        Path out = null;
        Path file = null;
        try {
            out = Files.createTempDirectory("botmaker-bmk");
            file = host.bmk(repo, tag, out);
            String sha = BotFiles.sha256(file);
            host.uploadAsset(repo, tag, file);
            return Optional.of(sha);
        } catch (Refusal e) {
            throw new Refusal("released " + tag + ", but " + e.getMessage() + " No pull request was opened; re-run"
                    + " once it is fixed (a fix that needs a commit goes in the next version).");
        } catch (IOException e) {
            throw new Refusal("released " + tag + ", but its .bmk could not be written: " + e.getMessage());
        } finally {
            try {
                if (file != null) Files.deleteIfExists(file);
                if (out != null) Files.deleteIfExists(out);
            } catch (IOException ignored) {
                // a temporary folder left behind
            }
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
