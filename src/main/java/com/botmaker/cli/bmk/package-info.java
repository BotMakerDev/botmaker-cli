/**
 * The {@code .bmk} bot file (spec {@code docs/superpowers/specs/2026-10-24-run-mode-bmk-design.md} § 2): one
 * reproducible zip of a bot at a commit, written and read by {@link com.botmaker.cli.bmk.BotFile} for every host —
 * {@code botmaker bot export}, Studio's export and open, Publish, and the catalog's check that rebuilds it.
 *
 * <p>Like {@code publish}, a silent library: it prints nothing, spawns no UI and runs no program. The host resolves
 * the classpath and lists the tracked files; this package only packs and unpacks.
 */
package com.botmaker.cli.bmk;
