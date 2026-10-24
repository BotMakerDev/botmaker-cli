package com.botmaker.cli.release;

import java.util.List;
import java.util.Optional;

/**
 * The modules {@code release.sh} can cut a tag for — fifteen, and this is now the list that owns that fact.
 *
 * <p><b>Keeping the list here is the opposite of the rule {@code botmaker-dashboard} follows, and both are
 * right.</b> The dashboard refuses to keep it because it is a <i>reader</i>: a second copy there would go
 * stale against the script and be discovered as a missing module. This package is the <i>owner</i> being
 * ported — the whole point of Part C is that {@code release.sh}'s decisions move here — so the list has to
 * land somewhere, and an enum is what makes "is that a module?" a compile-time question for every caller
 * that follows.
 *
 * <p><b>Not every submodule is here, and that is the distinction the dashboard reports as <i>not released
 * by release.sh</i>.</b> {@code botmaker-catalog} is a data repository with no artifact
 * ({@code botmaker-gallery} was the other until 2026-10-23). It has no flag, and the script answers {@code unknown arg} to one
 * invented for them. {@code botmaker-dashboard} was that case too until 2026-09-17: it is an application
 * nothing resolves, but it is an installable one now (rpm + deb from its own {@code package} job), so it
 * is tagged like Studio — last, and forced by the cli whose release rules it carries.
 *
 * <p>{@code botmaker-gamebot} was a module from 2026-09-21 to 2026-10-23, the worked bot New Project copied;
 * templates went with it, and a bot is now any published repository (Studio's Browse Bots), released by its
 * author and by nothing here.
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
     * are programs packaged by their own CI; the two apps are APKs.
     * JitPack builds none of the five, so neither the JitPack wait nor the clean-room verification applies.
     */
    public boolean onJitpack() {
        return mavenBuild() && this != STUDIO && this != REMOTE_SERVER && this != DASHBOARD;
    }

    /**
     * Whether this module's tagged build resolves its {@link #upstreams()} from JitPack: every JitPack build,
     * and Studio's and the dashboard's {@code package} jobs, which resolve the released pins their tag's pom
     * names (umbrella {@code docs/refactor/43-real-versions.md}). {@link Waits} reads it on the consumer.
     */
    public boolean resolvesFromJitpack() {
        return onJitpack() || this == STUDIO || this == DASHBOARD;
    }

    /**
     * Whether this module keeps a {@code CHANGELOG.md} the gate reads and the release stamps. The two
     * apps do not: an APK's release notes are JReleaser's commit log, and nothing reads notes out of one.
     */
    public boolean hasChangelog() {
        return this != PILOT && this != REMOTE;
    }

    /**
     * Whether a release of this module commits anything before it tags: the pom versions and the changelog
     * stamp. The two APKs are the only modules that tag without a commit.
     */
    public boolean commitsOnRelease() {
        return this != PILOT && this != REMOTE;
    }

    /**
     * The key a downstream pom pins this module under — {@code botmaker.<key>.version} — or empty for a
     * module nothing pins as a property.
     *
     * <p>A table rather than a derivation, because the keys are not the directory names with the dashes
     * removed by rule — {@code studioapi} is {@code botmaker-studio-api} and {@code plugintoolkit} is
     * {@code botmaker-plugin-toolkit}, but nothing makes {@code sdk} mean {@code botmaker-sdk} except that
     * somebody wrote both. Deriving it would silently map a typo to a plausible module.
     */
    public Optional<String> propertyKey() {
        return Optional.ofNullable(switch (this) {
            case STUDIO_API -> "studioapi";
            case PLUGIN_TOOLKIT -> "plugintoolkit";
            case PLUGIN_HOST -> "pluginhost";
            case PLUGIN_ARCHETYPE -> "pluginarchetype";
            case PLUGIN_BASICS -> "pluginbasics";
            case CLI -> "cli";
            case SHARED -> "shared";
            case SESSION -> "session";
            case SDK -> "sdk";
            default -> null;
        });
    }

    /** The module a property key names, or empty. */
    public static Optional<Module> byPropertyKey(String key) {
        for (Module module : values()) {
            if (module.propertyKey().filter(key::equals).isPresent()) {
                return Optional.of(module);
            }
        }
        return Optional.empty();
    }

    /**
     * The modules this one's pom pins as a {@code botmaker.<key>.version} property, in the order a release
     * names them; empty for one that pins nothing of ours.
     *
     * <p>Each pin is that upstream's own {@code -SNAPSHOT} on {@code main} and its released version on a tag
     * ({@link PomVersions}, umbrella {@code docs/refactor/43-real-versions.md}); {@link VersionsGate} holds the
     * pom to this list. <b>The empties are as deliberate as the entries.</b> Studio pins no SDK and no toolkit: it
     * has had no {@code botmaker-sdk} dependency since 2026-09-02, and the toolkit is a <i>plugin's</i>
     * dependency, never the host's; it pins the cli's main jar too since 2026-10-24 (five now). The dashboard pins
     * shared and the cli's main jar, whose own pins it excludes. {@code botmaker-studio-api} pins nothing: it has no upstream of ours.
     */
    public List<Module> upstreams() {
        return switch (this) {
            case PLUGIN_TOOLKIT, PLUGIN_HOST -> List.of(STUDIO_API);
            // Plugin #2 pins two: the contract at `provided` and the toolkit at `compile`. Both reach the
            // published pom, and that pom is what another plugin — the SDK — resolves. The example plugin
            // pins the same two (2026-10-19), and its tag's pom is the one a new plugin starts from.
            case PLUGIN_BASICS, PLUGIN_ARCHETYPE -> List.of(STUDIO_API, PLUGIN_TOOLKIT);
            // The archetype is the example plugin's `template` zip, bundled for `botmaker plugin new`.
            case CLI -> List.of(STUDIO_API, PLUGIN_HOST, PLUGIN_ARCHETYPE);
            case SESSION -> List.of(SHARED);
            case SDK -> List.of(SHARED, SESSION, STUDIO_API, PLUGIN_TOOLKIT, PLUGIN_BASICS);
            // The cli's main jar since 2026-10-24: Studio's Publish runs the cli's publish library.
            case STUDIO -> List.of(SHARED, SESSION, STUDIO_API, PLUGIN_HOST, CLI);
            case DASHBOARD -> List.of(SHARED, CLI);
            default -> List.of();
        };
    }

    /** The modules whose pom pins this one — {@link #upstreams()} read backwards. */
    public List<Module> dependents() {
        return java.util.Arrays.stream(values()).filter(module -> module.upstreams().contains(this)).toList();
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
