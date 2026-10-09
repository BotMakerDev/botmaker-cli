package com.botmaker.cli.release;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A module being cut must stand on {@code main} the way {@link PomVersions} left it — its own version a
 * {@code -SNAPSHOT}, and every upstream pin equal to that upstream's pom version (umbrella
 * {@code docs/refactor/43-real-versions.md}).
 *
 * <p><b>Why before the first tag.</b> The release commit replaces each pin with a released version, so a
 * stale pin never reaches a tag; what it says is that {@code main} has been building against something other
 * than its upstream's {@code main} — a dependent the last release could not move, or a hand edit — and the
 * module about to be cut was tested against that. A release version still on {@code main} means a release
 * that never returned to its snapshot.
 *
 * <p><b>A pin the release does not know is refused even under {@code --force}</b>, like
 * {@link CiDepsGate}'s unmapped key: {@link Module#upstreams()} is the table the release writes from, and a
 * pom pinning a module outside it is a pin no release would ever move.
 */
public final class VersionsGate {

    private static final Pattern PIN = Pattern.compile("<botmaker\\.([a-z]+)\\.version>");

    private VersionsGate() {
    }

    public static GateVerdict check(Path umbrella, Module module, boolean force) {
        if (!PomVersions.moves(module)) {
            return GateVerdict.ok("");
        }
        Path pom = PomVersions.pom(umbrella, module);
        if (!Files.exists(pom)) {
            return GateVerdict.ok("");
        }
        if (!Proc.onPath("mvn")) {
            return GateVerdict.refused(module.directory() + ": mvn is not on PATH, and the release moves pom"
                    + " versions with " + PomVersions.PLUGIN + ". --force does not override it.");
        }
        return check(module, PomVersions.read(pom), upstreamVersions(umbrella, module), force);
    }

    /** Each upstream's own pom version as checked out, {@code (none)} for one that names none. */
    static java.util.Map<Module, String> upstreamVersions(Path umbrella, Module module) {
        java.util.Map<Module, String> upstreamVersions = new java.util.EnumMap<>(Module.class);
        for (Module upstream : module.upstreams()) {
            upstreamVersions.put(upstream, PomVersions.projectVersion(
                    PomVersions.read(PomVersions.pom(umbrella, upstream))).orElse("(none)"));
        }
        return upstreamVersions;
    }

    /**
     * A pin that is not its upstream's {@code main} version.
     *
     * @param pinned what the pom says, {@code (none)} when it declares no property
     * @param theirs the upstream pom's own version
     */
    record Stale(Module upstream, String pinned, String theirs) {

        String line() {
            return "botmaker." + upstream.propertyKey().orElseThrow() + ".version is " + pinned + ", "
                    + upstream.directory() + "'s pom says " + theirs;
        }
    }

    /**
     * The pins this gate refuses — the one comparison it and {@link PomVersions#syncPins} make, so the repair
     * moves exactly what the refusal names.
     */
    static List<Stale> stale(Module module, String pom, java.util.Map<Module, String> upstreamVersions) {
        List<Stale> stale = new ArrayList<>();
        for (Module upstream : module.upstreams()) {
            String pinned = PomVersions.property(pom, upstream).orElse("(none)");
            String theirs = upstreamVersions.get(upstream);
            if (!pinned.equals(theirs)) {
                stale.add(new Stale(upstream, pinned, theirs));
            }
        }
        return List.copyOf(stale);
    }

    /**
     * The rule over the pom's text and each upstream's pom version — pure, so every arm is testable without a
     * checkout.
     */
    public static GateVerdict check(Module module, String pom, java.util.Map<Module, String> upstreamVersions,
                                    boolean force) {
        String own = PomVersions.projectVersion(pom).orElse("(none)");
        List<String> wrong = new ArrayList<>();
        List<Stale> stale = stale(module, pom, upstreamVersions);
        if (!own.endsWith("-SNAPSHOT")) {
            wrong.add("its own version is " + own + ", not a -SNAPSHOT — the last release did not return"
                    + " main to its snapshot");
        }
        stale.forEach(pin -> wrong.add(pin.line()));
        Matcher keys = PIN.matcher(pom.replaceAll("(?s)<!--.*?-->", ""));
        while (keys.find()) {
            String key = keys.group(1);
            Module.byPropertyKey(key)
                    .filter(pinned -> pinned != module && !module.upstreams().contains(pinned))
                    .ifPresent(pinned -> {
                        throw new ReleaseRefusal(module.directory() + ": pom.xml pins ${botmaker." + key
                                + ".version} and the release does not list " + pinned.directory()
                                + " among its upstreams,\n     so no release would ever move it. Add it to"
                                + " Module.upstreams().");
                    });
        }
        if (wrong.isEmpty()) {
            return GateVerdict.ok("  " + module.shortName() + ": " + own + ", every pin at its upstream's main"
                    + " — ok");
        }
        if (force) {
            return GateVerdict.forced("  " + module.shortName() + ": " + String.join("; ", wrong) + " — FORCED");
        }
        return GateVerdict.refused(module.directory() + ": pom.xml is not where the last release left it:\n"
                + wrong.stream().map(line -> "       " + line).reduce((a, b) -> a + "\n" + b).orElse("")
                + (stale.isEmpty() ? "\n     Fix the pom on main (umbrella docs/refactor/43-real-versions.md)"
                : "\n     Move the pins with botmaker release --sync-pins --execute (umbrella"
                + " docs/refactor/43-real-versions.md)") + ", or --force.");
    }
}
