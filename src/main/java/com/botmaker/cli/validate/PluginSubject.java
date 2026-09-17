package com.botmaker.cli.validate;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * What is being validated, and what the world already claims.
 *
 * <p><b>A record of resolved facts, never a coordinate or a directory.</b> Resolving one of those means
 * running Maven, and Maven is exactly what this package must not know about: the registry's CI resolves a
 * published coordinate and the author's CLI resolves a working copy, and they hand the same shape here so
 * that both get the same verdict. Everything to do with processes, downloads and command lines lives in
 * {@code com.botmaker.cli}.
 *
 * @param classpath         every jar and classes directory the plugin would be loaded from, the plugin's own
 *                          output first. Handed straight to {@code PluginLoader.open}
 * @param pom               the plugin's {@code pom.xml} — the working copy's, or the {@code .pom} resolved
 *                          alongside a published jar. {@code null} when there is none to read, which makes
 *                          {@link Check#POM_SCOPES} a skip rather than a failure
 * @param pinnedVersion     the version to ask {@code catalog(pin)} about, as a project's pom would spell it
 * @param claimedPluginIds  plugin ids the registry already holds, so a submission cannot take one
 * @param claimedValueTypeIds value type ids the registry already holds. Empty when validating locally, which
 *                          is why a clean local run is not a promise the PR will pass — say so in the report
 * @param entryPluginId     the id of the plugin this run is <b>about</b>, when something knows it — the
 *                          registry's entry filename. Blank when nothing does (a local run, where the
 *                          author has not said which of the plugins on their classpath is theirs), and
 *                          then every plugin found is judged, which is what it has always done.
 *                          <p><b>It exists because a plugin may depend on a plugin</b> (the umbrella's SDK
 *                          on {@code botmaker-plugin-basics}): resolving the SDK puts two plugins on one
 *                          classpath, {@code ServiceLoader} finds both, and the second one's id and value
 *                          types are registered — by its own entry, correctly. Without this the gate
 *                          refuses the SDK for claiming ids it does not claim, and the fix an author
 *                          would read out of the message is to rename somebody else's types.
 */
public record PluginSubject(List<Path> classpath, Path pom, String pinnedVersion,
                            Set<String> claimedPluginIds, Set<String> claimedValueTypeIds,
                            String entryPluginId) {

    public PluginSubject {
        classpath = List.copyOf(classpath);
        pinnedVersion = pinnedVersion == null ? "" : pinnedVersion;
        claimedPluginIds = Set.copyOf(claimedPluginIds);
        claimedValueTypeIds = Set.copyOf(claimedValueTypeIds);
        entryPluginId = entryPluginId == null ? "" : entryPluginId.strip();
    }

    /** Everything but the entry id, which only the registry knows. */
    public PluginSubject(List<Path> classpath, Path pom, String pinnedVersion,
                         Set<String> claimedPluginIds, Set<String> claimedValueTypeIds) {
        this(classpath, pom, pinnedVersion, claimedPluginIds, claimedValueTypeIds, "");
    }

    /** A local run: nothing is claimed yet, because nothing has been submitted. */
    public static PluginSubject local(List<Path> classpath, Path pom, String pinnedVersion) {
        return new PluginSubject(classpath, pom, pinnedVersion, Set.of(), Set.of(), "");
    }

    /** The same subject, told which plugin the submission is about. */
    public PluginSubject about(String pluginId) {
        return new PluginSubject(classpath, pom, pinnedVersion, claimedPluginIds, claimedValueTypeIds,
                pluginId);
    }

    /**
     * Whether this plugin is the one being submitted. True for every plugin when nothing said which —
     * so a local run, and a registry entry whose id no plugin on the classpath answers to, are checked
     * exactly as before (the id check is what reports the second case).
     */
    public boolean judges(String pluginId) {
        return entryPluginId.isEmpty() || entryPluginId.equals(pluginId);
    }
}
