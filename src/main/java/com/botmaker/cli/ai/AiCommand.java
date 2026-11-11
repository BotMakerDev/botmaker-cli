package com.botmaker.cli.ai;

import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import sun.misc.Signal;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code botmaker ai <tool>} — an AI tool started in a bot's folder with no Studio, under the rule Studio's Assistant
 * window keeps: it changes the bot through BotMaker's MCP tools only, and reads files freely. The tool is pointed
 * at this cli's own {@code mcp-bridge} ({@link AiTool.Connection.Stdio}) and denied its edits in its own vocabulary
 * ({@link AiTool#launch}). The terminal is the tool's until it exits; its exit code is this command's.
 */
@Command(name = "ai",
        header = "Start an AI tool on a bot, changing it only through BotMaker's MCP tools.",
        description = "In the bot's folder, starts Claude Code, Codex, Gemini CLI or opencode with BotMaker's MCP "
                + "tools (this cli's mcp-bridge: no Studio needed) and its own file edits denied. Exit 2: not a bot "
                + "folder or no such tool; 1: the tool is not installed; otherwise the tool's own exit code.",
        mixinStandardHelpOptions = true)
public final class AiCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<tool>",
            description = "claude (or claude-code), codex, gemini or opencode.")
    private String tool;

    @Parameters(index = "1..*", paramLabel = "<arg>",
            description = "Passed to the tool after BotMaker's own, behind -- when one starts with a dash: "
                    + "botmaker ai codex -- exec \"list my projects\".")
    private List<String> toolArgs = List.of();

    @Option(names = {"-C", "--folder"}, paramLabel = "<folder>",
            description = "The bot's folder; this folder when not given.")
    private Path folder;

    @Option(names = "--allow-shell",
            description = "Let the tool run shell commands (Codex always may, in its read-only sandbox).")
    private boolean allowShell;

    @Override
    public Integer call() throws IOException, InterruptedException {
        AiTool picked = AiTool.named(tool);
        if (picked == AiTool.UNKNOWN) {
            System.err.println("No AI tool named " + tool + ". Pick one of: claude, codex, gemini, opencode.");
            return ExitCode.USAGE;
        }
        Path bot = (folder == null ? Path.of("") : folder).toAbsolutePath().normalize();
        Optional<String> notABot = notABot(bot);
        if (notABot.isPresent()) {
            System.err.println(notABot.get());
            return ExitCode.USAGE;
        }
        Optional<Path> onPath = picked.locate(System.getenv("PATH"), Path.of(System.getProperty("user.home")));
        String shell = System.getenv("SHELL");
        Optional<Path> exe = onPath.isPresent() || AiTool.isWindows() ? onPath : picked.locateByLoginShell(shell);
        if (exe.isEmpty()) {
            System.err.println(picked.displayName() + " is not installed: " + picked.installHint());
            return ExitCode.SOFTWARE;
        }
        for (BotGuard.Guard guard : BotGuard.ensure(bot)) {
            // Only what concerns this session: another tool's own setting is the user's business.
            if (guard.tool() == picked || guard.tool() == AiTool.UNKNOWN) guard.warning(bot).ifPresent(System.err::println);
        }
        Path dir = privateDir();
        Process[] started = new Process[1];
        Thread cleanup = new Thread(() -> {
            if (started[0] != null) started[0].destroy();
            deleteTree(dir);
        }, "botmaker-ai-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        AiTool.Launch launch = picked.launch(exe.get(), new AiTool.Connection.Stdio(bridgeCommand(), dir), allowShell);
        List<String> command = new ArrayList<>(launch.command());
        command.addAll(toolArgs);
        // Only the login shell knew where the tool is: only it has the PATH the tool needs (node, mostly).
        if (onPath.isEmpty()) command = AiTool.viaLoginShell(shell, command);
        System.err.println(picked.displayName() + " on " + bot + ": " + picked.guard(allowShell));
        ProcessBuilder builder = new ProcessBuilder(command).directory(bot.toFile()).inheritIO();
        builder.environment().putAll(launch.env());
        ignoreInterrupts();
        started[0] = builder.start();
        int code = started[0].waitFor();
        Runtime.getRuntime().removeShutdownHook(cleanup);
        deleteTree(dir);
        return code;
    }

    /**
     * Why {@code folder} is not a bot's, or empty when it is one: a Maven project that declares no plugin. A
     * plugin's folder is refused, since the guard would keep its author from writing its code.
     */
    static Optional<String> notABot(Path folder) throws IOException {
        Path pom = folder.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return Optional.of(folder + " is not a bot's folder (no pom.xml). Run this in the bot's folder, or name it.");
        }
        // Latin-1 reads any byte, and the property is ASCII in every encoding a pom is written in.
        if (Files.readString(pom, StandardCharsets.ISO_8859_1).contains("<botmaker.plugin>")) {
            return Optional.of(folder + " is a plugin's folder; botmaker ai works on a bot.");
        }
        return Optional.empty();
    }

    /**
     * This cli's own {@code mcp-bridge}, run by the Java that runs this command, on its class path: the {@code -all}
     * jar a {@code java -jar} or the packaged launcher names, or a checkout's {@code -cp}.
     */
    static List<String> bridgeCommand() {
        Path java = Path.of(System.getProperty("java.home"), "bin", AiTool.isWindows() ? "java.exe" : "java");
        List<String> classPath = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isBlank()) classPath.add(Path.of(entry).toAbsolutePath().toString());
        }
        return List.of(java.toString(), "-cp", String.join(File.pathSeparator, classPath), "com.botmaker.cli.Main",
                "mcp-bridge");
    }

    /** A directory for the session's config files, the user's only where the filesystem says so. */
    private static Path privateDir() throws IOException {
        Path dir = Files.createTempDirectory("botmaker-ai-");
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException notPosix) {
            // Windows: the temp directory is under the user's own profile.
        }
        return dir;
    }

    /**
     * Ctrl+C belongs to the tool, which shares the terminal: Claude Code clears its input on the first press. Without
     * this the JVM would exit on it and hand the terminal back to the shell while the tool still runs. A closed
     * terminal (SIGHUP) or a kill still ends this command, and its shutdown hook the tool.
     */
    private static void ignoreInterrupts() {
        try {
            Signal.handle(new Signal("INT"), signal -> {});
        } catch (IllegalArgumentException unsupported) {
            // No such signal here: the default stays.
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            System.err.println("Could not delete " + dir + ": " + e.getMessage());
        }
    }
}
