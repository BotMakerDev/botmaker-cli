// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.publish;

import com.botmaker.cli.catalog.Advisory;
import com.botmaker.cli.catalog.ApprovedRelease;
import com.botmaker.cli.catalog.BotEntry;
import com.botmaker.cli.catalog.Catalog;
import com.botmaker.cli.catalog.CatalogJson;
import com.botmaker.cli.catalog.Entry;
import com.botmaker.cli.catalog.EntryFacts;
import com.botmaker.cli.catalog.EntryGate;
import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.catalog.GitHubFacts;
import com.botmaker.cli.catalog.PluginEntry;
import com.botmaker.cli.catalog.Publishers;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The one pull request a publish or an unpublish opens on {@code BotMakerDev/botmaker-catalog}: one entry file
 * written or removed, on a branch of its own, through the host ({@link PublishHost#pullRequest}: the cli's is
 * {@link #open}, through {@code gh}; Studio's the GitHub API). The catalog's own checks run on it and merge it
 * when they pass; nobody reviews it by hand.
 *
 * <p>The checks the catalog runs first on an entry (rules 2–5: the repository, the release, who made it) run before
 * it is opened ({@link Publish}), so the author meets a refusal on their own machine.
 */
public final class CatalogPr {

    public static final String REPO = "BotMakerDev/botmaker-catalog";
    private static final String BRANCH = "main";
    private static final String CLONE = "botmaker-catalog";

    private CatalogPr() {}

    /** What the catalog holds now, read from its {@code main}. An interface so a test answers instead of GitHub. */
    public interface Reader {

        /** The entry at {@code path}, or empty when there is none (or GitHub did not answer). */
        Optional<Entry> listed(EntryPath path);

        /** {@code catalog.json}, or empty. */
        Optional<Catalog> catalog();

        /** {@code publishers.json}, or empty: nobody is an admin. */
        default Optional<Publishers> publishers() {
            return Optional.empty();
        }

        /** The ids under {@code advisories/}, or empty when GitHub did not answer. */
        default Optional<List<String>> advisoryIds() {
            return Optional.empty();
        }

        /** The advisory {@code id}, or empty when there is none (or GitHub did not answer). */
        default Optional<Advisory> advisory(String id) {
            return Optional.empty();
        }

        /** Through GitHub's raw files. */
        static Reader of(GitHubFacts facts) {
            return new Reader() {
                @Override
                public Optional<List<String>> advisoryIds() {
                    return facts.names(REPO, BRANCH, Advisory.DIRECTORY).map(names -> names.stream()
                            .map(name -> Advisory.idOf(Advisory.DIRECTORY + name)).flatMap(Optional::stream).toList());
                }

                @Override
                public Optional<Advisory> advisory(String id) {
                    return facts.raw(REPO, BRANCH, Advisory.path(id)).flatMap(json -> {
                        try {
                            return Optional.of(CatalogJson.strict().readValue(json, Advisory.class));
                        } catch (JsonProcessingException e) {
                            return Optional.empty();
                        }
                    });
                }

                @Override
                public Optional<Entry> listed(EntryPath path) {
                    return facts.raw(REPO, BRANCH, path.path()).flatMap(json -> {
                        try {
                            return Optional.of(path.kind() == EntryPath.Kind.BOT
                                    ? CatalogJson.strict().readValue(json, BotEntry.class)
                                    : CatalogJson.strict().readValue(json, PluginEntry.class));
                        } catch (JsonProcessingException e) {
                            return Optional.empty();
                        }
                    });
                }

                @Override
                public Optional<Catalog> catalog() {
                    return facts.raw(REPO, BRANCH, Catalog.FILE).flatMap(json -> {
                        try {
                            return Optional.of(CatalogJson.lenient().readValue(json, Catalog.class));
                        } catch (JsonProcessingException e) {
                            return Optional.empty();
                        }
                    });
                }

                @Override
                public Optional<Publishers> publishers() {
                    return facts.raw(REPO, BRANCH, Publishers.FILE).flatMap(json -> {
                        try {
                            return Optional.of(CatalogJson.strict().readValue(json, Publishers.class));
                        } catch (JsonProcessingException e) {
                            return Optional.empty();
                        }
                    });
                }
            };
        }
    }

    /**
     * One pull request.
     *
     * @param file    the one file it changes, from the catalog's root: an entry's, or an advisory's
     * @param content the file's new text, or empty to remove it
     */
    public record Change(String file, Optional<String> content, String title, String body) {

        /**
         * Files or changes {@code advisory}, as {@code subject} (an admin's alone, spec 2026-11-21 § 5).
         */
        public static Change advisory(Advisory advisory, String subject, String command) {
            String json = advisory.toJson();
            String file = Advisory.path(advisory.id());
            return new Change(file, Optional.of(json), "advisory: " + subject, "Files or changes `" + file
                    + "`.\n\nOpened by `" + command + "`. The catalog's checks run on it and merge it when they pass."
                    + "\n\n```json\n" + json + "```");
        }

        /** Adds or updates {@code entry}'s file, titled after its newest release. */
        public static Change put(Entry entry, String command) {
            return put(entry, entry.repo() + " " + entry.newest().tag(), "Adds or updates", command);
        }

        /** {@code entry} with {@code tag} no longer approved, the other releases kept. */
        public static Change withdraw(Entry entry, String tag, String command) {
            return put(entry, entry.repo() + " remove " + tag, "Removes release " + tag + " from", command);
        }

        /** {@code entry}, its release {@code tag} already yanked or unyanked in it, the rest unchanged. */
        public static Change yank(Entry entry, String tag, boolean undo, String command) {
            return undo
                    ? put(entry, "unyank " + entry.repo() + " " + tag, "Unyanks release " + tag + " of", command)
                    : put(entry, "yank " + entry.repo() + " " + tag, "Yanks release " + tag + " of", command);
        }

        private static Change put(Entry entry, String subject, String verb, String command) {
            String json;
            try {
                json = CatalogJson.strict().writeValueAsString(entry) + "\n";
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("the entry could not be written", e);
            }
            EntryPath path = EntryPath.parse(EntryPath.of(entry))
                    .orElseThrow(() -> new IllegalArgumentException(EntryPath.of(entry) + " is no entry path"));
            return new Change(path.path(), Optional.of(json), kind(path) + ": " + subject,
                    verb + " `" + path.path() + "`.\n\nOpened by `" + command + "`. The catalog's checks run"
                            + " on it and merge it when they pass.\n\n```json\n" + json + "```");
        }

        /** Removes the entry at {@code path}. */
        public static Change remove(EntryPath path, String command) {
            return new Change(path.path(), Optional.empty(), kind(path) + ": remove " + path.key(),
                    "Removes `" + path.path() + "`.\n\nOpened by `" + command + "`. The catalog's checks run on it"
                            + " and merge it when they pass.");
        }

        /**
         * {@code entry/<path>}: one branch per entry, so a second publish of the same entry replaces the first; an
         * advisory's is {@code advisory/<path>}.
         */
        public String branch() {
            return (Advisory.idOf(file).isPresent() ? "advisory/" : "entry/") + file;
        }

        private static String kind(EntryPath path) {
            return path.kind() == EntryPath.Kind.BOT ? "bot" : "plugin";
        }
    }

    /** One step of {@link #open}, in order. */
    sealed interface Step {
        /** A command, in the working directory or in the catalog's clone. */
        record Run(boolean inClone, List<String> argv) implements Step {}

        /** The entry file written in the clone. */
        record Write(String path, String content) implements Step {}

        /** The commit, skipped with nothing to open when the file already reads so on {@code main}. */
        record Commit(String title) implements Step {}

        /** {@code gh pr create}, skipped when this entry's branch already has an open pull request. */
        record PullRequest(String head, List<String> argv) implements Step {}
    }

    /**
     * What {@link #open} does. The catalog is cloned fresh, so the branch starts at its current {@code main}. A
     * fork only when the account cannot push to the catalog (GitHub does not fork a repository into the account
     * that owns it): {@code gh repo fork} run inside the clone adds the fork as the remote {@code fork}, whatever
     * the fork is named. The branch is force-pushed: it is this entry's own, and a re-run replaces it.
     *
     * @param login the signed-in account, which owns the fork
     */
    static List<Step> steps(Change change, boolean push, String login) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step.Run(false, List.of("gh", "repo", "clone", REPO, CLONE)));
        if (!push) {
            steps.add(new Step.Run(true, List.of("gh", "repo", "fork", "--remote=true", "--remote-name=fork")));
        }
        steps.add(new Step.Run(true, List.of("git", "checkout", "-b", change.branch())));
        String file = change.file();
        if (change.content().isPresent()) {
            steps.add(new Step.Write(file, change.content().get()));
            steps.add(new Step.Run(true, List.of("git", "add", "--", file)));
        } else {
            steps.add(new Step.Run(true, List.of("git", "rm", "--quiet", "--", file)));
        }
        steps.add(new Step.Commit(change.title()));
        steps.add(new Step.Run(true, List.of("git", "push", "--force", "-u", push ? "origin" : "fork",
                change.branch())));
        String head = push ? change.branch() : login + ":" + change.branch();
        steps.add(new Step.PullRequest(head, List.of("gh", "pr", "create", "--repo", REPO, "--base", BRANCH,
                "--head", head, "--title", change.title(), "--body", change.body())));
        return steps;
    }

    /**
     * Opens {@code change}'s pull request as {@code login}.
     *
     * @return the pull request's URL, or empty when the catalog already reads so and there is nothing to open
     */
    static Optional<String> open(Change change, String login, StepRunner runner, Listener listener)
            throws Refusal {
        Path work;
        try {
            work = Files.createTempDirectory("botmaker-catalog-pr");
        } catch (IOException e) {
            throw new Refusal("could not make a folder to clone the catalog in: " + e.getMessage());
        }
        boolean push = runner.read(work, List.of("gh", "api", "repos/" + REPO, "--jq", ".permissions.push"))
                .orElse("").equals("true");
        Path clone = work.resolve(CLONE);
        for (Step step : steps(change, push, login)) {
            switch (step) {
                case Step.Run run -> {
                    StepRunner.Ran ran = runner.change(run.inClone() ? clone : work, run.argv());
                    if (!ran.ok()) {
                        throw new Refusal("`" + String.join(" ", run.argv().subList(0, 3)) + "` failed (its output"
                                + " is above); no pull request was opened on " + REPO + ".");
                    }
                }
                case Step.Commit commit -> {
                    if (runner.read(clone, List.of("git", "diff", "--cached", "--quiet")).isPresent()) {
                        listener.step(change.file() + " already reads so in the catalog; nothing to open.");
                        return Optional.empty();
                    }
                    if (!runner.change(clone, List.of("git", "commit", "--quiet", "-m", commit.title())).ok()) {
                        throw new Refusal("`git commit` failed (its output is above): set git's user.name and"
                                + " user.email, then re-run.");
                    }
                }
                case Step.PullRequest pr -> {
                    String open = runner.read(clone, List.of("gh", "pr", "list", "--repo", REPO, "--head",
                            change.branch(), "--state", "open", "--json", "url", "--jq", ".[].url")).orElse("");
                    if (!open.isBlank()) {
                        listener.step("updated the open pull request " + open);
                        return Optional.of(open.strip());
                    }
                    StepRunner.Ran created = runner.change(clone, pr.argv());
                    if (!created.ok()) {
                        throw new Refusal("`gh pr create` failed (its output is above); the branch " + pr.head()
                                + " is pushed, so `gh pr create --repo " + REPO + " --head " + pr.head()
                                + "` opens it by hand.");
                    }
                    return Optional.of(created.lastLine());
                }
                case Step.Write write -> {
                    Path target = clone.resolve(write.path());
                    if (!Files.isDirectory(target.getParent())) {
                        throw new Refusal("no " + clone.relativize(target.getParent()) + "/ in " + REPO
                                + ": the catalog is not open yet.");
                    }
                    try {
                        runner.write(target, write.content());
                    } catch (IOException e) {
                        throw new Refusal("could not write " + write.path() + " in the catalog's clone: "
                                + e.getMessage());
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Opens the pull request removing the entry at {@code path}, or only its release {@code release}, after checking
     * here what the catalog checks: it is listed, and {@code login} controls it (made its newest release, or owns its
     * repository). The only release removed is the whole entry removed.
     *
     * @return the pull request's URL, or empty when there was nothing to open
     */
    public static Optional<String> unpublish(EntryPath path, Optional<String> release, PublishHost.Account account,
                                             String command, EntryFacts facts, Reader catalog, Opener host,
                                             Listener listener) throws Refusal {
        String login = account.login();
        Entry held = catalog.listed(path).orElseThrow(() -> new Refusal(path.key() + " is not listed in the catalog ("
                + path.path() + "), or GitHub did not answer."));
        String newest = held.newest().tag();
        try {
            if (!EntryGate.controls(login, held.repo(), newest, facts)) {
                throw new Refusal("only whoever controls " + held.repo() + " may change this entry, and " + login
                        + " neither made its release " + newest + " nor owns it.");
            }
        } catch (UncheckedIOException e) {
            throw new Refusal("could not reach GitHub to check the entry: " + e.getCause().getMessage());
        }
        if (release.isEmpty()) return host.open(Change.remove(path, command), account, listener);
        String tag = release.get().strip();
        if (held.release(tag).isEmpty()) {
            throw new Refusal(path.key() + " lists no release " + tag + "; it lists "
                    + String.join(", ", held.releases().stream().map(ApprovedRelease::tag).toList()) + ".");
        }
        List<ApprovedRelease> kept = held.releases().stream().filter(r -> !r.tag().equals(tag)).toList();
        if (kept.isEmpty()) {
            listener.step(tag + " is " + path.key() + "'s only release, so the entry is removed.");
            return host.open(Change.remove(path, command), account, listener);
        }
        return host.open(Change.withdraw(held.withReleases(kept), tag, command), account, listener);
    }

    /**
     * Opens the pull request yanking {@code path}'s release {@code tag} for {@code reason} at {@code now}, or with
     * {@code reason} empty unyanking it (spec § 4), after checking here what the catalog checks: the release is
     * listed, yanked or not as the change needs, the reason fits, and {@code account} controls the entry or is an
     * admin.
     *
     * @return the pull request's URL, or empty when there was nothing to open
     */
    public static Optional<String> yank(EntryPath path, String tag, Optional<String> reason, Instant now,
                                        PublishHost.Account account, String command, EntryFacts facts, Reader catalog,
                                        Opener host, Listener listener) throws Refusal {
        Entry held = catalog.listed(path).orElseThrow(() -> new Refusal(path.key() + " is not listed in the catalog ("
                + path.path() + "), or GitHub did not answer."));
        String wanted = tag.strip();
        ApprovedRelease release = held.release(wanted).orElseThrow(() -> new Refusal(path.key()
                + " lists no release " + wanted + "; it lists "
                + String.join(", ", held.releases().stream().map(ApprovedRelease::tag).toList()) + "."));
        String newest = held.newest().tag();
        try {
            if (!admin(account, catalog) && !EntryGate.controls(account.login(), held.repo(), newest, facts)) {
                throw new Refusal("only whoever controls " + held.repo() + " or an admin may yank its releases, and "
                        + account.login() + " neither made its release " + newest + " nor owns it.");
            }
        } catch (UncheckedIOException e) {
            throw new Refusal("could not reach GitHub to check the entry: " + e.getCause().getMessage());
        }
        ApprovedRelease changed;
        if (reason.isEmpty()) {
            if (release.yanked() == null) throw new Refusal(wanted + " is not yanked.");
            changed = release.unyank();
        } else {
            if (release.yanked() != null) {
                throw new Refusal(wanted + " is already yanked: " + release.yanked().reason()
                        + " Unyank it first to give another reason.");
            }
            if (reason.get().isBlank()) {
                throw new Refusal("a yank says why, in 1 to " + ApprovedRelease.Yanked.MAX_REASON + " characters.");
            }
            ApprovedRelease.Yanked why = new ApprovedRelease.Yanked(reason.get().strip(), now.toString());
            String refusal = EntryGate.yankRefusal(why, now);
            if (refusal != null) throw new Refusal(refusal);
            changed = release.yank(why);
        }
        List<ApprovedRelease> releases = held.releases().stream().map(r -> r.tag().equals(wanted) ? changed : r)
                .toList();
        return host.open(Change.yank(held.withReleases(releases), wanted, reason.isEmpty(), command), account,
                listener);
    }

    /**
     * Opens the pull request filing a new advisory against {@code tags} of the entry at {@code repo} (spec § 5), after
     * checking here what the catalog checks: {@code account} is an admin, the entry is listed and approved each tag.
     * Its id is the next of {@code today}'s year among those the catalog holds.
     *
     * @return the pull request's URL, or empty when there was nothing to open
     */
    public static Optional<String> fileAdvisory(String repo, List<String> tags, Advisory.Severity severity,
                                                String summary, Optional<String> url, LocalDate today,
                                                PublishHost.Account account, String command, Reader catalog,
                                                Opener host, Listener listener) throws Refusal {
        requireAdmin(account, catalog);
        String slug = repo.strip();
        Entry entry = Stream.of(EntryPath.bot(slug), EntryPath.plugin(slug)).map(EntryPath::parse)
                .flatMap(Optional::stream).map(catalog::listed).flatMap(Optional::stream).findFirst()
                .orElseThrow(() -> new Refusal(slug + " is not listed in the catalog, or GitHub did not answer."));
        requireApproved(entry, tags);
        List<String> ids = catalog.advisoryIds().orElseThrow(() -> new Refusal("could not list the catalog's"
                + " advisories to number this one; re-run when GitHub answers."));
        Advisory advisory = new Advisory(Advisory.next(today.getYear(), ids), entry.repo(),
                tags.stream().map(String::strip).distinct().toList(), severity, summary.strip(),
                url.map(String::strip).orElse(null), today.toString(), null);
        String refusal = advisory.problem();
        if (refusal != null) throw new Refusal(refusal);
        return host.open(Change.advisory(advisory, advisory.id() + " " + entry.repo(), command), account, listener);
    }

    /**
     * Opens the pull request appending {@code tags} to advisory {@code id}, or with {@code reason} present
     * withdrawing it instead; the only two changes an advisory takes.
     */
    public static Optional<String> changeAdvisory(String id, List<String> tags, Optional<String> reason,
                                                  PublishHost.Account account, String command, Reader catalog,
                                                  Opener host, Listener listener) throws Refusal {
        requireAdmin(account, catalog);
        Advisory held = catalog.advisory(id.strip()).orElseThrow(() -> new Refusal("the catalog holds no advisory "
                + id.strip() + ", or GitHub did not answer."));
        if (!held.active()) throw new Refusal(held.id() + " is withdrawn: " + held.withdrawn());
        if (reason.isPresent()) {
            String why = reason.get().strip();
            if (why.isEmpty() || why.length() > Advisory.MAX_TEXT) {
                throw new Refusal("a withdrawal says why, in 1 to " + Advisory.MAX_TEXT + " characters.");
            }
            return host.open(Change.advisory(held.withdraw(why), "withdraw " + held.id(), command), account,
                    listener);
        }
        Entry entry = Stream.of(EntryPath.bot(held.repo()), EntryPath.plugin(held.repo())).map(EntryPath::parse)
                .flatMap(Optional::stream).map(catalog::listed).flatMap(Optional::stream).findFirst()
                .orElseThrow(() -> new Refusal(held.repo() + " is not listed in the catalog under that slug, or"
                        + " GitHub did not answer."));
        List<String> more = tags.stream().map(String::strip).filter(t -> !held.releases().contains(t)).distinct()
                .toList();
        if (more.isEmpty()) throw new Refusal(held.id() + " already names " + String.join(", ", tags) + ".");
        requireApproved(entry, more);
        return host.open(Change.advisory(held.extend(more), "extend " + held.id(), command), account, listener);
    }

    private static void requireAdmin(PublishHost.Account account, Reader catalog) throws Refusal {
        if (!admin(account, catalog)) {
            throw new Refusal("only an admin files or changes an advisory, and " + account.login() + " is not one"
                    + " in the catalog's publishers.json.");
        }
    }

    private static void requireApproved(Entry entry, List<String> tags) throws Refusal {
        if (tags.isEmpty()) throw new Refusal("an advisory names at least one release (--release).");
        for (String tag : tags) {
            if (entry.release(tag.strip()).isEmpty()) {
                throw new Refusal(entry.repo() + " lists no release " + tag.strip() + "; it lists "
                        + String.join(", ", entry.releases().stream().map(ApprovedRelease::tag).toList()) + ".");
            }
        }
    }

    /** What carries a pull request: a host's {@link PublishHost#pullRequest}, all an unpublish needs of it. */
    @FunctionalInterface
    public interface Opener {
        Optional<String> open(Change change, PublishHost.Account account, Listener listener) throws Refusal;
    }

    /**
     * Whether {@code account} is an admin in the catalog's {@code publishers.json}, by its account id as the catalog's
     * check reads it. Unreadable is no: the exception is lost, never granted.
     */
    public static boolean admin(PublishHost.Account account, Reader catalog) {
        return account.id() > 0 && catalog.publishers().map(p -> p.isAdmin(account.id())).orElse(false);
    }
}
