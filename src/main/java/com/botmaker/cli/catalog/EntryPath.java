package com.botmaker.cli.catalog;

import java.util.Optional;

/**
 * A path in the catalog that holds an entry, and what its name claims: a bot's {@code owner/repo} or a plugin's
 * id. Every other path (workflows, {@code tools/}, {@code publishers.json}, {@code catalog.json}) is none, which
 * is what lets the check and the merge refuse a pull request that touches one.
 *
 * <p>A bot's file is {@code bots/<owner>__<repo>.json}: a GitHub login holds no underscore, so the first
 * {@code __} is the separator however the repository is named.
 *
 * @param key the slug ({@code owner/repo}) for a bot, the id for a plugin
 */
public record EntryPath(Kind kind, String path, String key) {

    public enum Kind { BOT, PLUGIN }

    public static final String BOTS = "bots/";
    public static final String PLUGINS = "plugins/";
    private static final String JSON = ".json";

    /** The entry {@code path} names, or empty when it is not an entry's path. */
    public static Optional<EntryPath> parse(String path) {
        if (path == null || !path.endsWith(JSON)) return Optional.empty();
        if (path.startsWith(BOTS)) {
            String stem = path.substring(BOTS.length(), path.length() - JSON.length());
            int separator = stem.indexOf("__");
            if (separator < 0) return Optional.empty();
            String slug = stem.substring(0, separator) + "/" + stem.substring(separator + 2);
            return Entry.isSlug(slug) ? Optional.of(new EntryPath(Kind.BOT, path, slug)) : Optional.empty();
        }
        if (path.startsWith(PLUGINS)) {
            String id = path.substring(PLUGINS.length(), path.length() - JSON.length());
            return PluginEntry.ID.matcher(id).matches()
                    ? Optional.of(new EntryPath(Kind.PLUGIN, path, id)) : Optional.empty();
        }
        return Optional.empty();
    }

    /** The path {@code entry} lives at. */
    public static String of(Entry entry) {
        return switch (entry) {
            case BotEntry bot -> bot(bot.repo());
            case PluginEntry plugin -> PLUGINS + plugin.id() + JSON;
        };
    }

    /** The path the bot at {@code repo} ({@code owner/name}) lives at, before it has an entry. */
    public static String bot(String repo) {
        int slash = repo.indexOf('/');
        return BOTS + repo.substring(0, Math.max(slash, 0)) + "__" + repo.substring(slash + 1) + JSON;
    }

    /** Whether {@code entry}'s content is what this path's name claims. */
    public boolean matches(Entry entry) {
        return switch (entry) {
            case BotEntry bot -> kind == Kind.BOT && key.equals(bot.repo());
            case PluginEntry plugin -> kind == Kind.PLUGIN && key.equals(plugin.id());
        };
    }
}
