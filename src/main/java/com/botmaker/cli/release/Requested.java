package com.botmaker.cli.release;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a set of flags asks for, as the map {@link Plan#decide} takes — {@code release.sh}'s rule that an
 * explicit module flag beats {@code --all}.
 *
 * <p><b>One line of logic, and it is here because it has three callers.</b> {@code botmaker release}
 * collects eleven {@code @Option} fields, {@code botmaker-dashboard} collects eleven table rows and a
 * checkbox, and {@code .github/workflows/release.yml} collects eleven inputs — three spellings of one
 * question, and {@code putIfAbsent} is the answer to all of them. A second copy would diverge on the day
 * somebody decides {@code --all} should win for a module the operator left blank, and the divergence is a
 * version nobody chose, cut as a tag.
 *
 * <p><b>It is not the decide pass and takes no view on what is releasable.</b> Every module named here is
 * a module {@link Plan} will then consider and may skip; this only says which were asked about.
 */
public final class Requested {

    private Requested() {
    }

    /**
     * @param all      the level for {@code --all}, when every module is being asked at once. Empty means no
     *                 {@code --all}; a blank string is the bare flag, which the script reads as a patch.
     * @param explicit module to version-or-level, for the modules named one by one
     */
    public static Map<Module, String> of(Optional<String> all, Map<Module, String> explicit) {
        Map<Module, String> out = new EnumMap<>(Module.class);
        out.putAll(explicit);
        all.ifPresent(level -> {
            String spec = level.isBlank() ? Level.DEFAULT.spelling() : level.strip();
            for (Module module : Module.values()) {
                // putIfAbsent, not put: an explicit --sdk 1.2.0 beside --all means 1.2.0 for the SDK and
                // the level for everything else. Reversing it would release a version nobody typed.
                out.putIfAbsent(module, spec);
            }
        });
        return out;
    }
}
