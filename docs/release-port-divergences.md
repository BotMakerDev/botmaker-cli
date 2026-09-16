# The release port's dry-run divergences from `release.sh`

**Run 2026-09-06, against umbrella `4b6885f`, `botmaker-cli` at `cb267aa`.** Fourteen flag combinations,
both implementations, stdout diffed after stripping ANSI colour and the script's `==> ` narration prefix.

**Result: 14 of 14 combinations differ.** The claim in `com.botmaker.cli.release`'s `package-info.java` —
*"the plan's cutover test passes — the script's `--dry-run` and this library's agree across the flag
matrix"* — was **not true when it was written** and is corrected in the same commit as this file.

**But nothing in the diff is a decision.** Every module chosen, every version computed, every skip, every
gate verdict and every refusal message is byte-identical, including the two gates added on 2026-09-06. The
divergences are all in *narration, ordering and echo form*. That is a materially better position than "the
diffs are empty" would have been if it had been true by accident, and it is also not the cutover condition
the plan wrote down.

---

## Update, 2026-09-16 — the release-run matrix, and two writes that did not exist

**The 2026-09-06 matrix asked the wrong question of nine of its fourteen combinations.** Twelve of them cut
a single module, and a single-module run reaches almost none of the per-module write path: no
`.deps.env` with more than one pin, no Studio release beside an SDK release, no baseline bump on a module
whose newest tag has moved. Run once as `--all --sdk 1.2.0` — the release actually about to be cut — the
same comparison found **two things `release.sh` does that the port did not do at all**, both of which land
inside a module's own release commit:

### D11 — `bump_japicmp_baseline` was not ported. **Wrote nothing. Disarmed a gate. Fixed.**

`release.sh:627` moves `<botmaker.japicmp.baseline>` up to the module's newest existing tag, in the release
commit, for `botmaker-studio-api` and `botmaker-sdk`. The port had no equivalent, so a release cut through
it would leave both baselines where they were — and a baseline that does not name the previous release is
exactly the state `ignoreMissingOldVersion` turns into a gate that reports success and checks nothing. The
rules it carries are never-delete on `com.botmaker.sdk.api.**` and binary compatibility for the contract.

Now `Japicmp`, with the never-move-backwards rule intact (the SDK's `v1.2.0` outranks its older newest tag,
because never-delete *begins* there and is not retroactive). Which modules it runs for is read off their
poms rather than listed, so the two call sites the script names become one question asked of eleven.

### D12 — `SDK_FALLBACK_VERSION` was not bumped. **Wrote nothing. Fixed.**

`release.sh:1980` seds the constant in `MavenService.java` during the Studio release whenever the same run
cuts the SDK. Without it, every project created by the Studio this release publishes pins the *previous*
SDK — the failure that produced Studio v1.0.34, 1.0.35 and 1.0.36, and the entire reason `--sdk` forces
`--studio`.

Now `Fallback`, which also **takes ownership of the constant list** that `FallbackVersionsGate` used to
declare separately. Two lists is how `TOOLKIT_FALLBACK_VERSION` spent months being moved by a release and
checked by nothing.

### What else moved in the same pass

| Was | Now |
|---|---|
| **D1** pointer commit wording | `Module.pointerName()` transcribes the script's eleven labels, inconsistency included, so `git log --grep` over the release history does not gain a seam |
| **D4** three missing narration lines | two of the three print (`would verify on JitPack`, the Studio package-matrix line); `DRY RUN — no changes will be made.` stays the caller's, because a library has no opinion about a banner |
| **D6** unquoted echoes | `Runner.run` quotes each argument, so a commit subject echoes as one argument and the line is paste-able |
| **D7** `DEPS_EOF` on a changelog | the marker follows the file |
| **D8** whole-file changelog rewrite | `Stamp` is an anchored first-match replacement, tested to leave every other byte — CRLF, tabs, a missing final newline — exactly as it found them |
| **D9** push pass reported nothing useful in a dry run | it inspects for real and pushes nothing; reading `rev-list --count` is not a side effect |

**Still standing, both deliberately:** D3 (the port runs every gate; the script stops at the first `die`)
and D2/D5 (gate order, and the `Gates:` heading). The port runs the two Maven-backed SDK gates **last**
rather than third, so a cheap refusal is reported before Maven starts — which is a reason the script's order
does not have. Neither changes a verdict.

