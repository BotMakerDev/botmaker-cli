package com.botmaker.cli.catalog;

import com.botmaker.cli.project.Poms;
import com.botmaker.cli.validate.PluginValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@link DerivedFacts} read from each approved release's {@code pom.xml} at its pinned commit, as data: no build,
 * no entry's code.
 *
 * <p>The XML parser refuses a {@code DOCTYPE} ({@link Poms}), so a pom cannot reach a file or the network through
 * an entity. A {@code ${…}} resolves from the pom's own {@code <properties>} only; a value left unresolved is that
 * fact missing, never the literal text.
 */
public final class PomFacts {

    /** A pom at a ref (a release's pinned commit), fetched as text; empty when there is none. */
    @FunctionalInterface
    public interface Source {
        Optional<String> pom(String repo, String ref);
    }

    private PomFacts() {}

    /**
     * Every approved release's facts, each pom read at the release's pinned commit. A release whose pom could not
     * be fetched or parsed has none, which the builder answers with the previous row's while the pin holds.
     *
     * @param pluginIds the catalog's plugin ids by {@code groupId:artifactId} ({@link #key})
     */
    public static DerivedFacts read(List<? extends Entry> entries, Source poms, Map<String, String> pluginIds) {
        Map<String, Map<String, DerivedFacts.PluginFacts>> plugins = new HashMap<>();
        Map<String, Map<String, List<DerivedFacts.Pin>>> bots = new HashMap<>();
        for (Entry entry : entries) {
            for (ApprovedRelease release : entry.releases()) {
                Optional<String> text = poms.pom(entry.repo(), release.commit());
                if (text.isEmpty()) continue;
                try {
                    Path pom = Files.createTempFile("catalog-pom", ".xml");
                    try {
                        Files.writeString(pom, text.get());
                        // Read before the maps are touched: a pom that does not parse leaves no empty row.
                        switch (entry) {
                            case PluginEntry plugin -> {
                                DerivedFacts.PluginFacts read = plugin(pom, plugin);
                                plugins.computeIfAbsent(plugin.id(), k -> new HashMap<>()).put(release.tag(), read);
                            }
                            case BotEntry bot -> {
                                List<DerivedFacts.Pin> read = pins(pom, pluginIds);
                                bots.computeIfAbsent(bot.repo(), k -> new HashMap<>()).put(release.tag(), read);
                            }
                        }
                    } finally {
                        Files.deleteIfExists(pom);
                    }
                } catch (IOException e) {
                    System.err.println("no facts for " + EntryPath.of(entry) + ": its pom at " + release.tag()
                            + " does not parse");
                }
            }
        }
        return new DerivedFacts(plugins, bots);
    }

    /** A plugin's name (its pom's {@code <name>}, else the repository name), contract floor and editor dependencies. */
    static DerivedFacts.PluginFacts plugin(Path pom, PluginEntry entry) throws IOException {
        Map<String, String> properties = Poms.properties(pom);
        String name = resolved(Poms.name(pom), properties).orElse(entry.name());
        List<Poms.Dependency> declared = Poms.dependencies(pom);
        String contract = Poms.find(declared, PluginValidator.CONTRACT_GROUP, PluginValidator.CONTRACT_ARTIFACT)
                .flatMap(d -> resolved(d.version(), properties)).orElse(null);
        return new DerivedFacts.PluginFacts(name, contract, editorDependencies(declared, properties));
    }

    /**
     * The catalog plugins a pom declares, each at its version with the pom's properties applied. An unresolved
     * version stays as written, so the check and the builder refuse it by name.
     */
    public static List<DerivedFacts.Pin> pins(Path pom, Map<String, String> pluginIds) throws IOException {
        Map<String, String> properties = Poms.properties(pom);
        List<DerivedFacts.Pin> pins = new ArrayList<>();
        for (Poms.Dependency dependency : Poms.dependencies(pom)) {
            String id = pluginIds.get(key(dependency.groupId(), dependency.artifactId()));
            if (id != null) {
                pins.add(new DerivedFacts.Pin(id, Poms.interpolate(dependency.version(), properties).trim()));
            }
        }
        return List.copyOf(pins);
    }

    /**
     * Every {@code optional} dependency at a resolved version: what a project adding the plugin does not get
     * transitively. JavaFX (the host's own, parent-first) and the contract and toolkit (a bot's pom must declare
     * neither) are left out.
     */
    static List<String> editorDependencies(List<Poms.Dependency> declared, Map<String, String> properties) {
        List<String> companions = new ArrayList<>();
        for (Poms.Dependency dependency : declared) {
            if (!dependency.optional() || "org.openjfx".equals(dependency.groupId())) continue;
            if (PluginValidator.contractGroup(dependency.groupId())
                    && (PluginValidator.CONTRACT_ARTIFACT.equals(dependency.artifactId())
                    || PluginValidator.TOOLKIT_ARTIFACT.equals(dependency.artifactId()))) {
                continue;
            }
            resolved(dependency.version(), properties)
                    .ifPresent(version -> companions.add(dependency.coordinate() + ":" + version));
        }
        return List.copyOf(companions);
    }

    /** How the catalog keys a coordinate: {@code groupId:artifactId}, lower case, since JitPack ignores case. */
    public static String key(String groupId, String artifactId) {
        return (groupId + ":" + artifactId).toLowerCase(Locale.ROOT);
    }

    private static Optional<String> resolved(String value, Map<String, String> properties) {
        String out = Poms.interpolate(value, properties).trim();
        return out.isEmpty() || out.contains("${") ? Optional.empty() : Optional.of(out);
    }
}
