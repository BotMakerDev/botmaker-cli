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
 * <p>Two components went on 2026-09-23. {@code pinnedVersion} was the argument {@code catalog(pin)} was
 * asked with, and the contract's {@code catalog()} takes none. {@code claimedValueTypeIds} reserved the
 * string ids a registry entry listed, and a type is identified by its class now — which is named by its
 * package, so two plugins can collide only inside one build, where {@link Check#TYPES} sees both.
 *
 * @param classpath        every jar and classes directory the plugin would be loaded from, the plugin's own
 *                         output first. Handed straight to {@code PluginLoader.open}
 * @param pom              the plugin's {@code pom.xml} — the working copy's, or the {@code .pom} resolved
 *                         alongside a published jar. {@code null} when there is none to read, which makes
 *                         {@link Check#POM_SCOPES} a skip rather than a failure
 * @param claimedPluginIds plugin ids the registry already holds, so a submission cannot take one
 * @param entryPluginId    the id of the plugin this run is <b>about</b>, when something knows it — the
 *                         registry's entry filename. Blank when nothing does (a local run, where the author
 *                         has not said which of the plugins on their classpath is theirs), and then every
 *                         plugin found is judged, which is what it has always done.
 *                         <p><b>It exists because a plugin may depend on a plugin</b> (the umbrella's SDK on
 *                         {@code botmaker-plugin-basics}): resolving the SDK puts two plugins on one
 *                         classpath, {@code ServiceLoader} finds both, and the second one's id belongs to
 *                         its own entry. Without this the gate refuses the SDK for claiming an id it does
 *                         not claim.
 */
public record PluginSubject(List<Path> classpath, Path pom, Set<String> claimedPluginIds,
                            String entryPluginId) {

    public PluginSubject {
        classpath = List.copyOf(classpath);
        claimedPluginIds = Set.copyOf(claimedPluginIds);
        entryPluginId = entryPluginId == null ? "" : entryPluginId.strip();
    }

    /** Everything but the entry id, which only the registry knows. */
    public PluginSubject(List<Path> classpath, Path pom, Set<String> claimedPluginIds) {
        this(classpath, pom, claimedPluginIds, "");
    }

    /** A local run: nothing is claimed yet, because nothing has been submitted. */
    public static PluginSubject local(List<Path> classpath, Path pom) {
        return new PluginSubject(classpath, pom, Set.of(), "");
    }

    /** The same subject, told which plugin the submission is about. */
    public PluginSubject about(String pluginId) {
        return new PluginSubject(classpath, pom, claimedPluginIds, pluginId);
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
