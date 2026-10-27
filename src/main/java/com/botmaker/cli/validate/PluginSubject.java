package com.botmaker.cli.validate;

import com.botmaker.plugin.host.PluginCoordinate;

import java.nio.file.Path;
import java.util.List;

/**
 * What is being validated.
 *
 * <p><b>A record of resolved facts, never a coordinate to resolve or a directory.</b> Resolving one of those
 * means running Maven, and Maven is exactly what this package must not know about: the catalog's gate resolves a
 * published coordinate and the author's CLI resolves a working copy, and they hand the same shape here so that
 * both get the same verdict. Everything to do with processes, downloads and command lines lives in
 * {@code com.botmaker.cli}.
 *
 * <p>{@code claimedPluginIds} went on 2026-10-27 with the plugin id: a plugin is its pom's coordinate, which
 * Maven and the catalog already keep unique, so there is nothing left for a submission to take from another.
 *
 * @param classpath every jar and classes directory the plugin would be loaded from, the plugin's own output
 *                  first. Handed straight to {@code PluginLoader.open}
 * @param pom       the plugin's {@code pom.xml} — the working copy's, or the {@code .pom} resolved alongside a
 *                  published jar. {@code null} when there is none to read, which makes {@link Check#POM_SCOPES} a
 *                  skip rather than a failure
 * @param entry     the coordinate of the plugin this run is <b>about</b>, when something knows it — the catalog
 *                  entry's {@code repo}. Null when nothing does (a local run), and then every plugin found is
 *                  judged.
 *                  <p><b>It exists because a plugin may depend on a plugin</b> (the SDK on
 *                  {@code botmaker-plugin-basics}): resolving the SDK puts two plugins on one classpath, and the
 *                  second one belongs to its own entry.
 */
public record PluginSubject(List<Path> classpath, Path pom, PluginCoordinate entry) {

    public PluginSubject {
        classpath = List.copyOf(classpath);
    }

    /** A local run: nothing says which plugin is the author's, so every one found is judged. */
    public static PluginSubject local(List<Path> classpath, Path pom) {
        return new PluginSubject(classpath, pom, null);
    }

    /** The same subject, told which plugin the submission is about. */
    public PluginSubject about(PluginCoordinate coordinate) {
        return new PluginSubject(classpath, pom, coordinate);
    }

    /**
     * Whether the plugin {@code coordinate} names is the one being submitted. True for every plugin when nothing
     * said which. JitPack serves {@code com.github.<owner>}, and a GitHub owner is one account however it is
     * cased, so the groupId is compared without case; BotMaker's retired groupId is its current one.
     */
    public boolean judges(PluginCoordinate coordinate) {
        return entry == null || sameCoordinate(entry, coordinate);
    }

    /** Whether {@code a} and {@code b} are one artifact, by the rule {@link #judges} states. */
    public static boolean sameCoordinate(PluginCoordinate a, PluginCoordinate b) {
        return a.artifactId().equals(b.artifactId()) && (a.groupId().equalsIgnoreCase(b.groupId())
                || PluginValidator.contractGroup(a.groupId()) && PluginValidator.contractGroup(b.groupId()));
    }
}
