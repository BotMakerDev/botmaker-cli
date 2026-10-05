# botmaker-cli — roadmap

Completed work up to 2026-10-05: see CHANGELOG.md, docs/refactor/, and `git show 2559f7d:ROADMAP.md`.

## Open

- **`plugin publish`'s real path is unexercised.** Its two `gh` fixes (`repo fork --remote=false` with a
  repository argument; the direct-branch arm for the registry's own maintainer) landed unrun. It reports a
  missing registry `index.json` by name — check that message first on the first real run.
- **`plugin run` launching Studio** through `--umbrella` and `--studio` has never run; it needs a machine with
  a Studio checkout.
- **`--json` output for `validate`.** A CI job wants the report as data. `CheckResult` is a record and Jackson
  is a dependency; the schema should be part of this module's semver.
- **`stamp_changelog` parity.** The script's bounded `sed -i` and the library's whole-file rewrite have never
  been compared byte for byte (`docs/release-port-divergences.md`).

## Deliberately not planned

- **A native binary.** This program shells out to Maven and `gh`; a native launcher buys a hundred
  milliseconds and costs a per-OS release matrix.
- **Bundling Maven.** `validate` promises the answer the author's own build gives.
- **Reading or writing `activities.json`.** The SDK (`com.botmaker.sdk.authoring`) is its one owner.
- **Anything that loads a plugin and reports on what it *does*.** Every check is about whether a plugin
  works; a behaviour check would read as a safety claim nothing here can keep.
