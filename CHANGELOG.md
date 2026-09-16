# Changelog

All notable changes to `botmaker-cli`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this module uses
[semantic versioning](https://semver.org/). `release.sh` refuses to cut a version with no section here.

## [Unreleased]

### Added

- **The gallery's gate, catalog and auto-merge policy** (`com.botmaker.cli.gallery`). The gallery lists bots
  automatically now: a pull request that passes `GalleryGate` is merged by the gallery's own CI, subject to
  `ListingPolicy` (at most 3 new listings per author per 24 hours; updating your own listing never counts).
  Such a bot is listed as **Community**. A bot is **Vetted** when a maintainer writes
  `vetted/<owner>-<repo>.json`, which pins one release.
- `GalleryGate` refuses:
  - edits to the generated files;
  - `vetted/` changes by anyone but a maintainer;
  - a filename that is not the entry's `owner-repo`;
  - a schema newer than this gate;
  - a change or delisting of someone else's listing, checked against the **base** copy;
  - a repository with no release, or whose release archive does not download.

  A new entry for a repository the author is not, and a `requires` naming an unregistered plugin, are
  warnings.
- `CatalogBuilder` writes `catalog.json` (every listing with its tier, `schemaVersion: 2`) and
  `index.json`. The legacy file holds **vetted listings only**, in the old shape, so a Studio that cannot show
  a tier never shows an unvetted bot.
- Gallery entries carry `schemaVersion` (2). A file without one is version 1 and is read unchanged; nothing
  is rewritten to migrate. `launchTargets` is now carried rather than dropped, and `requires` names the
  plugins a bot's pom declares, by registry id.

### Changed

- **`bot publish`** writes a version 2 entry with `requires`, runs the gallery's own entry checks before
  opening the pull request, and says which tier the bot lands in.

## [0.0.13] — 2026-09-16

### Added

- **A release refuses a module whose CI is red on `main`** (`CiGate`). The newest finished `ci.yml` run on
  `main` must not be a failure or cancelled. A run still going, no `gh`, or no run is reported and does not
  refuse. `--force` overrides. `botmaker-plugin-host v0.1.0` was tagged on 2026-09-16 while its `main` had
  been red for ten days, and its tag failed the same test.
- **The Actions error in a release log says what failed**, not only where: the first `[ERROR]` and
  `##[error]` lines of each failed run's log, with the command before a runner error.

### Changed

- **Studio is tagged last.** Its package jobs check out every upstream at the tag its `.deps.env` names,
  and those tags used to be pushed after Studio's. Studio v1.1.0 failed exactly that way.
- **The release log is written before the first tag and kept current**, with a new `stage` column. A module
  that throws is recorded as `FAILED` with the step and message, later modules as `not reached`, and the log
  is committed locally with the pointers of the modules that were tagged, as `release (stopped): …`. Logs
  written before this column still read, as whole releases.
- **A failed tag push stops the release.** It was returned and ignored, while every later module pinned the
  tag that was not on origin.
- `--status` leaves a row that was never tagged as it is, rather than polling for a tag that does not exist.

- **`release.sh` is a wrapper over `botmaker release`, so this package is the release rather than a port of
  it.** 2,214 lines of bash to ~230: the decide pass, the gates, the tag order, every `.deps.env`, both
  source edits, the release log and the JitPack verification are reached through the command. What stays in
  the script is the part that is not a decision — the spelling everyone's fingers know, `--ci`'s runner
  preparation (a git identity, the push credential for the other ten repositories, reattaching submodules
  `actions/checkout` left detached), the `BOTMAKER_RELEASE_TOKEN` precondition, and one line of translation:
  no `--dry-run` means `--execute`. The defaults are inverted between the two on purpose — a script whose
  whole purpose is cutting a release should not need a flag to do it, and a command anyone can type should
  not push a tag by accident.

  **The dry-run diff that verified this package is retired with the script, and nothing replaces it.** Both
  sides of that comparison are now the same code. It was run once more immediately before the cutover, as
  `--all --sdk 1.2.0` over the real eleven-repository tree, and agreed on every decided version, skip, gate
  verdict, pin, stamp, source edit and the tag order; `docs/release-port-divergences.md` records that and the
  three differences that survive as deliberate behaviour. What guards the code from here is the property it
  was built on: a preview and a release are one `Release.run` with a different `Runner`, and `Runner` is the
  only class in the package that writes.

### Fixed

- **The release port made two source edits it never made: the japicmp baseline and
  `SDK_FALLBACK_VERSION`.** Both are `sed`s that land inside a module's own release commit, and neither
  existed in `com.botmaker.cli.release` — so a release cut through the library would have produced tags that
  differ from the script's in two ways that matter. `Japicmp` moves `<botmaker.japicmp.baseline>` up to the
  module's newest existing tag, and only ever upwards: left where it was, the baseline stops naming the
  previous release and `ignoreMissingOldVersion` turns never-delete into a gate that reports success and
  checks nothing. `Fallback` moves `MavenService.SDK_FALLBACK_VERSION` during the Studio release whenever
  the same run cuts the SDK — without it every project a freshly released Studio creates pins the
  *previous* SDK, which is what Studio v1.0.34, v1.0.35 and v1.0.36 each shipped.

  It also takes ownership of the constant list `FallbackVersionsGate` used to declare beside it. Two lists
  is how `TOOLKIT_FALLBACK_VERSION` spent months being moved by a release and checked by nothing.

  **Found by re-running the dry-run comparison as `--all --sdk 1.2.0`.** The 2026-09-06 matrix was twelve
  single-module runs, which reach almost none of the per-module write path — and a stdout diff finds a line
  worded differently, never a write nobody makes. `docs/release-port-divergences.md` records it as D11 and
  D12, with the restated test.

- **The changelog stamp no longer rewrites the whole file.** It was `readAllLines` then `join("\n")`, which
  normalises line endings and the final newline of a file the maintainer has been editing all week, and the
  release commit then carries that reformatting as if it were the release's. It is an anchored first-match
  replacement now, like the script's `sed '0,/^## \[Unreleased\]/s//…/'`.

- **A dry run's echoed commands are shell-quoted, and its push pass inspects for real.**
  `commit -am release: studio v1.0.38` echoed as four bare words where it is one argument, and the push pass
  answered `would push <module> if it is ahead of origin` for all eleven rather than reading the ahead-count
  — which is not a side effect, and is the difference between *eleven repositories might be pushed* and
  *these four will*. A dry run's output is read as a script by whoever is checking it.

- **`Runner.replace` refuses a pattern that matches nothing**, where the script's `sed` is silent. That
  silence is how `MIN_SDK_VERSION` went on being `sed`ded for weeks after the constant was deleted: a
  release that believes it moved a pin it did not move is worse than one that stops.

### Changed

- **The umbrella's pointer commit is worded as `release.sh` words it.** `Module.pointerName()` transcribes
  the script's eleven hand-written labels — `toolkit` and `archetype` abbreviated, `plugin-host` and
  `plugin-basics` not — rather than deriving a tidier set, because that inconsistency is in every umbrella
  release commit this project has made and a `git log --grep` over the release history would otherwise split
  in two at the day the port took over.

### Documentation

- **`docs/release-port-divergences.md` — what the release port's dry run still says differently.** The
  package documentation claimed the cutover test passed; running it for the first time found fourteen of
  fourteen flag combinations differ. Every decision, gate verdict and refusal text agrees byte for byte —
  what differs is narration, gate order and echo form, plus one thing that reaches a permanent artifact:
  the umbrella's pointer commit is worded `release: archetype v0.0.5` by `release.sh` and `release:
  plugin-archetype v0.0.5` here. The cutover condition is restated with it, because one divergence is a
  behaviour worth keeping: this package reports every gate where the script stops at the first refusal, so
  an empty stdout diff is unreachable for a refusing run and was never the property worth having.

### Added

- **Two release gates that refuse a shape the forcing rules only ever documented.** `ForcingGate` refuses a
  run that drags in a module nobody named: every "an `--sdk` release forces Studio" in this project rested
  on the operator having typed both flags, because both implementations evaluate a module's `forced` flag
  only for modules already on the command line. `botmaker release --sdk 1.1.6` cut the SDK alone on
  2026-09-05, so the `sed` that moves `MavenService.SDK_FALLBACK_VERSION` never ran and Studio v1.0.37
  shipped pinning the previous SDK. It refuses rather than pulling the module in, because a tag nobody
  named cannot be un-pushed either; the refusal names the flag to add and quotes the edge's own reason.
  `FallbackVersionsGate` is its companion: on a `--studio` release, `SDK_FALLBACK_VERSION` must name a tag
  that exists — that is a pin Studio *writes* into a generated bot's pom, so nothing in any build resolves
  it and no test can see it go wrong. It checks existence, never which version is the right default, and
  exempts a module being cut in the same run.

- **An eighth check, `plugin-deps`: the toolkit must not be `optional`.** `optional` means *not transitive*,
  so the dependency is on the plugin's own classpath — every test passes, the jar builds, and
  `botmaker plugin validate` over a working copy passes — and absent from the classpath a host resolves the
  plugin onto. The host cannot supply one either, so the plugin fails to load and the symptom is an empty
  palette. This project has shipped that exact shape three times. It is separate from `pom-scopes` because
  it is a different word read by a different party: a scope says what this build sees, `optional` says what
  a consumer sees. Not a blanket refusal of every `optional` dependency — the SDK marks the pilot's server
  and QR encoder optional on purpose, so a headless bot links neither — because only toolkit types are
  nameable exclusively from plugin code.

- **`ArchetypeSkeletonTest` — a second plugin, built by the test that needs it.** Every architectural claim
  here rested on one plugin, and that plugin is compiled from the same reactor as the toolkit it uses, so it
  cannot be out of step with anything. The test substitutes the archetype's three placeholders itself,
  compiles the skeleton with `javac` against this JVM's classpath plus the toolkit's classes, loads it
  through `PluginLoader` and runs every `PluginValidator` check over a `PluginSubject.local`. No Maven, no
  network — a test skipped on the machine that would have caught the regression is not a test. **It caught
  one on the day it was written**: `Source.string` had grown a return type and the skeleton no longer
  compiled, so `botmaker plugin new` produced a project that failed on its first build. What it does not
  cover, stated rather than implied: the archetype's own descriptor, since nothing here runs velocity.

- **`editorDependencies` in a registry entry, composed by `plugin publish` and checked by the gate.** What a
  plugin needs on the *editor's* classpath and does not carry into a bot is exactly the set it declares
  `optional` — the one thing resolving the plugin cannot tell a host, since `optional` means *not
  transitive*. `plugin publish` reads that set off the plugin's own pom, so the author writes nothing:
  `org.openjfx` is left out (the loader is parent-first for `javafx.`, so the host's copy is what every
  plugin links), the contract and the toolkit are left out (a bot's pom must declare neither), and a version
  that is an unresolved property is skipped with a warning rather than published as text. `RegistryGate`
  refuses a hand-written entry that gets any of that wrong, because every line in the field becomes a
  `provided` dependency in a stranger's project pom. What it replaces was an `if (isSdk(…))` inside Studio
  over a list written in Studio's own source — the one privilege plugin #1 had, and the one the platform
  exists to refuse. `PluginValidator`'s contract and toolkit coordinates are public now, so the check, the
  publish and the gate spell them once.

- **`com.botmaker.cli.release`, the first slice of `release.sh`'s port.** A library, not a command: the
  release has three callers (a terminal, `release.yml` and `botmaker-dashboard`), CI cannot run a JavaFX
  app, so the owner of these decisions is a package that prints nothing and knows no command line — the
  same shape, and for the same reason, as `com.botmaker.cli.validate`. This slice carries the module
  inventory (`Module`, ten flags derived from the directory names), the `x.y.z` arithmetic (`Version`,
  `Level`) ordered as `sort -V` orders it rather than as text, `latest_version` (`Tags`) and
  `resolve_version` (`VersionSpec`, a typed pair rather than a string every reader re-parses). Refusals
  carry the script's own wording character for character, because each slice ships on its `--dry-run`
  agreeing with the script's, not on being written. **Nothing in it pushes anything**; `release.sh` keeps
  cutting every release until the whole port agrees.
- **Slice 2 — what counts as something to release.** `Relevance` (`is_release_irrelevant`), a deny-list in
  which markdown is never release-relevant at any depth and an unclassified file counts as a change;
  `ChangeKind`, answering `REAL`/`DOCS`/`NONE` rather than a boolean, so a module whose only commits are
  markdown is skipped *with the reason said* instead of reported as unchanged; and `ReleaseDecision`
  (`should_release`), which carries the script's `SKIP_REASON` in its answer instead of a global. Verified
  against the live checkout: the script's own `change_kind`, sourced out of `release.sh`, agrees with the
  Java for all ten modules.
- **Slice 3 — what drags what, and in which order.** `Forcing`, the forced flags as data with **a reason per
  edge** (the script keeps them as an expression with the why in a comment, and a comment cannot be shown to
  the operator asking why a module they did not name is being released); `Order`, holding the decide order
  and the tag order, which differ from each other and from the flag order; and `DepTag`, the ref a
  downstream pins — the version *this run* is cutting whenever there is one, since that tag does not exist
  yet when the `.deps.env` naming it is written. Verified against `release.sh` on the live checkout: the
  forcing sets extracted from its own `decide` lines, the tag order read off its `commit_tag_push` sequence,
  and `dep_tag` for all ten modules all match.
- **Slice 4, first half — the gates that read files.** `GateVerdict` gives a gate four outcomes rather than
  two: a gate that **could not run** (no `mvn`, no `python3`, no `ci.yml`) is `SKIPPED` and does not stop a
  release, and `--force` downgrades a failure to a `FORCED` line that still says it failed — but never
  overrides a gate that could not run. `CiDepsGate` is `check_ci_deps` ported whole (it reads a pom and a
  workflow and answers); `ChangelogGate` is `check_changelog`, which **invokes** each module's own
  `tools/changelog-section.sh` rather than re-reading the file, because that extractor has two readers in two
  repositories and a second implementation is what it exists to prevent. `GatePlan` holds the placement —
  everything in the decide pass, pilot exempt from all three, studio exempt from the JitPack one only.
  Verified against `release.sh` on the live checkout: `check_ci_deps` for all nine modules and
  `check_changelog` at an unreleased `9.9.9` both match line for line, including which three modules have an
  `## [Unreleased]` section and which six do not.

- **Slice 4, second half — the gates that invoke a tool.** `SdkGates` (`check_api_pointers`,
  `check_sdk_plugin`) and `JitpackPluginsGate` with `MavenPrerequisite`. The last of those is the one gate
  whose *implementation* moved instead of being invoked: it was an inline `python3` heredoc with no second
  reader, so porting it copies nothing and drops a dependency on `python3` being installed. It reads each
  pinned plugin's own pom — from `~/.m2` first, Maven Central otherwise — resolves a `${property}`
  prerequisite in that same pom, compares part by part against JitPack's Maven 3.6.1, and reports anything it
  could not read as an **unknown that warns rather than refuses**. Verified against `release.sh` on the live
  checkout: identical for all eight JitPack-built modules, per-module unknown counts included, and both SDK
  gates identical too.

- **Slice 5 — the writes.** `Runner` puts every side effect behind one switch: a dry run takes the same code
  path and echoes `    $ <command>` instead of running it, so the plan on screen is produced by the code that
  performs the release rather than by a second preview implementation. `DepsEnv` writes each module's
  `.deps.env` — **including the `git add`**, whose absence tagged three modules with no `.deps.env` at all on
  2026-09-02 and made every artifact of the new plugin platform unresolvable while the release reported
  success. `Stamp` renames `## [Unreleased]`, idempotently, which is what makes `--all` usable. `CommitTagPush`
  commits, tags and pushes, checking **both** the worktree and the index because a staged first-ever file is
  invisible to `git diff --quiet`. Verified against `release.sh`: `write_deps_env` is byte-identical for all
  six modules that write one, and `stamp_changelog` byte-identical too. **Nothing has pushed anything** — a
  push needs a real `Runner`, and no caller hands it one yet.

- **Slice 6 — the record, and re-reading it.** `CleanRoom` is `resolve_clean_room`: a real
  `dependency:resolve` from a throwaway repository, which is the only check that catches a published pom
  naming a dependency nobody can resolve — a `HEAD` on the `.pom` answers a different question and
  `dependency:tree` exits 0 on the failure. `Actions` is `poll_actions`, the worst verdict of every workflow
  a tag fired, with `skipped` counting as fine and *no run at all* a finding (a tag is finished; nothing more
  will fire). `ReleaseLog` renders `releases/<YYYY-MM-DD-HHMM>.md` whole every time, verdicts `pending`
  because it is written the moment the last tag is pushed rather than after the poll, with errors in full
  under the table rather than squeezed into a cell. `ReleaseStatus` is `--status`, re-polling both columns
  through those same two readers so they cannot disagree about what *ok* means. Verified by re-rendering
  **all eight committed `releases/*.md`** — every table byte-identical, including a seven-module release.

- **`botmaker release` — the third noun, and the port's first caller.** `Plan` is the decide pass:
  specs resolved off each module's own latest tag, the forcing rules applied in dependency order, a skipped
  module's version cleared so everything downstream sees the final answer, then the gates and the tag order.
  `ReleaseCommand` is a command line and nothing else. **It cannot cut a release** — it builds a preview
  `Runner` and has no flag that changes that — because the port is verified by diffing its output against
  `./release.sh --dry-run`'s, and until those diffs are empty the script stays the only thing that pushes a
  tag. `--why` is the one addition, and it is opt-in for exactly that reason: printing the per-edge forcing
  reasons by default would fail the diff on an improvement nobody objects to. **The cutover matrix passes**:
  `--all`, `--all minor`, `--sdk 1.2.0 --studio`, `--cli` and `--all --force` all produce a plan block and a
  decide block identical to the script's.

- **The port is feature-complete.** `Release` runs a whole release — decide, gate, tag in order, write the
  log, verify, record the pointers, push the branches — and there is **one path through it**: `--dry-run` is
  a `Runner`, not a branch, so a preview walks every step and echoes the commands. `Umbrella` adds the
  pointer commit (carrying `releases/` in the same commit) and `push_branch`, umbrella last because its
  commit names submodule commits the remote must already have. `Jitpack` adds the wait between tags, which
  was removed in 2026-08 and put back on 2026-09-05: pinning refs exactly removes the *requirement* that an
  upstream be published first, but *builds on demand* is not *queues and retries*, and a build result is
  cached per tag — so losing the race burns a tag that cannot be reused. **`--execute` is off by default**,
  the inverse of the script, because the port is what is on trial.

### Fixed

- **`release.sh` would have refused every SDK release since 2026-09-05.** `check_sdk_plugin` ran
  `java -jar … validate botmaker-sdk`, and the noun-first command tree made the bare verb a hidden
  `MovedCommand` that prints its replacement and exits 2 — so the gate failed with a message about a command
  line rather than about the SDK. It now runs `plugin validate`. Found by porting the gate.
- **`-Dbotmaker.api.maxVersion` is documented and not passed.** The umbrella's `CLAUDE.md` said
  `check_api_pointers` runs `ApiPointersTest` with it; the property went on 2026-08-27 with the `@Replaces`
  back edge it bounded, and nothing has read it since. The port passes what the script passes and the
  paragraph now says so.
- `PomsTest.a_circular_property_terminates` asserted which of `${a}`/`${b}` survives a two-name property
  cycle. `Map.of`'s iteration order is randomised per JVM, so that is a coin flip; it passed for months and
  failed the first time an unrelated class changed surefire's ordering. It now asserts what the caller is
  actually promised: the call returns, and the survivor is still visibly unresolved.

### Changed

- **The four plugin verbs moved under `botmaker plugin`, and this is a break.** `botmaker new`,
  `validate`, `run` and `publish` are now `botmaker plugin new|validate|run|publish`. They always meant
  *plugin* and said so nowhere — a bot already had to spell its noun (`botmaker bot new`), so the most-typed
  verb in the program belonged to one of the two things a person creates here and `--help` was the only
  place that fact was written down.

  Taken as a break rather than as aliases: this is v0.x with an install base days old, and a permanent
  second spelling of every verb is worse to carry than one rename. Every option, exit code and behaviour is
  unchanged; only the path to each command moved.

  The old paths are kept as **hidden aliases that run nothing** — each prints where its verb went and exits
  2, the command line's own "that was wrong":

  ```
  $ botmaker validate
  error: `botmaker validate` moved: use `botmaker plugin validate`. The four plugin verbs are under
  `botmaker plugin`; a bot's are under `botmaker bot`.
  ```

  That is the whole value over deleting them, and it is why they are aliases of one command rather than four
  files: picocli's own answer is `Unmatched argument: 'validate'`, which teaches nobody where the verb went.
  They come out at 1.0.0.

### Added

- **`botmaker doctor`** — Java, Maven, `gh`, `gh auth`, `$BOTMAKER_STUDIO` and the projects root, answered
  together. No new capability: every verb already reports the tool it is missing, *at the moment it is
  needed*, which is halfway through the first real use — `plugin publish` finds there is no `gh` after
  building, validating and resolving a coordinate. It reaches no network, changes nothing, and exits 1 only
  for a missing **required** tool; `gh` and Studio are warnings, since `--dry-run` and `--umbrella` are real
  answers. The Maven it names is the one the other verbs will run (`./mvnw` beats `$MAVEN_HOME` beats
  `PATH`), read through `Mvn.executable` rather than probed a second time.

## [0.0.12] — 2026-09-05

### Fixed

- **`publish` wrote a property name into the registry entry where a version belongs.** Found by publishing
  the SDK for real, which is what that exercise was for:

  ```json
  "minContractVersion" : "${botmaker.studioapi.version}"
  ```

  `Poms` reads a pom as XML on purpose — the scope checks ask what the pom *says*, and a toolkit dependency
  inheriting `provided` from a parent is still a broken plugin — so a version declared through a property
  came out as its own name. That is the **ordinary** shape of a plugin's pom in this project: every module
  pins its BotMaker upstreams through a property so `jitpack.yml` can inject the released tag at build time.
  A compatibility floor reading `${botmaker.studioapi.version}` is one nothing can be compared against.

  `Poms.properties` and `Poms.interpolate` are new and are used by `publish` alone; `Poms.dependencies`
  still answers what the pom says. And interpolation is not enough by itself here — this project's committed
  poms resolve that property to the cosmetic `0.0.0-SNAPSHOT` JitPack overrides — so **`publish` now refuses
  a contract version that is unresolved, blank or a snapshot**, naming `--min-contract-version` and, for a
  module of this project, the `STUDIO_API_TAG` in its own `.deps.env`. Same argument as the `-SNAPSHOT`
  refusal on `verifiedVersion`: the author knows the answer, the tool does not, and a wrong value in an
  entry is a wrong value a stranger reads.

## [0.0.11] — 2026-09-05

### Changed

- **The registry gate tells "nobody said" apart from "this host bundles nothing".** `BOTMAKER_BUNDLED_PLUGINS`
  **unset** still warns — the ids a host's own plugins own are then unreserved without anyone having decided
  that, which is the hole `Bundled` exists to close. `BOTMAKER_BUNDLED_PLUGINS` **set to the empty string**
  is now silent: it is a registry stating that its host bundles nothing, which has been literally true since
  `botmaker-studio` stopped shipping a plugin on 2026-09-02. Both resolve to `Bundled.none()`, so no verdict
  changes; what changes is that the registry can say it without printing a false warning on every pull
  request forever. A warning nobody can act on is how a real one stops being read.

### Fixed

- **Two comments that described the world before SDK v1.1.5.** `Bundled`'s javadoc and this module's
  `CLAUDE.md` both said the SDK declares `botmaker-plugin-toolkit` `optional` and that Studio answers that
  with a `runtime` dependency of its own. Neither is true: the SDK's toolkit is an ordinary `compile`
  dependency since v1.1.5, and Studio has had no toolkit at all since 2026-09-02. The reason for resolving
  every bundled coordinate onto **one** classpath is unchanged and still stated — any bundled plugin's
  `optional` dependency has the same shape — the SDK is now named as the historical example rather than the
  live one. No behaviour changed.

## [0.0.10] — 2026-09-05

### Fixed

- **`0.0.9`'s pins were right and it lost a race instead.** `maven-shade-plugin` 3.5.1 and
  `flatten-maven-plugin` 1.4.1 both ran on JitPack. The build then failed one step earlier than the
  packaging it was cut to repair:

  ```
  Could not find artifact com.github.LiQiyeDev:botmaker-plugin-host:jar:v0.0.5 in jitpack.io
  ```

  This module's tag is pushed seconds after `botmaker-plugin-host`'s, and JitPack builds a pinned
  dependency tag **on demand but does not queue** — so the cli build started while the plugin-host build
  was still running and could not resolve it. A build result is cached per tag, so it stays failed forever
  and only a new tag repairs it. `botmaker-plugin-host:v0.0.5` itself is fine and resolves clean.

  The umbrella's `release.sh` now **waits between links of the chain by default** again
  (`--no-wait-jitpack` opts out). Nothing in this module changed.

## [0.0.9] — 2026-09-05

### Fixed

- **`0.0.8` failed on JitPack too, and this module carried two of the offending pins.** JitPack's builder
  runs **Apache Maven 3.6.1**, and Maven refuses to execute a plugin whose `<prerequisites><maven>` exceeds
  its own version — before publishing anything. `flatten-maven-plugin` 1.6.0 and `maven-shade-plugin` 3.5.3
  both ask for 3.6.3. Pinned to **1.4.1** and **3.5.1**.

  Shade matters more here than anywhere else: it is bound unconditionally rather than behind a profile, so
  its failure takes down the **main** artifact — the library `com.botmaker.cli.validate` that the plugin
  registry's CI resolves to decide a pull request — and not merely the `all` classifier.

  Use `0.0.9`; `0.0.8` was never published.

## [0.0.8] — 2026-09-04

### Fixed

- **The pin `0.0.7` added was itself unbuildable on JitPack**, so the library is still not resolvable at
  that version. `maven-compiler-plugin` raised its own Maven prerequisite to 3.6.3 in 3.12.0 and JitPack's
  Maven is older, so 3.13.0 failed with `requires Maven version 3.6.3`. Pinned to **3.11.0**, which is what
  `botmaker-shared` has always used. The rpm, the deb and the release jar are unaffected as before.

## [0.0.7] — 2026-09-04

### Fixed

- **The Maven artifact is resolvable from JitPack again.** The `.rpm`, the `.deb` and the jar attached to a
  GitHub Release were never affected — they are built by this repository's own CI. What was broken is the
  **library**: `com.botmaker.cli.validate` is resolved as a dependency by the plugin registry's CI, and no
  release of it since 2026-09-02 could be resolved at all, because `botmaker-studio-api` and this pom did
  not pin `maven-compiler-plugin` and JitPack's Maven defaults it to 3.1, which predates
  `maven.compiler.release` and builds with `source 5`. So the gate that decides a pull request could not
  load the checks its author had already run — which is the one property the two-artifact split exists to
  guarantee. Pinned to 3.13.0 here and in every module upstream of it.

## [0.0.6] — 2026-09-04

### Added

- **Tab completion, and `botmaker completion` that prints it.** `botmaker <TAB>` offers the verbs,
  `botmaker bot new --<TAB>` the options, and an option's enum values complete too. The rpm and the deb
  install the script at `/usr/share/bash-completion/completions/botmaker`; for the jar,
  `source <(botmaker completion)`. One script serves bash and zsh, since zsh reads it through
  `bashcompinit`.
  **It is generated by the command it completes, never committed.** A checked-in completion file is a third
  statement of what the options are — beside the parser and the usage text — and it is the one that fails
  silently: it does not break, it just stops offering a verb somebody added. That is the argument this
  program already made for taking picocli at all, applied to the shell, and a test now asserts the script
  names every registered subcommand. No new dependency: `picocli.AutoComplete` is in the jar already.

### Fixed

- **`bot new --from` renames the project, not only its package.** The pom came out half-renamed: `groupId`
  changed and `artifactId` did not, so `bot new farm --from …` produced a project called `farm` that built
  `base-0.0.1-SNAPSHOT.jar` and collided in `~/.m2` with every other copy of the same template. The
  groupId had only changed by accident, because it happened to equal the declared package prefix and the
  text pass caught it. The rule is now stated rather than incidental: **class names, file names and javadoc
  keep the author's wording — the Maven coordinate is the project's identity and takes the new name.** A
  dependency that happens to share the old artifactId is left alone, and so is a `<parent>`.
- **`--version` prints the version.** It answered `botmaker (dev)` on an rpm calling itself
  `botmaker-0.0.5-1`, because the manifest carried the pom's cosmetic `0.0.0-SNAPSHOT` — the same JitPack
  property that cost `v0.0.1` its release asset, surfacing in a third place. The release job passes the tag
  as `-Dbotmaker.cli.version`, shade writes it to `Implementation-Version`, and a build nobody released
  still says `(dev)`, which is true of it.

## [0.0.5] — 2026-09-04

### Fixed

All three found by publishing the first template for real — `LiQiyeDev/botmaker-base`, gallery pull request
&#35;1 — which is what that exercise was for. None of them could have been caught by a test that stubs `gh`.

- **A blank pom targets the platform, not the JVM that wrote it.** `maven.compiler.release` was
  `Runtime.version().feature()`, copied from Studio's `MavenService` where it is right: Studio creates a
  project on the machine that will build it. A project created here *travels* — it is one `bot publish`
  away from being a template somebody else downloads — so the running JVM is the one thing that must not
  reach the file. The first template was composed on a Java 27 box and would have answered
  `release version 27 not supported` to every author on 25, for a project containing one `println`. It is
  25 now: the platform's own baseline, which a newer JDK compiles happily.
- **`publish` and `bot publish` fork only when a fork is needed.** Both opened their pull request by
  forking, and GitHub does not fork a repository into the account that already owns it — so the gallery's
  and the registry's own maintainer was refused by the step that submits. The branch now goes straight onto
  the target when the authenticated user can push there (`repos/<slug>` → `.permissions.push`), and through
  a fork otherwise. Same pull request either way; only the head branch moves.
- **`gh repo fork --remote=false` is not a flag.** With a repository argument `gh` refuses it outright —
  `the --remote flag is unsupported when a repository argument is provided` — so *every* pull request either
  command has ever tried to open failed at that line. Both call sites drop it.
- **`bot publish` initialises `main`, not `master`.** `git init` on a machine with no `init.defaultBranch`
  gives `master`, and the branch name is in every URL the published entry's readers follow.

## [0.0.4] — 2026-09-04

### Fixed

- **The rpm is signed, so `sudo dnf install botmaker` completes.** `v0.0.3` published a correctly-signed
  *index* over an unsigned *package*, and dnf refused the transaction at its last step —
  `La vérification OpenPGP du paquet "botmaker-0.0.3-1.noarch" … a échoué : Le paquet n'est pas signé`,
  after downloading 2.6 MB. The reasoning behind not signing was that `repomd.xml.asc` covers
  `primary.xml`, which carries the package's checksum, so the index signature is already a signature over
  the payload. That chain is real and **apt accepts it**; dnf does not, because `gpgcheck` and
  `repo_gpgcheck` are two independent switches and the published `botmaker.repo` sets both — the first is
  the package's own header signature and nothing was producing one. nfpm now signs the rpm header with the
  same key the repository indexes are signed with (`NFPM_RPM_KEY_FILE` written from the existing
  `GPG_PRIVATE_KEY` secret), and a signing failure is a red job rather than a package that installs
  everywhere except under this project's own repository. The deb stays unsigned deliberately: debsigs
  signatures are not what apt checks.

## [0.0.3] — 2026-09-04

### Added

- **`sudo dnf install botmaker` and `sudo apt-get install botmaker`.** The release was a bare jar you ran
  with `java -jar` from whatever directory it landed in. It is now also an `.rpm` and a `.deb` — one noarch
  jar at `/usr/share/botmaker/`, a launcher at `/usr/bin/botmaker`, requiring a Java 25 runtime and
  *recommending* Maven — built by nfpm from one description and republished as signed dnf/apt repositories
  on GitHub Pages. Both packages are attached to the release as well, because the repository carries the
  latest version only. The JBang alias and the `java -jar` download are unchanged.
- **`botmaker bot` — a second noun, and the half of the platform that had no command.** The four verbs are
  all about a plugin; a *bot*, and in particular a bot published as a starting **template**, had nothing.
  Since a blank project names no plugin (2026-09-04), the richer starting point has to be a published bot
  carrying the `template` tag — and making one meant a repository, a push, a release and a hand-written
  gallery entry, by hand, with no error until an install 404'd on somebody else's machine.
  - **`botmaker bot new <name>`** writes a blank project — a pom, one `main()`, and
    `botmaker-template.properties`. No SDK and no plugin of any kind, which is Studio's own blank shape.
    `--from <owner/repo>` starts from a published template instead: its release archive is downloaded and
    **only its package** is renamed, exactly as Studio's `TemplateProject` does it.
  - **`botmaker bot publish`** creates the repository and pushes, cuts the release, downloads that release
    archive to prove an install can fetch it, then forks the gallery, writes `bots/<owner>-<repo>.json` and
    opens the pull request. `--template` adds the reserved tag. `--dry-run` prints the entry and nothing
    else.
- **`publish --tag <tag>`** — the published version the registry's gate will resolve. Spelled `--tag`
  because `--version` is taken by the standard help mixin, and because what JitPack serves under is a tag.

### Fixed

- **`publish` no longer composes an entry the registry cannot resolve.** The entry's `verifiedVersion` was
  the working pom's `<version>`, which is wrong twice over: a freshly generated plugin is
  `0.1.0-SNAPSHOT` — `botmaker new`'s own default — and JitPack resolves no snapshot, so `botmaker new`
  followed by `botmaker publish` produced a pull request whose gate failed in the *registry's* CI, which is
  the one experience the two-caller design exists to prevent. Deeper: JitPack serves an artifact under the
  **git tag**, and a pom `<version>` matches it only by coincidence. The entry now names the newest tag on
  the working copy, `--tag <tag>` overrides it, and a `-SNAPSHOT` is refused by name with the sentence that
  says what to do.
- **`publish` follows its own pointers before opening a pull request.** The coordinate is resolved through
  `Subjects.fromCoordinate` — literally the gate's first step — and `--repo` is confirmed with
  `gh repo view`. A coordinate nobody can download and a repository nobody can visit are both refusals now,
  and both cost seconds where finding out in CI costs a round trip.

## [0.0.2] — 2026-09-02

### Changed

- **Compiled for Java 25 (LTS).** The executable `botmaker-cli-all.jar` needs a Java 25 runtime, and so does
  the plugin registry's CI, which resolves the main artifact and calls the validator.

### Fixed

- **The executable jar is attached to the release again.** `v0.0.1` published no asset: `jreleaser.yml`
  looked for `target/botmaker-cli-{{projectVersion}}-all.jar`, and shade names the jar after the *pom's*
  version — the cosmetic `0.0.0-SNAPSHOT` that JitPack overrides with the tag — so the path it resolved to,
  `target/botmaker-cli-0.0.1-all.jar`, never existed. The README's
  `releases/latest/download/botmaker-cli-all.jar` install line was a 404 for the whole of `v0.0.1`. The
  workflow now renames the shaded jar to that stable name before JReleaser runs, and fails loudly if shade
  attached nothing, so exactly one name exists from the build onward.

## [0.0.1] — 2026-09-02

First release. `0.x` because the contract and the loader it is built on are both `0.x`, and a release of
either forces a release of this one: flatten bakes both pins into the pom the plugin registry's CI resolves,
and a gate loading plugins with a different loader than Studio's is a gate that admits plugins Studio then
refuses.

**Install:** the executable jar is attached to this release as `botmaker-cli-all.jar`, at a stable name, so
the README's install line can be `releases/latest/download/…`. `com.botmaker.cli.validate` is the **main**
artifact, resolved as a library — see below for why the two are separate.

### Added

- **The module** — the eleventh BotMaker repository, and the first aimed squarely at somebody who does not
  work on BotMaker at all. Four verbs around the plugin platform: `new`, `validate`, `run`, `publish`.
- **`com.botmaker.cli.validate` is a library, and that is the point of the module's shape.** The main
  artifact is the library and the executable jar is the `all` classifier, because the validator has two
  callers in two repositories — the plugin author running `botmaker validate` and the plugin registry's CI
  deciding a pull request — and a submission that fails for a reason its author could not have seen coming
  is exactly what the gate exists to prevent. The package prints nothing, spawns nothing and reaches no
  network: it is handed a `PluginSubject` of resolved facts.
- **The seven checks** — `classpath`, `loads`, `id`, `palette`, `value-types`, `editors`, `pom-scopes`. All
  seven are always reported: a check whose predecessor made it unanswerable is a `SKIP` with the reason,
  never silence, because a report that shrinks when things go wrong hides how much it did not look at.
- **`botmaker new`** — shells to `botmaker-plugin-archetype` and carries no templates of its own. The
  generated shape has exactly one source of truth; three of the things it gets right (the dependency scopes)
  are wrong in ways that produce no compile error.
- **`botmaker validate [dir]`**, and **`--coordinate G:A:V`** for a published artifact. The second is the one
  that catches a plugin which builds on the author's machine and publishes a pom nobody else can resolve —
  the shape of the `0.0.0-SNAPSHOT` bug that shipped in every SDK up to v1.0.24.
- **`botmaker run`** — `mvn install` into `~/.m2`, one idempotent dependency added to a bot project's pom,
  and Studio launched on it. **No tag is pushed**: Maven checks `~/.m2` before JitPack, which is the same
  property the SDK has had all along. It does *not* create a bot project, because composing one means
  composing its pom and only the thing that knows the whole plugin set can write the file that names them.
- **`botmaker publish`** — validates, composes the registry entry from what the plugin already says about
  itself, and opens the pull request with `gh`. `--dry-run` prints the entry and opens nothing. The author
  never hand-edits `index.json`.
- **The registry entry is one file, `plugins/<plugin-id>.json`, and the index is generated from those
  files.** Two authors publishing on the same day open two pull requests with no line in common, and a
  second plugin cannot take an id git already holds a file for — the "already claimed" arm of the `id`
  check becomes a property of the layout rather than of a scan somebody has to remember. Value type ids are
  not filenames, so those still need the scan, and `Registry.claimedValueTypeIds` is what fills the
  `PluginSubject` parameter a local run always leaves empty. An entry carries `verifiedVersion` beside
  `verifiedAt`: a date with no artifact beside it says nothing about what was checked.
- **`com.botmaker.cli.registry.RegistryGate`** — the check that decides a registry pull request, run from
  the registry's CI against this module's **main** artifact. It is here rather than there for the same
  reason the validator is a library at all: it has to be the code the author already ran. It parses two
  positional arguments by hand rather than with picocli, and reads attacker-chosen paths from a file rather
  than from a shell command line.
- **The gate also reserves the ids the host's own plugins own** (`Bundled`, `BOTMAKER_BUNDLED_PLUGINS`).
  A plugin the host *ships* has no entry file, so `com.botmaker.sdk` and the SDK's seventeen value type ids
  were claimed by nobody: a submission taking one passed every check and then lost silently inside
  `ValueCatalog.merge`. The gate resolves the named coordinates and asks the plugins for their ids rather
  than carrying a list that would drift. `Subjects.fromCoordinates` resolves several coordinates onto **one**
  classpath, which is what a host has — the SDK's toolkit dependency is `optional` and so not transitive, and
  `SdkPlugin` cannot be constructed without it.
- **`botmaker publish --dry-run` prints the entry on stdout and nothing else**, so it can be redirected into
  the entry file when `gh` is not installed. The report moved to stderr, which is the rule `Console` already
  stated and this command was breaking.
- **The command line is picocli, and the dependency is `optional`.** `--help` is generated from the same
  annotated fields that parse, so it cannot fall behind them, and an option nobody declared is refused with
  a suggestion instead of ignored. `optional` means it is not transitive: the executable jar carries it and
  the plugin registry, which resolves the main artifact as a *library*, does not — so the rule that
  `com.botmaker.cli.validate` knows no command line is now true of the dependency graph as well as of the
  source.

### Deliberately absent

- **JavaFX.** `javafx-controls` is `provided`, only so `SlotEditor` can be named. That makes the `editors`
  check narrower than it reads — an editor's *predicate* is checked and its *node* is not — and the check
  says so rather than implying otherwise. Carrying JavaFX would make this a per-OS download, and the
  single-jar promise is worth more; the node is seen the first time anybody clicks the slot, which is what
  `botmaker run` is for.
- **`botmaker-plugin-toolkit`.** `StubContexts` is written here rather than using the toolkit's
  `TestContexts`, for the same reason `botmaker-studio` may not depend on the toolkit: it is a *plugin's*
  dependency, resolved onto the plugin's own classloader so that two plugins may hold two versions of it.
- **An embedded Maven resolver.** Maven is shelled to, so that `validate` resolves what the author's own
  build resolves — and so that the registry's CI and the author get the same answer.
- **Any claim about safety.** A plugin runs arbitrary code in Studio's process. Every check here asks
  whether a plugin *works*; none asks whether it is *safe*, and the registry is a curated index with a
  working gate, never a security boundary.
