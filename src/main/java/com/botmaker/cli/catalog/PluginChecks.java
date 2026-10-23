package com.botmaker.cli.catalog;

import com.botmaker.cli.Subjects;
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
 * A plugin's own rules, on what JitPack serves at its tag — what Studio will resolve:
 *
 * <ol start="6">
 *   <li>{@link PluginValidator}'s checks pass (pom scopes, the contract, construction headless, declarations), and
 *       the jar holds a plugin whose id is the entry's {@code id};</li>
 *   <li>JitPack serves the coordinate at {@code tag}.</li>
 * </ol>
 *
 * <p>The plugin's constructor runs here, acceptable only because validate.yml holds no secret.
 */
public final class PluginChecks {

    private PluginChecks() {}

    /** @param claimedIds the ids every other plugin entry claims */
    public static List<Finding> check(PluginEntry entry, String path, EntryFacts facts, Subjects subjects,
                                      Set<String> claimedIds) throws IOException {
        Optional<EntryFacts.Repo> repo = facts.repo(entry.repo());
        if (repo.isEmpty()) {
            return List.of(Finding.error(path, entry.repo() + " is not a public repository on GitHub."));
        }
        String coordinate = repo.get().coordinate();
        if (!facts.jitpackServes(coordinate, entry.tag())) {
            return List.of(Finding.error(path, "JitPack does not serve " + coordinate + ":" + entry.tag()
                    + ", which is what Studio resolves (or JitPack did not answer). Open https://jitpack.io/#"
                    + entry.repo() + " to see its build log, then re-run the check."));
        }
        PluginSubject subject = subjects.fromCoordinate(coordinate + ":" + entry.tag(), claimedIds)
                .about(entry.id());
        List<CheckResult> results = PluginValidator.validate(subject);
        if (!PluginValidator.passed(results)) {
            return List.of(Finding.error(path, "the plugin checks fail: " + String.join(", ", results.stream()
                    .filter(CheckResult::failed).map(r -> r.check().id() + " (" + String.join("; ", r.detail()) + ")").toList())));
        }
        try (PluginLoader loaded = PluginLoader.open(subject.classpath().stream().map(Object::toString).toList())) {
            List<String> ids = loaded == null ? List.of() : loaded.plugins().stream().map(StudioPlugin::id).toList();
            return idFindings(path, entry.id(), ids);
        }
    }

    /** The jar must hold the plugin the entry names: the file name is the id, and the id is what Studio loads. */
    static List<Finding> idFindings(String path, String entryId, List<String> jarIds) {
        if (jarIds.contains(entryId)) return List.of();
        return List.of(Finding.error(path, "the entry's id is " + entryId + " but the jar's plugin"
                + (jarIds.size() == 1 ? " is " : "s are ") + String.join(", ", jarIds)
                + ". Name the file and the id after the plugin's own id()."));
    }
}
