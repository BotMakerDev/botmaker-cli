# `com.botmaker.cli.release` — `release.sh`, being ported into the library artifact

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
chain (since 2026-09-16: Studio's package jobs build against the upstream releases its pom pins, so those
must already be pushed) and the dashboard last of all (since 2026-09-17, the same shape over the cli). What a
module is exempt from is asked of `Module` itself (`mavenBuild`, `onJitpack`, `hasChangelog`), never by
naming it: the pilot and `botmaker-remote` are APKs; Studio, the dashboard and `botmaker-remote-server` are
programs JitPack never builds. Since 2026-10-06 Studio's and the dashboard's package jobs resolve their
pinned releases from JitPack (`Module.resolvesFromJitpack`), so `Waits` owes their upstreams the wait. **`--cli` forces `--dashboard`**: the dashboard's Release tab calls this
package in-process, so an installed dashboard decides by the cli it was built with, and a cli release
without a dashboard release leaves its previews on the previous rules. Its pom pins shared and the cli
(`Module.byPropertyKey` maps the `cli` key).

**Versions are real and the release writes them** (2026-10-06, umbrella `docs/refactor/43-real-versions.md`).
Every pom says `X.Y.Z-SNAPSHOT` on `main`, and each `botmaker.<key>.version` names that upstream's `main`
version; `Module.upstreams()` is the table, `VersionsGate` holds each pom to it before the first tag. For each
module `Release` makes **two commits**: the release commit (`PomVersions.release`: its own version and each
pin at the released version `DepTag.version` picks), tagged, then the back-to-snapshot commit
(`PomVersions.backToSnapshot`: the next patch `-SNAPSHOT`, each pin at its upstream's `main`), and pushes
both. A dependent outside the release then has its pin moved and committed (`PomVersions.follow`, never
fatal), and the umbrella records its pointer with the release's. Every edit is `versions-maven-plugin`
(`set`, `set-property`), pinned in `PomVersions.PLUGIN`, run locally and never on JitPack. A failure before
the release commit puts `pom.xml` back; one between the two commits puts the snapshot back in the working
tree, and nothing is pushed. `ChangeKind` does not count a pom whose only change is versions, or every
released module would read as changed by its own back-to-snapshot commit. This replaced `.deps.env`
(`DepsEnv`, deleted) and the `-D` injection in each `jitpack.yml`.

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

**A verdict is only as true as the moment it was asked, and `Actions` owns that moment.** The chain polls
each tag seconds after pushing it, so an empty `gh run list` means *not registered yet* far more often than
*nothing will ever run*. `no run on <tag>` stays a **failure** — a tag is finished, and a tag that fires
nothing is what this column was added to catch — and `Actions.poll` waits the sentence out: the empty answer
alone is retried, every 5 s for 60 s. A run that exists already answers `running (n of m)` and is returned
at once. The wait lives behind a `Supplier`/`Waiter` seam so the window is tested without being spent.

Landed: slice 1 (`Module`, `Version`, `Level`, `Tags` = `latest_version`, `VersionSpec` = `resolve_version`,
`Git`), slice 2 (`Relevance` = `is_release_irrelevant`, `ChangeKind`, `ReleaseDecision` = `should_release`),
slice 3 (`Forcing`, `Order`, `DepTag`), slice 4 (`GateVerdict`, `GatePlan`, `CiDepsGate`, `ChangelogGate`,
`SdkGates`, `JitpackPluginsGate`, `MavenPrerequisite`, `Proc`), slice 5 (`Runner`, `DepsEnv` — replaced by `PomVersions` on 2026-10-06, `Stamp`,
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
