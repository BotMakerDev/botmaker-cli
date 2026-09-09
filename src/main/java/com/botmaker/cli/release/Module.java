package com.botmaker.cli.release;

import java.util.Optional;

/**
 * The modules {@code release.sh} can cut a tag for — eleven, and this is now the list that owns that fact.
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
 * repositories with no artifact, and {@code botmaker-dashboard} is an application nothing resolves. None of
 * them has a flag, and the script answers {@code unknown arg} to one invented for them.
 *
 * <p><b>Declaration order is the script's flag order, and it is deliberately NOT the tag order.</b> The two
 * differ on purpose — the two longest CI jobs are tagged first so they run while the JitPack chain is still
 * going — and porting that order is slice 3's job. Nothing here may be read as an ordering.
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
    PILOT("botmaker-pilot");

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
     * without the {@code botmaker-} prefix, for all eleven.
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
