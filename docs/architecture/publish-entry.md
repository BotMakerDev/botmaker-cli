# Publishing from the folder: the version, the order of the steps, and why none of it is the pom's `<version>`

> **2026-10-24:** `plugin publish` and `bot publish` are one library, `com.botmaker.cli.publish` (`Publish`), which
> Studio's Publish and the release library call too. The author runs `botmaker plugin|bot publish
> [patch|minor|major]` in the folder and names nothing; spec `docs/superpowers/specs/2026-10-24-easy-publish-design.md`
> in the umbrella. Until 2026-10-23 the entry was `{id, repo, tag}` with `--repo` required and the newest tag taken
> as is; until 2026-10-24 a missing `--repo` meant a dry run.

## The steps, each refusing before the next

1. **Repository** (`PublishTarget`): the folder's `origin`, parsed to `owner/name`. `--repo` overrides it only when
   they agree: two answers are refused, never chosen between. A bot with no `origin` has its repository created
   (`gh repo create`, named after the folder under the signed-in account), and a bot folder that is not a git
   repository yet is initialised and committed; a plugin with no `origin` is refused, since JitPack builds it from
   GitHub.
2. **Version** (`NextVersion`): `--tag` names it exactly; else `HEAD`'s own tag the catalog has not approved; else
   the newest `vX.Y.Z` reachable from `HEAD` bumped (`release.Level`, `patch` by default; none → `v0.1.0`). A tag
   with a published release is `EXISTING`: nothing is tagged and the folder is not checked, which is how a release
   made elsewhere is listed once `HEAD` has moved past it. A newest tag of another shape is refused with `--tag`
   suggested. A tag the catalog lists already ends the run (`AlreadyListed`).
3. **Local check**, for a `NEW` version only: a clean tree, the kind's checks (`Checks.folder`: `PluginValidator`
   on a plugin's folder; rules 6–8 on a bot's, `BotChecks.checkFolder`), and rule 4 against a listed entry (it keeps
   its repository and belongs to whoever controls it). `--dry-run` stops here.
4. **Release**: `HEAD` pushed, the tag made and pushed, `gh release create` as the signed-in user. Each part is
   skipped when done, so a re-run after a failure finishes it instead of bumping again.
5. **A plugin waits for JitPack** (`Jitpack.waitFor` by coordinate, the release library's poll). A build JitPack
   ran `Error` stops the run with its log's URL and opens nothing: that tag stays broken, the next version repairs
   it. Then `PluginValidator` on the jar Maven resolves (`Checks.published`), the subject `validate.yml` builds.
6. **Pull request**: the release pinned (`ApprovedRelease.pinPlugin`/`pinBot`, retried a little since GitHub's API
   can lag a tag just pushed), appended to the entry or a new one, the catalog's rules 2–5 run here
   (`EntryGate.checkEntry`), then `CatalogPr.open`.

`Publish` prints nothing: progress goes to a `Listener`, every `git` and `gh` through a `StepRunner` (which tells
`gh` never to prompt), and a refusal is the `Outcome` it returns. A plugin's id names its entry and is read off
the loaded jar, so a plugin's entry path is known up front only from `catalog.json`'s row for its repository.

## The version is a git tag, not the pom's `<version>`

JitPack builds a tag on demand and serves the artifact under that tag whatever the pom says — this project's own
poms carried a cosmetic `0.0.0-SNAPSHOT` until 2026-10-06 — so a pom version resolves only where it happens to
equal the tag, and `botmaker plugin new` generates `0.1.0-SNAPSHOT`, which JitPack never resolves. Since
2026-10-24 the command makes the tag itself, so a snapshot can no longer be what it publishes; the old snapshot
refusal went with `project/Tags`.

`--tag`, not `--version`: `mixinStandardHelpOptions` already declares `-V, --version` on every command.
