# Two nouns, and the duplication under `bot` is deliberate

**`plugin` and `bot`, each with its own verbs (2026-09-05).** The four plugin verbs were the top level until
then — `botmaker new`, `validate`, `run`, `publish` — and meant *plugin* while saying so nowhere, because
the bot half already had to spell its noun. The most-typed verb in the program belonged to one of the two
things a person creates here and `--help` was the only place that fact appeared.

It was taken as a **break**, not as aliases: v0.x, an install base days old, and a permanent second spelling
of every verb is worse to carry than one rename. `MovedCommand` holds the four old paths as *hidden* aliases
of one command that **runs nothing** — it prints where the verb went and exits 2. The value over deleting
them is the difference between `Unmatched argument: 'validate'` and ``moved: use `botmaker plugin
validate` ``; delete the class at 1.0.0. Which alias was typed is read off the root's original arguments,
since picocli reports the primary name and three of the four users would otherwise be told the wrong verb.

`doctor` is the other new verb and belongs to neither noun. It reports Java, Maven (through
`Mvn.executable`, so it names the Maven the other verbs will actually run), `gh`, `gh auth`,
`$BOTMAKER_STUDIO` and the projects root. Nothing in it is a new capability — every verb already reports its
own missing tool, *at the moment it is needed*, which is halfway through the first real use. It reaches no
network, and only a missing **required** tool is exit 1.

`botmaker bot new` and `botmaker plugin new` remain different commands about different things that share an
English word, which is why both spell their noun.

`project/BlankProject` re-writes Studio's `MavenService.blankPomXml` + `StarterSources`, and
`gallery/Templates` re-writes its `TemplateProject`; `gallery/GalleryEntry` mirrors
`studio/sharing/GalleryEntry` the way `registry/RegistryEntry` mirrors the registry's. **`botmaker-studio` is
an application, not a library** — depending on it to share forty lines would put JavaFX, OpenCV and JNA
behind a command whose whole promise is a single jar. The precedent and the standing argument are
`validate/StubContexts`'s. What the copies must agree on is *files*, not code: the pom shape (which nothing
maintains after the first commit), and `bots/<owner>-<repo>.json`, which Studio reads.

**`bot publish` never writes `launchTargets`, but this copy of the entry carries it.** Studio reads its absence
as *the author never said*, and the honest declaration from a command that has run no launcher is silence.
The field was missing from the record entirely until 2026-09-16. Then `GalleryCatalog` started regenerating
the gallery's files from these records, and a copy that dropped the field would have deleted Studio's
declaration from every index it wrote.

**The blank names no plugin**, which is the platform rule reaching project creation: the SDK is one plugin
among any number, so a starting point naming it has chosen for the person starting. The repositories stay so
Manage Plugins can add one without hand-edited XML.

**`bot publish` follows the same rule as `publish`** — the release archive is downloaded before an entry
points at it. A gallery entry with no release behind it is a 404 on somebody else's machine, days later.
