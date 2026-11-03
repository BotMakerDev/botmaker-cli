package com.botmaker.cli;

import com.botmaker.cli.publish.Listener;
import com.botmaker.cli.publish.StepRunner;
import com.botmaker.cli.reports.GitHubGet;
import com.botmaker.cli.reports.IssueReports;
import com.botmaker.cli.reports.Reports;
import com.botmaker.cli.reports.ReportsText;
import com.botmaker.cli.reports.RunReports;
import com.botmaker.cli.validate.CheckResult;
import com.botmaker.runlog.ReportTarget;
import com.botmaker.runlog.api.Level;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker reports} — a bot's problems, counted: from its GitHub issues, every state, through
 * {@link IssueReports} as the account {@code gh} is signed in to, or from run logs on this computer.
 */
@Command(name = "reports",
        header = "Count a bot's reported problems.",
        description = "Reads the issues BotMaker opened on the bot's repository (its upstream remote, else "
                + "origin, else --repo), or run logs (--run), and counts their problems by one dimension.",
        mixinStandardHelpOptions = true)
final class ReportsCommand implements Callable<Integer> {

    @ParentCommand
    private Main parent;

    static final class Source {
        @Option(names = "--repo", paramLabel = "<owner/repo>", description = "The bot's GitHub repository.")
        String repo;

        @Option(names = "--run", paramLabel = "<folder>",
                description = "A run.jsonl, a run's folder, or a folder of runs (target/botmaker-runs).")
        Path run;
    }

    @ArgGroup(exclusive = true)
    private Source source = new Source();

    @Option(names = "--group-by", paramLabel = "<dimension>",
            description = "What to count by, a dimension the reports list; the first one when not given.")
    private String groupBy;

    @Option(names = "--level", paramLabel = "warn|error", description = "Only this level.")
    private String level;

    @Option(names = "--since", paramLabel = "<yyyy-mm-dd>", description = "Only problems met from this day on (UTC).")
    private String since;

    @Option(names = "--json", description = "Answer in JSON.")
    private boolean json;

    @Override
    public Integer call() {
        Console console = parent.console();
        Level only = null;
        if (level != null) {
            only = Level.fromId(level);
            if (!only.problem()) {
                console.error("--level takes warn or error, not " + level + ".");
                return 2;
            }
        }
        Instant from = null;
        if (since != null) {
            try {
                from = LocalDate.parse(since.strip()).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException e) {
                console.error("--since takes a day as yyyy-mm-dd, not " + since + ".");
                return 2;
            }
        }
        Reports reports;
        if (source.run != null) {
            reports = RunReports.read(source.run);
        } else {
            Optional<ReportTarget> target = source.repo != null ? ReportTarget.parse(source.repo)
                    : ReportTarget.of(Path.of("").toAbsolutePath());
            if (target.isEmpty()) {
                console.error(source.repo != null ? source.repo + " is not owner/repo."
                        : "this folder has no GitHub remote (upstream or origin): name one with --repo, or read "
                                + "run logs with --run.");
                return 2;
            }
            reports = new IssueReports(GitHubGet.gh(StepRunner.system(SILENT)), IssueReports.defaultCache())
                    .read(target.get());
        }
        reports = reports.filtered(only, from);
        try {
            String answer = json ? ReportsText.json(reports, groupBy) : ReportsText.text(reports, groupBy);
            System.out.print(answer);
        } catch (IllegalArgumentException e) {
            console.error(e.getMessage());
            return 2;
        }
        return 0;
    }

    /** {@code gh api} is only read here: nothing to echo. */
    private static final Listener SILENT = new Listener() {
        @Override
        public void step(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void checks(List<CheckResult> results) {
        }
    };
}
