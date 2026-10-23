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
 * shape is three dependency scopes, a services file, a palette and an editor, and every one of them is wrong
 * in a way that produces no compile error — so it has exactly one source of truth, a plugin its own CI builds
 * and tests. This jar bundles that plugin's sources ({@link PluginTemplate}), so the command needs no network
 * and no Maven.
 *
 * <p>What it adds over copying the repository by hand is the renaming nobody gets right twice: package and
 * directories, plugin id and name, the pom's coordinates, and the services file.
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

    /**
     * Deliberately not defaulted to the groupId. A plugin id must be unique across the registry, and a groupId
     * is not: two plugins from one author share it.
     */
    @Option(names = "--plugin-id",
            description = "The StudioPlugin id; must be unique in the catalog. Default: <group>.<name>")
    private String pluginId;

    @Option(names = "--plugin-name", description = "The name a user reads. Default: the artifact id, titled")
    private String pluginName;

    @Option(names = "--plugin-version", defaultValue = "0.1.0-SNAPSHOT",
            description = "The generated project's own version. Default: ${DEFAULT-VALUE}")
    private String pluginVersion;

    /**
     * No default of its own: the template pins the contract released with this {@code botmaker}, and a
     * version written here would owe an edit on every contract release.
     */
    @Option(names = "--studio-api",
            description = "botmaker-studio-api version in the generated pom. Default: the release this "
                    + "botmaker was built with")
    private String studioApiVersion;

    @Option(names = "--toolkit",
            description = "botmaker-plugin-toolkit version in the generated pom. Default: the release this "
                    + "botmaker was built with")
    private String toolkitVersion;

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
                    pluginId != null ? pluginId : group + "." + name,
                    pluginName != null ? pluginName : title(name),
                    Optional.ofNullable(studioApiVersion), Optional.ofNullable(toolkitVersion));
        } catch (IllegalArgumentException e) {
            console.error(e.getMessage() + " (see --package and --plugin-name)");
            return 1;
        }
        PluginTemplate.Made made;
        try (InputStream template = PluginTemplate.bundled()) {
            made = PluginTemplate.write(template, target, answers);
        }
        console.out("Created " + target + " (contract " + made.studioApiVersion() + ", toolkit "
                + made.toolkitVersion() + ")");
        if (made.studioApiVersion().endsWith("-SNAPSHOT") || made.toolkitVersion().endsWith("-SNAPSHOT")) {
            console.out("  A -SNAPSHOT pin resolves from this computer's ~/.m2 only: run `mvn install` in the "
                    + "BotMaker umbrella first, or pass --studio-api and --toolkit a released tag.");
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
