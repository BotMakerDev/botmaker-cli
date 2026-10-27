package com.botmaker.cli;

import com.botmaker.cli.project.Poms;
import com.botmaker.cli.release.PomVersions;
import com.botmaker.cli.validate.PluginClasses;
import com.botmaker.plugin.host.PluginPoms;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code botmaker plugin declare [class]} — the pom's {@code botmaker.plugin} set to the plugin class (2026-10-27), the
 * one line that makes a jar a plugin. {@code validate} offers it when the property is missing or names a class that
 * is not the plugin.
 *
 * <p>The class is one the build holds that extends {@code DeclaredPlugin} directly ({@link PluginClasses}): the one
 * there is, or the one named among several. A pom that sets the property already has it moved by
 * {@code versions:set-property}, as the release library moves a pin; one that lacks it gets the line at the top of its
 * {@code <properties>}, which no Maven goal adds.
 */
@Command(name = "declare",
        header = "Set the pom's botmaker.plugin to the plugin class.",
        description = "Compiles, finds the class extending DeclaredPlugin directly, and names it in pom.xml's "
                + "<properties>, the line that makes the jar a plugin.",
        mixinStandardHelpOptions = true)
final class PluginDeclareCommand implements Callable<Integer> {

    @ParentCommand
    private PluginCommand parent;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<class>",
            description = "The plugin class, by its binary name. Default: the one class extending DeclaredPlugin")
    private String className;

    @Option(names = "--dir", defaultValue = ".", description = "The plugin project. Default: ${DEFAULT-VALUE}")
    private String directory;

    @Option(names = "--no-build", description = "Trust the existing target/classes.")
    private boolean noBuild;

    /** The pom's first {@code <properties>} opening a line, with its indentation and line break. */
    private static final Pattern PROPERTIES = Pattern.compile("(?m)^([ \\t]*)<properties>[ \\t]*(\\R)");
    /** The line {@code <properties>} is inserted before when the pom has none. */
    private static final Pattern BEFORE = Pattern.compile("(?m)^([ \\t]*)<(repositories|dependencyManagement|dependencies|build)>");

    @Override
    public Integer call() throws IOException {
        Console console = parent.main().console();
        Path dir = Path.of(directory).toAbsolutePath().normalize();
        Path pom = dir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            console.error("no pom.xml in " + dir);
            return 1;
        }
        // The plugin's own classes over its resolved classpath, as validate loads them: a supertype from the toolkit
        // resolves, so the class is found rather than skipped as unloadable.
        List<Path> classpath = parent.main().subjects().fromDirectory(dir, !noBuild).classpath();
        Choice choice = choose(PluginClasses.declarable(classpath.getFirst(), classpath), className);
        if (choice instanceof Choice.Refused(String why)) {
            console.error(why);
            return 1;
        }
        String plugin = ((Choice.Chosen) choice).className();
        String current = Poms.properties(pom).get(PluginPoms.PROPERTY);
        if (plugin.equals(current)) {
            console.out("pom.xml already sets " + PluginPoms.PROPERTY + " to " + plugin + ".");
            return 0;
        }
        if (current != null) {
            Mvn.Result set = parent.main().mvn().run(dir, "-q", PomVersions.PLUGIN + ":set-property",
                    "-Dproperty=" + PluginPoms.PROPERTY, "-DnewVersion=" + plugin, "-DgenerateBackupPoms=false");
            if (!set.ok() || !plugin.equals(Poms.properties(pom).get(PluginPoms.PROPERTY))) {
                console.error("could not set " + PluginPoms.PROPERTY + " in pom.xml:\n" + set.tail(10));
                return 1;
            }
        } else {
            try {
                Files.writeString(pom, inserted(Files.readString(pom), plugin));
            } catch (IllegalStateException unplaced) {
                console.error(unplaced.getMessage());
                return 1;
            }
        }
        console.out("Set " + PluginPoms.PROPERTY + " to " + plugin + " in pom.xml. `botmaker plugin validate` checks"
                + " the rest.");
        return 0;
    }

    /** The class to declare, or why there is none. */
    sealed interface Choice {
        record Chosen(String className) implements Choice {}

        record Refused(String why) implements Choice {}
    }

    /** {@code requested} when it is a candidate, else the one candidate there is. */
    static Choice choose(List<String> candidates, String requested) {
        if (requested != null) {
            return candidates.contains(requested) ? new Choice.Chosen(requested)
                    : new Choice.Refused(requested + " is not a class in target/classes extending DeclaredPlugin"
                            + " directly" + (candidates.isEmpty() ? "" : "; these are: " + String.join(", ", candidates)));
        }
        return switch (candidates.size()) {
            case 0 -> new Choice.Refused("no class in target/classes extends DeclaredPlugin directly: a plugin is a"
                    + " public final class extending DeclaredPlugin, with a public no-argument constructor");
            case 1 -> new Choice.Chosen(candidates.getFirst());
            default -> new Choice.Refused("several classes extend DeclaredPlugin, and a jar is one plugin: name one,"
                    + " `botmaker plugin declare <class>` (" + String.join(", ", candidates) + ")");
        };
    }

    /** {@code pom} with the property as the first line of its {@code <properties>}, made when it has none. */
    static String inserted(String pom, String plugin) {
        // Only the project's own elements, never a profile's: those come after <profiles>.
        int profiles = pom.indexOf("<profiles>");
        String own = profiles < 0 ? pom : pom.substring(0, profiles);
        Matcher properties = PROPERTIES.matcher(own);
        if (properties.find()) {
            String line = properties.group(1) + "    " + element(plugin) + properties.group(2);
            return pom.substring(0, properties.end()) + line + pom.substring(properties.end());
        }
        if (own.contains("<properties")) {
            throw new IllegalStateException("pom.xml's <properties> does not open a line of its own; add "
                    + element(plugin) + " to it by hand");
        }
        Matcher before = BEFORE.matcher(own);
        if (!before.find()) throw new IllegalStateException("pom.xml has no <properties> and nothing to put one before");
        String indent = before.group(1);
        String block = indent + "<properties>\n" + indent + "    " + element(plugin) + "\n" + indent + "</properties>\n\n";
        return pom.substring(0, before.start()) + block + pom.substring(before.start());
    }

    private static String element(String plugin) {
        return "<" + PluginPoms.PROPERTY + ">" + plugin + "</" + PluginPoms.PROPERTY + ">";
    }
}
