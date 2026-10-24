/**
 * Publishing a plugin or a bot from its folder and listing it in the catalog ({@link com.botmaker.cli.publish.Publish}),
 * and the catalog pull request a publish or an unpublish opens ({@link com.botmaker.cli.publish.CatalogPr}).
 *
 * <p>Like {@code validate} and {@code release}, a silent library: it prints nothing and knows no command line. It
 * reports through a {@link com.botmaker.cli.publish.Listener} and does every side effect through a
 * {@link com.botmaker.cli.publish.PublishHost} — the cli's runs {@code git} and {@code gh} through a
 * {@link com.botmaker.cli.publish.StepRunner}, Studio's is JGit and its sign-in — so the cli's {@code plugin publish}
 * and {@code bot publish}, the release library and Studio's Publish are one implementation (2026-10-24).
 */
package com.botmaker.cli.publish;
