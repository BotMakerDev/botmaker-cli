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

    /** Where {@code botmaker-plugin-parent} lives, and the property its dependencyManagement pins the contract by. */
    private static final String PARENT_REPO = "BotMakerDev/" + PluginValidator.PARENT_ARTIFACT;
    private static final String CONTRACT_PROPERTY = "botmaker.studioapi.version";

    private PomFacts() {}

    /**
     * Every approved release's facts, each pom read at the release's pinned commit. A release whose pom could not
     * be fetched or parsed has none, which the builder answers with the previous row's while the pin holds.
     *
     * @param pluginRepos the catalog's plugins' {@code owner/repo} by {@code groupId:artifactId} ({@link #key})
     */
    public static DerivedFacts read(List<? extends Entry> entries, Source poms, Map<String, String> pluginRepos) {
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
                                DerivedFacts.PluginFacts read = plugin(pom, plugin, pluginRepos, poms);
                                plugins.computeIfAbsent(plugin.repo(), k -> new HashMap<>()).put(release.tag(), read);
                            }
                            case BotEntry bot -> {
                                List<DerivedFacts.Pin> read = pins(pom, pluginRepos);
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

    /**
     * A plugin's name (its pom's {@code <name>}, else the repository name), contract floor, editor dependencies and
     * the catalog plugins it declares.
     *
     * <p>A plugin on {@code botmaker-plugin-parent} declares the contract with no version: the parent's
     * {@code dependencyManagement} pins it as {@code ${botmaker.studioapi.version}}. That parent's pom is read at
     * the tag the plugin names, through {@code poms}, and the plugin's own properties override its own, as Maven does.
     */
    static DerivedFacts.PluginFacts plugin(Path pom, PluginEntry entry, Map<String, String> pluginRepos, Source poms)
            throws IOException {
        Map<String, String> properties = Poms.properties(pom);
        String name = resolved(Poms.name(pom), properties).orElse(entry.name());
        List<Poms.Dependency> declared = Poms.dependencies(pom);
        Optional<Poms.Dependency> declaredContract =
                Poms.find(declared, PluginValidator.CONTRACT_GROUP, PluginValidator.CONTRACT_ARTIFACT);
        String contract = (declaredContract.isPresent() && declaredContract.get().version().isBlank()
                ? managedContract(pom, properties, poms)
                : declaredContract.flatMap(d -> resolved(d.version(), properties))).orElse(null);
        return new DerivedFacts.PluginFacts(name, contract, editorDependencies(declared, properties),
                pins(pom, pluginRepos));
    }

    /**
     * The catalog plugins a pom declares, each at its version with the pom's properties applied. An unresolved
     * version stays as written, so the check and the builder refuse it by name.
     */
    public static List<DerivedFacts.Pin> pins(Path pom, Map<String, String> pluginRepos) throws IOException {
        Map<String, String> properties = Poms.properties(pom);
        List<DerivedFacts.Pin> pins = new ArrayList<>();
        for (Poms.Dependency dependency : Poms.dependencies(pom)) {
            String repo = pluginRepos.get(key(dependency.groupId(), dependency.artifactId()));
            if (repo != null) {
                pins.add(new DerivedFacts.Pin(repo, Poms.interpolate(dependency.version(), properties).trim()));
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

    /** The contract version {@code botmaker-plugin-parent} manages for this pom, or empty for any other parent. */
    private static Optional<String> managedContract(Path pom, Map<String, String> own, Source poms) throws IOException {
        Optional<Poms.Dependency> parent = Poms.parent(pom).filter(p -> PluginValidator.contractGroup(p.groupId())
                && PluginValidator.PARENT_ARTIFACT.equals(p.artifactId()) && !p.version().isBlank());
        if (parent.isEmpty()) return Optional.empty();
        Optional<String> text = poms.pom(PARENT_REPO, parent.get().version());
        if (text.isEmpty()) return Optional.empty();
        Path parentPom = Files.createTempFile("catalog-parent-pom", ".xml");
        try {
            Files.writeString(parentPom, text.get());
            Map<String, String> properties = new HashMap<>(Poms.properties(parentPom));
            properties.putAll(own);
            return resolved("${" + CONTRACT_PROPERTY + "}", properties);
        } finally {
            Files.deleteIfExists(parentPom);
        }
    }

    private static Optional<String> resolved(String value, Map<String, String> properties) {
        String out = Poms.interpolate(value, properties).trim();
        return out.isEmpty() || out.contains("${") ? Optional.empty() : Optional.of(out);
    }
}
