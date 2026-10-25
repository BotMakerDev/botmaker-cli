package com.botmaker.cli;

import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.catalog.GitHubFacts;
import com.botmaker.cli.publish.CatalogPr;
import com.botmaker.cli.publish.Listener;
import com.botmaker.cli.publish.NextVersion;
import com.botmaker.cli.publish.Publish;
import com.botmaker.cli.publish.PublishHost;
import com.botmaker.cli.publish.PublishRequest;
import com.botmaker.cli.publish.Refusal;
import com.botmaker.cli.publish.StepRunner;
import com.botmaker.cli.release.Jitpack;
import com.botmaker.cli.release.Level;
import com.botmaker.cli.validate.CheckResult;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What {@code plugin publish}, {@code bot publish} and the two {@code unpublish} commands share: the library
 * ({@link Publish}, {@link CatalogPr}) wired to this machine and its outcome printed.
 */
final class PublishCommands {

    private PublishCommands() {}

    /** {@code [patch|minor|major]}, refused in any other spelling. */
    static final class LevelConverter implements CommandLine.ITypeConverter<Level> {
        @Override
        public Level convert(String value) {
            return Level.of(value.strip()).orElseThrow(() -> new CommandLine.TypeConversionException(
                    "'" + value + "' is not patch, minor or major"));
        }
    }

    /** Runs {@code request}; exit code 0 when the pull request is open, the tag is listed already, or a dry run passed. */
    static int publish(Main main, PublishRequest request) {
        Console console = main.console();
        Listener listener = listener(console);
        GitHubFacts facts = GitHubFacts.anonymousOrToken();
        CatalogPr.Reader catalog = CatalogPr.Reader.of(facts);
        Publish publish = new Publish(facts, catalog, new CliChecks(main, catalog), Jitpack.Probe.real(),
                Jitpack.Sleeper.real());
        return switch (publish.run(request, listener,
                PublishHost.commands(request.dir(), StepRunner.system(listener), listener,
                        BotExport.maven(main.mvn())))) {
            case Publish.Outcome.Opened opened -> {
                opened.pullRequest().ifPresent(console::out);
                console.step(opened.entry().repo() + " " + opened.tag()
                        + " is listed once the catalog's checks pass on the pull request.");
                yield 0;
            }
            case Publish.Outcome.AlreadyListed listed -> {
                console.step(listed.repo() + " " + listed.tag() + " is already listed in the catalog.");
                yield 0;
            }
            case Publish.Outcome.WouldList would -> {
                console.out("would list " + would.pluginId().map(id -> id + " ").orElse("") + would.repo() + " "
                        + would.tag() + (would.listing() == NextVersion.Listing.NEW
                        ? " (tagged and released first)" : " (released already)"));
                yield 0;
            }
            case Publish.Outcome.Refused refused -> {
                console.error(refused.reason());
                yield 1;
            }
        };
    }

    /** The pull request removing {@code path}'s entry, or only its release {@code release}. */
    static int unpublish(Main main, EntryPath path, Optional<String> release, String command) {
        Console console = main.console();
        Listener listener = listener(console);
        PublishHost host = PublishHost.commands(Path.of("."), StepRunner.system(listener), listener,
                BotExport.maven(main.mvn()));
        GitHubFacts facts = GitHubFacts.anonymousOrToken();
        try {
            PublishHost.Account account = host.account()
                    .orElseThrow(() -> new Refusal("`gh` is not signed in; run `gh auth login` first."));
            CatalogPr.unpublish(path, release, account, command, facts, CatalogPr.Reader.of(facts), host::pullRequest,
                            listener)
                    .ifPresent(console::out);
        } catch (Refusal e) {
            console.error(e.getMessage());
            return 1;
        }
        console.step(release.map(tag -> path.key() + " " + tag).orElse(path.key())
                + " leaves the catalog once its checks pass on the pull request.");
        return 0;
    }

    /** Progress and warnings on stderr; the check report aside, so stdout holds only the answer. */
    static Listener listener(Console console) {
        return new Listener() {
            @Override
            public void step(String message) {
                console.step(message);
            }

            @Override
            public void warn(String message) {
                console.warn(message);
            }

            @Override
            public void checks(List<CheckResult> results) {
                console.reportAside(results);
            }
        };
    }
}
