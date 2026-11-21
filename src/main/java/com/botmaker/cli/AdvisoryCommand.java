// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli;

import com.botmaker.cli.catalog.Advisory;
import com.botmaker.cli.catalog.GitHubFacts;
import com.botmaker.cli.publish.CatalogPr;
import com.botmaker.cli.publish.Listener;
import com.botmaker.cli.publish.PublishHost;
import com.botmaker.cli.publish.Refusal;
import com.botmaker.cli.publish.StepRunner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker advisory new|extend|withdraw} — an admin's pull requests filing, extending or withdrawing a
 * security advisory in the catalog (spec 2026-11-21 § 5). Studio refuses an affected release until its user runs it
 * anyway; the catalog's check holds every rule again, this command only says them sooner.
 */
@Command(name = "advisory",
        header = "File, extend or withdraw a security advisory (admins only).",
        description = "Opens the catalog pull request for advisories/<id>.json. An advisory is never deleted.",
        mixinStandardHelpOptions = true,
        subcommands = {AdvisoryCommand.New.class, AdvisoryCommand.Extend.class, AdvisoryCommand.Withdraw.class})
final class AdvisoryCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Override
    public Integer call() {
        main.console().error("say `new`, `extend` or `withdraw`; `botmaker advisory --help` lists them.");
        return 2;
    }

    @Command(name = "new", header = "File an advisory against releases of one entry.", mixinStandardHelpOptions = true)
    static final class New implements Callable<Integer> {
        @ParentCommand
        private AdvisoryCommand parent;

        @Option(names = "--repo", paramLabel = "<owner/name>", required = true, description = "The entry's repository.")
        private String repo;

        @Option(names = "--release", paramLabel = "<tag>", required = true, split = ",",
                description = "An affected release; repeat or separate by commas.")
        private List<String> releases;

        @Option(names = "--severity", paramLabel = "<low|medium|high|critical>", required = true,
                description = "How bad it is.")
        private String severity;

        @Option(names = "--summary", paramLabel = "<text>", required = true,
                description = "What is wrong, in 1 to 300 characters, shown in Studio's refusal.")
        private String summary;

        @Option(names = "--url", paramLabel = "<https link>", description = "Where it is described.")
        private String url;

        @Override
        public Integer call() {
            Advisory.Severity level = Advisory.Severity.fromId(severity);
            if (level == Advisory.Severity.UNKNOWN) {
                parent.main.console().error("--severity is one of low, medium, high, critical.");
                return 2;
            }
            return parent.open("botmaker advisory new", (account, facts, host, listener) ->
                    CatalogPr.fileAdvisory(repo, releases, level, summary,
                            Optional.ofNullable(url), LocalDate.now(ZoneOffset.UTC), account, "botmaker advisory new",
                            CatalogPr.Reader.of(facts), host::pullRequest, listener));
        }
    }

    @Command(name = "extend", header = "Name more releases in an advisory.", mixinStandardHelpOptions = true)
    static final class Extend implements Callable<Integer> {
        @ParentCommand
        private AdvisoryCommand parent;

        @Parameters(index = "0", paramLabel = "<id>", description = "The advisory, BMA-<year>-<number>.")
        private String id;

        @Option(names = "--release", paramLabel = "<tag>", required = true, split = ",",
                description = "A release it also affects; repeat or separate by commas.")
        private List<String> releases;

        @Override
        public Integer call() {
            return parent.open("botmaker advisory extend", (account, facts, host, listener) ->
                    CatalogPr.changeAdvisory(id, releases, Optional.empty(), account, "botmaker advisory extend",
                            CatalogPr.Reader.of(facts), host::pullRequest, listener));
        }
    }

    @Command(name = "withdraw", header = "Withdraw an advisory, with a reason.", mixinStandardHelpOptions = true)
    static final class Withdraw implements Callable<Integer> {
        @ParentCommand
        private AdvisoryCommand parent;

        @Parameters(index = "0", paramLabel = "<id>", description = "The advisory, BMA-<year>-<number>.")
        private String id;

        @Option(names = "--reason", paramLabel = "<text>", required = true,
                description = "Why it no longer holds, in 1 to 300 characters.")
        private String reason;

        @Override
        public Integer call() {
            return parent.open("botmaker advisory withdraw", (account, facts, host, listener) ->
                    CatalogPr.changeAdvisory(id, List.of(), Optional.of(reason), account, "botmaker advisory withdraw",
                            CatalogPr.Reader.of(facts), host::pullRequest, listener));
        }
    }

    private interface Opening {
        Optional<String> open(PublishHost.Account account, GitHubFacts facts, PublishHost host, Listener listener)
                throws Refusal;
    }

    private int open(String command, Opening opening) {
        Console console = main.console();
        Listener listener = PublishCommands.listener(console);
        PublishHost host = PublishHost.commands(Path.of("."), StepRunner.system(listener), listener,
                BotExport.maven(main.mvn()));
        GitHubFacts facts = GitHubFacts.anonymousOrToken();
        try {
            PublishHost.Account account = host.account()
                    .orElseThrow(() -> new Refusal("`gh` is not signed in; run `gh auth login` first."));
            opening.open(account, facts, host, listener).ifPresent(console::out);
        } catch (Refusal e) {
            console.error(e.getMessage());
            return 1;
        }
        console.step(command + ": the catalog merges it once its checks pass on the pull request.");
        return 0;
    }
}
