package com.botmaker.cli.publish;

import java.util.Optional;

/**
 * The kind's own checks a publish runs: the folder's before anything is tagged, and a plugin's published jar once
 * JitPack built it (the subject the catalog's check builds). An interface because each needs Maven and the cli's
 * {@code Subjects}, which a test answers in place of.
 */
public interface Checks {

    /**
     * What a check found.
     *
     * @param refusal  why it failed, or empty when it passed
     * @param pluginId the plugin's own id read off the loaded jar, which names its entry; empty for a bot
     */
    record Result(Optional<String> refusal, Optional<String> pluginId) {

        public static Result passed(Optional<String> pluginId) {
            return new Result(Optional.empty(), pluginId);
        }

        public static Result refused(String why) {
            return new Result(Optional.of(why), Optional.empty());
        }
    }

    /**
     * The folder at {@code HEAD}: a plugin's {@code PluginValidator} checks, a bot's rules 6–8 (a catalog plugin at a
     * released version, it compiles, a class holds {@code main}).
     */
    Result folder(PublishTarget target, Listener listener);

    /** A plugin's {@code PluginValidator} checks on {@code coordinate} ({@code group:artifact:tag}) as Maven resolves it. */
    Result published(String coordinate, Listener listener);

    /**
     * None run here: the catalog's check on the pull request runs them all. For a host that loads no plugin (the
     * dashboard, which has no plugin-host), listing a release the catalog's entry already names; a folder, which
     * would be tagged on the strength of a check, is refused.
     */
    Checks LEFT_TO_THE_CATALOG = new Checks() {
        @Override
        public Result folder(PublishTarget target, Listener listener) {
            return Result.refused("this host runs no plugin checks, so it publishes no new version; run `botmaker "
                    + "plugin publish` in " + target.dir() + ".");
        }

        @Override
        public Result published(String coordinate, Listener listener) {
            listener.step("The catalog's own check runs the plugin's checks on " + coordinate + ".");
            return Result.passed(Optional.empty());
        }
    };
}
