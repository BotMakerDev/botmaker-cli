package com.botmaker.cli.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A module being cut compiles against the releases its tag will pin — not against its upstreams' {@code main}.
 *
 * <p><b>Added 2026-10-12.</b> CI and every other gate build a module against its upstreams' {@code main}
 * snapshots; its tag builds against their released tags. SDK v1.4.3 called shared's {@code LaunchSpec.runsOn},
 * which was on shared's {@code main} and in no shared release, so the tag could never have built. Refusing
 * whenever an upstream has unreleased commits would guess; compiling is the answer (the maintainer's pick).
 *
 * <p><b>How.</b> The module's {@code HEAD} — what the release tags — is checked out into a throwaway
 * {@code git worktree}, so the working copy and its {@code target/} are untouched, and compiled with each
 * upstream <i>outside</i> this release pinned to its newest tag ({@code -Dbotmaker.shared.version=v0.2.4}),
 * resolved from JitPack as the tag will. An upstream <i>in</i> this release keeps its {@code main}
 * {@code -SNAPSHOT}: its tag does not exist yet, and its snapshot in {@code ~/.m2} is the nearest thing. A
 * module whose upstreams are all in this release is not built here.
 *
 * <p>No {@code mvn} on {@code PATH}, or an upstream snapshot missing from {@code ~/.m2} — the machine, not the
 * module — is {@link GateVerdict.Status#SKIPPED}, with the command that fixes it. {@code --force} overrides a
 * module that does not compile.
 */
public final class BuildsAtPinsGate {

    /** How many {@code [ERROR]} lines a refusal quotes. */
    private static final int QUOTED = 12;

    private BuildsAtPinsGate() {
    }

    /** One build: the module, and each upstream outside the release with the tag its pin will name. */
    record Build(Module module, Map<Module, String> pins) {

        /** The {@code -D} arguments that pin those upstreams, in upstream order. */
        List<String> properties() {
            return module.upstreams().stream().filter(pins::containsKey)
                    .map(upstream -> "-Dbotmaker." + upstream.propertyKey().orElseThrow() + ".version="
                            + pins.get(upstream))
                    .toList();
        }

        /** {@code shared v0.2.4, cli v0.3.3}: the pins as a refusal names them. */
        String said() {
            return String.join(", ", module.upstreams().stream().filter(pins::containsKey)
                    .map(upstream -> upstream.shortName() + " " + pins.get(upstream)).toList());
        }
    }

    public static List<GateVerdict> check(Path umbrella, Map<Module, Version> releasing, boolean force) {
        // The ref the tag's pom will name, as DepTag spells it: the tag that exists, v-prefixed or bare.
        List<Build> builds = builds(releasing.keySet(), upstream -> Tags.latest(umbrella, upstream)
                .map(version -> Tags.existingRef(umbrella, upstream, version).orElseGet(version::tag)));
        if (builds.isEmpty()) {
            return List.of();
        }
        if (!Proc.onPath("mvn")) {
            return List.of(GateVerdict.skipped("  mvn not on PATH — no module built at its pins"));
        }
        List<GateVerdict> verdicts = new ArrayList<>();
        for (Build build : builds) {
            verdicts.add(verdict(build, compile(umbrella, build), force));
        }
        return List.copyOf(verdicts);
    }

    /**
     * The builds this release needs, pure over what the checkout answers.
     *
     * @param released an upstream's pin outside this release — its newest tag's name — or empty when it has
     *                 never been released, which {@link DepTag} refuses elsewhere
     */
    static List<Build> builds(java.util.Set<Module> releasing,
                              java.util.function.Function<Module, Optional<String>> released) {
        List<Build> builds = new ArrayList<>();
        for (Module module : Order.toDecide(releasing)) {
            if (!module.mavenBuild() || !module.resolvesFromJitpack()) {
                continue;
            }
            Map<Module, String> pins = new java.util.EnumMap<>(Module.class);
            for (Module upstream : module.upstreams()) {
                if (!releasing.contains(upstream)) {
                    released.apply(upstream).ifPresent(tag -> pins.put(upstream, tag));
                }
            }
            if (!pins.isEmpty()) {
                builds.add(new Build(module, pins));
            }
        }
        return List.copyOf(builds);
    }

    /** {@code mvn compile} of the module's {@code HEAD} in a throwaway worktree, with the build's pins. */
    private static Proc.Result compile(Path umbrella, Build build) {
        Path repo = umbrella.resolve(build.module().directory());
        Path tree;
        try {
            tree = Files.createTempDirectory("botmaker-at-pins-");
            Files.delete(tree);                                   // git worktree add wants to create it
        } catch (IOException e) {
            return new Proc.Result(1, "[ERROR] could not make a temporary directory: " + e.getMessage());
        }
        Proc.Result added = Proc.run(repo, "git", "worktree", "add", "--detach", "--quiet", tree.toString(), "HEAD");
        if (!added.ok()) {
            return new Proc.Result(1, "[ERROR] git worktree add failed: " + added.out());
        }
        try {
            List<String> argv = new ArrayList<>(List.of("mvn", "-B", "-q", "-U", "compile",
                    "-Dbotmaker.japicmp.skip=true"));
            argv.addAll(build.properties());
            return Proc.run(tree, argv.toArray(String[]::new));
        } finally {
            Proc.run(repo, "git", "worktree", "remove", "--force", tree.toString());
        }
    }

    /** The rule over one build's outcome — pure, so every arm is testable without Maven. */
    static GateVerdict verdict(Build build, Proc.Result run, boolean force) {
        String name = build.module().shortName();
        if (run.ok()) {
            return GateVerdict.ok("  " + name + ": compiles at its pins (" + build.said() + ") — ok");
        }
        Optional<String> snapshot = missingSnapshot(run.out());
        if (snapshot.isPresent()) {
            return GateVerdict.skipped("  " + name + ": " + snapshot.get() + " is not in ~/.m2, so it was not built at "
                    + "its pins — run `mvn -pl " + build.module().directory() + " -am install -DskipTests` first");
        }
        if (force) {
            return GateVerdict.forced("  " + name + ": does not compile at its pins (" + build.said() + ") — FORCED");
        }
        List<String> flags = build.pins().keySet().stream().map(Module::flag).toList();
        return GateVerdict.refused(build.module().directory() + " does not compile against the releases its tag "
                + "would pin (" + build.said() + ").\n"
                + "     CI built it against those modules' main, which has what it needs; the tag would not.\n"
                + "     Release the upstream it needs with it — " + String.join(" ", flags) + " — or --force.\n\n"
                + errors(run));
    }

    /** The {@code -SNAPSHOT} artifact Maven could not find locally, when that is why the build failed. */
    static Optional<String> missingSnapshot(String output) {
        return output.lines()
                .filter(line -> line.contains("Could not find artifact") || line.contains("Could not resolve"))
                .map(line -> java.util.regex.Pattern.compile("(com\\.github\\.[\\w.]+:[\\w-]+:[\\w]+:[\\w.]+-SNAPSHOT)")
                        .matcher(line))
                .filter(java.util.regex.Matcher::find)
                .map(m -> m.group(1))
                .findFirst();
    }

    private static String errors(Proc.Result run) {
        return String.join("\n", run.out().lines().filter(line -> line.contains("[ERROR]")).limit(QUOTED).toList());
    }
}
