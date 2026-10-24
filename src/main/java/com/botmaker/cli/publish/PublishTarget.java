package com.botmaker.cli.publish;

import com.botmaker.cli.catalog.Entry;
import com.botmaker.cli.catalog.EntryPath;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What is published and where: the folder, its kind and its GitHub repository.
 *
 * <p><b>The repository is the folder's {@code origin}</b> (2026-10-24; the host's remote, {@link PublishHost#remoteUrl},
 * which is Studio's {@code mine}), so the author names nothing; {@code --repo}
 * is an override, and one that disagrees with {@code origin} is refused rather than chosen between. A bot with no
 * {@code origin} has its repository created, named after the folder under the signed-in account; a plugin with
 * none is refused, since JitPack builds it from GitHub and there is nothing there yet.
 *
 * @param create whether the repository is created by this publish (a bot with no {@code origin})
 * @param fresh  whether the folder is not a git repository yet (a bot's first publish; initialised then)
 */
public record PublishTarget(Path dir, EntryPath.Kind kind, String repo, boolean create, boolean fresh) {

    private static final Pattern GITHUB = Pattern.compile(
            "(?:https?://(?:[^@/]+@)?github\\.com/|git@github\\.com:|ssh://git@github\\.com/)([^/]+/[^/]+?)(?:\\.git)?/*");

    /**
     * Reads the folder's repository.
     *
     * @param override {@code --repo}
     * @param login    the signed-in account, which owns a repository this publish creates
     */
    public static PublishTarget of(Path dir, EntryPath.Kind kind, Optional<String> override, String login,
                                   PublishHost host) throws Refusal {
        Optional<String> named = override.map(String::strip).filter(s -> !s.isEmpty());
        if (named.isPresent() && !Entry.isSlug(named.get())) {
            throw new Refusal("--repo wants owner/name on GitHub; " + named.get() + " is not.");
        }
        boolean fresh = !host.isRepository();
        if (fresh && kind == EntryPath.Kind.PLUGIN) {
            throw new Refusal(dir + " is not a git repository. Push the plugin to GitHub first: JitPack builds it"
                    + " from there.");
        }
        Optional<String> origin = fresh ? Optional.empty() : host.remoteUrl();
        if (origin.isPresent()) {
            String repo = slugOf(origin.get()).orElseThrow(() -> new Refusal("origin is " + origin.get()
                    + ", not a GitHub repository; the catalog lists repositories on GitHub."));
            if (named.isPresent() && !named.get().equalsIgnoreCase(repo)) {
                throw new Refusal("this folder's origin is " + repo + ", and --repo names " + named.get()
                        + ". Drop --repo, or point origin at " + named.get() + " if that is where it lives.");
            }
            return new PublishTarget(dir, kind, repo, false, false);
        }
        if (kind == EntryPath.Kind.PLUGIN) {
            throw new Refusal("this folder has no origin. Push the plugin to GitHub first (`gh repo create --source ."
                    + " --push`): JitPack builds it from there.");
        }
        String repo = named.orElse(login + "/" + repositoryName(dir));
        if (!Entry.isSlug(repo)) {
            throw new Refusal(repo + " is no GitHub repository name; pass --repo owner/name.");
        }
        return new PublishTarget(dir, kind, repo, true, fresh);
    }

    /** {@code owner/name} of a GitHub remote URL (https, scp-like or ssh), or empty for any other remote. */
    public static Optional<String> slugOf(String url) {
        Matcher matcher = GITHUB.matcher(url.strip());
        return matcher.matches() && Entry.isSlug(matcher.group(1)) ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** The folder's name as GitHub would take it: what it refuses becomes {@code -}. */
    static String repositoryName(Path dir) {
        Path name = dir.toAbsolutePath().normalize().getFileName();
        String cleaned = name == null ? "" : name.toString().replaceAll("[^A-Za-z0-9._-]+", "-");
        return cleaned.isEmpty() || cleaned.chars().allMatch(c -> c == '.') ? "bot" : cleaned;
    }
}
