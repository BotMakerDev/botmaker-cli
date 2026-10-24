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
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.host.PluginLoader;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
                    EntryGate.pluginIds(catalog.catalog()), main.mvn());
            List<String> errors = findings.stream().filter(f -> f.severity() == Finding.Severity.ERROR)
                    .map(Finding::message).toList();
            findings.stream().filter(f -> f.severity() != Finding.Severity.ERROR).forEach(f -> listener.warn(f.message()));
            return errors.isEmpty() ? Result.passed(Optional.empty()) : Result.refused(String.join("\n", errors));
        } catch (IOException e) {
            return Result.refused(e.getMessage());
        }
    }

    @Override
    public Result published(String coordinate, Listener listener) {
        try {
            return validated(main.subjects().fromCoordinate(coordinate, Set.of()), listener,
                    "the published jar does not pass the plugin's checks (above).");
        } catch (IOException e) {
            return Result.refused("nobody can download " + coordinate + ": " + e.getMessage());
        }
    }

    private static Result validated(PluginSubject subject, Listener listener, String failure) {
        List<CheckResult> results = PluginValidator.validate(subject);
        listener.checks(results);
        if (!PluginValidator.passed(results)) return Result.refused(failure);
        return Result.passed(pluginId(subject, listener));
    }

    /** The plugin's own id, which names its entry. */
    private static Optional<String> pluginId(PluginSubject subject, Listener listener) {
        try (PluginLoader loaded =
                     PluginLoader.open(subject.classpath().stream().map(Object::toString).toList())) {
            // The validator has already refused an empty load. The FIRST is the entry's subject: a jar contributing
            // two is legal and rare, and picking silently would be worse than the warning.
            List<StudioPlugin> plugins = loaded == null ? List.of() : loaded.plugins();
            if (plugins.size() > 1) {
                listener.warn("this jar contributes " + plugins.size() + " plugins; the entry lists the first, "
                        + plugins.getFirst().id());
            }
            return plugins.stream().findFirst().map(StudioPlugin::id);
        }
    }
}
