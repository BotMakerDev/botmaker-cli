package com.botmaker.cli.registry;

import com.botmaker.cli.Console;
import com.botmaker.cli.Mvn;
import com.botmaker.cli.Subjects;
import com.botmaker.cli.validate.CheckResult;
import com.botmaker.cli.validate.PluginSubject;
import com.botmaker.cli.validate.PluginValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The registry's gate: the check that decides a pull request, run from the registry's own CI.
 *
 * <p><b>It is here, in {@code botmaker-cli}, and not in the registry repository, because it must be the
 * same code the author already ran.</b> A submission that fails for a reason its author could not have seen
 * coming is the experience the whole gate exists to prevent, so the registry's workflow resolves this
 * module's <em>main</em> artifact — the library, with no picocli in it — and calls this class. Everything it
 * adds on top of {@code botmaker validate} is what only the registry knows: the ids every other entry
 * already claims, the ids the host's bundled plugins own ({@link Bundled}), and the rule that
 * {@code index.json} is generated.
 *
 * <p><b>No command-line library, deliberately.</b> Two positional arguments, parsed by reading an array:
 * picocli is {@code optional} in this module precisely so a consumer resolving it as a library does not
 * download a parser, and a gate that needed one would undo that.
 *
 * <pre>
 * java -cp … com.botmaker.cli.registry.RegistryGate &lt;registry-root&gt; &lt;changed-path&gt;…
 * java -cp … com.botmaker.cli.registry.RegistryGate &lt;registry-root&gt; @changed.txt
 * </pre>
 *
 * <p>The {@code @file} form exists for a security reason rather than a convenience one: the changed paths
 * come from a pull request, so they are attacker-chosen text, and a filename containing a shell
 * metacharacter interpolated into a workflow's {@code run:} line is a command injection. Reading them from
 * a file, one per line, means no shell ever sees them.
 *
 * <p>Exit code: {@code 0} every changed entry passes, {@code 1} something failed, {@code 2} the invocation
 * itself was wrong. The same three the CLI uses, for the same reason — a workflow reads them.
 */
public final class RegistryGate {

    private RegistryGate() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Console console = new Console(false);
        if (args.length < 2) {
            console.error("usage: RegistryGate <registry-root> <changed-path>...");
            return 2;
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        List<String> changed;
        try {
            changed = changedPaths(List.of(args).subList(1, args.length));
        } catch (IOException e) {
            console.error("could not read the changed-path list: " + e.getMessage());
            return 2;
        }

        // The generated file. Refused before anything is resolved, because a pull request that edits it has
        // misunderstood the layout rather than shipped a bad plugin, and telling it to go and download a jar
        // first would be telling it the wrong thing slowly.
        if (changed.stream().anyMatch(path -> path.equals(Registry.INDEX))) {
            console.error(Registry.INDEX + " is generated from " + Registry.ENTRIES_DIRECTORY
                    + "/*.json and committed by CI. Edit " + Registry.ENTRIES_DIRECTORY
                    + "/<plugin-id>.json instead — or let `botmaker publish` write it for you.");
            return 1;
        }

        List<String> entryPaths = changed.stream()
                .filter(path -> path.startsWith(Registry.ENTRIES_DIRECTORY + "/") && path.endsWith(".json"))
                .toList();
        if (entryPaths.isEmpty()) {
            console.out("No registry entries changed; nothing to validate.");
            return 0;
        }

        Registry registry;
        try {
            registry = Registry.read(root.resolve(Registry.ENTRIES_DIRECTORY));
        } catch (IOException e) {
            console.error("could not read " + Registry.ENTRIES_DIRECTORY + ": " + e.getMessage());
            return 1;
        }

        Subjects subjects = new Subjects(console, new Mvn(console));
        Bundled bundled;
        String coordinates = System.getenv(Bundled.ENVIRONMENT_VARIABLE);
        // Unset and set-but-empty are different answers and only one of them is a warning. Unset means
        // nobody said, and the ids a host's own plugins own are then unreserved without anyone deciding
        // that — the hole `Bundled` exists to close. Set and empty is a registry saying *this host bundles
        // nothing*, which has been literally true since botmaker-studio stopped shipping a plugin on
        // 2026-09-02; warning about it would print a false line on every pull request forever, and a
        // warning nobody can act on is how a real one stops being read.
        if (coordinates == null) {
            console.warn(Bundled.ENVIRONMENT_VARIABLE + " is not set, so the ids the host's own bundled"
                    + " plugins own are NOT reserved. Set it to the bundled coordinates, comma separated —"
                    + " or to the empty string if the host bundles nothing.");
            bundled = Bundled.none();
        } else if (coordinates.isBlank()) {
            bundled = Bundled.none();
        } else {
            try {
                bundled = Bundled.resolve(console, subjects, coordinates);
            } catch (IOException e) {
                console.error("could not read the bundled plugins named by " + Bundled.ENVIRONMENT_VARIABLE
                        + ": " + e.getMessage());
                return 1;
            }
        }

        int failures = 0;
        for (String path : entryPaths) {
            Path file = root.resolve(path);
            if (!Files.isRegularFile(file)) {
                // A deletion. Removing a plugin from the index needs no artifact to resolve, and refusing
                // one would make an unmaintained plugin unremovable.
                console.out("removed: " + path);
                continue;
            }
            console.out("");
            console.out("── " + path);
            failures += validate(console, subjects, registry, bundled, file) ? 0 : 1;
        }

        console.out("");
        if (failures > 0) {
            console.out(failures + " entr" + (failures == 1 ? "y" : "ies") + " failed.");
            return 1;
        }
        console.out("All submitted entries pass.");
        return 0;
    }

