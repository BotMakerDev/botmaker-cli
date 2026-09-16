package com.botmaker.cli.gallery;

import com.botmaker.cli.Console;
import com.botmaker.cli.registry.Registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The gallery's gate: the check that decides whether a pull request on {@code botmaker-gallery} may be listed.
 *
 * <p><b>Since 2026-09-16 nobody reads the pull request after this does.</b> A submission that passes is merged
 * by the gallery's {@code automerge.yml}, subject only to {@link ListingPolicy}'s rate limit, and lands as
 * {@link Tier#COMMUNITY}. So everything that was implicitly the maintainer's eye is a rule here, and the rules
 * are the ones whose failure is somebody else's broken install or somebody else's hijacked listing:
 *
 * <ul>
 *   <li>the generated files ({@value GalleryCatalog#CATALOG}, {@value GalleryCatalog#INDEX}) are never edited;</li>
 *   <li>{@code vetted/} is a maintainer's to change, and a vetted record points at a release that exists;</li>
 *   <li>an entry's filename is its {@code owner-repo}, it names a project, and its schema is one this gate
 *       knows;</li>
 *   <li>an entry that already exists is changed or removed only by its owner — checked against the
 *       <b>base</b> copy, because {@code a-b-c.json} is both {@code a-b/c} and {@code a/b-c} and the head copy
 *       says whatever the pull request wants it to;</li>
 *   <li>the repository has a release, and that release's archive — what an install downloads — downloads.</li>
 * </ul>
 *
 * <p>A <em>new</em> entry for a repository the pull request's author does not own is a warning, not a refusal:
 * an organisation's bot is legitimate and simply cannot be proven from a login, so {@link ListingPolicy} hands
 * it to a maintainer instead of merging it. {@code requires} naming a plugin the registry does not list is a
 * warning too — the bot may well work, and the registry is not the only place a plugin can come from.
 *
 * <p>Same shape as {@code registry/RegistryGate}, and in this module for the same reason: it must be the code
 * {@code botmaker bot publish} already ran. No command-line library, and changed paths may arrive as
 * {@code @file} because a pull request chooses its own filenames.
 *
 * <pre>
 * java -cp … com.botmaker.cli.gallery.GalleryGate &lt;head-root&gt; --author &lt;login&gt;
 *      [--base &lt;base-root&gt;] [--maintainer] &lt;changed-path&gt;… | @changed.txt
 * </pre>
 *
 * <p>Exit code: {@code 0} passes (warnings allowed), {@code 1} refused, {@code 2} bad invocation.
 */
public final class GalleryGate {

    public enum Severity { ERROR, WARNING }

    /** One thing the gate has to say, about one path. */
    public record Finding(Severity severity, String path, String message) {
        static Finding error(String path, String message) {
            return new Finding(Severity.ERROR, path, message);
        }

        static Finding warning(String path, String message) {
            return new Finding(Severity.WARNING, path, message);
        }
    }

    /**
     * A pull request as the gate sees it.
     *
     * @param head       the pull request's tree
     * @param base       the tree it would merge into, when there is one — without it an edit cannot be told
     *                   from a hijack, so every existing-entry change is refused
     * @param changed    the changed paths, as git names them
     * @param author     the pull request author's login
     * @param maintainer whether that author may push to the gallery
     */
    public record Request(Path head, Optional<Path> base, List<String> changed, String author,
                          boolean maintainer) {
        public Request {
            base = base == null ? Optional.empty() : base;
            changed = List.copyOf(changed);
            author = author == null ? "" : author.trim();
        }
    }

    private GalleryGate() {
    }

    public static void main(String[] args) {
        System.exit(run(args, GalleryFacts.github()));
    }

    static int run(String[] args, GalleryFacts facts) {
        Console console = new Console(false);
        Path head = null;
        Path base = null;
        String author = null;
        boolean maintainer = false;
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--author" -> author = i + 1 < args.length ? args[++i] : null;
                case "--base" -> base = i + 1 < args.length ? Path.of(args[++i]) : null;
                case "--maintainer" -> maintainer = true;
                default -> {
                    if (head == null) {
                        head = Path.of(args[i]);
                    } else {
                        paths.add(args[i]);
                    }
                }
            }
        }
        if (head == null || author == null || author.isBlank() || paths.isEmpty()) {
            console.error("usage: GalleryGate <head-root> --author <login> [--base <base-root>] [--maintainer]"
                    + " <changed-path>... | @changed.txt");
            return 2;
        }
        List<String> changed;
        try {
            changed = changedPaths(paths);
        } catch (IOException e) {
            console.error("could not read the changed-path list: " + e.getMessage());
            return 2;
        }

        List<Finding> findings = check(new Request(head.toAbsolutePath().normalize(),
                Optional.ofNullable(base).map(p -> p.toAbsolutePath().normalize()), changed, author, maintainer),
                facts);
        for (Finding finding : findings) {
            String line = finding.path() + ": " + finding.message();
            if (finding.severity() == Severity.ERROR) {
                console.error(line);
            } else {
                console.warn(line);
            }
        }
        long errors = findings.stream().filter(f -> f.severity() == Severity.ERROR).count();
        if (errors > 0) {
            console.out(errors + " problem(s); this pull request cannot be listed.");
            return 1;
        }
        console.out("The gallery's checks pass.");
        return 0;
    }

    /** Every finding for a pull request. Empty means it may be listed. */
    public static List<Finding> check(Request request, GalleryFacts facts) {
        List<Finding> findings = new ArrayList<>();
        for (String path : request.changed()) {
            if (path.equals(GalleryCatalog.INDEX) || path.equals(GalleryCatalog.CATALOG)) {
                findings.add(Finding.error(path, path + " is generated from " + GalleryEntry.ENTRIES_DIRECTORY
                        + "/ and " + VettedRecord.DIRECTORY + "/ and committed by CI. If BotMaker Studio opened"
                        + " this pull request, update Studio: releases before the per-entry gallery wrote the"
                        + " whole index and can no longer publish. Otherwise edit "
                        + GalleryEntry.ENTRIES_DIRECTORY + "/<owner>-<repo>.json instead."));
            } else if (isJsonIn(path, VettedRecord.DIRECTORY)) {
                findings.addAll(checkVetted(request, path, facts));
            } else if (isJsonIn(path, GalleryEntry.ENTRIES_DIRECTORY)) {
                findings.addAll(checkEntryFile(request, path, facts));
            }
        }
        return findings;
    }

    /**
     * The checks on one entry, with no pull request around it — what {@code bot publish} runs before it opens
     * one, so the author meets a refusal on their own machine rather than as a red check.
     *
     * @param previous the entry as the gallery holds it today, when it holds one
     */
    public static List<Finding> checkEntry(GalleryEntry entry, String path, String author, boolean maintainer,
                                           Optional<GalleryEntry> previous, GalleryFacts facts) {
        List<Finding> findings = new ArrayList<>();
        if (entry.schemaVersion() > GalleryEntry.CURRENT_SCHEMA) {
            findings.add(Finding.error(path, "schemaVersion " + entry.schemaVersion() + " is newer than this gate"
                    + " knows (" + GalleryEntry.CURRENT_SCHEMA + "). The gallery's botmaker-cli pin needs a bump"
                    + " before this entry can be read without losing fields."));
            return findings;
        }
        if (entry.name().isEmpty() || entry.owner().isEmpty() || entry.repo().isEmpty()) {
            findings.add(Finding.error(path, "an entry needs a name, an owner and a repo."));
            return findings;
        }
        if (!path.equals(entry.path())) {
            findings.add(Finding.error(path, "the filename is the bot's owner-repo: rename this to "
                    + entry.path()));
            return findings;
        }
        previous.ifPresent(held -> {
            if (!maintainer && !ownedBy(author, held.owner())) {
                findings.add(Finding.error(path, "this listing belongs to " + held.owner() + ", and only its owner"
                        + " may change it. Publish your bot under your own repository instead."));
            }
        });
        if (previous.isEmpty() && !maintainer && !ownedBy(author, entry.owner())) {
            findings.add(Finding.warning(path, author + " is not " + entry.owner() + ", so ownership cannot be"
                    + " proven from the login alone and a maintainer will list it by hand."));
        }
        for (GalleryEntry.Requirement requirement : entry.requires()) {
            if (requirement.id().isEmpty()) {
                findings.add(Finding.error(path, "requires holds an entry with no plugin id."));
            }
        }
        Optional<Set<String>> known = facts.pluginIds();
        known.ifPresent(ids -> entry.requires().stream()
                .map(GalleryEntry.Requirement::id)
                .filter(id -> !id.isEmpty() && !ids.contains(id))
                .forEach(id -> findings.add(Finding.warning(path, "requires " + id + ", which the plugin"
                        + " registry does not list — a reader will not be able to install it from Studio."))));
        findings.addAll(releaseFindings(path, entry.owner(), entry.repo(), facts));
        return findings;
    }

    /**
     * Whether {@code author} is {@code owner}. GitHub logins are case-insensitive. The one statement of the
     * ownership rule, so {@link ListingPolicy} and this gate cannot disagree about it.
     */
    public static boolean ownedBy(String author, String owner) {
        return author != null && owner != null && !author.isBlank()
                && author.trim().toLowerCase(Locale.ROOT).equals(owner.trim().toLowerCase(Locale.ROOT));
    }

    private static List<Finding> checkEntryFile(Request request, String path, GalleryFacts facts) {
        if (request.base().isEmpty() && !request.maintainer()) {
            // Without the base tree an edit, a hijack and a new listing all look the same. Refuse rather than
            // guess; CI always passes --base.
            return List.of(Finding.error(path, "the gate was run without --base, so it cannot tell whose"
                    + " listing this was."));
        }
        Optional<GalleryEntry> previous;
        try {
            previous = read(request.base(), path, GalleryEntry.class);
        } catch (IOException e) {
            // An existing file nobody can parse still belongs to somebody. Only a maintainer replaces it.
            if (!request.maintainer()) {
                return List.of(Finding.error(path, "the gallery's current copy of this file does not parse, so"
                        + " whose listing it is cannot be told. A maintainer has to replace it."));
            }
            previous = Optional.empty();
        }
        Path file = request.head().resolve(path);
        if (!Files.isRegularFile(file)) {
            // A deletion: delisting needs no release, but it does need to be the owner's.
            return previous
                    .filter(held -> !request.maintainer() && !ownedBy(request.author(), held.owner()))
                    .map(held -> List.of(Finding.error(path, "only " + held.owner() + " may delist this bot.")))
                    .orElse(List.of());
        }
        GalleryEntry entry;
        try {
            entry = Registry.mapper().readValue(Files.readString(file), GalleryEntry.class);
        } catch (IOException e) {
            return List.of(Finding.error(path, "not valid JSON for a gallery entry: " + e.getMessage()));
        }
        return checkEntry(entry, path, request.author(), request.maintainer(), previous, facts);
    }

    private static List<Finding> checkVetted(Request request, String path, GalleryFacts facts) {
        if (!request.maintainer()) {
            return List.of(Finding.error(path, VettedRecord.DIRECTORY + "/ is changed only by a gallery"
                    + " maintainer. A bot becomes Vetted when a maintainer vets one of its releases."));
        }
        Path file = request.head().resolve(path);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        VettedRecord record;
        try {
            record = Registry.mapper().readValue(Files.readString(file), VettedRecord.class);
        } catch (IOException e) {
            return List.of(Finding.error(path, "not valid JSON for a vetted record: " + e.getMessage()));
        }
        List<Finding> findings = new ArrayList<>();
        if (!path.equals(record.path())) {
            findings.add(Finding.error(path, "the filename is the bot's owner-repo: rename this to "
                    + record.path()));
            return findings;
        }
        if (record.vettedVersion().isEmpty()) {
            findings.add(Finding.error(path, "a vetted record names the release it vets (vettedVersion)."));
            return findings;
        }
        Path entry = request.head().resolve(GalleryEntry.ENTRIES_DIRECTORY)
                .resolve(record.owner() + "-" + record.repo() + ".json");
        if (!Files.isRegularFile(entry)) {
            findings.add(Finding.error(path, "vets " + record.slug() + ", which has no entry in "
                    + GalleryEntry.ENTRIES_DIRECTORY + "/."));
        }
        try {
            if (!facts.archiveExists(record.owner(), record.repo(), record.vettedVersion())) {
                findings.add(Finding.error(path, "the archive for " + record.slug() + "@" + record.vettedVersion()
                        + " does not download, so the vetted release is not installable."));
            }
        } catch (IOException e) {
            findings.add(Finding.error(path, "could not reach GitHub to check the vetted release: "
                    + e.getMessage()));
        }
        return findings;
    }

    private static List<Finding> releaseFindings(String path, String owner, String repo, GalleryFacts facts) {
        try {
            Optional<String> tag = facts.latestReleaseTag(owner, repo);
            if (tag.isEmpty()) {
                return List.of(Finding.error(path, owner + "/" + repo + " has no release, and a bot is installed"
                        + " from its release archive. Cut one, then re-run the checks."));
            }
            if (!facts.archiveExists(owner, repo, tag.get())) {
                return List.of(Finding.error(path, "the archive for " + owner + "/" + repo + "@" + tag.get()
                        + " does not download — that archive is what an install fetches."));
            }
            return List.of();
        } catch (IOException e) {
            return List.of(Finding.error(path, "could not reach GitHub to check the release: " + e.getMessage()));
        }
    }

    private static boolean isJsonIn(String path, String directory) {
        return path.startsWith(directory + "/") && path.endsWith(".json")
                && path.indexOf('/', directory.length() + 1) < 0;
    }

    private static <T> Optional<T> read(Optional<Path> root, String path, Class<T> type) throws IOException {
        if (root.isEmpty()) {
            return Optional.empty();
        }
        Path file = root.get().resolve(path);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(Registry.mapper().readValue(Files.readString(file), type));
    }

    private static List<String> changedPaths(List<String> args) throws IOException {
        if (args.size() == 1 && args.getFirst().startsWith("@")) {
            return Files.readAllLines(Path.of(args.getFirst().substring(1))).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        }
        return args;
    }
}
