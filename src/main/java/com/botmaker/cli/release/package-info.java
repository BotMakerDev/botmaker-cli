/**
 * {@code release.sh}, ported — the ordered cross-module release, as a library with three callers.
 *
 * <p><b>Why a library and not a command, and not the dashboard.</b> The release has two callers today and
 * will have three: a maintainer's terminal, {@code .github/workflows/release.yml} (which runs the script
 * with {@code --ci}), and {@code botmaker-dashboard}. CI cannot run a JavaFX app, so the owner of these
 * decisions cannot be the GUI. It is the same shape as {@link com.botmaker.cli.validate}, and it is here for
 * the same reason that package is: <i>the check that refuses must be the check its author already ran</i>.
 * So this package <b>prints nothing, spawns no UI and knows no command line</b> — everything that formats a
 * line for a human lives in the command, and everything that decides lives here.
 *
 * <p><b>The port is staged, and no slice ships on being written — it ships on agreeing.</b> A wrong tag is
 * permanent and no exit code recalls one, so each slice is verified by running both implementations'
 * {@code --dry-run} over a matrix of flag combinations and diffing the output. {@code release.sh} keeps
 * cutting the real releases throughout; it is deleted when every slice's diff is empty and one full
 * {@code --all} has been cut through this library.
 *
 * <p>That is also why refusals here carry the script's exact wording (see {@link
 * com.botmaker.cli.release.ReleaseRefusal}): the diff is over stdout, so a rephrased message is a failing
 * slice even when it refuses the same input for the same reason.
 *
 * <h2>What is here so far — slices 1 to 6</h2>
 *
 * <ul>
 *   <li>{@link com.botmaker.cli.release.Module} — the ten modules a tag can be cut for, with the flag
 *       derived from the directory name. This package is the <i>owner</i> of that list, which is why it
 *       keeps one where {@code botmaker-dashboard}, a reader, deliberately does not.</li>
 *   <li>{@link com.botmaker.cli.release.Version} and {@link com.botmaker.cli.release.Level} — the
 *       {@code x.y.z} arithmetic of {@code bump}, ordered as {@code sort -V} orders it.</li>
 *   <li>{@link com.botmaker.cli.release.Tags} — {@code latest_version}: the newest tag a bump is computed
 *       off, fetched first so a release cut elsewhere is not invisible.</li>
 *   <li>{@link com.botmaker.cli.release.VersionSpec} — {@code resolve_version}, as a typed pair rather than
 *       a string every reader re-parses.</li>
 *   <li>{@link com.botmaker.cli.release.Git} — one external command. Only the five algorithms are ported;
 *       everything else stays a process.</li>
 *   <li>{@link com.botmaker.cli.release.Relevance} — {@code is_release_irrelevant}: the deny-list that keeps
 *       a markdown-only diff from cutting a tag whose artifact is byte-identical.</li>
 *   <li>{@link com.botmaker.cli.release.ChangeKind} — {@code change_kind}, three answers rather than a
 *       boolean, because <i>only docs</i> and <i>nothing at all</i> are different things to tell a
 *       maintainer.</li>
 *   <li>{@link com.botmaker.cli.release.ReleaseDecision} — {@code should_release}, carrying its
 *       {@code SKIP_REASON} in the answer instead of a global.</li>
 *   <li>{@link com.botmaker.cli.release.Forcing} — the {@code forced} flags as <b>data with a reason per
 *       edge</b>, which is the one place the port deliberately improves on the script: the reasons record
 *       bugs that shipped, and a shell comment cannot be shown to the operator asking why a module they did
 *       not name is in the plan.</li>
 *   <li>{@link com.botmaker.cli.release.Order} — the decide order and the tag order, which are two
 *       different orders and neither is {@code Module}'s own.</li>
 *   <li>{@link com.botmaker.cli.release.DepTag} — {@code dep_tag}: the ref a downstream pins, which is the
 *       version <i>this run</i> is cutting whenever there is one.</li>
 *   <li>{@link com.botmaker.cli.release.GateVerdict} and {@link com.botmaker.cli.release.GatePlan} — the
 *       gates' four outcomes (a gate that <i>could not run</i> is not a gate that failed) and which module
 *       gets which gate, all of them in the decide pass because a pushed tag cannot be edited.</li>
 *   <li>{@link com.botmaker.cli.release.CiDepsGate} — {@code check_ci_deps}, ported whole: it reads two
 *       files and answers, so there is nothing to shell to.</li>
 *   <li>{@link com.botmaker.cli.release.ChangelogGate} — {@code check_changelog}, which <b>invokes</b> each
 *       module's own {@code tools/changelog-section.sh} rather than reading the file: that extractor has two
 *       readers in two repositories, and a second implementation of it is precisely what it exists to
 *       prevent.</li>
 *   <li>{@link com.botmaker.cli.release.ForcingGate} and
 *       {@link com.botmaker.cli.release.FallbackVersionsGate} — {@code check_forced_but_unrequested} and
 *       {@code check_fallback_versions}, the two gates <b>added on 2026-09-06 rather than transcribed</b>,
 *       and the exception to this package's own rule that the port changes nothing. They exist because
 *       porting {@link com.botmaker.cli.release.Forcing} read the {@code decide} loop closely enough to
 *       notice that a forcing edge only ever lifted a <i>skip</i> and could not add a module — so every
 *       "an {@code --sdk} release forces Studio" in this project was documentation. It was already false
 *       in production when it was noticed. Both were written into {@code release.sh} in the same commit,
 *       word for word, so the cutover diff stays empty.</li>
 *   <li>{@link com.botmaker.cli.release.SdkGates} — {@code check_api_pointers} and
 *       {@code check_sdk_plugin}, both invocations: Maven runs one test, and the CLI's own shaded jar
 *       validates the SDK, because <i>this gate and {@code botmaker plugin validate} in an author's
 *       terminal are one program</i>.</li>
 *   <li>{@link com.botmaker.cli.release.JitpackPluginsGate} and
 *       {@link com.botmaker.cli.release.MavenPrerequisite} — {@code check_jitpack_plugins}, the one gate
 *       whose implementation moved rather than being invoked: it was an inline {@code python3} heredoc with
 *       no other reader, so porting it creates no second copy of anything and drops a dependency on
 *       {@code python3} being installed.</li>
 *   <li>{@link com.botmaker.cli.release.Proc} — one external command, which is what most of the script is
 *       and stays.</li>
 *   <li>{@link com.botmaker.cli.release.Runner} — <b>every side effect, behind one switch.</b> A dry run
 *       decides, gates and computes exactly as a real one does and echoes each command instead of running
 *       it, which is what makes {@code --dry-run} worth trusting. Nothing in this package may write,
 *       commit, tag or push except through it.</li>
 *   <li>{@link com.botmaker.cli.release.DepsEnv} — {@code write_deps_env}, including the {@code git add}
 *       whose absence tagged three modules with no {@code .deps.env} at all on 2026-09-02.</li>
 *   <li>{@link com.botmaker.cli.release.Stamp} — {@code stamp_changelog}, the half that makes {@code --all}
 *       usable: the version is not knowable while the prose is written, so it is stamped a moment before
 *       the tag.</li>
 *   <li>{@link com.botmaker.cli.release.CommitTagPush} — commit, tag, push, idempotently.</li>
 *   <li>{@link com.botmaker.cli.release.CleanRoom} — {@code resolve_clean_room}: a real
 *       {@code dependency:resolve} from a throwaway repository, which is the only thing that catches a
 *       published pom naming a dependency nobody can resolve.</li>
 *   <li>{@link com.botmaker.cli.release.Actions} — {@code poll_actions}, the worst verdict of every
 *       workflow a tag fired.</li>
 *   <li>{@link com.botmaker.cli.release.ReleaseLog} — {@code releases/<YYYY-MM-DD-HHMM>.md}, rendered
 *       whole every time so the two writers cannot leave a half-updated table.</li>
 *   <li>{@link com.botmaker.cli.release.ReleaseStatus} — {@code --status}, re-polling both columns through
 *       those same two readers.</li>
 * </ul>
 *
 * <p>{@link com.botmaker.cli.release.Release} is the whole run, {@link com.botmaker.cli.release.Plan} the
 * decide pass, {@link com.botmaker.cli.release.Gates} the gate loop,
 * {@link com.botmaker.cli.release.Umbrella} the pointer commit and the branch pushes, and
 * {@link com.botmaker.cli.release.Jitpack} the wait between tags. {@code botmaker release} is the terminal
 * caller.
 *
 * <p><b>What is left is partly code, and this paragraph said otherwise until 2026-09-06.</b> It claimed the
 * cutover test passed — that the script's {@code --dry-run} and this library's agree across the flag matrix
 * — and running that matrix for the first time found <b>fourteen combinations out of fourteen differ</b>.
 * {@code botmaker-cli/docs/release-port-divergences.md} is the whole list, with a verdict each.
 *
 * <p>The half that matters does agree, and it is worth saying in the same breath: every module chosen,
 * every version computed, every skip, every gate verdict and every refusal message is byte-identical,
 * including the two gates added the same day. What differs is narration, gate order and echo form.
 *
 * <p><b>And that matrix asked the wrong question of nine of its fourteen combinations (2026-09-16).</b>
 * Twelve of them cut a single module, which reaches almost none of the per-module write path. Re-run as
 * {@code --all --sdk 1.2.0} — the release actually about to be cut — it found <b>two things the script does
 * that this package did not do at all</b>, both landing inside a module's own release commit:
 * {@code bump_japicmp_baseline}, whose absence leaves both baselines naming a tag that is no longer the
 * previous release and so turns the never-delete gate into a silent pass, and the
 * {@code SDK_FALLBACK_VERSION} {@code sed}, whose absence makes every project a freshly released Studio
 * creates pin the <i>previous</i> SDK. They are {@link com.botmaker.cli.release.Japicmp} and
 * {@link com.botmaker.cli.release.Fallback} now.
 *
 * <p><b>The lesson is about the test, not about the two functions.</b> A stdout diff finds a line that is
 * worded differently; it does not find a write that nobody makes, because an omission prints nothing on
 * either side unless the run happens to be one the script narrates. The matrix has to include a run that
 * exercises every write path, which is what {@code --all} beside an explicit version is.
 *
 * <p><b>And the test itself has to be restated, because one divergence is a behaviour worth keeping.</b>
 * {@link com.botmaker.cli.release.Gates} runs every gate where the script stops at its first {@code die},
 * so an empty stdout diff is unreachable for any refusing run — which is five of the fourteen. The property
 * worth having was never the empty diff; it was <i>the decisions, the gate verdicts and the refusal texts
 * agree</i>, and the empty diff was a proxy for it. That is the condition to hold the cutover to.
 *
 * <p><b>Everything on that list is now closed except the last item (2026-09-16).</b> The pointer-commit
 * wording is settled — {@link com.botmaker.cli.release.Module#pointerName()} transcribes the script's own
 * eleven labels, inconsistency and all, so the umbrella's release history gains no seam. The changelog
 * stamp is an anchored first-match replacement rather than a whole-file rewrite, and
 * {@code SourceEditsTest} holds that it leaves every other byte alone — CRLF, tabs, a missing final
 * newline — which is the comparison the dry-run matrix could not make, because a dry run writes nothing.
 * The one-directional gaps are closed: echoes are shell-quoted, the push pass inspects for real in a dry
 * run, and the two dry-run lines the script prints and this did not now print.
 *
 * <p>So what remains is one thing: <b>cut a real release through here and watch it end to end</b>, before
 * {@code release.sh} is deleted. Until then a release from this package requires {@code --execute}, which
 * is the inverse of the script's default on purpose — the port is what is on trial, and a tag is permanent.
 *
 * <p><b>Nothing in this package has pushed anything yet, and that is now a fact about its callers rather
 * than about its code.</b> {@link com.botmaker.cli.release.CommitTagPush} can push; it is reached only
 * through a {@link com.botmaker.cli.release.Runner}, and no caller has yet handed it a real one. The plan's
 * discipline holds until then: the first tag cut by this library is a single-module release, watched end to
 * end.
 */
package com.botmaker.cli.release;
