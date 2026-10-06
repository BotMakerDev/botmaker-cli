package com.botmaker.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * {@code botmaker} — the entry point.
 *
 * <p><b>Two nouns and two verbs.</b> {@link PluginCommand} and {@link BotCommand} are the two things a
 * person creates here, each with its own {@code new} and {@code publish}; {@link DoctorCommand} and
 * {@link CompletionCommand} are about the tool itself and belong to neither.
 *
 * <p>The shape of the plugin verb set is the argument for the tool existing at all: {@code new} and
 * {@code publish} are the two ends of a plugin's life, and {@code validate} and {@code run} are the loop in
 * between. Each of them is something an author can do today by hand — {@code mvn archetype:generate} with
 * eight properties, {@code mvn install} then a pom edit then a Studio launch, a hand-written
 * {@code index.json} row — and each is something they will get wrong the first time in a way that produces
 * no error until much later.
 *
 * <p>Those four sat at the top level until 2026-09-05, where they meant <em>plugin</em> and said so nowhere:
 * a bot already had to spell its noun ({@code botmaker bot new}), so the bare {@code new} was the asymmetry.
 * The rename is a break, taken while the tool is v0.x rather than carried as aliases forever; the old paths
 * survive as {@link MovedCommand}, which runs nothing and names its replacement.
 *
 * <p><b>Exit codes are a contract, because CI reads them.</b> {@code 0} success, {@code 1} the thing failed,
 * {@code 2} the command line was wrong. A validation failure is {@code 1} and not {@code 2}: a plugin that
 * does not pass is a well-formed question with a bad answer. picocli's {@code ExitCode.USAGE} is already 2,
 * and a subcommand's returned {@code Integer} is the exit code, so the contract holds without being restated.
 *
 * <p><b>Why picocli, in a program that otherwise takes no dependency it can avoid.</b> This was hand-rolled
 * first — sixty lines of parsing, a usage text as a Java text block, and a table listing every option each
 * verb accepts. Those are <em>three statements of one fact</em>, and nothing made them agree: the first real
 * invocation of {@code new} passed an option that did not exist, which was silently ignored, generated a
 * project pinned to something else and said so nowhere. Here the usage text is the same annotated fields
 * that parse, so it cannot fall behind them, and an unrecognised option is refused with a suggestion rather
 * than dropped. The dependency is declared {@code optional} in the pom: the shaded jar carries it and the
 * plugin registry, which resolves the main artifact as a library, does not.
 */
@Command(
        name = "botmaker",
        header = "The BotMaker command.",
        description = "Two nouns: `plugin` is what extends Studio, `bot` is what you build with it. "
                + "Each can be created, tried and published.",
        mixinStandardHelpOptions = true,
        versionProvider = Main.ManifestVersion.class,
        synopsisSubcommandLabel = "<command>",
        subcommands = {PluginCommand.class, BotCommand.class, ReleaseCommand.class, DoctorCommand.class,
                CompletionCommand.class, MovedCommand.class})
public final class Main implements Callable<Integer> {

    /**
     * Suppresses progress on stderr. {@code INHERIT} so it may be written after the verb, where a user
     * naturally types it, rather than only before.
     */
    @Option(names = "--quiet", scope = ScopeType.INHERIT, description = "No progress on stderr.")
    private boolean quiet;

    @Option(names = "--debug", scope = ScopeType.INHERIT,
            description = "Print stack traces as well as the message.")
    private boolean debug;

    private Console console;
    private Mvn mvn;
    private Subjects subjects;

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] argv) {
        Main main = new Main();
        return new CommandLine(main)
                .setExecutionExceptionHandler(main.exceptions())
                .execute(argv);
    }

    /** No verb: the usage text, and exit 2 — a bare invocation is a command line that said nothing. */
    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return CommandLine.ExitCode.USAGE;
    }

    // The three collaborators, built once and lazily, because --quiet is not known until parsing is done.

    Console console() {
        if (console == null) {
            console = new Console(quiet);
        }
        return console;
    }

    Mvn mvn() {
        if (mvn == null) {
            mvn = new Mvn(console());
        }
        return mvn;
    }

    Subjects subjects() {
        if (subjects == null) {
            subjects = new Subjects(console(), mvn());
        }
        return subjects;
    }

    /**
     * The message, not the stack.
     *
     * <p>Every {@link IOException} thrown in this program is thrown with a sentence written for the person
     * reading it, and a trace on top of that sentence buries it. {@code --debug} is how you get the trace
     * when the sentence is not enough. Anything else is a defect and prints in full.
     */
    private CommandLine.IExecutionExceptionHandler exceptions() {
        return (ex, commandLine, parseResult) -> {
            if (ex instanceof IOException) {
                console().error(ex.getMessage());
                if (debug) {
                    ex.printStackTrace();
                }
            } else {
                console().error(String.valueOf(ex));
                ex.printStackTrace();
            }
            return 1;
        };
    }

    /**
     * The version, out of the jar's manifest.
     *
     * <p>{@code (dev)} when there is no manifest, which is what an IDE launch and a test run both look like —
     * and equally when the manifest says {@code dev}, which is what an unreleased build of the executable
     * jar carries. Not read from a generated constant, and <em>not</em> from {@code ${project.version}}:
     * this project's poms carried the cosmetic {@code 0.0.0-SNAPSHOT} until 2026-10-06, and on {@code main}
     * still carry a {@code -SNAPSHOT}, so either would name no release. The release job passes the tag as
     * {@code -Dbotmaker.cli.version}, which the shade plugin writes into {@code Implementation-Version};
     * see the property in the pom.
     */
    static final class ManifestVersion implements IVersionProvider {
        @Override
        public String[] getVersion() {
            String implementation = Main.class.getPackage().getImplementationVersion();
            boolean released = implementation != null && !implementation.isBlank()
                    && !implementation.equals("dev") && !implementation.endsWith("-SNAPSHOT");
            return new String[]{"botmaker " + (released ? implementation : "(dev)")};
        }
    }
}
