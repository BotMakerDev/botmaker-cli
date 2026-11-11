package com.botmaker.cli.ai;

import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/** {@code botmaker mcp} — BotMaker's MCP server in the AI tools you start yourself. */
@Command(name = "mcp",
        header = "Register BotMaker's MCP tools in an AI tool.",
        synopsisSubcommandLabel = "<command>",
        subcommands = McpCommand.Install.class,
        mixinStandardHelpOptions = true)
public final class McpCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("Name a command: botmaker mcp install <tool>.");
        return ExitCode.USAGE;
    }

    /**
     * {@code botmaker mcp install <tool>} — every session of the tool gets BotMaker's MCP tools ({@link McpInstall}).
     * The server is this cli's own {@code mcp-bridge}, or with {@code --dev} a checkout's build started with
     * {@code --umbrella}, as BotMaker's developer runs it.
     */
    @Command(name = "install",
            header = "Register BotMaker's MCP tools in an AI tool's user settings.",
            description = "Every session of the tool, in any folder, gets the tools (this cli's mcp-bridge: no Studio "
                    + "needed). It adds no guard: in a bot's folder, botmaker ai <tool> keeps the tool to the MCP "
                    + "tools. Claude Code is registered through its own claude mcp add; Codex, Gemini CLI and "
                    + "opencode have the server's entry written into their user config, the rest kept. Exit 2: no "
                    + "such tool or no build for --dev; 1: the config could not be changed.",
            mixinStandardHelpOptions = true)
    static final class Install implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<tool>", description = "claude (or claude-code), codex, gemini or opencode.")
        private String tool;

        @Option(names = "--dev",
                description = "Register this umbrella checkout's built cli (botmaker-cli/target/*-all.jar), started "
                        + "with --umbrella: the core and start_studio run the checkout's code.")
        private boolean dev;

        @Option(names = "--umbrella", paramLabel = "<dir>",
                description = "The checkout for --dev (implies it); else $BOTMAKER_UMBRELLA, else the folder this "
                        + "runs in or above it.")
        private Path umbrella;

        @Option(names = "--print", description = "Show what would be registered, and where; change nothing.")
        private boolean print;

        @Override
        public Integer call() throws IOException, InterruptedException {
            AiTool picked = AiTool.named(tool);
            if (picked == AiTool.UNKNOWN) {
                System.err.println("No AI tool named " + tool + ". Pick one of: claude, codex, gemini, opencode.");
                return ExitCode.USAGE;
            }
            Optional<McpInstall.Server> server = dev || umbrella != null ? devServer() : Optional.of(userServer());
            if (server.isEmpty()) return ExitCode.USAGE;
            Path home = Path.of(System.getProperty("user.home"));
            return picked == AiTool.CLAUDE_CODE ? claude(home, server.get())
                    : file(picked, McpInstall.configFile(picked, home, System.getenv()).orElseThrow(), server.get());
        }

        private int file(AiTool picked, Path config, McpInstall.Server server) throws IOException {
            if (print) {
                System.out.println("# " + config);
                System.out.print(McpInstall.preview(picked, null, server));
                return ExitCode.OK;
            }
            McpInstall.Done done = McpInstall.install(picked, config, server);
            switch (done.result()) {
                case WRITTEN -> System.err.println(registered(picked, done.file()));
                case ALREADY -> System.err.println(AiTool.SERVER + " is already registered in " + done.file() + ".");
                case LEFT_ALONE -> {
                    System.err.println(done.file() + " was left alone: " + done.why() + ". The entry, from "
                            + "botmaker mcp install " + picked.id() + " --print:");
                    System.err.print(McpInstall.preview(picked, null, server));
                    return ExitCode.SOFTWARE;
                }
            }
            return ExitCode.OK;
        }

        private int claude(Path home, McpInstall.Server server) throws IOException, InterruptedException {
            Optional<Path> exe = AiTool.CLAUDE_CODE.locate(System.getenv("PATH"), home);
            if (print) {
                System.out.println(McpInstall.preview(AiTool.CLAUDE_CODE, exe.orElse(Path.of("claude")), server));
                return ExitCode.OK;
            }
            if (exe.isEmpty()) {
                System.err.println("Claude Code is not installed: " + AiTool.CLAUDE_CODE.installHint());
                return ExitCode.SOFTWARE;
            }
            List<List<String>> commands = McpInstall.claudeCommands(exe.get(), server);
            // The first only clears an earlier install's entry, and says so on stderr when there is none.
            new ProcessBuilder(commands.getFirst()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
            int code = new ProcessBuilder(commands.getLast()).inheritIO().start().waitFor();
            if (code != 0) return ExitCode.SOFTWARE;
            System.err.println(registered(AiTool.CLAUDE_CODE, home.resolve(".claude.json")));
            return ExitCode.OK;
        }

        private String registered(AiTool picked, Path file) {
            return AiTool.SERVER + " is registered for " + picked.displayName() + " in " + file + "; a new session "
                    + "has its tools." + (dev || umbrella != null ? "" : " In a bot's folder, botmaker ai "
                    + picked.id() + " also keeps the tool from editing its files.");
        }

        /** This cli's own bridge, as the Java running it runs it. */
        private static McpInstall.Server userServer() {
            return new McpInstall.Server(AiCommand.bridgeCommand(), Map.of());
        }

        /**
         * The checkout's built bridge, told where the checkout is. Its Maven, which the core's start resolves Studio's
         * class path with, is passed on when this shell names one: a tool started from a desktop often has none.
         */
        private Optional<McpInstall.Server> devServer() throws IOException {
            Optional<Path> dir = umbrella != null ? Optional.of(umbrella)
                    : Optional.ofNullable(System.getenv("BOTMAKER_UMBRELLA")).filter(s -> !s.isBlank()).map(Path::of)
                    .or(() -> McpInstall.umbrellaAbove(Path.of("")));
            if (dir.isEmpty() || !McpInstall.isUmbrella(dir.get().toAbsolutePath().normalize())) {
                System.err.println(dir.map(d -> d + " is not a BotMaker checkout").orElse("No BotMaker checkout here")
                        + ". Name one with --umbrella <dir>.");
                return Optional.empty();
            }
            Path checkout = dir.get().toAbsolutePath().normalize();
            Optional<Path> jar = McpInstall.builtJar(checkout);
            if (jar.isEmpty()) {
                System.err.println("No built cli in " + checkout.resolve("botmaker-cli/target")
                        + ". Build it: mvn -pl botmaker-cli -am install");
                return Optional.empty();
            }
            Path java = Path.of(System.getProperty("java.home"), "bin", AiTool.isWindows() ? "java.exe" : "java");
            String maven = System.getenv("MAVEN_HOME");
            return Optional.of(new McpInstall.Server(List.of(java.toString(), "-jar", jar.get().toString(),
                    "mcp-bridge", "--umbrella", checkout.toString()),
                    maven == null || maven.isBlank() ? Map.of() : Map.of("MAVEN_HOME", maven)));
        }
    }
}
