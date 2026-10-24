package com.botmaker.cli.release;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A released plugin the catalog lists gets its new tag listed: after every tag is pushed and verified, for each
 * module the run tagged that JitPack did not build broken (2026-10-24, the user's call: the release always opens
 * the catalog's pull request).
 *
 * <p>An interface here because the steps are {@code com.botmaker.cli.publish}'s, which builds on this package; each
 * caller ({@code botmaker release}, the dashboard's Release tab) hands in {@code ReleaseListing}. Its answer is the
 * release log's {@code ## Catalog} row; a refusal or a throw is that row, and stops nothing.
 */
@FunctionalInterface
public interface CatalogStep {

    /**
     * What the step did for one module.
     *
     * @param verdict the row's word: {@code pull request opened}, {@code listed already}, {@code refused}
     * @param url     the pull request, or empty
     * @param error   why it was refused, in full, for the log's {@code ## Errors}; empty when it was not
     */
    record Listed(String verdict, String url, String error) {

        public static Listed refused(String why) {
            return new Listed(REFUSED, "", why);
        }
    }

    /** The verdict of a module the step could not list. */
    String REFUSED = "refused";

    /** Lists nothing: a release with no catalog step. */
    CatalogStep NONE = (module, version, dir, say) -> Optional.empty();

    /**
     * @param dir the module's working copy, at its next snapshot: the tag is listed as a release made already
     * @param say the run's narration
     * @return empty when the catalog lists no entry for the module's repository
     */
    Optional<Listed> list(Module module, Version version, Path dir, Consumer<String> say);
}
