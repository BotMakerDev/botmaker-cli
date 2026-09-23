# botmaker-cli — roadmap

## Done

### 2026-09-23 — `value-types` checks a `freshCall()`'s shape

`PluginType.freshSource()` (text) became `freshCall()` (a `Method`). A type answering a null `fresh()` must
answer a public static method with no parameters whose return type is the declared class by name; any other
shape is a failure naming the method and what is wrong (`PluginValidator.freshCallProblem`), since a host
skips it and the type is left with nothing to start as. Studio's grammar checks the same shape again before
writing, rather than trusting that validate ran. `botmaker plugin validate botmaker-sdk` passes: the four
vision results answer `Vision.lastMatch` and its siblings.

### 2026-09-06 — the release port's dry-run matrix was run for the first time, and the diff is not empty

`package-info.java` said *"the plan's cutover test passes — the script's `--dry-run` and this library's
agree across the flag matrix"*. Running that matrix — fourteen flag combinations, both implementations,
stdout diffed with colour and the script's `==> ` prefix stripped — found **fourteen of fourteen differ**.
The claim was written and never checked. `docs/release-port-divergences.md` is the whole list with a verdict
each; the paragraph is corrected.

**The half that matters agrees, and that is the finding rather than a consolation.** Every module chosen,
every version computed, every skip, every gate verdict and every refusal message is byte-identical across
all fourteen, exit codes included — the two gates added the same day included, so `--sdk 1.1.7` alone
refuses in both with the same paragraph naming `--studio`. What differs is narration, gate order and echo
form.

Three things come out of it that were not known before:

