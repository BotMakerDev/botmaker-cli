package com.botmaker.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bridge against a fake Studio that starts late, stops, and comes back with new sessions. */
class McpBridgeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BlockingQueue<JsonNode> fromBridge = new LinkedBlockingQueue<>();
    private PipedOutputStream toBridge;
    private FakeStudio studio;
    private Thread bridge;

    @AfterEach
    void stop() throws IOException {
        if (studio != null) studio.stop();
        toBridge.close();
        bridge.interrupt();
    }

    @Test
    void studioDownThenUpThenRestartedKeepsTheClientConnected() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        start(port);

        send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\"}}}");
        JsonNode init = answer(1);
        assertTrue(init.at("/result/capabilities/tools/listChanged").asBoolean(), "the client is told to listen");
        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"open\"}}");
        JsonNode down = answer(2);
        assertTrue(down.at("/result/isError").asBoolean());
        assertEquals(McpBridge.DOWN, down.at("/result/content/0/text").asText());

        studio = new FakeStudio(port, "a");
        assertNotNull(notice(), "Studio up: the client is told the tools changed");
        send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}");
        assertEquals("open", answer(3).at("/result/tools/0/name").asText(), "listed by Studio, over its stream");

        studio.stop();
        send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}");
        assertEquals("open", answer(4).at("/result/tools/0/name").asText(), "Studio down: its last list");

        studio = new FakeStudio(port, "b");
        send("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"open\"}}");
        assertEquals("opened by b", answer(5).at("/result/content/0/text").asText(), "a new session, the call made");
    }

    private void start(int port) throws IOException {
        PipedInputStream in = new PipedInputStream(1 << 16);
        toBridge = new PipedOutputStream(in);
        OutputStream out = new OutputStream() {
            private final StringBuilder line = new StringBuilder();

            @Override
            public void write(int b) {
                if (b != '\n') {
                    line.append((char) b);
                    return;
                }
                try {
                    fromBridge.add(JSON.readTree(line.toString()));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                line.setLength(0);
            }
        };
        McpBridge.Upstream at = new McpBridge.Upstream(URI.create("http://127.0.0.1:" + port + "/mcp"), "t");
        bridge = Thread.ofVirtual().start(() -> {
            try {
                new McpBridge(() -> Optional.of(at), in, out, Duration.ofMillis(50)).run();
            } catch (IOException ended) {
                // stdin closed by the test
            }
        });
    }

    private void send(String line) throws IOException {
        toBridge.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        toBridge.flush();
    }

    private JsonNode answer(int id) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            JsonNode next = fromBridge.poll(100, TimeUnit.MILLISECONDS);
            if (next != null && next.path("id").asInt(-1) == id) return next;
        }
        throw new AssertionError("no answer to " + id);
    }

    private JsonNode notice() throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            JsonNode next = fromBridge.poll(100, TimeUnit.MILLISECONDS);
            if (next != null && "notifications/tools/list_changed".equals(next.path("method").asText())) return next;
        }
        return null;
    }

    /** A Studio speaking streamable HTTP: a session per initialize, one answer as JSON and one as an event stream. */
    private static final class FakeStudio {
        private final HttpServer server;
        private final String session;

        FakeStudio(int port, String session) throws IOException {
            this.session = session;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/mcp", this::handle);
            server.start();
        }

        void stop() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            if (!"Bearer t".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                reply(exchange, 401, null, "");
                return;
            }
            if (exchange.getRequestMethod().equals("GET")) {
                reply(exchange, 405, null, "");
                return;
            }
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            if (method.equals("initialize")) {
                exchange.getResponseHeaders().add("Mcp-Session-Id", session);
                reply(exchange, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id")
                        + ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},"
                        + "\"serverInfo\":{\"name\":\"botmaker-studio\",\"version\":\"1\"}}}");
                return;
            }
            if (!session.equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))) {
                reply(exchange, 404, null, "");
                return;
            }
            if (!request.has("id")) {
                reply(exchange, 202, null, "");
                return;
            }
            String result = switch (method) {
                case "tools/list" -> "{\"tools\":[{\"name\":\"open\",\"inputSchema\":{\"type\":\"object\"}}]}";
                case "tools/call" -> "{\"content\":[{\"type\":\"text\",\"text\":\"opened by " + session + "\"}]}";
                default -> "{}";
            };
            String message = "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"result\":" + result + "}";
            reply(exchange, 200, "text/event-stream", "event: message\ndata: " + message + "\n\n");
        }

        private static void reply(HttpExchange exchange, int status, String type, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (type != null) exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
