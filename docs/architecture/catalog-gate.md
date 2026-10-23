# The catalog's check, merge and index are in this module (2026-10-23)

`botmaker-catalog` holds one file per listed bot (`bots/<owner>__<repo>.json`, `{repo, tag}`, optionally
`detached`) and per listed plugin (`plugins/<id>.json`, `{id, repo, tag}`), plus `publishers.json` (the ranks) and
the generated `catalog.json`. Its three workflows call `com.botmaker.cli.catalog`, resolved from JitPack at this
module's newest GitHub Release (`tools/resolve-cli.sh`; no version is stored there since 2026-10-23, so a release
reaches open pull requests at their next run). The spec is the umbrella's `docs/superpowers/specs/2026-10-23-catalog-design.md`;
`registry-gate.md` is the gate this replaced.

The check lives here for the reason the validator is a library: **it must be the code the author already ran.**
`botmaker plugin publish` and `botmaker bot publish` run rules 2–5 before opening a pull request (`CatalogPr`,
`EntryGate.checkEntry`), and Studio's Publish runs 3 and 5 (`CatalogListing.refusal`; 1 and 2 hold by how it
writes the entry), so a refusal is met on the author's own machine first.

## validate.yml — `EntryGate`

Runs on `pull_request` with a read-only token and no secret. Rules 1–5 are shared:

1. Exactly one entry path changes, and it is no symbolic link.
2. The file's name is what its content claims, the JSON is the schema, and no other entry's path differs from it
   only by case.
3. The pull request's author made the GitHub Release at `tag` (which needs push access). A release made by
   `github-actions[bot]` falls back to "the author owns the repository".
4. An existing entry changes or goes only by whoever controls the **base** copy's repository and tag.
5. `tag` is a published release, not a draft or a prerelease.

The kind's rules follow, on a clone at the tag: `BotChecks` (6: the pom declares a catalog plugin at a released
version; 7: `mvn compile` passes; 8: a class holds `main`) and `PluginChecks` (6: `PluginValidator` passes and
the jar's id is the entry's; 7: JitPack serves the coordinate). `--part rules|kind` splits them so the step that
runs an entry's code holds no token at all (`Mvn.runUntrusted` clears its environment too).

**An admin changes anyone's entry.** When `--author-id` is an admin in the base `publishers.json`, rules 3 and 4
pass; 1, 2, 5 and the kind's rules still run, so an admin lists only a published release that builds. The
dashboard's edits rely on this. A login is never trusted: logins can be renamed and then taken.

**`PublishersCheck`** is the check on a pull request changing `publishers.json` alone: its author is an admin in
the base copy by account id, the new file is the schema, every id is listed once, and at least one admin is left.

## automerge.yml — `ListingPolicy`

Holds the write token, so it never checks a pull request out: the workflow reads the facts through the API
(`tools/entry-facts.sh`) and `ListingPolicy` decides, as a pure function, `MERGE`, `HOLD` or `WAIT`. It holds a
pull request whose check has not passed or that is not exactly one entry path. It holds one checked against
other ranks than `main`'s, compared at the merge base, unless its author is an admin on `main` now. It makes a
fourth new entry by one author in 24 hours wait. A hold is said in one sticky comment.

## index.yml — `IndexCommand`

After each merge, on a push to `publishers.json`, and nightly: `catalog.json` from GitHub's facts and each pom at
its tag read as data (`PomFacts`), then `CatalogBuilder`, which checks every derived value again. **It runs no
entry's code.** A failed request stops the run and writes nothing, rather than unlisting what it could not see.
