package com.botmaker.cli;

import com.botmaker.cli.catalog.BotChecks;
import com.botmaker.cli.catalog.EntryGate;
import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.catalog.Finding;
import com.botmaker.cli.publish.CatalogPr;
import com.botmaker.cli.publish.Checks;
import com.botmaker.cli.publish.Listener;
import com.botmaker.cli.publish.PublishTarget;
import com.botmaker.cli.validate.CheckResult;
import com.botmaker.cli.validate.PluginSubject;
import com.botmaker.cli.validate.PluginValidator;

import java.io.IOException;
import java.util.List;

/**
 * A publish's checks on this machine: {@code plugin validate}'s on a plugin's folder and on its published jar, the
 * catalog's rules 6–8 on a bot's folder. The validator runs first and a failure stops the publish: the check that
 * refuses the pull request is the check that refused to open it.
 */
final class CliChecks implements Checks {

    private final Main main;
    private final CatalogPr.Reader catalog;

    CliChecks(Main main, CatalogPr.Reader catalog) {
        this.main = main;
        this.catalog = catalog;
    }

    @Override
    public Result folder(PublishTarget target, Listener listener) {
        try {
            if (target.kind() == EntryPath.Kind.PLUGIN) {
                return validated(main.subjects().fromDirectory(target.dir(), true), listener,
                        "the plugin does not pass its own checks (above).");
            }
            List<Finding> findings = BotChecks.checkFolder(target.dir(), EntryPath.bot(target.repo()),
                    EntryGate.pluginRepos(catalog.catalog()), main.mvn());
            List<String> errors = findings.stream().filter(f -> f.severity() == Finding.Severity.ERROR)
                    .map(Finding::message).toList();
            findings.stream().filter(f -> f.severity() != Finding.Severity.ERROR).forEach(f -> listener.warn(f.message()));
            return errors.isEmpty() ? Result.passed() : Result.refused(String.join("\n", errors));
        } catch (IOException e) {
            return Result.refused(e.getMessage());
        }
    }

    @Override
    public Result published(String coordinate, Listener listener) {
        try {
            return validated(main.subjects().fromCoordinate(coordinate), listener,
                    "the published jar does not pass the plugin's checks (above).");
        } catch (IOException e) {
            return Result.refused("nobody can download " + coordinate + ": " + e.getMessage());
        }
    }

    private static Result validated(PluginSubject subject, Listener listener, String failure) {
        List<CheckResult> results = PluginValidator.validate(subject);
        listener.checks(results);
        return PluginValidator.passed(results) ? Result.passed() : Result.refused(failure);
    }
}
