package com.botmaker.cli.catalog;

import com.botmaker.cli.bmk.Central;

import java.util.List;
import java.util.Optional;

/**
 * What GitHub and JitPack say about an entry's repository, read as data. An interface so the check and the
 * builder are tested without the network.
 */
public interface EntryFacts {

    /** A GitHub Release. {@code authorLogin} made it, which needs push access to the repository. */
    record Release(String authorLogin, boolean draft, boolean prerelease) {}

    /**
     * A repository as GitHub describes it now (after a rename or a transfer).
     *
     * @param name   its current name, which a rename changes while the entry still says the old one
     * @param parent the repository it was forked from, {@code owner/name}
     */
    record Repo(long ownerId, String ownerLogin, String name, String description, List<String> topics, int stars,
                Optional<String> parent) {

        /** What JitPack serves it as now: {@code com.github.<owner>:<name>}. */
        public String coordinate() {
            return "com.github." + ownerLogin + ":" + name;
        }

        public Repo {
            topics = topics == null ? List.of() : List.copyOf(topics);
            description = description == null ? "" : description;
        }
    }

    /** The release at {@code tag}, or empty when there is none. */
    Optional<Release> release(String repo, String tag);

    /** The repository, or empty when it is gone or private. */
    Optional<Repo> repo(String repo);

    /** Whether JitPack serves {@code coordinate} (no version) at {@code tag}. */
    boolean jitpackServes(String coordinate, String tag);

    /**
     * The sha256 (lower-case hex) of the file {@code name} attached to {@code repo}'s GitHub Release at {@code tag},
     * as GitHub serves it now; empty when there is no such release or file. A bot's {@code .bmk} (2026-10-25).
     */
    Optional<String> releaseAssetSha256(String repo, String tag, String name);

    /** The commit {@code tag} points at now (an annotated tag peeled), or empty when there is no such tag. */
    Optional<String> tagCommit(String repo, String tag);

    /** The two files of a plugin release a host downloads, and what {@link ApprovedRelease} pins. */
    enum Artifact {
        JAR("jar"), POM("pom");

        private final String extension;

        Artifact(String extension) {
            this.extension = extension;
        }

        public String extension() {
            return extension;
        }
    }

    /**
     * The sha256 (lower-case hex) of {@code coordinate}'s {@code artifact} at {@code tag} as JitPack serves it now,
     * or empty when it serves none.
     */
    Optional<String> jitpackSha256(String coordinate, String tag, Artifact artifact);

    /** Maven Central, which decides the jars a bot's {@code .bmk} lists rather than packs; one per check. */
    Central central();
}
