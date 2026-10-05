# The gallery's gate, catalog and merge policy are here too (2026-09-16)

`com.botmaker.cli.gallery.{GalleryGate, CatalogBuilder, ListingPolicy}` are what `botmaker-gallery`'s three
workflows run, for the reason `RegistryGate` is here: `bot publish` runs `GalleryGate.checkEntry` before it
opens a pull request, so the check that refuses one is the check its author already ran.

- **Nobody reads a gallery pull request after the gate.** A passing one is merged by the gallery's
  `automerge.yml` and lands as `Tier.COMMUNITY`. So the maintainer's review became rules, and each rule
  stops somebody else's broken install or a hijacked listing.
- **Ownership of an existing listing is read from the base tree, never from the head.** `a-b-c.json` is both
  `a-b/c` and `a/b-c`, and the head copy says whatever the pull request wants. Without `--base` the gate
  refuses every entry for a non-maintainer rather than guessing.
- **`vetted/` is a path rule, not a field.** A vetted flag inside the author's entry would be a field every
  submission can set. `VettedRecord` pins a release, like the registry's `verifiedVersion`.
- **`index.json` is vetted-only.** Every Studio before this date reads it and cannot label a tier, so it is
  given the set somebody looked at. `catalog.json` is the file with every listing and its tier.
- **`ListingPolicy` is pure and is handed facts.** The workflow collects them with `gh api` and never checks
  out the pull request's code, which is what makes running with a write token safe. `GalleryGate.ownedBy` is
  the one statement of the ownership rule both use.
- **`schemaVersion` migrates in memory.** A file without it is version 1 and is never rewritten: rewriting
  every entry would be one pull request touching every author's file at once.
