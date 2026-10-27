package com.botmaker.cli;

import com.botmaker.cli.project.Poms;
import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.PomVersions;
import com.botmaker.cli.validate.PluginValidator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code botmaker plugin upgrade [--to vX]} — the plugin's {@code botmaker-plugin-parent} moved to its newest release
 * (or {@code --to}'s), then compiled and validated (2026-10-27). The parent pins the contract, the toolkit and JavaFX
 * together, so this one version is everything a plugin builds against.
 *
 * <p>The parent's version literal is moved as the release library moves a child's parent pin, not by
 * {@code versions:update-parent}: any mojo first resolves the parent the pom names now, and the {@code -SNAPSHOT} that
 * publish sends the author here to replace is in no {@code ~/.m2} but the one that built it.
 */
@Command(name = "upgrade",
        header = "Move the plugin's botmaker-plugin-parent to its newest release, then check the plugin.",
        description = "The parent pins the contract, the toolkit and JavaFX together. After the move the plugin is "
                + "compiled and run through the checks `botmaker plugin validate` runs.",
        mixinStandardHelpOptions = true)
final class PluginUpgradeCommand implements Callable<Integer> {

    /** Where the parent's releases are. */
    static final String PARENT_REPO = "BotMakerDev/" + PluginValidator.PARENT_ARTIFACT;

    private static final Pattern TAG = Pattern.compile("[\\w.+-]+");

    @ParentCommand
    private PluginCommand parent;

    @Option(names = "--to", paramLabel = "<tag>",
            description = "The parent release to move to. Default: its newest GitHub Release")
    private String to;

    @Option(names = "--dir", defaultValue = ".", description = "The plugin project. Default: ${DEFAULT-VALUE}")
    private String directory;

    @Override
    public Integer call() throws IOException {
        Console console = parent.main().console();
        Path dir = Path.of(directory).toAbsolutePath().normalize();
        Path pom = dir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            console.error("no pom.xml in " + dir);
            return 1;
        }
        Optional<Poms.Dependency> current = Poms.parent(pom)
                .filter(p -> p.artifactId().equals(PluginValidator.PARENT_ARTIFACT));
        if (current.isEmpty()) {
            console.error("pom.xml names no " + PluginValidator.PARENT_ARTIFACT + " <parent>, which is what this moves."
                    + " A plugin made by `botmaker plugin new` has one.");
            return 1;
        }
        String target = to != null ? to.strip() : BotNewCommand.newestRelease(PARENT_REPO);
        String from = current.get().version();
        if (target.equals(from)) {
            console.out(PluginValidator.PARENT_ARTIFACT + " is at " + from + " already.");
            return 0;
        }
        if (!TAG.matcher(target).matches()) {
            console.error("'" + target + "' is not a release tag");
            return 1;
        }
        String text = Files.readString(pom);
        Matcher version = PomVersions.parentVersion(Module.PLUGIN_PARENT).matcher(text);
        if (!version.find()) {
            console.error("could not move " + PluginValidator.PARENT_ARTIFACT + " from " + from + ": its <version> "
                    + "must come right after its <artifactId> in the <parent>, as `botmaker plugin new` writes it");
            return 1;
        }
        Files.writeString(pom, text.substring(0, version.start()) + version.group(1) + target + version.group(2)
                + text.substring(version.end()));
        console.out("Moved " + PluginValidator.PARENT_ARTIFACT + " from " + from + " to " + target + ".");
        return PluginValidateCommand.report(console, parent.main().subjects().fromDirectory(dir, true));
    }
}
