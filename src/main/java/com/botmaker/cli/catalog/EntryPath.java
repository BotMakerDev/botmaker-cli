package com.botmaker.cli.catalog;

import java.util.Optional;

/**
 * A path in the catalog that holds an entry, and the {@code owner/repo} its name claims. Every other path
 * (workflows, {@code tools/}, {@code publishers.json}, {@code catalog.json}) is none, which is what lets the check
 * and the merge refuse a pull request that touches one.
 *
 * <p>An entry's file is {@code bots/<owner>__<repo>.json} or {@code plugins/<owner>__<repo>.json} (a plugin's was
 * named by its id until 2026-10-27): a GitHub login holds no underscore, so the first {@code __} is the separator
 * however the repository is named.
 *
 * @param key the slug, {@code owner/repo}
 */
public record EntryPath(Kind kind, String path, String key) {

    public enum Kind { BOT, PLUGIN }

    public static final String BOTS = "bots/";
    public static final String PLUGINS = "plugins/";
    private static final String JSON = ".json";

    /** The entry {@code path} names, or empty when it is not an entry's path. */
    public static Optional<EntryPath> parse(String path) {
        if (path == null || !path.endsWith(JSON)) return Optional.empty();
        Kind kind = path.startsWith(BOTS) ? Kind.BOT : path.startsWith(PLUGINS) ? Kind.PLUGIN : null;
        if (kind == null) return Optional.empty();
        String stem = path.substring(directory(kind).length(), path.length() - JSON.length());
        int separator = stem.indexOf("__");
        if (separator < 0) return Optional.empty();
        String slug = stem.substring(0, separator) + "/" + stem.substring(separator + 2);
        return Entry.isSlug(slug) ? Optional.of(new EntryPath(kind, path, slug)) : Optional.empty();
    }

    /** The path {@code entry} lives at. */
    public static String of(Entry entry) {
        return switch (entry) {
            case BotEntry bot -> bot(bot.repo());
            case PluginEntry plugin -> plugin(plugin.repo());
        };
    }

    /** The path the bot at {@code repo} ({@code owner/name}) lives at, before it has an entry. */
    public static String bot(String repo) {
        return file(Kind.BOT, repo);
    }

    /** The path the plugin at {@code repo} ({@code owner/name}) lives at, before it has an entry. */
    public static String plugin(String repo) {
        return file(Kind.PLUGIN, repo);
    }

    /** Whether {@code entry}'s content is what this path's name claims. */
    public boolean matches(Entry entry) {
        return switch (entry) {
            case BotEntry bot -> kind == Kind.BOT && key.equals(bot.repo());
            case PluginEntry plugin -> kind == Kind.PLUGIN && key.equals(plugin.repo());
        };
    }

    private static String file(Kind kind, String repo) {
        int slash = repo.indexOf('/');
        return directory(kind) + repo.substring(0, Math.max(slash, 0)) + "__" + repo.substring(slash + 1) + JSON;
    }

    private static String directory(Kind kind) {
        return kind == Kind.BOT ? BOTS : PLUGINS;
    }
}
