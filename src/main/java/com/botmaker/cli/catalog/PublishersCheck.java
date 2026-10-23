package com.botmaker.cli.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The check on a pull request that changes {@code publishers.json} and nothing else (2026-10-23, the dashboard's
 * rank edits). It is merged like an entry, so this is the only thing standing between a pull request and the ranks:
 *
 * <ol>
 *   <li>Its author is an admin in the <b>base</b> copy, by account id: the pull request's own copy says whatever
 *       it likes, and a login can be renamed and then taken.</li>
 *   <li>The new file is the schema ({@code admin} and {@code trusted}, each {@code {id, login}}), a plain file.</li>
 *   <li>Every id is a GitHub account id, listed once across both ranks.</li>
 *   <li>At least one admin is left, so the file can still be changed.</li>
 * </ol>
 *
 * <p>No entry's code is involved, so the kind's step has nothing to do for it.
 */
final class PublishersCheck {

    private PublishersCheck() {}

    static List<Finding> check(Path head, Path base, long authorId) {
        String path = Publishers.FILE;
        Publishers current;
        try {
            current = Publishers.read(base.resolve(path));
        } catch (IOException | IllegalArgumentException e) {
            return List.of(Finding.error(path, "the catalog's current publishers.json does not read, so who is an"
                    + " admin cannot be told; an admin repairs it on main."));
        }
        if (!current.isAdmin(authorId)) {
            return List.of(Finding.error(path, "only an admin changes publishers.json: the pull request's author is"
                    + " not an admin, by GitHub account id, in the catalog's current copy."));
        }
        Path file = head.resolve(path);
        if (Files.isSymbolicLink(file)) {
            return List.of(Finding.error(path, "a symbolic link; publishers.json is a plain JSON file."));
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of(Finding.error(path, "publishers.json cannot be removed: it ranks every publisher."));
        }
        Publishers next;
        try {
            next = Publishers.read(file);
        } catch (IOException | IllegalArgumentException e) {
            return List.of(Finding.error(path, "not publishers.json's schema: {\"admin\": [...], \"trusted\": [...]},"
                    + " each {\"id\", \"login\"}, nothing else."));
        }
        Set<Long> seen = new HashSet<>();
        for (Publishers.Publisher publisher : Stream.concat(next.admin().stream(), next.trusted().stream()).toList()) {
            if (publisher.id() <= 0 || publisher.login() == null || publisher.login().isBlank()) {
                return List.of(Finding.error(path, "every publisher is a GitHub account id and its login; "
                        + publisher + " is not."));
            }
            if (!seen.add(publisher.id())) {
                return List.of(Finding.error(path, "account id " + publisher.id() + " is listed more than once."));
            }
        }
        if (next.admin().isEmpty()) {
            return List.of(Finding.error(path, "publishers.json keeps at least one admin, or nobody could change it"
                    + " again."));
        }
        return List.of();
    }
}
