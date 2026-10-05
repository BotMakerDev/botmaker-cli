package com.botmaker.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code botmaker plugin new <name>} — the archetype, with the prompts already answered.
 *
 * <p><b>It shells to {@code botmaker-plugin-archetype} and carries no templates of its own.</b> The
 * generated shape is three dependency scopes, a services file, a palette and an editor, and every one of
 * them is wrong in a way that produces no compile error — so it must have exactly one source of truth. Two
 * copies of a skeleton drift, and they drift silently, because nothing ever compiles both.
 *
 * <p>What this adds over typing {@code mvn archetype:generate} is the part nobody remembers: the archetype's
 * own coordinate, {@code -DinteractiveMode=false}, and sane values for the four required properties derived
 * from one name.
 */
@Command(name = "new",
        header = "Generate a plugin project from the archetype.",
        description = "Every value below has a default derived from the artifact id, so the id alone is a "
                + "working command line.",
        mixinStandardHelpOptions = true)
final class PluginNewCommand implements Callable<Integer> {

    private static final String ARCHETYPE_GROUP = "com.github.BotMakerDev";
    private static final String ARCHETYPE_ARTIFACT = "botmaker-plugin-archetype";

    /**
     * The BotMaker versions written into the generated pom.
     *
     * <p>{@code main-SNAPSHOT} is the archetype's own default and this passes it through rather than
     * substituting a release. A released tag baked in here would owe an edit on every contract release and
     * age silently between them; {@code main-SNAPSHOT} is a real JitPack coordinate that never goes stale,
     * and it is wrong in the direction the generated README tells the author to correct.
     */
    private static final String DEFAULT_BOTMAKER_VERSION = "main-SNAPSHOT";

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
     * Deliberately not defaulted to the groupId, which is what {@code mvn archetype:generate} does. A plugin
     * id must be unique across the registry, and a groupId is not: two plugins from one author share it.
     */
    @Option(names = "--plugin-id",
            description = "The StudioPlugin id; must be unique in the registry. Default: <group>.<name>")
    private String pluginId;

    @Option(names = "--plugin-name", description = "The name a user reads. Default: the artifact id, titled")
    private String pluginName;

    @Option(names = "--plugin-version", defaultValue = "0.1.0-SNAPSHOT",
            description = "The generated project's own version. Default: ${DEFAULT-VALUE}")
    private String pluginVersion;

    @Option(names = "--studio-api", defaultValue = DEFAULT_BOTMAKER_VERSION,
            description = "botmaker-studio-api version in the generated pom. Default: ${DEFAULT-VALUE}")
    private String studioApiVersion;

    @Option(names = "--toolkit", defaultValue = DEFAULT_BOTMAKER_VERSION,
            description = "botmaker-plugin-toolkit version in the generated pom. Default: ${DEFAULT-VALUE}")
    private String toolkitVersion;

    @Option(names = "--archetype-version", defaultValue = DEFAULT_BOTMAKER_VERSION,
            description = "The archetype's own version. Default: ${DEFAULT-VALUE}")
    private String archetypeVersion;

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
        Files.createDirectories(parentDir);

        String javaPackage = pkg != null ? pkg : group + "." + name.replace("-", "").toLowerCase();
        List<String> goals = new ArrayList<>(List.of(
                "archetype:generate",
                "-DinteractiveMode=false",
                "-DarchetypeGroupId=" + ARCHETYPE_GROUP,
                "-DarchetypeArtifactId=" + ARCHETYPE_ARTIFACT,
                "-DarchetypeVersion=" + archetypeVersion,
                "-DgroupId=" + group,
                "-DartifactId=" + name,
                "-Dversion=" + pluginVersion,
                "-Dpackage=" + javaPackage,
                "-DpluginId=" + (pluginId != null ? pluginId : group + "." + name),
                "-DpluginName=" + (pluginName != null ? pluginName : title(name)),
                "-DstudioApiVersion=" + studioApiVersion,
                "-DtoolkitVersion=" + toolkitVersion));

        Mvn.Result result = parent.main().mvn().runInteractive(parentDir, goals.toArray(String[]::new));
        if (!result.ok()) {
            console.error("mvn archetype:generate failed");
            return 1;
        }
        console.out("Created " + target);
        console.out("");
        console.out("  cd " + name);
        console.out("  mvn verify         # the generated tests should pass unedited");
        console.out("  botmaker plugin validate  # the same eight checks the registry runs");
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
