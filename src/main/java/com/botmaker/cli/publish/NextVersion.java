package com.botmaker.cli.publish;

import com.botmaker.cli.catalog.ApprovedRelease;
import com.botmaker.cli.catalog.Entry;
import com.botmaker.cli.catalog.EntryFacts;
import com.botmaker.cli.catalog.EntryGate;
import com.botmaker.cli.release.Level;
import com.botmaker.cli.release.Version;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The tag a publish lists, and whether it still has to be made.
 *
 * <p>In this order (2026-10-24):
 * <ol>
 *   <li>{@code --tag} names it exactly. One that already has a published release is {@link Listing#EXISTING}: how a
 *       release made elsewhere is listed once {@code HEAD} has moved past it.</li>
 *   <li>{@code HEAD} carries a {@code vX.Y.Z} tag the catalog has not approved: that tag. With a published release it is
 *       {@code EXISTING} (the release library made it, or a publish stopped after the release); without one it is
 *       {@link Listing#NEW} and its release is made. Either way a re-run after a failure past the tag lists that
 *       tag and never bumps again.</li>
 *   <li>Otherwise the highest {@code vX.Y.Z} reachable from {@code HEAD} or approved, bumped by the level
 *       ({@code patch} by default); no tag yet is {@value #FIRST}. The newest tag must itself be {@code vX.Y.Z}. A newest tag of another shape is refused: there is no number to
 *       bump, and a guess would be a tag nobody can take back.</li>
 * </ol>
 *
 * @param listing whether the tag and its release are made by this publish ({@code NEW}) or exist already
 */
public record NextVersion(String tag, Listing listing) {

    /** A first publish's tag. */
    public static final String FIRST = "v0.1.0";

    public enum Listing {
        /** Tagged and released by this publish, after the folder's own check. */
        NEW,
        /** Released already: nothing tagged, the folder not checked, a plugin's jar checked from JitPack. */
        EXISTING
    }

    /**
     * Decides.
     *
     * @param listed the catalog's entry for this repository, whose approved tags are never published again
     * @param repo   the repository, {@code owner/name}, whose GitHub Releases say what is published
     */
    public static NextVersion decide(Path dir, Level bump, Optional<String> tag, Optional<? extends Entry> listed,
                                     String repo, EntryFacts facts, StepRunner runner) throws Refusal {
        Set<String> approved = listed.map(e -> e.releases().stream().map(ApprovedRelease::tag)
                .collect(Collectors.toSet())).orElse(Set.of());
        Optional<String> head = runner.read(dir, List.of("git", "rev-parse", "HEAD"));
        if (tag.isPresent()) {
            String named = tag.get().strip();
            if (!EntryGate.TAG.matcher(named).matches()) {
                throw new Refusal("--tag " + named + " is not a tag the catalog lists (letters, digits, '.', '_',"
                        + " '+' and '-').");
            }
            if (published(facts, repo, named)) return new NextVersion(named, Listing.EXISTING);
            Optional<String> at = commitOf(dir, named, runner);
            if (at.isPresent() && !at.equals(head)) {
                throw new Refusal("tag " + named + " exists at " + at.get() + ", not at HEAD, and has no published"
                        + " release. Check it out to publish it, or name another tag.");
            }
            return new NextVersion(named, Listing.NEW);
        }
        // Only vX.Y.Z: a tag such as `latest` or `deploy` on HEAD was made for something else.
        List<String> atHead = lines(runner.read(dir, List.of("git", "tag", "--points-at", "HEAD"))).stream()
                .filter(NextVersion::isVersion).sorted(NEWEST_FIRST).toList();
        for (String carried : atHead) {
            if (!approved.contains(carried)) {
                return new NextVersion(carried, published(facts, repo, carried) ? Listing.EXISTING : Listing.NEW);
            }
        }
        if (!atHead.isEmpty()) {
            throw new Refusal("HEAD is " + atHead.getFirst() + ", which the catalog lists already. Commit a change to"
                    + " publish a new version, or name one with --tag.");
        }
        Optional<String> newest = runner.read(dir, List.of("git", "describe", "--tags", "--abbrev=0"))
                .filter(s -> !s.isBlank());
        if (newest.isEmpty()) return fresh(dir, FIRST, repo, facts, runner);
        if (!isVersion(newest.get())) {
            throw new Refusal("the newest tag, " + newest.get() + ", is not vX.Y.Z, so there is no number to bump."
                    + " Name the version with --tag <tag>.");
        }
        // The highest vX.Y.Z reachable from HEAD or approved, not the nearest: a hotfix tag nearer HEAD than a
        // higher release would otherwise bump to a version older than one already out.
        Version highest = Stream.concat(lines(runner.read(dir, List.of("git", "tag", "--merged", "HEAD"))).stream(),
                        approved.stream())
                .filter(NextVersion::isVersion).map(t -> Version.parse(t).orElseThrow())
                .max(Comparator.naturalOrder()).orElseThrow();
        return fresh(dir, highest.bump(bump).tag(), repo, facts, runner);
    }

    /** {@code vX.Y.Z}, what a bump reads and writes. */
    private static boolean isVersion(String tag) {
        return tag.startsWith("v") && Version.parse(tag).isPresent();
    }

    private static List<String> lines(Optional<String> out) {
        return out.map(text -> text.lines().map(String::strip).filter(s -> !s.isEmpty()).toList()).orElse(List.of());
    }

    /** A bumped tag, refused when it exists already elsewhere: tagging it would move nothing or clash. */
    private static NextVersion fresh(Path dir, String tag, String repo, EntryFacts facts, StepRunner runner)
            throws Refusal {
        if (commitOf(dir, tag, runner).isPresent() || published(facts, repo, tag)) {
            throw new Refusal("the next version, " + tag + ", is tagged already on another commit. Name the version"
                    + " with --tag <tag>.");
        }
        return new NextVersion(tag, Listing.NEW);
    }

    /** Whether {@code repo} has a published (not draft) GitHub Release at {@code tag}. */
    private static boolean published(EntryFacts facts, String repo, String tag) {
        return facts.release(repo, tag).filter(r -> !r.draft()).isPresent();
    }

    /** The commit the folder's tag points at, or empty when the folder has no such tag. */
    private static Optional<String> commitOf(Path dir, String tag, StepRunner runner) {
        return runner.read(dir, List.of("git", "rev-parse", "-q", "--verify", "refs/tags/" + tag + "^{commit}"))
                .filter(s -> !s.isBlank());
    }

    /** {@code vX.Y.Z} tags, newest first. */
    private static final Comparator<String> NEWEST_FIRST =
            Comparator.comparing((String t) -> Version.parse(t).orElseThrow()).reversed();
}
