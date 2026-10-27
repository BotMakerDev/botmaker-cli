package com.botmaker.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker plugin new <name>} — the example plugin, copied and renamed.
 *
 * <p><b>It copies {@code botmaker-plugin-archetype} and carries no templates of its own.</b> The generated
 * shape is three dependency scopes, the pom's {@code botmaker.plugin} class, a palette and an editor, and every one
 * of them is wrong in a way that produces no compile error — so it has exactly one source of truth, a plugin its own CI builds
 * and tests. This jar bundles that plugin's sources ({@link PluginTemplate}), so the command needs no network
 * and no Maven.
 *
 * <p>What it adds over copying the repository by hand is the renaming nobody gets right twice: package and
 * directories, the plugin's name, the pom's coordinates (the plugin's identity) and the class it names.
 */
@Command(name = "new",
        header = "Create a plugin project from the example plugin.",
        description = "Every value below has a default derived from the artifact id, so the id alone is a "
                + "working command line.",
        mixinStandardHelpOptions = true)
final class PluginNewCommand implements Callable<Integer> {

    @ParentCommand
    private PluginCommand parent;

    @Parameters(index = "0", paramLabel = "<artifact-id>",
            description = "The Maven artifact id, and the directory name. e.g. discord-notifier")
    private String name;

    @Option(names = "--group", defaultValue = "com.example", description = "groupId. Default: ${DEFAULT-VALUE}")
    private String group;

    @Option(names = "--package", description = "Java package. Default: <group>.<name with no punctuation>")
    private String pkg;

    @Option(names = "--plugin-name", description = "The name a user reads. Default: the artifact id, titled")
    private String pluginName;

    @Option(names = "--plugin-version", defaultValue = "0.1.0-SNAPSHOT",
            description = "The generated project's own version. Default: ${DEFAULT-VALUE}")
    private String pluginVersion;

    /**
     * No default of its own: the template names the parent released with this {@code botmaker}, and a version
     * written here would owe an edit on every release. The parent pins the contract and the toolkit together.
     */
    @Option(names = "--parent",
            description = "botmaker-plugin-parent version the generated pom names; it pins the contract and the "
                    + "toolkit. Default: the release this botmaker was built with")
    private String parentVersion;

    @Option(names = "--dir", defaultValue = ".", description = "Where to generate. Default: ${DEFAULT-VALUE}")
    private String directory;

    @Override
    public Integer call() throws IOException {
        Console console = parent.main().console();
        Path parentDir = Path.of(directory).toAbsolutePath().normalize();
        Path target = parentDir.resolve(name);
        if (Files.exists(target)) {
            console.error(target + " already exists");
            return 1;
        }

        String javaPackage = pkg != null ? pkg : group + "." + name.replace("-", "").toLowerCase();
        PluginTemplate.Answers answers;
        try {
            answers = new PluginTemplate.Answers(group, name, pluginVersion, javaPackage,
                    pluginName != null ? pluginName : title(name),
                    Optional.ofNullable(parentVersion));
        } catch (IllegalArgumentException e) {
            console.error(e.getMessage() + " (see --package and --plugin-name)");
            return 1;
        }
        PluginTemplate.Made made;
        try (InputStream template = PluginTemplate.bundled()) {
            made = PluginTemplate.write(template, target, answers);
        }
        console.out("Created " + target + " (" + PluginTemplate.PARENT + " " + made.parentVersion() + ")");
        if (made.parentVersion().endsWith("-SNAPSHOT")) {
            console.out("  A -SNAPSHOT parent resolves from this computer's ~/.m2 only: run `mvn install` in the "
                    + "BotMaker umbrella first, or pass --parent a released tag.");
        }
        console.out("");
        console.out("  cd " + name);
        console.out("  mvn verify               # the example's tests pass unedited");
        console.out("  botmaker plugin validate # the checks the plugin registry runs");
        return 0;
    }

    /** {@code discord-notifier} to {@code Discord Notifier} — the name a user reads in Studio. */
    private static String title(String artifactId) {
        StringBuilder out = new StringBuilder();
        for (String word : artifactId.split("[-_.]")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.isEmpty() ? artifactId : out.toString();
    }
}
