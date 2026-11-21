// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The check on a pull request that adds or changes one advisory, {@code advisories/<id>.json} (spec 2026-11-21 § 5).
 * It is merged like an entry, so it holds everything:
 *
 * <ol>
 *   <li>Its author is an admin in the <b>base</b> {@code publishers.json}, by account id.</li>
 *   <li>The file is a plain file of the schema, named after its {@code id}; a severity of the closed set, a summary
 *       and a withdrawal reason of 1 to {@value Advisory#MAX_TEXT} characters, an {@code https} link or none.</li>
 *   <li>A new advisory takes the next id of its year ({@link Advisory#next}), the year it was published, and is not
 *       withdrawn.</li>
 *   <li>Its {@code repo} is an entry the base lists, by its slug or one it had {@code formerly}, and every tag it adds
 *       is one of that entry's approved releases.</li>
 *   <li>A change only withdraws it, or only appends releases; an advisory is never deleted, its other fields never
 *       edited.</li>
 * </ol>
 *
 * <p>No entry's code is involved, so the kind's step has nothing to do for it.
 */
final class AdvisoryCheck {

    private AdvisoryCheck() {}

    static List<Finding> check(Path head, Path base, long authorId, String path, String id) {
        Publishers publishers;
        try {
            publishers = Publishers.read(base.resolve(Publishers.FILE));
        } catch (IOException | IllegalArgumentException e) {
            return List.of(Finding.error(path, "the catalog's current publishers.json does not read, so who is an"
                    + " admin cannot be told; an admin repairs it on main."));
        }
        if (!publishers.isAdmin(authorId)) {
            return List.of(Finding.error(path, "only an admin files or changes an advisory: the pull request's"
                    + " author is not an admin, by GitHub account id, in the catalog's current copy."));
        }
        Path file = head.resolve(path);
        if (Files.isSymbolicLink(file)) {
            return List.of(Finding.error(path, "a symbolic link; an advisory is a plain JSON file."));
        }
        Optional<Advisory> held;
        try {
            Path was = base.resolve(path);
            held = Files.isRegularFile(was, LinkOption.NOFOLLOW_LINKS) ? Optional.of(Advisory.read(was))
                    : Optional.empty();
        } catch (IOException | IllegalArgumentException e) {
            return List.of(Finding.error(path, "the catalog's current copy of this advisory does not read; an admin"
                    + " repairs it on main."));
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of(Finding.error(path, held.isPresent()
                    ? "an advisory is never deleted; withdraw it instead (`botmaker advisory withdraw " + id + "`)."
                    : "removes a file the catalog does not hold."));
        }
        Advisory advisory;
        try {
            advisory = Advisory.read(file);
        } catch (IOException | IllegalArgumentException e) {
            return List.of(Finding.error(path, "not an advisory of this schema: {\"id\", \"repo\", \"releases\": [tag,"
                    + " …], \"severity\": low|medium|high|critical, \"summary\", \"url\"?, \"published\": YYYY-MM-DD,"
                    + " \"withdrawn\"?}, nothing else."));
        }
        if (!id.equals(advisory.id())) {
            return List.of(Finding.error(path, "the file name is the advisory's id: rename it to "
                    + Advisory.path(String.valueOf(advisory.id())) + ", or fix its \"id\"."));
        }
        String refusal = advisory.problem();
        if (refusal != null) return List.of(Finding.error(path, refusal));
        List<Entry> entries;
        try {
            entries = CatalogBuilder.entries(base);
        } catch (IOException e) {
            return List.of(Finding.error(path, "the catalog's entries could not be read: " + e.getMessage()));
        }
        Optional<Entry> entry = entries.stream().filter(e -> e.repo().equalsIgnoreCase(advisory.repo())).findFirst()
                .or(() -> entries.stream().filter(e -> e.formerly().stream()
                        .anyMatch(advisory.repo()::equalsIgnoreCase)).findFirst());
        if (entry.isEmpty()) {
            return List.of(Finding.error(path, advisory.repo() + " is no entry the catalog lists, nor a slug one had"
                    + " before a rename or a transfer."));
        }
        List<String> added;
        if (held.isEmpty()) {
            refusal = newRefusal(advisory, base);
            if (refusal != null) return List.of(Finding.error(path, refusal));
            added = advisory.releases();
        } else {
            Advisory was = held.get();
            if (!was.active()) {
                return List.of(Finding.error(path, "advisory " + id + " is withdrawn; it changes no more."));
            }
            boolean withdraws = !advisory.active();
            boolean extends_ = advisory.releases().size() > was.releases().size()
                    && advisory.releases().subList(0, was.releases().size()).equals(was.releases());
            Advisory expected = withdraws ? was.withdraw(advisory.withdrawn())
                    : extends_ ? was.extend(advisory.releases()) : was;
            if (advisory.equals(was)) return List.of(Finding.error(path, "changes nothing the advisory holds."));
            if (withdraws && extends_ || !advisory.equals(expected)) {
                return List.of(Finding.error(path, "a change to an advisory either withdraws it, with a reason, or"
                        + " appends releases after the ones it names; nothing else of it is ever edited."));
            }
            added = advisory.releases().subList(was.releases().size(), advisory.releases().size());
        }
        for (String tag : added) {
            if (entry.get().release(tag).isEmpty()) {
                return List.of(Finding.error(path, entry.get().repo() + " lists no approved release " + tag + "."));
            }
        }
        return List.of();
    }

    /** Why a new advisory cannot be filed as it is, or {@code null}: its id in sequence, its year, not withdrawn. */
    private static String newRefusal(Advisory advisory, Path base) {
        if (!advisory.active()) return "a new advisory is never filed withdrawn.";
        int year = Integer.parseInt(advisory.id().substring(4, 8));
        if (LocalDate.parse(advisory.published()).getYear() != year) {
            return "an advisory's id carries the year it was published, " + advisory.published().substring(0, 4) + ".";
        }
        List<String> ids;
        try {
            ids = Advisory.readAll(base).stream().map(Advisory::id).toList();
        } catch (IOException e) {
            return "the catalog's advisories could not be read: " + e.getMessage();
        }
        String next = Advisory.next(year, ids);
        return next.equals(advisory.id()) ? null : "the next advisory of " + year + " is " + next + ", not "
                + advisory.id() + ".";
    }
}
