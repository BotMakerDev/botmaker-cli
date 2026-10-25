package com.botmaker.cli.catalog;

import com.botmaker.cli.Console;
import com.botmaker.cli.Mvn;
import com.botmaker.cli.Subjects;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The catalog's check on a pull request (validate.yml), and the same rules before one is opened.
 *
 * <p>Nobody reads a pull request after this: one that passes is merged by {@link ListingPolicy}'s workflow. The
 * shared rules, in the spec's numbering:
 *
 * <ol>
 *   <li>The pull request changes exactly one entry path, and it is no symbolic link.</li>
 *   <li>The file's name is what its content claims, its JSON is the schema, and no other entry's path differs
 *       from it only by case (GitHub ignores case in a slug, a checkout on macOS or Windows too). Against the
 *       catalog's copy it is one {@link Change}: a new entry with one release, one release appended, releases
 *       removed, or a bot's {@code detached} alone. An approved release is never edited or reordered, and an
 *       entry's {@code repo} never changes.</li>
 *   <li>The pull request's author controls the repository: they made the GitHub Release the change approves, which
 *       needs push access. A release made by {@value #ACTIONS_BOT} falls back to "the author is the repository's
 *       owner", which an organisation releasing from CI never is.</li>
 *   <li>An existing entry changes or goes only by whoever controls the <b>base</b> copy's repository (its newest
 *       release), so a plugin id never moves to somebody else's repository.</li>
 *   <li>The approved release is published (not a draft, not a prerelease) and its tag points at the pinned
 *       {@code commit} ({@link ApprovedRelease}).</li>
 * </ol>
 *
 * <p><b>An admin may change anyone's entry</b> (2026-10-23, the dashboard's edits): when the author's account id
 * is an admin in the base {@code publishers.json}, rules 3 and 4 pass. Rules 1, 2 and 5 and the kind's checks
 * still run, so an admin lists only a published release that builds. A pull request that changes only
 * {@code publishers.json} is {@link PublishersCheck}'s.
 *
 * <p>The kind's own rules follow in {@link BotChecks} and {@link PluginChecks}, on the approved release only:
 * releases already approved are not rebuilt. No command-line library (picocli is {@code optional} here), and the
 * changed paths may arrive as {@code @file}, since a pull request chooses its own file names and a shell must never
 * see them.
 *
 * <pre>
 * java -cp … com.botmaker.cli.catalog.EntryGate &lt;head-root&gt; --author &lt;login&gt; [--author-id &lt;id&gt;]
 *      [--base &lt;base-root&gt;] [--part all|rules|kind] (@changed.txt | -- &lt;changed-path&gt;…)
 * </pre>
 *
 * <p>Exit code: {@code 0} passes, {@code 1} refused, {@code 2} bad invocation.
 */
public final class EntryGate {

    /** Who a release made by a GitHub Action is authored by. */
    public static final String ACTIONS_BOT = "github-actions[bot]";

