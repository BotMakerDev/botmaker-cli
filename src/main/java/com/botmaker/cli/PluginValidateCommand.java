package com.botmaker.cli;

import com.botmaker.cli.validate.CheckResult;
import com.botmaker.cli.validate.PluginSubject;
import com.botmaker.cli.validate.PluginValidator;
import com.botmaker.cli.validate.Status;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code botmaker plugin validate} — the eight checks, against a working copy or a published coordinate.
 *
 * <p>This class is a <em>front end</em>: it resolves a subject, calls
 * {@link PluginValidator#validate(PluginSubject)} and prints. Every rule it appears to enforce lives in that
 * library, because the registry's CI calls the library directly and a rule implemented here would be a rule
 * the author sees and the gate does not.
 */
@Command(name = "validate",
        header = "Run the eight checks the plugin registry runs.",
        description = "A local pass is not a promise the pull request passes: two checks ask whether an id "
                + "is already claimed, and only the registry's index holds those answers.",
        mixinStandardHelpOptions = true)
final class PluginValidateCommand implements Callable<Integer> {

    @ParentCommand
    private PluginCommand parent;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<dir>",
            description = "The plugin project. Default: the working directory")
    private String dirArgument;

    @Option(names = "--dir", description = "The plugin project, as an option rather than a position")
    private String dirOption;

    @Option(names = "--coordinate", paramLabel = "<G:A:V>",
            description = "Validate a PUBLISHED artifact instead of a working copy. The one that catches a "
                    + "plugin which builds on your machine and publishes a pom nobody else can resolve.")
    private String coordinate;

    @Option(names = "--no-build", description = "Trust the existing target/classes.")
    private boolean noBuild;

    @Override
    public Integer call() throws IOException {
        Console console = parent.main().console();
        PluginSubject subject;
        if (coordinate != null) {
            subject = parent.main().subjects().fromCoordinate(coordinate, Set.of());
        } else {
            String directory = dirArgument != null ? dirArgument : dirOption != null ? dirOption : ".";
            subject = parent.main().subjects()
                    .fromDirectory(Path.of(directory).toAbsolutePath().normalize(), !noBuild);
        }

        List<CheckResult> results = PluginValidator.validate(subject);
        console.report(results);

        boolean passed = PluginValidator.passed(results);
        console.out("");
        if (!passed) {
            console.out(results.stream().filter(CheckResult::failed).count() + " check(s) failed.");
            return 1;
        }
        // Said on every pass, and not as a formality. Two of the eight checks ask whether an id is already
        // claimed, and locally nothing is claimed — the registry's index is what holds those answers. An
        // author who reads "all checks passed" as "the PR will be accepted" has been misled by this tool.
        console.out("All checks passed"
                + (results.stream().anyMatch(r -> r.status() == Status.SKIP) ? " (some were skipped)." : ".")
                + " Collisions with plugins already in the registry are checked by the registry's own CI,"
                + " which runs this same code against the index.");
        return 0;
    }
}