**Where that leaves the comparison**, run as `--all --sdk 1.2.0` on 2026-09-16: every decided version, every
skip, every gate verdict, the tag order, every pin, every stamp, both source edits, the pointer-commit
subject and the final `Released:` line agree, line for line. What remains in a raw `diff` is the `==> `
prefix, the gate ordering above, heredoc bodies the port elides on purpose, and the minute in the release
log's filename.

---

## How it was run

```bash
./release.sh <flags> --dry-run
java -jar botmaker-cli/target/botmaker-cli-0.0.0-SNAPSHOT-all.jar release <flags>
```

The matrix: `--all`, `--all minor`, each of the ten module flags alone, `--sdk 1.1.7 --studio 1.0.38` (the
pair the Phase 1 forcing gate exists to require), and `--studio-api 0.0.5 --plugin-toolkit 0.0.6
--plugin-host 0.0.6 --cli 0.0.10` (a contract release with everything it forces).

Exit codes agreed in all fourteen.

## What agrees, stated first because it is the load-bearing half

| Compared | Result |
|---|---|
| Which modules a run cuts | identical, all 14 |
| The version each gets, patch and minor | identical |
| Skip decisions and their reasons | identical |
| Every gate's *verdict* (ok / not checked / FORCED / refused) | identical |
| Every refusal's *text*, word for word | identical |
| Exit code | identical, all 14 |

The two 2026-09-06 gates are exact. `--sdk 1.1.7` alone refuses in both with the same paragraph, naming
`--studio` and the same reason (`the release bumps MavenService.SDK_FALLBACK_VERSION, so Studio's own
source changes — without a Studio release, freshly created bots keep pinning the previous SDK`), and
`--sdk 1.1.7 --studio 1.0.38` proceeds past it in both.

## The divergences

Ranked by whether they can change what a real release *does*, not by diff size.

### D1 — the umbrella pointer commit is worded differently. **Writes something. Fix before cutover.**

`./release.sh --plugin-archetype 0.0.5` commits the umbrella as `release: archetype v0.0.5`; the port
commits `release: plugin-archetype v0.0.5`. Same for the toolkit: the script says `toolkit`, the port says
`plugin-toolkit`. The submodule's own commit uses the full directory name in **both**, so only the umbrella
subject and the run's last line differ.

The script keeps a hand-written label per module (`release.sh:2101-2110`); the port derives it in
`Module.shortName()` as the directory minus `botmaker-`. The port's is more consistent and the script's is
what every umbrella release commit in this repository's history says.

**Verdict: the maintainer's call, and it is the only divergence that reaches a permanent artifact.** Either
give `Module` a short-name table matching the script, or change the script's two labels and accept the
history seam. Do not cut over while they disagree.

### D2 — the gates run in a different order. **Fix in the port; mechanical.**

| | order |
|---|---|
| `release.sh` (`:1861-1894`) | forcing → fallback → **sdk gates** → ci-deps → jitpack-plugins → **changelog** |
| `Gates.run` | forcing → fallback → **changelog** → ci-deps → jitpack-plugins → **sdk gates** |

No verdict changes; the lines appear in a different sequence. The port's ordering has no stated reason in
its javadoc, and the script's does — the SDK gates run Maven, so an operator learns about a cheap refusal
first only by accident either way. Reorder `Gates.run` to match; it is five statements.

### D3 — the port runs every gate; the script stops at the first refusal. **Deliberate. Decide what the test means.**

`Gates.java`'s own javadoc: *"All of them run even after one refuses… the script stops at its first `die`;
this reports the whole list, because the operator is about to go and fix something and a second refusal
discovered on the next run is a second round trip."*

That is a real improvement and it makes an empty stdout diff **impossible** for any refusing run — five of
the fourteen. Visible cleanly in `--shared 1.1.1`: the script prints the forcing refusal and stops; the port
prints four more `— ok` lines and then the same refusal.

**Verdict: keep the behaviour, change the cutover test.** The test that survives is *decisions and refusal
texts agree*, which is what §"What agrees" above measures. An empty stdout diff was never the property worth
having; it was a proxy for it.

### D4 — three narration lines the port never prints. **Fix in the port; one line each.**

- `DRY RUN — no changes will be made.` (`release.sh:485`) — missing in **all fourteen**.
- `(dry-run) would verify on JitPack: <specs>` (`release.sh:1511`) — missing wherever JitPack builds the
  module.
- `botmaker-studio v1.0.38 tagged — its package matrix runs in parallel with the chain below.` — missing.