    /** The paths as given, or — for a single {@code @file} argument — the lines of that file. */
    private static List<String> changedPaths(List<String> args) throws IOException {
        if (args.size() == 1 && args.getFirst().startsWith("@")) {
            return Files.readAllLines(Path.of(args.getFirst().substring(1))).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        }
        return args;
    }

    private static boolean validate(Console console, Subjects subjects, Registry registry, Bundled bundled,
                                    Path file) {
        Registry.Entry submitted;
        try {
            submitted = new Registry.Entry(file,
                    Registry.mapper().readValue(Files.readString(file), RegistryEntry.class));
        } catch (IOException e) {
            console.error("not valid JSON for a registry entry: " + e.getMessage());
            return false;
        }
        RegistryEntry entry = submitted.entry();

        // The layout's own check, and the reason it is worth having: git refuses a second file of the same
        // name, so a filename that equals the plugin's id makes id-uniqueness a property of the repository.
        // A file named after something else silently gives that away.
        if (!submitted.idFromFilename().equals(entry.id())) {
            console.error("the file is named " + submitted.idFromFilename() + ".json but the entry's id is "
                    + entry.id() + ". The filename IS the id: rename the file to " + entry.id() + ".json.");
            return false;
        }
        for (String required : List.of(entry.coordinate(), entry.verifiedVersion(), entry.repo())) {
            if (required == null || required.isBlank()) {
                console.error("coordinate, verifiedVersion and repo are all required; `botmaker publish`"
                        + " fills every one of them in.");
                return false;
            }
        }

        String editorDependencies = editorDependenciesRefusal(entry.editorDependencies());
        if (editorDependencies != null) {
            console.error(editorDependencies);
            return false;
        }

        String coordinate = entry.coordinate() + ":" + entry.verifiedVersion();
        PluginSubject subject;
        try {
            // The bundled ids are unioned in and are never excluded by `except`: an entry claiming a host's
            // own plugin id must be refused, where an entry re-claiming its own id is an update.
            // .about(entry.id()): the filename is the id claim, so this run knows which of the plugins on
            // the resolved classpath the entry is about. A plugin-to-plugin dependency puts more than one
            // there (the SDK brings plugin-basics), and those are registered under their own entries.
            subject = subjects.fromCoordinate(coordinate,
                    Bundled.union(registry.claimedPluginIds(entry.id()), bundled.pluginIds()))
                    .about(entry.id());
        } catch (IOException e) {
            console.error("could not resolve " + coordinate + ": " + e.getMessage());
            return false;
        }

        List<CheckResult> results = PluginValidator.validate(subject);
        console.report(results);
        if (!PluginValidator.passed(results)) {
            console.out(failedChecks(results));
            return false;
        }
        return true;
    }

    /**
     * Why this entry's {@code editorDependencies} cannot be published, or {@code null} when it can.
     *
     * <p><b>Every line here becomes a {@code provided} dependency in a stranger's project pom</b>, written
     * by Studio when somebody installs the plugin. So the shape is checked rather than trusted: a
     * {@code groupId:artifactId:version} with three non-blank parts, named once, and neither the contract nor
     * the toolkit — a bot's pom must declare neither, and an entry asking for one is the {@code pom-scopes}
     * failure one level removed, arriving in a project whose owner never saw this file.
     *
     * <p>It is checked here and not by {@link PluginValidator} because it is a fact about the <em>entry</em>,
     * not about the plugin: the jar resolves identically either way, which is the whole reason the list has
     * to exist. {@code plugin publish} composes it from the pom and so cannot get it wrong; a hand-written
     * entry can, and a hand-written entry is exactly what a pull request may be.
     */
    static String editorDependenciesRefusal(List<String> editorDependencies) {
        List<String> seen = new java.util.ArrayList<>();
        for (String entry : editorDependencies) {
            String[] parts = entry == null ? new String[0] : entry.trim().split(":");
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                return "editorDependencies holds \"" + entry + "\", which is not a"
                        + " groupId:artifactId:version. Studio writes each of these into the pom of every"
                        + " project that installs this plugin, so it has to be a coordinate Maven resolves.";
            }
            String coordinate = parts[0] + ":" + parts[1];
            if (seen.contains(coordinate)) {
                return "editorDependencies names " + coordinate + " twice. Two versions of one artifact on a"
                        + " classpath is the least diagnosable failure this platform has.";
            }
            seen.add(coordinate);
            if (PluginValidator.CONTRACT_GROUP.equals(parts[0])
                    && (PluginValidator.CONTRACT_ARTIFACT.equals(parts[1])
                    || PluginValidator.TOOLKIT_ARTIFACT.equals(parts[1]))) {
                return "editorDependencies names " + coordinate + ", which a bot's pom must never declare."
                        + " The host already has the contract — a second copy makes a contract class two"
                        + " different classes — and the toolkit is this plugin's own dependency at compile"
                        + " scope, so it arrives with the plugin. Take it out of the entry and declare it in"
                        + " the plugin's pom.";
            }
        }
        return null;
    }

    /**
     * The failing checks by id, on one line.
     *
     * <p>So the pull request's check list names what went wrong without anybody opening a log — which is the
     * difference between a contributor fixing their plugin and a contributor waiting for a maintainer.
     */
    private static String failedChecks(List<CheckResult> results) {
        return "FAILED: " + String.join(", ",
                results.stream().filter(CheckResult::failed).map(r -> r.check().id()).toList());
    }
}
