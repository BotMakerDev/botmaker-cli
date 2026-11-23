// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli;

import com.botmaker.cli.mcp.McpBridge;
import com.botmaker.cli.update.StudioUpdate;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker studio}: starts the released Studio, or with {@code dev} the checkout's, built first; {@code set}
 * says once where each is. Studio runs on its own and its output goes to a log in BotMaker's cache folder.
 */
@Command(name = "studio",
        header = "Start BotMaker Studio: the released one, or your checkout's with `dev`.",
        description = "Studio starts on its own and this command returns; what it prints goes to a log, whose path is "
                + "said. The released Studio is --studio, else $BOTMAKER_STUDIO, else what `studio set --command` "
                + "saved, else the package's /opt/botmaker-studio/bin/BotMaker Studio.",
        mixinStandardHelpOptions = true,
        // `update` is Studio's updater (update.StudioUpdate), which Studio's Check for Updates runs too.
        subcommands = {StudioCommand.Dev.class, StudioCommand.Set.class, StudioCommand.Update.class})
final class StudioCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Parameters(arity = "0..1", paramLabel = "<project>", description = "A project under ~/BotMakerProjects to open.")
    private String project;

    @Option(names = "--studio", paramLabel = "<command>", description = "The released Studio's command.")
    private String studioCommand;

    @Override
    public Integer call() throws IOException {
        Console console = main.console();
        Optional<List<String>> released = StudioPlaces.released(studioCommand);
        if (released.isEmpty()) {
            console.error("No released Studio here: install it, or say where it is with "
                    + "`botmaker studio set --command <command>`.");
            return 1;
        }
        List<String> argv = new ArrayList<>(released.get());
        if (project != null && !project.isBlank()) argv.add("--project=" + project.strip());
        return detached(console, argv, null, "studio.log", Map.of());
    }

    /**
     * {@code argv} started in {@code dir} on its own with {@code environment} added, its output in {@code log} in the
     * cache folder; 0, or 1 when unstarted.
     */
    static int detached(Console console, List<String> argv, Path dir, String log, Map<String, String> environment)
            throws IOException {
        Path to = McpBridge.cacheRoot().resolve(log);
        Files.createDirectories(to.getParent());
        console.step("$ " + String.join(" ", argv));
        try {
            ProcessBuilder builder = new ProcessBuilder(argv).directory(dir == null ? null : dir.toFile())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(to.toFile()));
            builder.environment().putAll(environment);
            builder.start();
        } catch (IOException unstarted) {
            console.error("Studio did not start: " + unstarted.getMessage());
            return 1;
        }
        console.out("Studio is starting; what it prints is in " + to);
        return 0;
    }

    @Command(name = "dev",
            header = "Start the dev Studio of your BotMaker checkout, built first.",
            description = "Installs botmaker-studio and every module it is built from (mvn -pl botmaker-studio -am "
                    + "install, no tests), so what you changed is in, then starts it with javafx:run. The checkout "
                    + "is --umbrella, else $BOTMAKER_UMBRELLA, else what `studio set --umbrella` saved. It serves "
                    + "the assistant's ui tool over its own windows, so Claude can drive it, unless --no-ui.",
            mixinStandardHelpOptions = true)
    static final class Dev implements Callable<Integer> {

        @ParentCommand
        private StudioCommand studio;

        @Parameters(arity = "0..1", paramLabel = "<project>",
                description = "A project under ~/BotMakerProjects to open.")
        private String project;

        @Option(names = "--umbrella", paramLabel = "<dir>", description = "The BotMaker checkout.")
        private String umbrella;

        @Option(names = "--no-build", description = "Start on what ~/.m2 holds; build nothing.")
        private boolean noBuild;

        // On by default (2026-11-23, the user's call): a dev Studio is one's own checkout, and Claude drives it without
        // a restart. The released Studio never serves the ui tool.
        @Option(names = "--no-ui", description = "Serve no ui tool: Claude cannot press or read Studio's windows.")
        private boolean noUi;

        @Override
        public Integer call() throws IOException {
            Console console = studio.main.console();
            Optional<Path> given = StudioPlaces.umbrella(umbrella);
            if (given.isEmpty()) {
                console.error("No checkout given: `botmaker studio set --umbrella <dir>` once, or --umbrella, or "
                        + "$BOTMAKER_UMBRELLA.");
                return 1;
            }
            Path checkout;
            try {
                checkout = StudioPlaces.checkout(given.get());
            } catch (IllegalArgumentException notOne) {
                console.error(notOne.getMessage());
                return 1;
            }
            String mvn = Mvn.executable(checkout);
            if (!noBuild) {
                // A dev launcher, not a gate: tests and the contract's japicmp are for /test and the release.
                List<String> build = List.of(mvn, "-B", "-q", "-nsu", "-pl", "botmaker-studio", "-am", "install",
                        "-DskipTests", "-Dbotmaker.japicmp.skip=true", "-Djapicmp.skip=true");
                console.step("$ " + String.join(" ", build));
                int built = run(build, checkout);
                if (built != 0) {
                    console.error("The build failed (exit " + built + "); Studio was not started. --no-build starts "
                            + "it on what ~/.m2 holds.");
                    return 1;
                }
            }
            List<String> argv = new ArrayList<>(List.of(mvn, "-B", "-o", "-pl", "botmaker-studio", "javafx:run"));
            if (project != null && !project.isBlank()) argv.add("-Djavafx.args=--project=" + project.strip());
            return detached(console, argv, checkout, "studio-dev.log", McpBridge.environment("", !noUi));
        }

        private static int run(List<String> argv, Path dir) throws IOException {
            Process process = new ProcessBuilder(argv).directory(dir.toFile()).inheritIO().start();
            try {
                return process.waitFor();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                process.destroy();
                throw new IOException("interrupted while building", stopped);
            }
        }
    }

    @Command(name = "update",
            header = "Update the installed Studio to its latest release, the way it was installed.",
            description = "The rpm or deb is upgraded from its repository by dnf or apt (else the release's package "
                    + "is installed), an AppImage replaced, the MSI run, the .app replaced from the dmg; each "
                    + "download is checked against the release's checksums_sha256.txt. Root is asked by sudo, else "
                    + "pkexec. Refused while Studio runs. The Studio is --studio, else $BOTMAKER_STUDIO, else what "
                    + "`studio set --command` saved, else where its installer puts it.",
            mixinStandardHelpOptions = true)
    static final class Update implements Callable<Integer> {

        @ParentCommand
        private StudioCommand studio;

        @Option(names = "--check", description = "Say where the installed Studio stands; install nothing.")
        private boolean check;

        @Option(names = "--studio", paramLabel = "<path>", description = "The installed Studio's program or AppImage.")
        private String studioCommand;

        @Override
        public Integer call() throws IOException {
            Console console = studio.main.console();
            Optional<Path> given = StudioPlaces.released(studioCommand)
                    .filter(argv -> argv.size() == 1).map(argv -> Path.of(argv.getFirst()));
            StudioUpdate update = StudioUpdate.forCommand(given);
            if (check) {
                StudioUpdate.Check checked = update.check();
                String latest = checked.latest().map(tag -> "the latest release is " + tag)
                        .orElse("its latest release could not be read");
                console.out(checked.installed().map(v -> "BotMaker Studio " + v)
                        .orElse("BotMaker Studio (its version could not be read)") + "; " + latest);
                console.out(checked.install().describe());
                if (!checked.running().isEmpty()) {
                    console.out("running now (process " + checked.running().getFirst() + "): close it to update");
                }
                if (checked.updatable()) {
                    console.out("botmaker studio update installs it");
                } else if (checked.installed().isEmpty() && checked.latest().isPresent() && checked.managed()) {
                    console.out("botmaker studio update installs the latest release");
                }
                return 0;
            }
            switch (update.update(fraction -> { })) {
                case StudioUpdate.Outcome.Current current ->
                        console.out("BotMaker Studio is up to date (latest release " + current.tag() + ").");
                case StudioUpdate.Outcome.Updated updated ->
                        console.out("BotMaker Studio " + updated.tag() + " is installed.");
                case StudioUpdate.Outcome.Started started ->
                        console.out("BotMaker Studio " + started.tag() + "'s installer is started.");
            }
            return 0;
        }
    }

    @Command(name = "set",
            header = "Say once where Studio is: your checkout, a released Studio's command, or both.",
            description = "Saved in BotMaker's config folder (studio-places.properties) and read by `studio`, "
                    + "`studio dev`, `plugin run` and `mcp-bridge` after their flag and environment variable. With "
                    + "neither option, says what is saved.",
            mixinStandardHelpOptions = true)
    static final class Set implements Callable<Integer> {

        @ParentCommand
        private StudioCommand studio;

        @Option(names = "--umbrella", paramLabel = "<dir>",
                description = "The BotMaker checkout, or its botmaker-studio folder.")
        private Path umbrella;

        @Option(names = "--command", paramLabel = "<command>",
                description = "The released Studio's command, an executable's path or a command line.")
        private String command;

        @Option(names = "--forget-umbrella", description = "Forget the saved checkout.")
        private boolean forgetUmbrella;

        @Option(names = "--forget-command", description = "Forget the saved released command.")
        private boolean forgetCommand;

        @Override
        public Integer call() throws IOException {
            Console console = studio.main.console();
            StudioPlaces saved = StudioPlaces.saved();
            if (forgetUmbrella || forgetCommand) {
                saved = new StudioPlaces(forgetUmbrella ? Optional.empty() : saved.umbrella(),
                        forgetCommand ? Optional.empty() : saved.command());
                saved.save();
            }
            if (umbrella == null && command == null) {
                console.out("checkout: " + saved.umbrella().map(Path::toString).orElse("(none)"));
                console.out("released: " + saved.command().orElse("(none)"));
                console.out("in " + StudioPlaces.file());
                return 0;
            }
            Optional<Path> checkout = saved.umbrella();
            if (umbrella != null) {
                try {
                    checkout = Optional.of(StudioPlaces.checkout(umbrella));
                } catch (IllegalArgumentException notOne) {
                    console.error(notOne.getMessage());
                    return 1;
                }
            }
            Optional<String> released = command == null ? saved.command()
                    : Optional.of(command.strip()).filter(s -> !s.isEmpty());
            new StudioPlaces(checkout, released).save();
            console.out("checkout: " + checkout.map(Path::toString).orElse("(none)"));
            console.out("released: " + released.orElse("(none)"));
            return 0;
        }
    }
}
