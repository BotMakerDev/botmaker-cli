# The catalog's check, merge and index are in this module (2026-10-23)

`botmaker-catalog` holds one file per listed bot (`bots/<owner>__<repo>.json`) and per listed plugin
(`plugins/<id>.json`), plus `publishers.json` (the ranks) and the generated `catalog.json`. Its three workflows
call `com.botmaker.cli.catalog`, resolved from JitPack at this module's newest GitHub Release
(`tools/resolve-cli.sh`; no version is stored there since 2026-10-23, so a release reaches open pull requests at
their next run). The spec is the umbrella's `docs/superpowers/specs/2026-10-23-catalog-design.md`, changed by
`2026-10-24-easy-publish-design.md`; `registry-gate.md` is the gate this replaced.

The check lives here for the reason the validator is a library: **it must be the code the author already ran.**
`botmaker plugin publish` and `botmaker bot publish` run rules 2–5 before opening a pull request (`CatalogPr`,
`EntryGate.checkEntry`), and Studio's Publish runs 3 and 5 (`CatalogListing.refusal`; 1 and 2 hold by how it
writes the entry), so a refusal is met on the author's own machine first.

## Every approved release, pinned (2026-10-24)

An entry lists **every release the catalog approved**, oldest first (`releases`; the name `tags` is taken in
`catalog.json` by a repository's topics). A plugin's is `{id, repo, releases: [{tag, commit, jarSha256,
pomSha256}]}`, a bot's `{repo, releases: [{tag, commit}], detached?}` (`ApprovedRelease`). A tag pushed with errors
is never approved, so Studio offers only what passed.

Each release is **pinned**: the commit its tag pointed at when it was approved and, for a plugin, the sha256 of the
jar and the pom JitPack served then (the user, 2026-10-24: "does each plugin release have its own signature so that
the plugin dev can't modify the jar that is tagged"). A tag can be moved and JitPack rebuilds a tag whose build is
gone; the pins hold the bytes the check approved. The publish commands compute them
(`ApprovedRelease.pinPlugin`/`pinBot`), the check computes them again and refuses a difference, and Studio installs
a bot at its commit and refuses a plugin file whose hash differs. No key is involved: nothing is signed.

A pull request is one `EntryGate.Change` against the base copy: **create** (a new entry, one release),
**append** (one release after the others), **remove** (some releases, the rest in order; the whole entry goes by
deleting its file) or **detach** (a bot's `detached` alone). An approved release is never edited or reordered, and a
listed entry's `repo` never changes. There is no reading of schema 3's single `tag`.

## validate.yml — `EntryGate`

Runs on `pull_request` with a read-only token and no secret. Rules 1–5 are shared:

1. Exactly one entry path changes, and it is no symbolic link.
2. The file's name is what its content claims, the JSON is the schema, no other entry's path differs from it only
   by case, and the change is one of the four above.
3. The pull request's author made the GitHub Release the change approves (which needs push access). A release
   made by `github-actions[bot]` falls back to "the author owns the repository".
4. An existing entry changes or goes only by whoever controls the **base** copy's repository (its newest release).
5. The approved release is published, not a draft or a prerelease, and its tag points at the pinned commit.

The kind's rules follow, **on the approved release only** (a removal or a detach has none): `BotChecks` on a clone
fetched by the pinned commit (6: the pom declares a catalog plugin at a released version; 7: `mvn compile` passes;
8: a class holds `main`) and `PluginChecks` (6: `PluginValidator` passes and the jar's id is the entry's; 7: JitPack
serves the coordinate, and its jar and pom hash to the pins). `--part rules|kind` splits them so the step that runs
an entry's code holds no token at all (`Mvn.runUntrusted` clears its environment too); both need `--base`.

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
fourth new entry (an added file: a create) by one author in 24 hours wait; an append or a removal never waits. A
hold is said in one sticky comment.

## index.yml — `IndexCommand`

After each merge, on a push to `publishers.json`, and nightly: `catalog.json` (schema 4) from GitHub's facts and
each release's pom read as data at its pinned commit (`PomFacts`), then `CatalogBuilder`, which checks every derived
value again. Each entry lists its releases newest first with their pins and facts; a release whose GitHub Release is
gone (or became a draft) is left out, and an entry with none left too. **It runs no entry's code.** A failed
request stops the run and writes nothing, rather than unlisting what it could not see.
