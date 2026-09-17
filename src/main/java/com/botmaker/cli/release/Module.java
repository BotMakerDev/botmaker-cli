package com.botmaker.cli.release;

import java.util.Optional;

/**
 * The modules {@code release.sh} can cut a tag for — fourteen, and this is now the list that owns that fact.
 *
 * <p><b>Keeping the list here is the opposite of the rule {@code botmaker-dashboard} follows, and both are
 * right.</b> The dashboard refuses to keep it because it is a <i>reader</i>: a second copy there would go
 * stale against the script and be discovered as a missing module. This package is the <i>owner</i> being
 * ported — the whole point of Part C is that {@code release.sh}'s decisions move here — so the list has to
 * land somewhere, and an enum is what makes "is that a module?" a compile-time question for every caller
 * that follows.
 *
 * <p><b>Not every submodule is here, and that is the distinction the dashboard reports as <i>not released
 * by release.sh</i>.</b> {@code botmaker-gallery} and {@code botmaker-plugin-registry} are data
 * repositories with no artifact. Neither has a flag, and the script answers {@code unknown arg} to one
 * invented for them. {@code botmaker-dashboard} was that case too until 2026-09-17: it is an application
 * nothing resolves, but it is an installable one now (rpm + deb from its own {@code package} job), so it
 * is tagged like Studio — last, and forced by the cli whose release rules it carries.
 *
 * <p><b>Declaration order is the script's flag order, and it is deliberately NOT the tag order.</b> The two
 * differ on purpose — see {@link Order#TAG} — and nothing here may be read as an ordering.
 */
public enum Module {

    STUDIO_API("botmaker-studio-api"),
    PLUGIN_TOOLKIT("botmaker-plugin-toolkit"),
    PLUGIN_HOST("botmaker-plugin-host"),
    PLUGIN_ARCHETYPE("botmaker-plugin-archetype"),
    PLUGIN_BASICS("botmaker-plugin-basics"),
    CLI("botmaker-cli"),
    SHARED("botmaker-shared"),
    SESSION("botmaker-session"),
    SDK("botmaker-sdk"),
    STUDIO("botmaker-studio"),
    PILOT("botmaker-pilot"),
    REMOTE_SERVER("botmaker-remote-server"),
    REMOTE("botmaker-remote"),
    DASHBOARD("botmaker-dashboard");

    private static final String PREFIX = "botmaker-";

    private final String directory;

    Module(String directory) {
        this.directory = directory;
    }

    /** The submodule directory under the umbrella root, which is also the GitHub repository name. */
    public String directory() {
        return directory;
    }

    /**
     * The command-line flag, derived rather than tabulated: {@code --plugin-toolkit} is the directory
     * without the {@code botmaker-} prefix, for all fourteen.
     */
    public String flag() {
        return "--" + directory.substring(PREFIX.length());
    }

    /**
     * The short name the script prints in its own messages — {@code studio-api}, not
     * {@code botmaker-studio-api}.
     *
     * <p>It matters because the decide pass keys its output by this name, and the port's verification is a
     * diff of that output.
     */
    public String shortName() {
        return directory.substring(PREFIX.length());
    }

    /**
     * The name the umbrella's pointer commit calls this module — {@code release: toolkit v0.0.6}.
     *
     * <p><b>It is {@link #shortName()} for all but two, and a recorded exception for those</b>, and
     * the exception is a transcription rather than a design: {@code release.sh} writes {@code toolkit} and
     * {@code archetype} while writing {@code plugin-host} and {@code plugin-basics} in full, in a hand-kept
     * list of eleven lines. That inconsistency is in every pointer commit this project has ever made, so it
     * is what a {@code git log --grep} of the release history matches. Deriving a tidier name here would
     * silently split that history in two at the day the port took over.
     */
    public String pointerName() {
        return switch (this) {
            case PLUGIN_TOOLKIT -> "toolkit";
            case PLUGIN_ARCHETYPE -> "archetype";
            default -> shortName();
        };
    }

    /**
     * Whether this is a Maven build at all — a pom CI builds standalone, plugin pins, a japicmp baseline.
     * The two phone apps are Capacitor projects: no pom, nothing of that applies.
     */
    public boolean mavenBuild() {
        return this != PILOT && this != REMOTE;
    }

    /**
     * Whether anybody resolves this module as a Maven artifact. Studio, the dashboard and remote-server
     * are programs packaged by their own CI; the two apps are APKs. JitPack builds none of the five, so
     * neither the JitPack wait nor the clean-room verification applies to them.
     */
    public boolean onJitpack() {
        return mavenBuild() && this != STUDIO && this != REMOTE_SERVER && this != DASHBOARD;
    }

    /**
     * Whether this module keeps a {@code CHANGELOG.md} the gate reads and the release stamps. The two
     * apps do not: an APK's release notes are JReleaser's commit log, and nothing reads notes out of one.
     */
    public boolean hasChangelog() {
        return this != PILOT && this != REMOTE;
    }

    /** The module a flag names, or empty — which the caller reports as the script's {@code unknown arg}. */
    public static Optional<Module> byFlag(String flag) {
        for (Module module : values()) {
            if (module.flag().equals(flag)) {
                return Optional.of(module);
            }
        }
        return Optional.empty();
    }

    /** The module a directory name names, or empty. */
    public static Optional<Module> byDirectory(String directory) {
        for (Module module : values()) {
            if (module.directory.equals(directory)) {
                return Optional.of(module);
            }
        }
        return Optional.empty();
    }
}