    /** A tag git and a URL both carry as is. */
    public static final Pattern TAG =Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._+-]{0,99}");

    /**
     * A pull request as the check sees it.
     *
     * @param head    the pull request's tree
     * @param base    the tree it merges into; without it an edit cannot be told from a takeover, so every change
     *                to an existing entry is refused
     * @param changed the changed paths, as git names them
     * @param author   the pull request author's login
     * @param authorId the author's GitHub account id, what {@code publishers.json} names; {@code 0} when unknown,
     *                 which is never an admin
     */
    public record Request(Path head, Optional<Path> base, List<String> changed, String author, long authorId) {
        public Request {
            base = base == null ? Optional.empty() : base;
            changed = List.copyOf(changed);
            author = author == null ? "" : author.trim();
        }
    }

    /** What a pull request does to one entry the catalog holds (or does not), the only changes rule 2 allows. */
    public sealed interface Change {

        /** A new entry, listing the one release it approves. */
        record Create(ApprovedRelease release) implements Change {}

        /** {@code release} approved after the ones already listed. */
        record Append(ApprovedRelease release) implements Change {}

        /** These tags no longer approved; the others kept, in order. The whole entry goes by deleting its file. */
        record Remove(List<String> tags) implements Change {
            public Remove {
                tags = List.copyOf(tags);
            }
        }

        /** A bot's {@code detached} changed, its releases kept. */
        record Detach(boolean detached) implements Change {}

        /** The release the change approves: what rules 3 and 5 and the kind's checks run on. */
        default Optional<ApprovedRelease> approves() {
            return switch (this) {
                case Create create -> Optional.of(create.release());
                case Append append -> Optional.of(append.release());
                case Remove ignored -> Optional.empty();
                case Detach ignored -> Optional.empty();
            };
        }

        /**
         * What {@code head} does to {@code base}, the catalog's copy at the same path.
         *
         * @throws Refused when it is none of the allowed changes, saying why
         */
        static Change between(Optional<? extends Entry> base, Entry head) throws Refused {
            List<ApprovedRelease> now = head.releases();
            if (base.isEmpty()) {
                if (now.size() == 1) return new Create(now.getFirst());
                throw new Refused("a new entry lists one release, the one its pull request approves; this one lists "
                        + now.size() + ".");
            }
            Entry held = base.get();
            if (!held.repo().equals(head.repo())) {
                throw new Refused("a listed entry keeps its repository " + held.repo() + "; it cannot become "
                        + head.repo() + ".");
            }
            List<ApprovedRelease> before = held.releases();
            boolean detach = held instanceof BotEntry bot && head instanceof BotEntry next && bot.detached() != next.detached();
            if (now.equals(before)) {
                if (detach) return new Detach(((BotEntry) head).detached());
                throw new Refused("changes nothing the entry holds.");
            }
            if (detach) {
                throw new Refused("\"detached\" changes in a pull request of its own, with the releases kept.");
            }
            if (now.size() == before.size() + 1 && now.subList(0, before.size()).equals(before)) {
                return new Append(now.getLast());
            }
            if (now.size() < before.size() && keepsOrder(before, now)) {
                Set<String> kept = now.stream().map(ApprovedRelease::tag).collect(Collectors.toSet());
                return new Remove(before.stream().map(ApprovedRelease::tag).filter(t -> !kept.contains(t)).toList());
            }
            for (ApprovedRelease release : now) {
                Optional<ApprovedRelease> was = held.release(release.tag());
                if (was.isPresent() && !was.get().equals(release)) {
                    throw new Refused("release " + release.tag() + " was edited; an approved release is never"
                            + " edited or reordered.");
                }
            }
            throw new Refused("an approved release is never edited or reordered: a pull request appends one release"
                    + " at the end, or removes some.");
        }

        /** Whether {@code now} is {@code before} with some releases left out, the rest in order. */
        private static boolean keepsOrder(List<ApprovedRelease> before, List<ApprovedRelease> now) {
            int at = 0;
            for (ApprovedRelease release : before) {
                if (at < now.size() && now.get(at).equals(release)) at++;
            }
            return at == now.size();
        }
    }

    /** A change rule 2 does not allow; the message says why, for the author. */
    public static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    /**
     * Which rules one run checks. validate.yml runs {@link #RULES} with its token and {@link #KIND} in a later step
     * without one, since a plugin's constructor runs in this JVM; the author's own run checks {@link #ALL}.
     */
    enum Part {
        ALL, RULES, KIND;

        static Optional<Part> fromId(String id) {
            return Stream.of(values()).filter(p -> p.name().equalsIgnoreCase(id)).findFirst();
        }
    }

    private EntryGate() {}

    public static void main(String[] args) {
        System.exit(run(args, GitHubFacts.anonymousOrToken()));
    }

    static int run(String[] args, EntryFacts facts) {
        Console console = new Console(false);
        Path head = null;
        Path base = null;
        String author = null;
        long authorId = 0;
        Optional<Part> part = Optional.of(Part.ALL);
        List<String> paths = new ArrayList<>();
        boolean stray = false;
        // Changed paths are file names a pull request chose, so none is ever read as a flag: they come after
        // `--`, or one per line in an @file.
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--")) {
                paths.addAll(List.of(args).subList(i + 1, args.length));
                break;
            }
            switch (args[i]) {
                case "--author" -> author = i + 1 < args.length ? args[++i] : null;
                case "--author-id" -> authorId = i + 1 < args.length ? accountId(args[++i]) : -1;
                case "--base" -> base = i + 1 < args.length ? Path.of(args[++i]) : null;
                case "--part" -> part = i + 1 < args.length ? Part.fromId(args[++i]) : Optional.empty();
                default -> {
                    if (head == null) head = Path.of(args[i]);
                    else if (args[i].startsWith("@") && paths.isEmpty()) paths.add(args[i]);
                    else stray = true;
                }
            }
        }
        if (stray || head == null || author == null || author.isBlank() || authorId < 0 || paths.isEmpty()
                || part.isEmpty()) {
            console.error("usage: EntryGate <head-root> --author <login> [--author-id <id>] [--base <base-root>]"
                    + " [--part all|rules|kind] (@changed.txt | -- <changed-path>...)");
            return 2;
        }
        List<String> changed;
        try {
            changed = changedPaths(paths);
        } catch (IOException e) {
            console.error("could not read the changed-path list: " + e.getMessage());
            return 2;
        }
        Request request = new Request(head.toAbsolutePath().normalize(),
                Optional.ofNullable(base).map(p -> p.toAbsolutePath().normalize()), changed, author, authorId);
        List<Finding> findings = new ArrayList<>(part.get() == Part.KIND ? oneEntry(request) : check(request, facts));
        if (!Finding.refused(findings) && part.get() != Part.RULES) {
            findings.addAll(kindChecks(request, facts, console));
        }
        for (Finding finding : findings) {
            String line = finding.path() + ": " + finding.message();
            if (finding.severity() == Finding.Severity.ERROR) console.error(line);
            else console.warn(line);
        }
        if (Finding.refused(findings)) {
            console.out("This pull request cannot be listed.");
            return 1;
        }
        console.out("The catalog's checks pass.");
        return 0;
    }

    /** Rules 1–5 for a pull request. Empty means the kind's own checks run next. */
    public static List<Finding> check(Request request, EntryFacts facts) {
        if (request.changed().size() != 1) {
            return List.of(Finding.error(String.join(", ", request.changed()), "a pull request changes exactly one"
                    + " entry file, bots/<owner>__<repo>.json or plugins/<id>.json; this one changes "
                    + request.changed().size() + " paths."));
        }
        String path = request.changed().getFirst();
        Optional<EntryPath> named = EntryPath.parse(path);
        if (named.isEmpty() && !path.equals(Publishers.FILE)) {
            return List.of(Finding.error(path, "not an entry path: a pull request changes one"
                    + " bots/<owner>__<repo>.json or plugins/<id>.json, nothing else."));
        }
        if (request.base().isEmpty()) {
            return List.of(Finding.error(path, "the check ran without --base, so whose entry this was cannot be"
                    + " told."));
        }
        if (named.isEmpty()) {
            return PublishersCheck.check(request.head(), request.base().get(), request.authorId());
        }
        try {
            return checkPath(request, named.get(), facts);
        } catch (UncheckedIOException e) {
            return List.of(Finding.error(path, "could not reach GitHub: " + e.getCause().getMessage()
                    + ". Re-run the check."));
        }
    }

    private static List<Finding> checkPath(Request request, EntryPath named, EntryFacts facts) {
        String path = named.path();
        Path base = request.base().orElseThrow();
        Optional<Entry> previous;
        try {
            previous = readIfPresent(base, named);
        } catch (IOException | IllegalArgumentException e) {
            return List.of(Finding.error(path, "the catalog's current copy of this file does not read, so whose"
                    + " entry it is cannot be told; the admin replaces it."));
        }
        boolean admin = isAdmin(base, request.authorId());
        Path file = request.head().resolve(path);
        if (Files.isSymbolicLink(file)) {
            return List.of(Finding.error(path, "a symbolic link; an entry is a plain JSON file."));
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return previous.map(held -> admin ? List.<Finding>of() : removal(path, held, request.author(), facts))
                    .orElse(List.of(Finding.error(path, "removes a file the catalog does not hold.")));
        }
        Entry entry;
        try {
            entry = read(file, named.kind());
        } catch (IOException | IllegalArgumentException e) {
            return List.of(Finding.error(path, "not an entry of this schema: "
                    + (named.kind() == EntryPath.Kind.BOT
                    ? "{\"repo\", \"releases\": [{\"tag\", \"commit\"[, \"bmkSha256\"]}, …]} and an optional"
                    + " \"detached\""
                    : "{\"id\", \"repo\", \"releases\": [{\"tag\", \"commit\", \"jarSha256\", \"pomSha256\"}, …]}")
                    + ", no tag twice, nothing else."));
        }
        if (!named.matches(entry)) {
            return List.of(Finding.error(path, "the file name is what the entry names: rename it to "
                    + EntryPath.of(entry) + "."));
        }
        Optional<String> twin = caseTwin(base, named);
        if (twin.isPresent()) {
            return List.of(Finding.error(path, "the catalog already lists " + twin.get() + ", which differs only by"
                    + " case; GitHub reads both as one repository or one file."));
        }
        return checkEntry(entry, path, request.author(), admin, previous, facts);
    }

    /**
     * Rules 2–5 on one entry with no pull request around it: what {@code bot publish} and {@code plugin publish}
     * run before they open one, so the author meets a refusal on their own machine.
     *
     * @param previous the entry the catalog holds at this path today
     */
    public static List<Finding> checkEntry(Entry entry, String path, String author, Optional<? extends Entry> previous,
                                           EntryFacts facts) {
        return checkEntry(entry, path, author, false, previous, facts);
    }

    /** {@link #checkEntry(Entry, String, String, Optional, EntryFacts)}, with rules 3 and 4 passed for an admin. */
    public static List<Finding> checkEntry(Entry entry, String path, String author, boolean admin,
                                    Optional<? extends Entry> previous, EntryFacts facts) {
        List<Finding> findings = new ArrayList<>();
        if (!Entry.isSlug(entry.repo())) {
            findings.add(Finding.error(path, "\"repo\" is owner/name on GitHub; " + entry.repo() + " is not."));
            return findings;
        }
        for (ApprovedRelease release : entry.releases()) {
            String refusal = pinRefusal(release);
            if (refusal != null) {
                findings.add(Finding.error(path, refusal));
                return findings;
            }
        }
        Change change;
        try {
            change = Change.between(previous, entry);
        } catch (Refused e) {
            findings.add(Finding.error(path, e.getMessage()));
            return findings;
        }
        previous.ifPresent(held -> {
            if (!admin && !controls(author, held.repo(), held.newest().tag(), facts)) {
                findings.add(Finding.error(path, "this entry belongs to whoever controls " + held.repo()
                        + ", and only they may change it: " + author + " did not make its release "
                        + held.newest().tag() + ". Publish under your own entry instead."));
            }
        });
        Optional<ApprovedRelease> approved = change.approves();
        if (approved.isEmpty()) return findings;
        ApprovedRelease release = approved.get();
        String tag = release.tag();
        Optional<EntryFacts.Repo> repo = facts.repo(entry.repo());
        if (repo.isEmpty()) {
            findings.add(Finding.error(path, entry.repo() + " is not a public repository on GitHub."));
            return findings;
        }
        Optional<EntryFacts.Release> published = facts.release(entry.repo(), tag);
        if (published.isEmpty()) {
            findings.add(Finding.error(path, entry.repo() + " has no GitHub Release " + tag
                    + ". Publish one, then re-run the check."));
            return findings;
        }
        if (published.get().draft() || published.get().prerelease()) {
            findings.add(Finding.error(path, "release " + tag + " is a "
                    + (published.get().draft() ? "draft" : "prerelease") + "; the catalog lists published releases."));
        }
        Optional<String> commit = facts.tagCommit(entry.repo(), tag);
        if (!commit.equals(Optional.of(release.commit()))) {
            findings.add(Finding.error(path, "tag " + tag + " points at " + commit.orElse("nothing") + ", not the"
                    + " pinned commit " + release.commit() + ". Pin the release again (`botmaker plugin publish` or"
                    + " `botmaker bot publish` does)."));
        }
        if (!admin && !controls(author, entry.repo(), tag, facts)) {
            findings.add(Finding.error(path, ACTIONS_BOT.equals(published.get().authorLogin())
                    ? "release " + tag + " was made by a GitHub Action, and " + author + " is not "
                    + repo.get().ownerLogin() + ", so who controls " + entry.repo() + " cannot be told. Make the"
                    + " release from Studio or the CLI, which makes it as you."
                    : "release " + tag + " was made by " + published.get().authorLogin() + ", not " + author
                    + ": the entry is opened by whoever made the release it lists."));
        }
        return findings;
    }

    /**
     * Why a release's tag or pins are not what the rules allow, or {@code null}. They reach git and a URL, so the
     * kind's checks match them again.
     */
    static String pinRefusal(ApprovedRelease release) {
        if (!TAG.matcher(release.tag()).matches()) return "\"tag\" " + release.tag() + " is not a release tag.";
        if (!ApprovedRelease.COMMIT.matcher(release.commit()).matches()) {
            return "\"commit\" of " + release.tag() + " is not a full commit hash (40 lower-case hex digits).";
        }
        for (String hash : Stream.of(release.jarSha256(), release.pomSha256(), release.bmkSha256())
                .filter(h -> h != null).toList()) {
            if (!ApprovedRelease.SHA256.matcher(hash).matches()) {
                return "a sha256 of " + release.tag() + " is not 64 lower-case hex digits.";
            }
        }
        return null;
    }

    /**
     * Rule 3: {@code author} made the release at {@code tag}, or the release was made by an Action (or is gone)
     * and {@code author} is the repository's current owner. GitHub logins ignore case.
     */
    public static boolean controls(String author, String repo, String tag, EntryFacts facts) {
        // An Action opening the pull request proves nothing about who controls a repository.
        if (author == null || author.isBlank() || same(author, ACTIONS_BOT)) return false;
        Optional<EntryFacts.Release> release = facts.release(repo, tag);
        if (release.isPresent() && same(author, release.get().authorLogin())) return true;
        if (release.isPresent() && !ACTIONS_BOT.equals(release.get().authorLogin())) return false;
        return facts.repo(repo).map(r -> same(author, r.ownerLogin())).orElse(false);
    }

    /**
     * A removal, only by whoever controls the entry. Not even when its repository answers 404: a repository made
     * private for a while answers that too, and removing its entry would free its plugin id for anyone to claim.
     * The catalog leaves a gone repository out anyway; the admin removes its file by hand.
     */
    private static List<Finding> removal(String path, Entry held, String author, EntryFacts facts) {
        if (controls(author, held.repo(), held.newest().tag(), facts)) return List.of();
        return List.of(Finding.error(path, "only whoever controls " + held.repo() + " may remove this entry."));
    }

    /**
     * Whether {@code authorId} is an admin in the base tree's {@code publishers.json}. One that does not read
     * makes nobody an admin: the exception is lost, never granted.
     */
    private static boolean isAdmin(Path base, long authorId) {
        try {
            return Publishers.read(base.resolve(Publishers.FILE)).isAdmin(authorId);
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    /** What {@link Part#KIND} needs before it reads the entry, when rule 1 ran in another step. */
    private static List<Finding> oneEntry(Request request) {
        if (request.changed().size() == 1 && (EntryPath.parse(request.changed().getFirst()).isPresent()
                || isPublishers(request))) {
            return List.of();
        }
        return List.of(Finding.error(String.join(", ", request.changed()), "the kind's checks read exactly one"
                + " entry path; run the rules first."));
    }

    /**
     * The bot or plugin checks for the one release a passing pull request approves; a removal or a detach has
     * none. The pins are matched again here, since they reach git and a URL and {@link Part#KIND} runs without
     * rules 2–5.
     */
    private static List<Finding> kindChecks(Request request, EntryFacts facts, Console console) {
        if (isPublishers(request)) return List.of();
        String path = request.changed().getFirst();
        Path file = request.head().resolve(path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (request.base().isEmpty()) {
            return List.of(Finding.error(path, "the kind's checks ran without --base, so which release this pull"
                    + " request approves cannot be told."));
        }
        try {
            EntryPath named = EntryPath.parse(path).orElseThrow();
            Entry entry = read(file, named.kind());
            Optional<ApprovedRelease> approved = Change.between(readIfPresent(request.base().get(), named), entry)
                    .approves();
            if (approved.isEmpty()) return List.of();
            ApprovedRelease release = approved.get();
            if (!Entry.isSlug(entry.repo()) || pinRefusal(release) != null) {
                return List.of(Finding.error(path, "\"repo\" or the release's pins are not what the rules allow;"
                        + " run the rules first."));
            }
            Optional<Catalog> catalog = Catalog.read(request.head().resolve(Catalog.FILE));
            Mvn mvn = new Mvn(console);
            return switch (entry) {
                case BotEntry bot -> BotChecks.checkRelease(bot.repo(), release, path, pluginIds(catalog), catalog,
                        facts, mvn);
                case PluginEntry plugin -> PluginChecks.check(plugin, release, path, facts,
                        new Subjects(console, mvn), claimedIds(request.head(), plugin.id()));
            };
        } catch (Refused e) {
            return List.of(Finding.error(path, e.getMessage()));
        } catch (IOException | IllegalArgumentException e) {
            return List.of(Finding.error(path, "could not run the checks: " + e.getMessage()));
        } catch (UncheckedIOException e) {
            return List.of(Finding.error(path, "could not reach GitHub: " + e.getCause().getMessage()
                    + ". Re-run the check."));
        }
    }

    /** The catalog's plugin ids by coordinate key ({@link PomFacts#key}). */
    public static Map<String, String> pluginIds(Optional<Catalog> catalog) {
        return catalog.map(c -> c.plugins().stream().collect(Collectors.toMap(
                p -> p.coordinate().toLowerCase(Locale.ROOT), Catalog.Plugin::id, (a, b) -> a))).orElse(Map.of());
    }

    /** The ids every other plugin entry claims: a jar answering to one of them is refused. */
    private static Set<String> claimedIds(Path head, String except) throws IOException {
        Path dir = head.resolve(EntryPath.PLUGINS);
        if (!Files.isDirectory(dir)) return Set.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> EntryPath.parse(EntryPath.PLUGINS + f.getFileName()))
                    .flatMap(Optional::stream).map(EntryPath::key)
                    .filter(id -> !id.equals(except)).collect(Collectors.toUnmodifiableSet());
        }
    }

    /** Another entry file in {@code root} whose name equals {@code named}'s ignoring case only. */
    private static Optional<String> caseTwin(Path root, EntryPath named) {
        String directory = named.kind() == EntryPath.Kind.BOT ? EntryPath.BOTS : EntryPath.PLUGINS;
        String name = named.path().substring(directory.length());
        Path dir = root.resolve(directory);
        if (!Files.isDirectory(dir)) return Optional.empty();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString())
                    .filter(other -> other.equalsIgnoreCase(name) && !other.equals(name))
                    .map(other -> directory + other).findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Optional<Entry> readIfPresent(Path root, EntryPath named) throws IOException {
        Path file = root.resolve(named.path());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        return Optional.of(read(file, named.kind()));
    }

    private static Entry read(Path file, EntryPath.Kind kind) throws IOException {
        return kind == EntryPath.Kind.BOT ? BotEntry.read(file) : PluginEntry.read(file);
    }

    /** A pull request that changes {@code publishers.json} alone: no entry, so no kind's check. */
    private static boolean isPublishers(Request request) {
        return request.changed().equals(List.of(Publishers.FILE));
    }

    /** An account id as GitHub prints it, or {@code -1}, which the usage check refuses. */
    private static long accountId(String text) {
        try {
            long id = Long.parseLong(text.trim());
            return id < 0 ? -1 : id;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    /** Lines as written: a trailing space is part of a file name, and trimming it would check another file. */
    private static List<String> changedPaths(List<String> args) throws IOException {
        if (args.size() == 1 && args.getFirst().startsWith("@")) {
            return Files.readAllLines(Path.of(args.getFirst().substring(1))).stream()
                    .filter(line -> !line.isEmpty()).toList();
        }
        return args;
    }
}
