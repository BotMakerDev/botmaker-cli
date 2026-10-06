package com.botmaker.cli.release;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The git ref a downstream build should pin an upstream module to — {@code release.sh}'s {@code dep_tag}.
 *
 * <p>Two cases, and the first is why this is not simply "the newest tag": when <b>this run</b> is releasing
 * the module, the pin is the version being cut, which does not exist as a tag yet at the moment the
 * downstream's release commit is written. Reading the repository there would pin the <i>previous</i>
 * release, and the downstream would be published resolving an upstream it was not built against.
 *
 * <p>That is the guess this file replaced. Five {@code .deps.env} values (the pin file a release wrote until
 * 2026-10-06, before the poms named real versions) were once
 * {@code git ls-remote --tags | sort -V | tail -1} — "newest tag", which is only <i>usually</i> "the tag
 * this release just cut".
 *
 * <p><b>The ref name is probed, not composed.</b> Tags here are {@code v1.2.3} and the SDK's older ones are
 * bare {@code 1.2.3}; a pin naming a ref that does not exist fails the downstream's JitPack build, which is
 * cached per tag and so cannot be repaired without cutting another one.
 */
public final class DepTag {

    private DepTag() {
    }

    /**
     * @param cutting the version this run is releasing for {@code module}, or empty when it is not being
     *                released — in which case the module's newest existing tag is the pin
     * @throws ReleaseRefusal when the module has never been tagged and this run is not tagging it: there is
     *                        nothing a downstream could resolve, and publishing it with a made-up pin is the
     *                        one outcome worse than refusing
     */
    public static String of(Path umbrella, Module module, Optional<Version> cutting) {
        if (cutting.isPresent()) {
            return cutting.get().tag();
        }
        Version latest = version(umbrella, module, cutting);
        // The script falls back to the v-prefixed spelling when neither ref resolves, so a checkout that
        // cannot answer still produces the name the tag is about to have rather than nothing.
        return Tags.existingRef(umbrella, module, latest).orElseGet(latest::tag);
    }

    /**
     * The released version a downstream pom pins {@code module} at — the same choice as {@link #of}, as the
     * bare number a pom names ({@code 0.4.2}). JitPack resolves it against the tag {@code v0.4.2}.
     *
     * @throws ReleaseRefusal as {@link #of} does
     */
    public static Version version(Path umbrella, Module module, Optional<Version> cutting) {
        if (cutting.isPresent()) {
            return cutting.get();
        }
        return Tags.latest(umbrella, module).orElseThrow(() ->
                new ReleaseRefusal(module.directory() + ": no tag to pin a downstream build to"));
    }
}
