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
 *   <li>{@link com.botmaker.cli.release.Module} — the fifteen modules a tag can be cut for, with the flag
 *       derived from the directory name. This package is the <i>owner</i> of that list, which is why it
 *       keeps one where {@code botmaker-dashboard}, a reader, deliberately does not. What a module is
 *       <i>exempt</i> from is asked of it ({@code mavenBuild}, {@code onJitpack}, {@code hasChangelog},
 *       {@code commitsOnRelease}, {@code template}) and never decided by naming it.</li>
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
 *       workflow a tag fired, and since 2026-09-16 the error lines of each failed run's log.</li>
 *   <li>{@link com.botmaker.cli.release.CiGate} — no script counterpart (2026-09-16): refuses a module whose
 *       newest finished CI run on {@code main} is red.</li>
 *   <li>{@link com.botmaker.cli.release.ReleaseLog} — {@code releases/<YYYY-MM-DD-HHMM>.md}, rendered
 *       whole every time so the two writers cannot leave a half-updated table. Since 2026-09-16 it is
 *       written before the first tag with a per-row stage, and a release that throws is recorded rather
 *       than lost ({@code Release.tagChain}).</li>
 *   <li>{@link com.botmaker.cli.release.ReleaseStatus} — {@code --status}, re-polling both columns through
 *       those same two readers.</li>
 *   <li>{@link com.botmaker.cli.release.TemplatePin} and {@link com.botmaker.cli.release.TemplateGate} — no
 *       script counterpart (2026-09-21): the worked bot, {@code --gamebot}. See below.</li>
 * </ul>
 *
 * <h2>The templates, which the script never released (2026-09-21)</h2>
 *
 * <p><b>{@code botmaker-gamebot} is the only published thing a release did not touch, and it went stale
 * silently.</b> It is the template <i>New project from a template</i> copies; its pom pins one released SDK,
 * by hand. On 2026-09-21 its source was migrated to {@code @Param}, {@code @Managed} and a flow written in
 * Java while the pin still said {@code 1.1.9} — the template did not compile at its own pin for a day —
 * and a working copy of it also declared {@code botmaker-plugin-toolkit} beside the SDK that brings it, so
 * Maven's nearest-wins pinned the toolkit four contract releases back and opening the project died on
 * {@code com/botmaker/plugin/api/ValueContext}.
 *
 * <p>{@link com.botmaker.cli.release.TemplatePin} is {@link com.botmaker.cli.release.Fallback} for the other
 * bot pom this project owns. {@code Fallback} rewrites what a <i>freshly generated</i> bot pins
 * ({@code MavenService.SDK_FALLBACK_VERSION}, Studio's source); this rewrites what a bot copied <i>from the
 * template</i> pins, which is the template's own pom. Same anchored regex, same rule: it rewrites only when
 * the run is cutting the module it names, and {@link com.botmaker.cli.release.Runner#replace} refuses when
 * the pattern stops matching, so a bump that quietly moved nothing is impossible.
 *
 * <p><b>{@link com.botmaker.cli.release.TemplateGate} compiles the template rather than comparing its
 * pin</b>, because a pin comparison would have caught neither failure above. It runs whenever the release
 * cuts the SDK <b>or</b> a template — which is what a forcing edge would otherwise have been, and
 * deliberately is not: an SDK patch must not demand a template version, but an SDK release is exactly the
 * moment an untouched template can stop compiling. It is asked of a <i>directory</i>, so it covers
 * {@code botmaker-base} too, which names no SDK and so has no pin and no flag.
 *
 * <p>A template takes neither CI gate ({@code botmaker-gamebot} has no {@code .github/workflows} at all), is
 * out of the JitPack gates through {@code onJitpack()} and out of the changelog gate through
 * {@code hasChangelog()} — and it still <b>commits</b> on release, which is why
 * {@code Module.commitsOnRelease()} exists: the release commit was derived from {@code hasChangelog()}, true
 * for everything that had something to commit until a module arrived with no changelog and a pom pin to
 * rewrite.
 *
 * <p>{@link com.botmaker.cli.release.Order} puts it last in both orders: its pom pins tags this run is
 * cutting, so every one of them must be on origin before anybody clones the template — Studio's own reason,
 * one door further out. {@code botmaker-dashboard}'s Release tab is the one caller that hides it, and not by
 * declining a flag: a template is published as a <i>bot</i>, so its fast update sits on its row in the
 * Catalog tab, reaching this same library.
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
 * <p>So what remains is one thing: <b>cut a real release through here and watch it end to end</b>. A release
 * from this package requires {@code --execute}, which is the inverse of the script's default on purpose —
 * the port is what is on trial, and a tag is permanent.
 *
 * <p><b>Nothing in this package has pushed anything yet, and that is now a fact about its callers rather
 * than about its code.</b> {@link com.botmaker.cli.release.CommitTagPush} can push; it is reached only
 * through a {@link com.botmaker.cli.release.Runner}, and no caller has yet handed it a real one.
 *
 * <p><b>All three callers reach it now (2026-09-16), and the script is one of them.</b> {@code release.sh}
 * is a ~230-line wrapper: it owns the spelling, {@code --ci}'s runner preparation, the
 * {@code BOTMAKER_RELEASE_TOKEN} precondition and one line of translation — no {@code --dry-run} means
 * {@code --execute}, because a script whose purpose is cutting a release should not need a flag to do it
 * while a command anyone can type should not push a tag by accident. {@code .github/workflows/release.yml}
 * calls that script for {@code --ci} alone; {@code botmaker-dashboard} calls {@link
 * com.botmaker.cli.release.Release#run} in-process.
 *
 * <p><b>Which retires the diff, and the reason is worth keeping.</b> The port was verified against the
 * script's {@code --dry-run}; with the script a wrapper there is no second implementation to disagree with,
 * so that check is gone and cannot be brought back. What replaces it is the property this package was built
 * on: a preview and a release are this same call with a different {@link com.botmaker.cli.release.Runner},
 * and {@code Runner} is the only class here that writes, commits, tags or pushes. A preview is the release,
 * less its writes. Keep it that way — a write that reaches the file system by some other route makes a
 * preview a description of the code rather than a rehearsal of it.
 */
package com.botmaker.cli.release;
