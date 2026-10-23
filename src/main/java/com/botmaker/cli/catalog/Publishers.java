package com.botmaker.cli.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code publishers.json}: the accounts the maintainer ranked, changed only by a pull request an admin opened
 * ({@link PublishersCheck}, 2026-10-23), which is merged like an entry. Everyone not listed is
 * {@link Rank#COMMUNITY}.
 *
 * <p>An account is its GitHub <b>id</b>, which survives a rename; the login is there for a person reading the
 * file and is never compared.
 */
public record Publishers(List<Publisher> admin, List<Publisher> trusted) {

    /** The file's name at the catalog's root. */
    public static final String FILE = "publishers.json";

    /** One ranked account, user or organisation. */
    public record Publisher(long id, String login) {}

    public Publishers {
        admin = admin == null ? List.of() : List.copyOf(admin);
        trusted = trusted == null ? List.of() : List.copyOf(trusted);
    }

    /** The rank of a repository whose owner has {@code ownerId}; admin wins over trusted. */
    public Rank rankOf(long ownerId) {
        if (admin.stream().anyMatch(p -> p.id() == ownerId)) return Rank.ADMIN;
        if (trusted.stream().anyMatch(p -> p.id() == ownerId)) return Rank.TRUSTED;
        return Rank.COMMUNITY;
    }

    /** Whether the account {@code id} is an admin; {@code 0}, an unknown account, never is. */
    public boolean isAdmin(long id) {
        return id > 0 && admin.stream().anyMatch(p -> p.id() == id);
    }

    /** Reads {@code file}; a missing file ranks nobody. */
    public static Publishers read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return new Publishers(List.of(), List.of());
        return CatalogJson.strict().readValue(Files.readString(file), Publishers.class);
    }
}