The first is the one that matters: a preview that does not say it is a preview is the wrong thing to omit
from a tool whose entire safety argument is "run it dry first".

### D5 — the port prints a `Gates:` heading the script has not. **Cosmetic; drop it or add it to the script.**

### D6 — command echoes are not shell-quoted, and the idempotence wrappers are invisible.

Script: `$ bash -c git -C '…' rev-parse -q --verify 'refs/tags/v0.0.10' >/dev/null || git -C '…' tag 'v0.0.10'`
Port: `$ git -C … tag v0.0.10`

The port implements the same idempotence in Java (`CommitTagPush`) and echoes only the effective command.
The echoed line is therefore **not copy-pasteable** and no longer shows the guard. `Runner.run` joins argv
with spaces and quotes nothing, so a commit subject containing a space echoes as bare words:
`$ git -C … commit -am release: studio v1.0.38`.

**Verdict: quote the echo.** The behaviour is right; the transcript is misleading, and a dry run's output is
read as a script by whoever is checking it.

### D7 — file writes elide the body, and always say `DEPS_EOF`.

`Runner.write` prints `$ cat > <path> <<'DEPS_EOF' … DEPS_EOF` where the script prints the whole heredoc.
Deliberate, and documented — *"the interesting part of a `.deps.env` is the pins, and those are printed by
the line above it"* — which is fair for `.deps.env` and wrong for the other caller: the changelog stamp
prints `cat > …/CHANGELOG.md <<'DEPS_EOF' … DEPS_EOF`, naming a marker that has nothing to do with a
changelog.

### D8 — the changelog stamp is a different operation. **Worth a second look.**

Script: `sed -i '0,/^## \[Unreleased\]/s//## [1.0.38] — 2026-09-06/' <file>` — a bounded in-place edit of
the first matching line.
Port: rewrites the whole file through `Runner.write`.

Same intended result, and the port's is the riskier shape: a whole-file rewrite of a file the maintainer
has been editing loses anything the port's reader did not model (final newline, CRLF, encoding). Not
observed to differ here — the stamped output was not compared byte for byte, because the dry run does not
write. **This is the one divergence this matrix did not actually test**, and it should be tested before
cutover by stamping a copy with both and diffing.

### D9 — the push pass reports differently.

Script computes each repository's ahead-count in the dry run and lists only those that are ahead:
`botmaker-cli: 'main' is 4 commit(s) ahead of origin/main`. The port prints
`(dry-run) would push <module> if it is ahead of origin` for all eleven, because `Umbrella.pushBranch`
returns early on `runner.dryRun()` before reading git at all.

**Verdict: fix in the port.** Reading `rev-list --count` is not a side effect, and the script's line is
strictly more informative — it is the difference between "eleven repositories might get pushed" and "these
five will".

### D10 — a trailing space.

`  pinning botmaker-cli to STUDIO_API_TAG=v0.0.4 PLUGIN_HOST_TAG=v0.0.5 ` — the script leaves one, the port
strips it. Listed only because a stdout diff sees it.

## Where this leaves the cutover

`package-info.java` states the condition as *every slice's dry-run diff empty **and** one full `--all` cut
through the library*. On the evidence above:

1. **The first half is unreachable as written**, because D3 is a behaviour the port should keep. Restate it:
   *the decisions, the gate verdicts and the refusal texts agree across the matrix* — which they now
   demonstrably do.
2. **D1 must be settled first regardless**, because it is the one divergence that writes a permanent commit
   subject, and the umbrella's release history is the thing that would carry the seam.
3. **D8 needs the test this matrix could not run**: stamp a copy of a real `CHANGELOG.md` with both
   implementations and diff the files, not the narration.
4. D2, D4, D6, D7, D9 are small and one-directional — the port is missing or mangling something the script
   says. Fixing them costs an afternoon and removes most of the diff.
5. **`.github/workflows/release.yml:151` still invokes `./release.sh`, and should stay that way** until
   1–3 are settled and one single-module release has been cut through the library and watched end to end.
   A tag is permanent and no exit code recalls one.

## Reproducing

The matrix script is not committed — it is fourteen invocations of the two commands at the top of this
file, with `sed 's/\x1b\[[0-9;]*m//g'` on both and `sed 's/^==> //'` on the script's. Re-run it after any
change to either implementation; it needs network (both fetch tags) and about eight minutes, most of it
Maven inside the two SDK gates.
