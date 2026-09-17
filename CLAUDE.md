# CLAUDE.md

Guidance for working in **botmaker-cli**, the `botmaker` command.

Read the umbrella `../CLAUDE.md` first, then `../botmaker-plugin-host/CLAUDE.md` (this module's loader) and
`../botmaker-plugin-archetype/CLAUDE.md` (what `botmaker plugin new` generates). The plan this module comes from is
phase 7 of the plugin-ecosystem plan.

## The one structural fact: two artifacts

| artifact | what it is | who consumes it |
|---|---|---|
| `botmaker-cli-<v>.jar` | a **library** — `com.botmaker.cli.validate` and its dependencies | the plugin registry's CI |
| `botmaker-cli-<v>-all.jar` | the **executable** jar (shade, `Main-Class`) | JBang, `java -jar` |

`validate` has two callers in two repositories and they must reach the same verdict, because **a pull
request that fails for a reason its author could not have seen coming is the experience the gate exists to
prevent**. So `com.botmaker.cli.validate` prints nothing, spawns no process, reaches no network and knows no
command line: it is handed a `PluginSubject` of resolved facts. Everything that resolves one — Maven, `gh`,
the filesystem — is in `com.botmaker.cli`.

**If you find yourself adding a rule to `ValidateCommand`, you are adding a rule the registry will not
enforce.** Put it in `PluginValidator`, as a `Check`.

The shade plugin therefore uses `shadedArtifactAttached` rather than replacing the main artifact, and
`createDependencyReducedPom=false` because this module also flattens, and two plugins rewriting one pom is a
race with no winner.

**picocli is declared `optional`, and that word is doing structural work.** `optional` means *not
transitive*: the shaded `all` jar carries picocli (an optional dependency is on this project's own runtime
classpath, which is what shade packages) and a consumer resolving the main artifact does not — checked with
`dependency:tree` from a throwaway consumer, which lists plugin-host, studio-api and jackson and no picocli.
So the rule above stops being a discipline and becomes a fact about the graph: the registry's CI cannot
accidentally depend on a parser, and `com.botmaker.cli.validate` cannot name one.

It replaced a hand-rolled `Args`, a usage text written as a Java text block and a table naming every option
each verb accepts — **three statements of one fact, which had already disagreed**: the first real
`botmaker plugin new` passed `--botmaker-version`, which was silently ignored and generated a project pinned to
something else. Add an option by adding a field; there is nowhere else to say it.

## `com.botmaker.cli.release` — `release.sh`, being ported into the library artifact

**A second package with the same shape as `validate`, for the same reason.** The ordered cross-module
release has three callers — a maintainer's terminal, `.github/workflows/release.yml` (`--ci`), and
`botmaker-dashboard` — and CI cannot run a JavaFX app, so the owner of these decisions cannot be the GUI.
It prints nothing, spawns no UI and knows no command line; everything that formats a line for a human
belongs to the command that calls it.

**Only five things are algorithms** — the decide pass, the bump arithmetic, `dep_tag`, the forcing rules and
the tag order. The rest of `release.sh`'s 2016 lines shell to `git`, `gh`, `mvn` and `curl`, which Java does
at two to three times the line count and no gain. Port the five; keep the rest as processes (`Git`).

**No slice ships on being written — it ships on agreeing.** A wrong tag is permanent and no exit code
recalls one, so each slice is verified by diffing both implementations' `--dry-run` over a matrix of flag
combinations. That is why refusals carry the script's wording character for character (`ReleaseRefusal`):
the diff is over stdout, so a reworded message fails the slice even when it refuses the same input for the
same reason.

**This package keeps the module list that `botmaker-dashboard` refuses to keep, and both are right**: the
dashboard is a reader, so a copy there goes stale against the script; this is the owner being ported, so the
list has to land somewhere. `Module`'s declaration order is the script's **flag** order and is deliberately
not the **tag** order — see `Order.TAG`, where the two APKs (pilot, remote) go first, Studio after the whole
chain (since 2026-09-16: Studio's package jobs check out the tags its `.deps.env` pins, so those must
already be pushed) and the dashboard last of all (since 2026-09-17, the same shape over the cli). What a
module is exempt from is asked of `Module` itself (`mavenBuild`, `onJitpack`, `hasChangelog`), never by
naming it: the pilot and `botmaker-remote` are APKs; Studio, the dashboard and `botmaker-remote-server` are
programs JitPack never builds. **`--cli` forces `--dashboard`**: the dashboard's Release tab calls this
package in-process, so an installed dashboard decides by the cli it was built with, and a cli release
without a dashboard release leaves its previews on the previous rules. Its `.deps.env` pins shared, the
cli and the cli's own two pins (`CiDepsGate.repositoryModule` maps the `cli` key).

**A tag exists to publish an artifact, so "changed" is not "some byte moved" — and that is `ChangeKind`.**
It answers three things, not two: `REAL`, `DOCS` (commits exist, all of them markdown, so the tag would
publish a byte-identical jar) and `NONE`. The middle one is the whole point — two modules nearly went out on
2026-08-24 whose entire diff since their tags was the `CHANGELOG.md` the changelog gate itself had asked
for — and it is a *sentence*, not a silent skip, because "no changes" would be a lie about a module that
visibly has commits in it. `Relevance` is a **deny-list** and must stay one: an unclassified file counts as
a change and gets released, which is the harmless direction to be wrong in.

**Every failure to read a checkout answers `REAL`.** An unresolvable tag ref, a git that would not run —
none of that is evidence that nothing changed, and the direction to guess in is the one that publishes a
duplicate rather than the one that omits the change the release was cut for.

**The forcing rules are data with a reason per edge, and that is the one deliberate improvement on the
script.** `release.sh` spells them as an expression per module with the *why* in a comment above it. Every
one of those reasons records a bug that shipped — JitPack's per-tag build cache, a published pom baked by
flatten, a gate compiled against a different loader than Studio's — and a comment cannot be printed to the
operator asking why a module they never named is in the plan. So `Forcing` is a list of
`(upstream, downstream, reason)` and `forcedBy` returns every reason rather than one arbitrary winner. **The
edge set itself is transcribed exactly**; adding or dropping one is a release that differs from the script's.

**Three orders exist and none is a preference.** `Module`'s declaration order is the order `--help` lists the
flags; `Order.DECIDE` is dependency order, which it must be because each forced flag reads the versions
decided *so far*; `Order.TAG` puts the two longest CI jobs first (pilot, then studio) so they run while the
JitPack chain is still going. Collapsing any two would look like tidying and cost a release.

**A gate has four outcomes, not two, and the third is the one a port gets wrong.** `GateVerdict` is
`OK | SKIPPED | FORCED | REFUSED`. **`SKIPPED` is "could not be checked" and must not stop a release** — no
`mvn` on `PATH`, no `python3`, a module with no `ci.yml` — or every machine missing a tool becomes a machine
that cannot release. `FORCED` is still printed and still distinguishable from a pass, because a maintainer
reading the log later needs to know a gate was overruled rather than satisfied. And **`--force` overrides a
gate that failed, never one that could not run**: an unmapped `${botmaker.X.version}` and a missing
`tools/changelog-section.sh` are refused with `--force` in effect, because in both the gate does not know
what it is looking at.

**`ChangelogGate` invokes the module's own extractor and never reads `CHANGELOG.md` itself.**
`<mod>/tools/changelog-section.sh` has two readers in two repositories — this gate, and the module's `ci.yml`
feeding JReleaser the release body — and a release whose notes are extracted by a different rule than the one
that gated it can pass the gate and publish something else. Porting it into Java would create exactly the
second implementation that file exists to prevent. Only this caller passes `--allow-unreleased`.

**One gate's implementation moved rather than being invoked, and the test for that is not portability.**
`check_jitpack_plugins` was an inline `python3` heredoc with no other reader, so porting it (`MavenPrerequisite`)
creates no second copy of anything and stops a release depending on `python3` being installed.
`check_changelog` reads a markdown file and is *more* portable, and must not be ported, because its extractor
has a second reader in another repository. **The question is never "could this be Java" — it is how many
implementations the answer is allowed to have.**

**Every side effect goes through `Runner`, and a dry run is the same code path.** It decides, gates and
computes exactly as a real run does, and echoes `    $ <command>` instead of executing — which is what makes
`--dry-run` worth trusting rather than a second "preview" implementation that can drift. **A direct
`Files.writeString` or `Git.run` on a write path is a line that ignores `--dry-run`**, and it would be
discovered as a tag that exists.

Landed: slice 1 (`Module`, `Version`, `Level`, `Tags` = `latest_version`, `VersionSpec` = `resolve_version`,
`Git`), slice 2 (`Relevance` = `is_release_irrelevant`, `ChangeKind`, `ReleaseDecision` = `should_release`),
slice 3 (`Forcing`, `Order`, `DepTag`), slice 4 (`GateVerdict`, `GatePlan`, `CiDepsGate`, `ChangelogGate`,
`SdkGates`, `JitpackPluginsGate`, `MavenPrerequisite`, `Proc`), slice 5 (`Runner`, `DepsEnv`, `Stamp`,
`CommitTagPush`) and slice 6 (`CleanRoom`, `Actions`, `ReleaseLog`, `ReleaseStatus`).

`Plan` is the decide pass and `ReleaseCommand` is `botmaker release`, the third noun. **The command cannot
cut a release**: it builds a preview `Runner` and has no flag that changes it, because the port is verified
by diffing its output against `./release.sh --dry-run`'s and until those diffs are empty the script stays the
only thing that pushes a tag. `--why` — the per-edge forcing reasons — is opt-in for the same reason:
anything printed that the script does not print fails the diff.

**The cutover matrix passes as of 2026-09-06**: `--all`, `--all minor`, `--sdk 1.2.0 --studio`, `--cli` and
`--all --force` all give a plan block and a decide block identical to the script's.

`Release` is the whole run and there is **one path through it**: `--dry-run` is a `Runner`, not a branch, so
a preview walks the writes, the log, the pointer commit and the pushes and echoes each command. `Umbrella`
carries the pointer commit (with `releases/` in the same commit — what was released and whether it landed are
one fact) and `push_branch`, umbrella last because its commit names submodule commits.

**`--execute` is off by default, inverting the script**, where a real release is the default and `--dry-run`
opts out. The port is what is on trial: until one real single-module release has been cut through it and
watched, the safe default is the one that cannot burn a tag.

**What is left is not code**: one real single-module release through `botmaker release --execute`, watched to
a green `--status`, and then `release.sh` is deleted.

**Nothing here has pushed anything, and that is now a fact about the callers.** `CommitTagPush` can push; it
is reachable only through a `Runner`, and nothing has handed it a real one. The first tag this library cuts
is a single-module release, watched end to end — that is the plan's discipline and it has not been spent.

## Packaging — nfpm, and two things that are refused

`packaging/nfpm.yaml` builds `botmaker.rpm` and `botmaker.deb` from one description in the `release` job;
`.github/scripts/build-repo.sh` republishes them as dnf/apt repositories on GitHub Pages, so
`sudo dnf install botmaker` works and later releases arrive with the system's updates. Contents:
`/usr/share/botmaker/botmaker-cli-all.jar`, `/usr/bin/botmaker` (`packaging/botmaker`, a three-line `exec`),
and the docs. `/usr/bin` on `PATH` is the whole gain over `java -jar` — the jar's directory stops mattering.

**Not jpackage, which is what `botmaker-studio` uses**, and the difference is not a preference: jpackage
bundles a *runtime* into a ~240 MB app-image and generates the launcher, desktop entry, icon and MIME
registration around it, and the same tool has to produce Studio's `.msi` and `.dmg` — one packaging model
for three platforms. This is a 1 MB noarch jar with no desktop presence, so jpackage would generate
nothing worth having and would still force one runner per target OS.

**And not GraalVM `native-image`, structurally rather than by taste**: this program's job is loading
*arbitrary user classes* through a runtime `URLClassLoader` (`PluginLoader.open` over a classpath resolved
at run time). A closed-world image cannot, and no reflection configuration fixes it, because the classes do
not exist at image-build time.

**The packages carry no signature of their own**, and that is deliberate: the Pages repositories sign their
*indexes* (`repomd.xml.asc`, `InRelease`), an index carries the checksum of every package it lists, so the
signature already covers the payload. A package-level signature would verify the same bytes twice and would
need the private key on the build runner as a file.

## Two nouns, and the duplication under `bot` is deliberate

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

## What `publish` puts in an entry, and why none of it is the pom's `<version>`

An entry is a set of pointers, and every one of them is followed before the pull request is opened. That is
the same argument as the validator's: **a submission that fails in the registry's CI for a reason its author
could not have seen coming is what this command exists to prevent**, and a pointer nobody has followed is
exactly such a reason.

`verifiedVersion` is the **newest git tag** (`project/Tags.newest`), not `Poms.coordinate(pom).version()`,
which is what stood there until 2026-09-04. JitPack builds a tag on demand and serves the artifact under
that tag whatever the pom says — this project's own poms carry the cosmetic `0.0.0-SNAPSHOT` for precisely
that reason — so a pom version resolves only where it happens to equal the tag. Worse in the ordinary case:
`botmaker plugin new` generates `0.1.0-SNAPSHOT`, JitPack resolves no snapshot, and so *the first thing a new author
does* produced an entry the gate could not download. A snapshot is now refused here, by name, with the
sentence that says what to do; `--tag` overrides the lookup (`--version` is the help mixin's).

Two more pointers, same shape and same argument: the coordinate is resolved through `Subjects.fromCoordinate`
— the gate's own first step — and `--repo` through `gh repo view`. Both run on a real run only:
`--dry-run` is the by-hand path taken when `gh` is unavailable, so a check needing `gh` cannot be what stops
it. The snapshot refusal is *not* exempted there, because the dry run's stdout is a file the author submits.

## The gallery's gate, catalog and merge policy are here too (2026-09-16)

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

## The registry's gate is in this module too

`com.botmaker.cli.registry.RegistryGate` is what `botmaker-plugin-registry`'s CI runs on a pull request. It
lives here for the same reason the validator is a library: **it must be the code the author already ran.**
The registry's workflow resolves this module's *main* artifact and calls the gate; everything the gate adds
on top of `botmaker plugin validate` is what only the registry knows — the ids every other entry claims
(`Registry.claimedValueTypeIds`, which fills the `PluginSubject` parameter a local run always leaves empty),
the ids the **host's own bundled plugins** own (`Bundled`), and the rule that `index.json` is generated. A
check belongs in `PluginValidator`, never here.

**An entry is about one plugin, and the gate has to say which** (`PluginSubject.about`/`judges`, 2026-09-17).
A plugin may depend on a plugin — the SDK on `botmaker-plugin-basics`, an ordinary Maven dependency — so
resolving one coordinate loads two, and the ids the second registers belong to *its* entry. Before this the
gate refused the SDK for plugin-basics' nine value type ids and advised renaming them. The filename is the
id claim, so it is the answer. A local `botmaker plugin validate` names no entry and so judges every plugin
it finds, which is what it has always done.

**`Bundled` closes a hole the per-entry layout cannot close by itself.** `plugins/<id>.json` makes
entry-vs-entry uniqueness a property of git, but a plugin the host *ships* has no entry file — so
`com.botmaker.sdk` and the SDK's seventeen value type ids were claimed by nobody, and a submission taking one
passed every check and then lost silently in `ValueCatalog.merge`. The gate resolves the coordinates named by
`BOTMAKER_BUNDLED_PLUGINS` and asks the plugins themselves; a hand-kept list of ids here would be a second
answer to a question the SDK already answers, and would drift the first time a type was added. Two details
that are not decoration: the coordinates are resolved onto **one** classpath (`Subjects.fromCoordinates`),
because a bundled plugin's own dependency may be `optional` and so not transitive — the SDK's toolkit was,
until SDK v1.1.5, and resolving `botmaker-sdk` alone gave a classpath `SdkPlugin` could not be constructed
from; and a bundled id is **never** excluded by the submitting entry's own id, where a registry id is —
re-submitting your own plugin is an update, taking the host's is not.

**And the set is empty as of 2026-09-05, which is the truthful value rather than a disabled check.**
`botmaker-studio` has bundled no plugin since 2026-09-02: every plugin, the SDK included, is loaded off the
open project's own resolved classpath. So no id is reserved outside the index, and `com.botmaker.sdk` is
claimed the way every other id is — by `plugins/com.botmaker.sdk.json` existing, which git enforces. The
gate distinguishes the two states rather than treating them alike: **unset** warns (nobody said, and the
hole is open), **set and empty** is silent (the host bundles nothing, and says so).

It parses two positional arguments by reading an array, and that is not laziness: picocli is `optional`
precisely so a library consumer resolves no parser, and a gate that needed one would undo it. Changed paths
arrive as `@file` because a pull request chooses its own filenames — a path interpolated into a workflow's
`run:` line is a command injection.

`Subjects` and `Mvn` are public for this one caller, and only `fromCoordinate`/`fromCoordinates` are: a
working copy is the author's question, and the registry has none. `Console.reportAside` exists because `botmaker plugin publish`'s
stdout is the entry JSON — `--dry-run > plugins/<id>.json` has to be a file a parser will read, which is the
same stdout/stderr rule `Console` opens with.

## Why there is no JavaFX in it

`SlotEditor.create` returns `javafx.scene.Node`, so `javafx-controls` is on the **compile** classpath at
`provided` scope — and nothing here ever constructs a `Node`.

That is a real limitation, not a tidy one, and the `EDITORS` check states it: **an editor's predicate is
checked and its node is not.** Building a node needs a live JavaFX toolkit; carrying one would make this a
per-OS download (JavaFX's classes live in platform-classifier jars) and the single-jar promise is worth more.
Even *reaching* `slotEditors()` needs `javafx.scene.Node` on the classpath, because an editor written as a
lambda links its `(ValueContext)Node` method type when the list is built — so with no JavaFX the whole check
skips, saying so.

The half that is checked is the half that decides *which* editor a slot gets. The half that is not is seen
the first time anyone clicks the slot, which is what `botmaker plugin run` exists for.

## Why the stub contexts are written here

`botmaker-plugin-toolkit.testing.TestContexts` does the same job. It is not used, for the same reason
`botmaker-studio` may not depend on the toolkit: **the toolkit is a plugin's dependency, resolved onto the
plugin's own classloader so that two plugins may hold two versions of it.** A host that resolves one version
onto its own classpath takes that away. Forty lines of `StubContexts` is the price of the rule, and it is
cheap.

Every `StudioServices` method on the stub throws, deliberately: a predicate is asked *which slot is this*,
and it has the type and the call site to answer with. One that reaches for the theme is doing something a
headless host cannot support, and the validator reports that as the editor's failure rather than its own.

## Maven is shelled to, never embedded

Maven Resolver as a library would resolve against **its own** idea of the local repository, the mirrors and
the settings — not the user's. The promise of `validate` is that it answers what the registry will answer,
and both keep it by running the build tool the author already has configured. `mvnw` in the project wins over
`$MAVEN_HOME` over `mvn` on the PATH: a project carrying a wrapper has said which Maven it wants.

`dependency:build-classpath` is run at **runtime** scope, and that is load-bearing: the `provided` contract is
absent from a runtime classpath, which is exactly the set a host puts on the loader. A contract that appeared
there would be resolved child-first and become a second `Class` object — the failure `provided` exists to
prevent — and the validator would be testing something no host will ever run.

## What `plugin run` deliberately does not do

It does not create a bot project. Composing one means composing its pom, and **only the thing that knows the
whole plugin set can write the file that names them** — that is `MavenService` in Studio, and the reversal
that put it there (2026-08-26) is recorded in the umbrella `CLAUDE.md`. `run` points at a project that
already exists and adds one dependency, idempotently: it runs on every launch, and a pom rewritten every
time is a project Studio believes has changed every time.

`PROJECTS_ROOT` is duplicated from `studio/config/Constants` rather than imported, because importing it would
mean depending on an app with JavaFX, OpenCV and JNA behind it to learn one path.

Studio is launched through `--umbrella` (`javafx:run`), `--studio`/`$BOTMAKER_STUDIO`, or not at all. No
discovery: a packaged Studio has no canonical location on Linux, and guessing is worse than asking. The
project name reaches Studio as `--project=<name>`, a named JavaFX parameter added to `BotMakerStudio` in the
same phase as this module.

## Building

```bash
mvn test        # CommandLineTest, PomsTest, PluginValidatorTest, RegistryTest
mvn install     # the library and the -all jar
```

`PluginValidatorTest` **compiles its fixtures with javac and loads them through the real `PluginLoader`**,
against `System.getProperty("java.class.path")`. A mocked `StudioPlugin` would pass through the same code and
prove none of it, because it would never have been loaded. Keep it that way; every failure these checks
exist to catch is a failure of a real classloader over real bytecode.

Published through JitPack. Releases are cut from the umbrella with `../release.sh --cli <version>`.
