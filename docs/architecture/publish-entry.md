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

Rule 4, at step 3 and in step 6's `checkEntry`, has the catalog's own exception: an admin in `publishers.json` (the
signed-in account's id, `gh api user`) may change any entry (2026-10-24). A release the Actions bot made in an
organisation's repository is otherwise nobody's to list.

`Publish.listRelease` is the release library's entry (`ReleaseListing`, `release-library.md`): a tag the release
just pushed, listed only when `catalog.json` lists a plugin of the repository, once its GitHub Release is out.
`Checks.LEFT_TO_THE_CATALOG` is for a host that loads no plugin (the dashboard): it checks no jar, the known entry
path stands, and it refuses to publish a new version.

`Publish` prints nothing: progress goes to a `Listener`, every side effect through a `PublishHost`, and a refusal is
the `Outcome` it returns. **The host is the port (2026-10-24)**: the folder's git questions (`head`, tags, `clean`,
the remote) and changes (`pushHead`, `tag`, `release`, `pullRequest`), as the signed-in `Account` (login and id).
The cli's is `PublishHost.commands`, `git` and `gh` through a `StepRunner` (which tells `gh` never to prompt);
Studio's is its own `StudioPublishHost`, JGit and Studio's sign-in, pushing to the project's `mine` — so Studio runs
these steps and rules without the `gh` program, and the rules stay in one place. `CatalogPr.unpublish` takes only
the host's pull-request opener (`CatalogPr.Opener`). A plugin's id names its entry and is read off
the loaded jar, so a plugin's entry path is known up front only from `catalog.json`'s row for its repository.

## The version is a git tag, not the pom's `<version>`

JitPack builds a tag on demand and serves the artifact under that tag whatever the pom says — this project's own
poms carried a cosmetic `0.0.0-SNAPSHOT` until 2026-10-06 — so a pom version resolves only where it happens to
equal the tag, and `botmaker plugin new` generates `0.1.0-SNAPSHOT`, which JitPack never resolves. Since
2026-10-24 the command makes the tag itself, so a snapshot can no longer be what it publishes; the old snapshot
refusal went with `project/Tags`.

`--tag`, not `--version`: `mixinStandardHelpOptions` already declares `-V, --version` on every command.

## The `.bmk` (2026-10-25)

`botmaker bot export` writes the bot at a clean `HEAD` that exactly one tag names as one file
(`com.botmaker.cli.bmk.BotFile`), which someone who only runs bots opens in BotMaker. It holds `bot.json`,
`pom.xml`, `icon.png`, `src/main/java/**` and `src/main/resources/**` as the commit holds them (read with `git
ls-tree` and `git cat-file --batch`, never from the checkout: `core.autocrlf`, a filter or a skip-worktree file
would make another machine's file differ; a symlink, a submodule or a Git LFS pointer is refused), and under `lib/` the `com.github.` jars the build resolves (JitPack
builds them on demand and may lose a tag; the user's call). Every other runtime jar is listed in `bot.json` with
its coordinate and sha256, and fetched from Maven Central on first open. Native-classifier jars (JavaFX's
`linux`, `win`, `mac`…) are left out: the opening machine fetches its own. The repository is `origin`'s, else
`mine`'s (Studio's).

The file is reproducible, because the catalog rebuilds it from the tag's commit on another machine and compares
sha256: entries are sorted, carry the time 1980-01-01 00:00 (DOS local time, so no time zone), no directory
entry, no extra field and no unix mode (`ZipOutputStream` cannot set one). Every entry is **STORED** with its
CRC, not deflated: deflate output depends on the zlib the JDK links (Fedora's is zlib-ng), so two machines could
compress the same bytes differently. `bot.json` is pretty-printed with `\n` whatever the system's line separator.

Opening (`BotFile.read`) checks everything before it writes, and writing refuses the same names first: each name is
relative with no `.`/`..` segment, none of Windows' forbidden or control characters, no segment ending in `.` or
a space, no device name (`NUL`, `CON.txt`), and is one of the allowed entries; no two names differ only by case (one file on
Windows and macOS); `bot.json` names a format this reader knows (a newer one says to update BotMaker); each
`lib/` jar matches its listed sha256, and none is unlisted or missing. It extracts into a staging folder beside
the target, with a size cap, then moves it into place, so a refused file leaves nothing behind.

`BotFile.peek` reads only `bot.json`, with the same name and format checks, and refuses an owner, repository or tag
that is not one plain folder name (a host files the bot under `<owner>/<repo>/<tag>`); Studio peeks to find the
catalog's `bmkSha256` and ask for trust before unpacking anything (2026-10-25). `BotFiles.head` is the one reading of
the pom and sources (`maven.compiler.release`, `main`, the name) that `bot export` and Studio's export share.
