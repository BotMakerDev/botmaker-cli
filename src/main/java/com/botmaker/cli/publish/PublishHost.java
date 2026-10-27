package com.botmaker.cli.publish;

import com.botmaker.cli.BotExport;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where a publish's side effects happen: the folder's git and GitHub, as the host signs in (2026-10-24). The rules and
 * the order of the steps are {@link Publish}'s; a host only answers and acts. The cli's is {@link #commands}, the
 * programs {@code git} and {@code gh}; Studio's is JGit and its own sign-in, pushing to the project's {@code mine}.
 *
 * <p>One host is one folder. The questions change nothing and answer empty or false when there is no answer; every
 * change refuses with a {@link Refusal} worded for the author, so {@code --dry-run} is a publish that stops before the
 * first change.
 */
public interface PublishHost {

    /** The signed-in GitHub account: its login, and the id the catalog's ranks name it by. */
    record Account(String login, long id) {}

    /** Who is signed in, or empty. */
    Optional<Account> account();

    /** Whether the folder is a git repository yet. */
    boolean isRepository();

    /** The URL of the remote a publish pushes to ({@code origin} for the cli), or empty when there is none. */
    Optional<String> remoteUrl();

    /** The commit {@code HEAD} is, or empty. */
    Optional<String> head();

    /** The tags on {@code HEAD}. */
    List<String> tagsAtHead();

    /** The nearest tag reachable from {@code HEAD}, or empty. */
    Optional<String> nearestTag();

    /** Every tag reachable from {@code HEAD}. */
    List<String> tagsMerged();

    /** The commit the folder's {@code tag} points at, or empty when it has no such tag. */
    Optional<String> tagCommit(String tag);

    /** Whether the working tree holds nothing uncommitted. */
    boolean clean();

    /** Whether {@code repo} exists on GitHub, as the signed-in account sees it (a private one too). */
    boolean repositoryExists(String repo);

    /** Whether {@code repo} has a GitHub Release, draft or not, at {@code tag}. */
    boolean releaseExists(String repo, String tag);

    /** A folder that is no repository made one, with a first commit of everything but the build. */
    void firstCommit() throws Refusal;

    /**
     * {@code files} (each text by its path in the folder) written and committed as {@code message}, only those paths;
     * in a folder that is no repository yet they are only written, and {@link #firstCommit} commits them. A plugin's
     * JitPack build ({@link JitpackFiles}); a host that publishes no plugin refuses.
     */
    default void commitFiles(String message, Map<String, String> files) throws Refusal {
        throw new Refusal("this host publishes no plugin, so it writes no JitPack build; run `botmaker plugin publish`.");
    }

    /** {@code repo} created, public, from the folder, which becomes its remote and is pushed. */
    void createRepository(String repo) throws Refusal;

    /** {@code HEAD} pushed to the remote. */
    void pushHead() throws Refusal;

    /** {@code tag} made on {@code HEAD} when the folder lacks it, and pushed. */
    void tag(String tag) throws Refusal;

    /** {@code repo}'s GitHub Release at {@code tag}, made by the signed-in account. */
    void release(String repo, String tag) throws Refusal;

    /**
     * A bot's {@code HEAD}, which {@code tag} of {@code repo} names, written as a {@code .bmk} in {@code outDir}: its
     * path, named {@code BotFiles.fileName}. The jars are the ones the bot resolves on this machine; the catalog's
     * check rebuilds the file from the commit and compares (2026-10-25).
     */
    Path bmk(String repo, String tag, Path outDir) throws Refusal;

    /** {@code file} attached to {@code repo}'s GitHub Release at {@code tag}, replacing one of the same name. */
    void uploadAsset(String repo, String tag, Path file) throws Refusal;

    /**
     * {@code change}'s pull request on the catalog, opened as {@code account} or updated in place.
     *
     * @return its URL, or empty when the catalog already reads so and there is nothing to open
     */
    Optional<String> pullRequest(CatalogPr.Change change, Account account, Listener listener) throws Refusal;

    /**
     * The programs themselves: {@code git} in {@code dir}, pushing to {@code origin}, and {@code gh} as the account it
     * is signed in to.
     */
    static PublishHost commands(Path dir, StepRunner runner, Listener listener, BotExport.Jars jars) {
        return new CommandHost(dir, runner, listener, jars);
    }
}