- **One divergence writes a permanent artifact.** The umbrella's pointer commit is `release: archetype
  v0.0.5` from the script and `release: plugin-archetype v0.0.5` from here, because `Module.shortName()`
  derives what `release.sh:2101-2110` tabulates. The toolkit differs the same way. The submodule's own
  commit agrees in both. This blocks the cutover on its own, and which spelling is right is the
  maintainer's call rather than the port's.
- **The cutover test has to be restated, because one divergence is a behaviour worth keeping.** `Gates` runs
  every gate where the script stops at its first `die`, so an empty stdout diff is unreachable for any
  refusing run — five of the fourteen. The property worth having was never the empty diff; it was *the
  decisions, the gate verdicts and the refusal texts agree*, which they do.
- **One thing the matrix could not test at all**: `stamp_changelog` is a bounded `sed -i` in the script and a
  whole-file rewrite here, and a dry run writes nothing, so the two outputs have never been compared. Do
  that byte for byte before cutting over.

The rest — a missing `DRY RUN` banner, a missing JitPack-verify line, gate order, unquoted command echoes, a
push pass that reports "would push X if it is ahead" instead of the script's real ahead-counts — are small
and one-directional: the port is missing or mangling something the script says.

`.github/workflows/release.yml:151` still invokes `./release.sh`, and stays that way.

### 2026-09-06 — a registry entry says what its plugin needs in the editor

`RegistryEntry.editorDependencies` — `groupId:artifactId:version` of what a plugin needs on the host's
classpath and does not carry into a bot. `plugin publish` composes it by reading the plugin's own pom for the
dependencies it declares `optional`, which is exactly the set a host resolving the plugin does **not** get;
`org.openjfx` (parent-first in the loader), the contract and the toolkit (a bot's pom must declare neither)
are excluded, and a version that is an unresolved property is skipped with a warning rather than published.
`RegistryGate.editorDependenciesRefusal` refuses a malformed line, a duplicate coordinate and either of the
two excluded artifacts, because every line becomes a `provided` dependency in a stranger's project pom.

**What it replaces is the interesting part**: `MavenService.installPlugin` had an `if (isSdk(…))` branch over
a list written in Studio's own source — the only privilege plugin #1 held, and the one the platform exists to
refuse. `PluginValidator`'s `CONTRACT_GROUP`/`CONTRACT_ARTIFACT`/`TOOLKIT_ARTIFACT` are public now so the
check, the publish and the gate spell them once.

### 2026-09-04 — `sudo dnf install botmaker`

nfpm builds the rpm and the deb from `packaging/nfpm.yaml` in the `release` job, and a `pages` job lifted
from Studio's republishes them as signed dnf/apt repositories. Both packages are small enough to host on
Pages outright, which is the one place this differs from Studio's script (~240 MB there, so its rpm is
metadata-only with a `--baseurl` rewrite).

**Configured on 2026-09-04, and the three things it took are worth writing down, because a repository added
later meets all three again.** `GPG_KEY_ID` / `GPG_PRIVATE_KEY` / `GPG_PASSPHRASE` are per-repository
secrets — `LiQiyeDev` is a user account, not an organisation, so Studio's do not carry over and the key has
to be re-exported; `GPG_PRIVATE_KEY` is base64 of the *armored private key* and must be **piped**
(`gpg --export-secret-keys --armor <id> | base64 -w0 | gh secret set …`), because the blob is ~8 KB on one
line and pasting it through a terminal loses part of it — the symptom is
`gpg: no valid OpenPGP data found` in the `pages` job. Enabling Pages creates a `github-pages`
**environment** whose deployment rules allow the default branch only, so a tag-triggered deploy is refused
with `Tag "v0.0.3" is not allowed to deploy to github-pages due to environment protection rules`; the fix is
a second rule of type *tag* matching `v*` (Settings ▸ Environments ▸ `github-pages`), which is what
`botmaker-studio` already had.

### 2026-09-04 — the rpm is signed, because dnf checks two things

`v0.0.3` was the first real install and it failed at the last step: a correctly-signed index over an
unsigned package. `gpgcheck` and `repo_gpgcheck` are independent, the generated `botmaker.repo` sets both,
and only the second was being satisfied. The reasoning that produced the gap is sound and is exactly why it
is recorded rather than quietly patched — `repomd.xml.asc` covers `primary.xml`, which carries the rpm's
checksum, so the index signature *is* a signature over the payload, and **apt accepts that chain**. dnf
does not. nfpm signs the header from `NFPM_RPM_KEY_FILE`, written in the workflow out of the same
`GPG_PRIVATE_KEY` secret; with it unset nfpm builds an unsigned package rather than failing, which is a
fork's tag build. The deb stays unsigned: no ordinary apt verifies a debsigs signature.

### 2026-09-04 — `botmaker bot`, the half of the platform with no command

`bot new` writes the blank project (a pom naming no plugin, one `main`, the template declaration) or
downloads a published template and renames only its package; `bot publish` does the repository, the push,
the release, the archive check and the gallery pull request. The blank composer and the repackager are
deliberate duplicates of Studio's — an application cannot be depended on — and `gallery/GalleryEntry`
mirrors Studio's for the reason `registry/RegistryEntry` mirrors the registry's.

### 2026-09-05 — two nouns, and a `doctor`

The four plugin verbs moved under `botmaker plugin`, so both halves of the platform spell their noun. A
break rather than aliases: v0.x, and a second permanent spelling of every verb costs more than one rename.
The old paths stay as hidden aliases of `MovedCommand`, which runs nothing and names the replacement —
picocli's `Unmatched argument: 'validate'` is the alternative, and it teaches nobody where the verb went.

`doctor` reports Java, Maven, `gh`, `gh auth`, `$BOTMAKER_STUDIO` and the projects root in one pass, with no
network. It adds no capability: every verb already reports its missing tool, halfway through the first real
use. The Maven it names is `Mvn.executable`'s answer, not a second implementation of that precedence.

### 2026-09-04 — `publish` follows its own pointers

The entry's `verifiedVersion` was the working pom's `<version>` and is now the newest git tag. JitPack
serves an artifact under the tag, so the pom version matched by coincidence at best — and in the ordinary
case not at all: `botmaker new` generates `0.1.0-SNAPSHOT`, which JitPack cannot resolve, so publishing
straight after generating produced a pull request that failed in the registry's CI. `--tag` overrides,
`-SNAPSHOT` is refused by name, the coordinate is resolved through `Subjects.fromCoordinate` before the pull
request is opened, and `--repo` is checked with `gh repo view`.

### 2026-09-02 — JDK 25 LTS

`jitpack.yml` → `openjdk25`, the pom to `maven.compiler.release` 25, `javafx.version` → 25.0.4 (still
compile-only — nothing here constructs a `Node`), CI to `java-version: '25'`. The plugin registry's
`validate.yml` moved with it, in its own repository: it resolves this module's main artifact and runs the
validator, so it must never be older than this module's bytecode. Full account in
`../botmaker-studio-api/ROADMAP.md`, dated the same day.

## Now

Both nouns exist and `validate` is a library. What is unproven is the half that needs the other two
repositories:

- **`bot publish`'s real path has now run, and it found three defects the tests could not.**
  `LiQiyeDev/botmaker-base` is the first published template (gallery pull request #1, `validate` green): a
  blank project, since a template pinning the SDK would name a plugin the registry does not carry yet.
  What the run caught: `gh repo fork --remote=false` is not a flag when a repository argument is given, so
  **no** pull request either publisher had ever tried to open could have succeeded; the gallery's own
  maintainer cannot fork their own repository, so the submit step needs a direct-branch arm; and a blank
  pom was writing the *publisher's* JVM into a file that travels. The lesson is the one the phase was for —
  a stubbed `gh` proves the arguments are assembled, not that they are accepted.
- **`publish`'s real path is still unexercised.** The same two `gh` fixes landed on it unrun, because
  `LiQiyeDev/botmaker-plugin-registry` does not exist yet. It reports the missing `index.json` by name
  rather than failing obscurely, and that message is the thing to check first when the registry lands.
- **`run` has been exercised without Studio.** The install and the pom edit are covered; launching Studio
  through `--umbrella` and `--studio` needs a machine with one.

## Next — and the ordering is not free

1. **The registry** (`botmaker-plugin-registry`, plan phase 8). Its CI resolves this module as a library and
   calls `PluginValidator.validate` with the index's claimed ids filled in. Two of the eight checks
   (`id`, `value-types`) can only ever half-answer here, and the registry is where the other half lives.
2. **`--json` output.** A CI job wants the report as data, not as a terminal rendering. `CheckResult` is
   already a record and Jackson is already a dependency; the only real decision is whether the schema is
   part of this module's semver, and it should be.
3. **A `botmaker doctor`**, or whatever it ends up called: *why does Studio not see my plugin* asked from
   the bot project's side rather than the plugin's. It is the same loader over a different classpath, and it
   is the question an author will actually ask.

## Deliberately not planned

- **A native binary.** JReleaser's `nativeImage` assembler could produce one without changing a single
  command, and the reason not to is that this program's whole job is to shell out to Maven and `gh`. A
  native launcher for a process supervisor buys a hundred milliseconds and costs a per-OS release matrix.
- **Bundling Maven.** The promise of `validate` is that it answers what the author's own build answers.
- **Reading or writing `activities.json`.** That file has one owner and it is the SDK
  (`com.botmaker.sdk.authoring`). A CLI that learned to read it would be a second author of a format whose
  whole design was to have one.
- **Anything that loads a plugin and then reports on what it *does*.** Every check here is about whether a
  plugin works. A check that claimed something about behaviour would read as a safety claim, and nothing in
  this process can keep one.
