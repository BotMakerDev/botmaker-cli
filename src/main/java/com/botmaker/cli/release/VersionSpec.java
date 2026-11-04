package com.botmaker.cli.release;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What a module flag's argument means — {@code release.sh}'s {@code resolve_version}.
 *
 * <p>Two shapes and no third: a literal {@code x.y.z}, which passes through untouched, or a bump level,
 * which is applied to that module's <b>own latest tag</b>. Anything else is refused with the script's own
 * sentence, before any tag is cut, because the alternative is discovering it with other modules already
 * tagged.
 *
 * <p><b>Typed as a sealed pair rather than kept as the raw string</b>, because the two behave differently
 * everywhere downstream: an exact version is knowable without touching git, and a level is not knowable
 * until the module's tags have been read. The decide pass needs to tell those apart, and a string forces
 * every reader to re-parse it — which is how two readers end up disagreeing about {@code 1.2}.
 */
public sealed interface VersionSpec {

    /** The version the flag named outright. */
    record Exact(Version version) implements VersionSpec {
    }

    /** A bump off the module's latest tag, resolved when that tag is known. */
    record Bump(Level level) implements VersionSpec {
    }

    /**
     * Parses one flag's argument, refusing exactly as the script does.
     *
     * <p>The refusal names the module because a run carries up to ten of these and "bad version/level" with
     * no subject is unactionable. Its wording is the script's, character for character: the port's
     * verification is a diff of both implementations' output, so a reworded refusal is a failing diff.
     *
     * @param module the module whose flag carried it — its <i>directory</i> name appears in the refusal
     * @param spec   the argument, or the bare flag's implied {@code patch}
     */
    static VersionSpec parse(Module module, String spec) {
        Optional<Version> exact = Version.parse(spec);
        // A leading `v` is not accepted here even though Version.parse tolerates one: the script's test is
        // ^[0-9]+\.[0-9]+\.[0-9]+$ on the argument, and `v1.2.0` is refused. Version.parse is lenient
        // because it also reads tag names, which do carry the v.
        if (exact.isPresent() && !spec.startsWith("v")) {
            return new Exact(exact.get());
        }
        return Level.of(spec)
                .<VersionSpec>map(Bump::new)
                .orElseThrow(() -> new ReleaseRefusal(module.directory()
                        + ": bad version/level '" + spec + "' (want x.y.z or patch|minor|major)"));
    }

    /** The version this spec means, given the module's newest existing tag ({@link Version#ZERO} if none). */
    default Version against(Optional<Version> latest) {
        return switch (this) {
            case Exact exact -> exact.version();
            case Bump bump -> latest.orElse(Version.ZERO).bump(bump.level());
        };
    }

    /**
     * The version this spec means for a module in a checkout — the whole of {@code resolve_version}.
     *
     * <p>Reads git only for a bump level. An exact version is an answer already, and asking a remote for
     * tags in order to ignore them is a fetch per module per run for nothing.
     */
    default Version resolve(Path umbrella, Module module) {
        return this instanceof Exact exact
                ? exact.version()
                : inCheckout(umbrella, module, Tags.latest(umbrella, module));
    }

    /**
     * {@link #against}, except that a bump of a module never tagged is the version its pom already says: a bump
     * off nothing would tag {@code botmaker-java}'s first release v0.0.1, below the 0.1.0-SNAPSHOT on its main. A
     * module with no readable pom (an APK) bumps off {@link Version#ZERO} as before.
     */
    default Version inCheckout(Path umbrella, Module module, Optional<Version> latest) {
        return this instanceof Bump && latest.isEmpty()
                ? PomVersions.firstRelease(umbrella, module).orElseGet(() -> against(latest))
                : against(latest);
    }
}
