package com.botmaker.cli;

import com.botmaker.cli.project.Poms;
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

/**
 * {@code botmaker plugin upgrade [--to vX]} — the plugin's {@code botmaker-plugin-parent} moved to its newest release
 * (or {@code --to}'s), then compiled and validated (2026-10-27). The parent pins the contract, the toolkit and JavaFX
 * together, so this one version is everything a plugin builds against.
 *
 * <p>The pom changes by {@code versions:update-parent}, as the release library's goals move a module's pins; with
 * {@code skipResolution} it writes the tag named, which JitPack serves under that name and no version range finds.
 */
@Command(name = "upgrade",
        header = "Move the plugin's botmaker-plugin-parent to its newest release, then check the plugin.",
        description = "The parent pins the contract, the toolkit and JavaFX together. After the move the plugin is "
                + "compiled and run through the checks `botmaker plugin validate` runs.",
        mixinStandardHelpOptions = true)
final class PluginUpgradeCommand implements Callable<Integer> {

    /** Where the parent's releases are. */
    static final String PARENT_REPO = "BotMakerDev/" + PluginValidator.PARENT_ARTIFACT;

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
        Mvn.Result moved = parent.main().mvn().run(dir, "-q", PomVersions.PLUGIN + ":update-parent",
                "-DparentVersion=" + target, "-DskipResolution=true", "-DallowSnapshots=true",
                "-DgenerateBackupPoms=false");
        if (!moved.ok() || !Poms.parent(pom).map(Poms.Dependency::version).orElse("").equals(target)) {
            console.error("could not move " + PluginValidator.PARENT_ARTIFACT + " from " + from + " to " + target
                    + " (Maven must first read the parent the pom names now):\n" + moved.tail(10));
            return 1;
        }
        console.out("Moved " + PluginValidator.PARENT_ARTIFACT + " from " + from + " to " + target + ".");
        return PluginValidateCommand.report(console, parent.main().subjects().fromDirectory(dir, true));
    }
}
