package com.botmaker.cli.release;

import java.nio.file.Path;
import java.util.List;

/**
 * The two gates that only run when the SDK is being cut — {@code release.sh}'s {@code check_api_pointers}
 * and {@code check_sdk_plugin}.
 *
 * <p>Both are <b>invocations</b>: Maven runs one test, and the CLI's own shaded jar validates the SDK. What
 * is ported is where they run, what they refuse, and how they degrade — not what they check.
 *
 * <p><b>Both skip with a line when {@code mvn} is absent, and that is deliberate rather than lenient.</b> A
 * gate that cannot run must say so; passing silently is the failure mode that let three releases report
 * {@code UNVERIFIED} while six of eight published modules were unresolvable.
 */
public final class SdkGates {

    /** Lines from the tool's output that a refusal quotes back, capped as the script caps them. */
    private static final int QUOTED = 20;

    private SdkGates() {
    }

    /**
     * {@code check_api_pointers}: the SDK's own {@code ApiPointersTest}, run against the version being cut.
     *
     * <p>It asks only whether a redirect somebody <b>declared</b> is complete — never whether a break was
     * covered, which is a supported outcome. The version reaches only the message: this pass passed it as
     * {@code -Dbotmaker.api.maxVersion} until 2026-08-27, when the {@code @Replaces} back edge it bounded
     * was deleted and nothing was left to read it. Adding it back here would be a flag that changes
     * nothing today and refuses something surprising the day somebody reads the property again.
     */
    public static GateVerdict apiPointers(Path umbrella, Version version, boolean force) {
        if (!Proc.onPath("mvn")) {
            return GateVerdict.skipped("  sdk: mvn not on PATH — pointer gate skipped");
        }
        Path pom = umbrella.resolve(Module.SDK.directory()).resolve("pom.xml");
        Proc.Result run = Proc.run(umbrella, "mvn", "-B", "-q", "-f", pom.toString(), "test",
                "-Dtest=ApiPointersTest", "-DfailIfNoTests=false");
        if (run.ok()) {
            return GateVerdict.ok("  sdk: @ReplacedBy pointers complete for v" + version + " — ok");
        }
        if (force) {
            return GateVerdict.forced("  sdk: pointer gate failed — FORCED");
        }
        return GateVerdict.refused("sdk: the redirect pointers do not hold for v" + version + ".\n"
                + "     A bot upgrading past this release would default + mark the calls this was meant to"
                + " carry across.\n"
                + "     Fix the annotations, or --force.\n\n"
                + errors(run));
    }

    /**
     * {@code check_sdk_plugin}: the plugin registry's own validator, run over the SDK.
     *
     * <p>The SDK is Studio's plugin #1 with no exemption — <i>a rule the host's own plugin breaks is a rule
     * the gate cannot enforce on anybody else</i> — and until 2026-09-02 nobody had ever run it on the SDK.
     * When somebody did, {@code pom-scopes} failed.
     *
     * <p><b>It runs the shaded jar rather than the library, deliberately.</b>
     * {@code com.botmaker.cli.validate} is handed a {@code PluginSubject} of resolved facts, so the half
     * that turns a directory into a subject — Maven, the classpath, the pom — belongs to the command.
     * Running the command is the only way to exercise what a plugin author actually runs, which is the whole
     * point: this gate and {@code botmaker plugin validate} in an author's terminal are one program.
     *
     * <p><b>And it rebuilds that jar every run, which is not a convenience.</b> {@code PluginLoader} is
     * parent-first for {@code com.botmaker.plugin.api.**}, so the contract the plugin links against is the
     * host's — this jar's — and never the plugin's own resolved classpath. The jar <i>is</i> the contract
     * under test. Until 2026-09-21 this built only when the jar was <b>absent</b>, and a jar two days old,
     * still carrying {@code ValueShape} from before the shape axis was deleted, failed {@code value-types}
     * with {@code NoClassDefFoundError: …/ValueContainer} — a refusal that read as a defect in the SDK and
     * was a defect in the gate's own tooling. {@code release.sh} rebuilds on every run and never showed it;
     * the dashboard calls this library in-process and did. The reactor form ({@code -pl … -am} from the
     * umbrella, the line {@code release.sh} uses) is the point of the rebuild: {@code -f botmaker-cli/pom.xml}
     * would resolve studio-api and plugin-host from {@code ~/.m2} and shade a contract nobody in this
     * checkout wrote.
     */
    public static GateVerdict sdkPlugin(Path umbrella, boolean force) {
        if (!Proc.onPath("mvn")) {
            return GateVerdict.skipped("  sdk: mvn not on PATH — plugin gate skipped");
        }
        Path cli = umbrella.resolve(Module.CLI.directory());
        Path jar = cli.resolve("target/botmaker-cli-0.0.0-SNAPSHOT-all.jar");
        Proc.Result build = Proc.run(umbrella, "mvn", "-B", "-q",
                "-pl", Module.CLI.directory(), "-am", "package", "-DskipTests");
        if (!build.ok()) {
            if (force) {
                return GateVerdict.forced("  sdk: botmaker-cli would not build — plugin gate FORCED");
            }
            return GateVerdict.refused("sdk: botmaker-cli will not build, so the plugin gate cannot"
                    + " run.\n"
                    + "     It is the same validator the plugin registry runs on every submission, and"
                    + " the SDK is plugin #1.\n"
                    + "     Fix the CLI build, or --force.\n\n"
                    + errors(build));
        }
        // `plugin validate`, not `validate`: the noun-first tree landed 2026-09-05 and the bare verb is a
        // hidden MovedCommand that prints its replacement and exits 2. A gate spelling it the old way would
        // refuse every SDK release, with a message about a command line rather than about the SDK.
        Proc.Result validate = Proc.run(umbrella, "java", "-jar", jar.toString(),
                "plugin", "validate", umbrella.resolve(Module.SDK.directory()).toString());
        if (validate.ok()) {
            return GateVerdict.ok("  sdk: botmaker plugin validate passes — ok");
        }
        if (force) {
            return GateVerdict.forced("  sdk: botmaker plugin validate failed — FORCED");
        }
        return GateVerdict.refused("sdk: botmaker plugin validate refuses this build of botmaker-sdk.\n"
                + "     These are the checks the plugin registry runs on every third-party submission, and"
                + " the SDK is\n"
                + "     plugin #1 with no exemption — a rule the host's own plugin breaks is a rule the gate"
                + " cannot enforce\n"
                + "     on anybody else. Fix it, or --force.\n\n"
                + fromFail(validate));
    }

    /** The {@code [ERROR]} lines a Maven failure is quoted by. */
    private static String errors(Proc.Result run) {
        return String.join("\n", run.out().lines()
                .filter(line -> line.contains("[ERROR]"))
                .limit(QUOTED)
                .toList());
    }

    /** The validator's output from its first {@code FAIL} onwards, as the script's {@code sed} takes it. */
    private static String fromFail(Proc.Result run) {
        List<String> lines = run.out().lines().toList();
        int start = 0;
        while (start < lines.size() && !lines.get(start).contains("FAIL")) {
            start++;
        }
        return start == lines.size() ? ""
                : String.join("\n", lines.subList(start, Math.min(lines.size(), start + QUOTED)));
    }
}
