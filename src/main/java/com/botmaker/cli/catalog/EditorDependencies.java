package com.botmaker.cli.catalog;

import com.botmaker.cli.validate.PluginValidator;

import java.util.ArrayList;
import java.util.List;

/**
 * The rule on a plugin's {@code editorDependencies}, applied by the check and again by {@link CatalogBuilder}:
 * the builder re-reads the pom at the tag on every run, and a tag can be moved after its pull request merged.
 *
 * <p><b>Every line here becomes a {@code provided} dependency in a stranger's project pom</b>, written by Studio
 * when somebody installs the plugin. So the shape is checked rather than trusted: a
 * {@code groupId:artifactId:version} with three non-blank parts, named once, and none of what a bot's pom must
 * never declare — the contract and the toolkit (a second copy makes a contract class two different classes; the
 * toolkit arrives with the plugin), nor {@code org.openjfx:*}, which {@code PluginLoader} resolves parent-first
 * from the host.
 */
public final class EditorDependencies {

    private EditorDependencies() {}

    /** Why {@code editorDependencies} is refused, or {@code null} when it may be listed. */
    public static String refusal(List<String> editorDependencies) {
        List<String> seen = new ArrayList<>();
        for (String entry : editorDependencies) {
            String[] parts = entry == null ? new String[0] : entry.trim().split(":");
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                return "editorDependencies holds \"" + entry + "\", which is not a"
                        + " groupId:artifactId:version. Studio writes each of these into the pom of every"
                        + " project that installs this plugin, so it has to be a coordinate Maven resolves.";
            }
            String coordinate = parts[0] + ":" + parts[1];
            if (seen.contains(coordinate)) {
                return "editorDependencies names " + coordinate + " twice. Two versions of one artifact on a"
                        + " classpath is the least diagnosable failure this platform has.";
            }
            seen.add(coordinate);
            if ("org.openjfx".equals(parts[0])) {
                return "editorDependencies names " + coordinate + ", which the host provides: PluginLoader"
                        + " resolves JavaFX parent-first, so a plugin's own copy is never the one it links.";
            }
            if (PluginValidator.contractGroup(parts[0])
                    && (PluginValidator.CONTRACT_ARTIFACT.equals(parts[1])
                    || PluginValidator.TOOLKIT_ARTIFACT.equals(parts[1]))) {
                return "editorDependencies names " + coordinate + ", which a bot's pom must never declare."
                        + " The host already has the contract — a second copy makes a contract class two"
                        + " different classes — and the toolkit is this plugin's own dependency at compile"
                        + " scope, so it arrives with the plugin. Declare it in the plugin's pom instead.";
            }
        }
        return null;
    }
}
