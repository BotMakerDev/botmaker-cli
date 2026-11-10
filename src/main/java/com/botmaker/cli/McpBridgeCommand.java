package com.botmaker.cli;

import com.botmaker.cli.mcp.McpBridge;
import com.botmaker.cli.mcp.McpBridge.Launch;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code botmaker mcp-bridge} — BotMaker's MCP tools for a client that starts it as a stdio server, kept across
 * Studio's restarts and served with no Studio at all ({@link McpBridge}). Its stdout is the protocol: nothing else is
 * printed there.
 */
@Command(name = "mcp-bridge",
        header = "Serve BotMaker's MCP tools over stdio, with or without Studio.",
        description = "For an MCP client's config: {\"type\":\"stdio\",\"command\":\"botmaker\",\"args\":"
                + "[\"mcp-bridge\"]}. Forwards to BotMaker's core on this computer, starting it as the last Studio "
                + "did when none runs, reads its endpoint and token from mcp.json, and tells the client when "
                + "Studio's tools come and go. Its start_studio tool starts Studio the way --umbrella or --studio "
                + "says.",
        mixinStandardHelpOptions = true)
final class McpBridgeCommand implements Callable<Integer> {

    @Option(names = "--umbrella", paramLabel = "<dir>",
            description = "A BotMaker umbrella checkout: start_studio runs its dev Studio with javafx:run, and the "
                    + "core starts from its built Studio when no Studio has started one on this computer. Or "
                    + "$BOTMAKER_UMBRELLA.")
    private String umbrella;

    @Option(names = "--studio", paramLabel = "<command>",
            description = "How start_studio launches a packaged Studio. Or $BOTMAKER_STUDIO.")
    private String studioCommand;

    @Override
    public Integer call() throws IOException {
        McpBridge.onThisComputer(launch(), System.in, System.out, Duration.ofSeconds(2)).run();
        return 0;
    }

    /** The checkout first, as {@code plugin run} launches Studio; none of them, start_studio says how to give one. */
    private Optional<Launch> launch() {
        String checkout = umbrella != null ? umbrella : System.getenv("BOTMAKER_UMBRELLA");
        if (checkout != null && !checkout.isBlank()) {
            Path dir = Path.of(checkout);
            return Optional.of(Launch.of(List.of(Mvn.executable(dir), "-B", "-o", "-pl", "botmaker-studio",
                    "javafx:run"), dir, "-Djavafx.args=--project=%s"));
        }
        String command = studioCommand != null ? studioCommand : System.getenv("BOTMAKER_STUDIO");
        if (command == null || command.isBlank()) return Optional.empty();
        return Optional.of(Launch.of(List.of(command.strip().split("\\s+")), null, "--project=%s"));
    }
}
