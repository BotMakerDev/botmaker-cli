package com.botmaker.cli.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One release the catalog approved, <b>pinned</b> (2026-10-24, the user's call): the commit its tag pointed at when
 * the check passed it, and for a plugin the sha256 of the jar and the pom JitPack served then. A tag can be moved
 * and JitPack rebuilds a tag whose build is gone, so the tag alone does not hold the bytes the check approved; the
 * pins do. Studio installs a bot at {@code commit} and refuses a plugin file whose hash differs.
 *
 * @param commit    40 lower-case hex digits
 * @param jarSha256 64 lower-case hex digits; a plugin's only, absent for a bot
 * @param pomSha256 likewise; the pom names the dependencies Studio downloads with the jar
 */
@JsonPropertyOrder({"tag", "commit", "jarSha256", "pomSha256"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovedRelease(String tag, String commit, String jarSha256, String pomSha256) {

    static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public ApprovedRelease {
        Entry.required(tag, "tag");
        Entry.required(commit, "commit");
    }

    /** A bot's release: a commit, no hashes. */
    public static ApprovedRelease ofBot(String tag, String commit) {
        return new ApprovedRelease(tag, commit, null, null);
    }

    /** Whether it carries a plugin's two hashes; a bot's release carries neither. */
    boolean hashed() {
        return jarSha256 != null && pomSha256 != null;
    }

    /** The pins of {@code repo}'s bot release at {@code tag} as GitHub says now, or empty when the tag is gone. */
    public static Optional<ApprovedRelease> pinBot(EntryFacts facts, String repo, String tag) {
        return facts.tagCommit(repo, tag).map(commit -> ofBot(tag, commit));
    }

    /**
     * The pins of {@code repo}'s plugin release at {@code tag} as GitHub and JitPack say now, or empty when the tag,
     * the repository or a JitPack file is missing.
     */
    public static Optional<ApprovedRelease> pinPlugin(EntryFacts facts, String repo, String tag) {
        Optional<String> commit = facts.tagCommit(repo, tag);
        Optional<String> coordinate = facts.repo(repo).map(EntryFacts.Repo::coordinate);
        if (commit.isEmpty() || coordinate.isEmpty()) return Optional.empty();
        Optional<String> jar = facts.jitpackSha256(coordinate.get(), tag, EntryFacts.Artifact.JAR);
        Optional<String> pom = facts.jitpackSha256(coordinate.get(), tag, EntryFacts.Artifact.POM);
        if (jar.isEmpty() || pom.isEmpty()) return Optional.empty();
        return Optional.of(new ApprovedRelease(tag, commit.get(), jar.get(), pom.get()));
    }
}
