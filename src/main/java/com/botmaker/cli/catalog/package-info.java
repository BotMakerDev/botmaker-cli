/**
 * {@code botmaker-catalog}: the one list of bots and plugins Studio reads (2026-10-23, replacing the plugin
 * registry and the topic-found bots).
 *
 * <p>An author writes one small entry file per bot or plugin ({@link com.botmaker.cli.catalog.BotEntry},
 * {@link com.botmaker.cli.catalog.PluginEntry}) by pull request; the catalog's workflows check it, merge it
 * with nobody reading it, and derive {@code catalog.json} ({@link com.botmaker.cli.catalog.Catalog}) from the
 * entries, GitHub's facts about each repository and {@code publishers.json}
 * ({@link com.botmaker.cli.catalog.Publishers}). Every rule is in this package, so {@code botmaker bot|plugin
 * publish} runs the same check on the author's machine. An entry keeps every release the catalog approved, each
 * pinned to its commit and, for a plugin, its jar's and pom's sha256 ({@link com.botmaker.cli.catalog.ApprovedRelease}).
 * Design: the umbrella's {@code docs/superpowers/specs/2026-10-23-catalog-design.md} and
 * {@code 2026-10-24-easy-publish-design.md}.
 */
package com.botmaker.cli.catalog;
