package com.botmaker.cli;

import com.botmaker.cli.mcp.McpBridge;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * {@code botmaker mcp-bridge} — Studio's MCP tools for a client that starts it as a stdio server, kept across
 * Studio's restarts ({@link McpBridge}). Its stdout is the protocol: nothing else is printed there.
 */
@Command(name = "mcp-bridge",
        header = "Serve Studio's MCP tools over stdio, across Studio's restarts.",
        description = "For an MCP client's config: {\"type\":\"stdio\",\"command\":\"botmaker\",\"args\":"
                + "[\"mcp-bridge\"]}. Forwards to the Studio running on this computer, reads its endpoint and token "
                + "from Studio's mcp.json, and tells the client when Studio's tools come back.",
        mixinStandardHelpOptions = true)
final class McpBridgeCommand implements Callable<Integer> {

    @Override
    public Integer call() throws IOException {
        new McpBridge(McpBridge.fromStudioConfig(), System.in, System.out, Duration.ofSeconds(2)).run();
        return 0;
    }
}
