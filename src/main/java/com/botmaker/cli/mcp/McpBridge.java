package com.botmaker.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Studio's MCP tools over stdio, for a client that would otherwise lose them each time Studio stops: Studio serves
 * them on a loopback HTTP endpoint that lives and dies with it, and a client marks such a server failed until the
 * user reconnects it by hand. This process is the client's server instead, for the client's whole session, and
 * forwards to Studio while it runs (the user's call, 2026-10-15).
 *
 * <ul>
 *   <li>Studio down: {@code initialize} is answered here, a tool call answers that Studio is not running, and
 *       {@code tools/list} the tools Studio served last.</li>
 *   <li>Studio up again, or restarted: a new session is opened with it and the client is told
 *       {@code notifications/tools/list_changed}, so it lists the tools again without a reconnect.</li>
 * </ul>
 *
 * The endpoint and its token are read from Studio's own {@code mcp.json} at each connect, so neither sits in the
 * client's config. One client, one Studio; requests from Studio to the client (sampling, roots) are not relayed.
 */
public final class McpBridge {

    /** Where Studio listens and the token it wants; {@link #fromStudioConfig} reads them. */
    public record Upstream(URI url, String token) {}

    /** What the client was told while Studio is down. */
    static final String DOWN = "BotMaker Studio is not running, or its MCP endpoint is off. Start Studio: its "
            + "tools come back by themselves.";
    private static final String SESSION = "Mcp-Session-Id";
    private static final String FALLBACK_PROTOCOL = "2025-06-18";

    private final Supplier<Optional<Upstream>> upstream;
    private final InputStream in;
    private final OutputStream out;
    private final Duration poll;
    /** The longest the bridge's own requests (initialize, ping) wait; a tool call waits as long as it runs. */
    private static final Duration QUICK = Duration.ofSeconds(5);

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong ids = new AtomicLong();

    private volatile ObjectNode clientInit;
    /** The client said it is initialized: it may be told the tools changed. */
    private volatile boolean initialized;
    private volatile Connection connection;
    private volatile ArrayNode tools;

    /** The open session with Studio. */
    private record Connection(Upstream upstream, String session, String protocol, JsonNode init) {}

    /** Studio answered that it knows no such session: it restarted. */
    private static final class SessionLost extends IOException {
        SessionLost() {
            super("session lost");
        }
    }

    /** @param poll how often Studio is looked for while it is down, and checked while it is up */
    public McpBridge(Supplier<Optional<Upstream>> upstream, InputStream in, OutputStream out, Duration poll) {
        this.upstream = upstream;
        this.in = in;
        this.out = out;
        this.poll = poll;
        this.tools = json.createArrayNode();
    }

    /** Relays until the client closes stdin. */
    public void run() throws IOException {
        Thread watcher = Thread.ofVirtual().name("mcp-bridge-watch").start(this::watch);
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode message;
                try {
                    message = json.readTree(line);
                } catch (IOException unreadable) {
                    continue;
                }
                if (message instanceof ObjectNode object) {
                    Thread.ofVirtual().start(() -> receive(object));
                }
            }
        } finally {
            watcher.interrupt();
        }
    }

    /**
     * The endpoint Studio's {@code mcp.json} names, in BotMaker's cache folder (as Studio's {@code CacheDirs} finds
     * it); empty when Studio never wrote one.
     */
    public static Supplier<Optional<Upstream>> fromStudioConfig() {
        ObjectMapper json = new ObjectMapper();
        return () -> {
            Path file = cacheRoot().resolve("mcp.json");
            try {
                if (!Files.isRegularFile(file)) return Optional.empty();
                JsonNode config = json.readTree(file.toFile());
                String token = config.path("token").asText("");
                if (token.isBlank()) return Optional.empty();
                int port = config.path("port").asInt(7431);
                return Optional.of(new Upstream(URI.create("http://127.0.0.1:" + port + "/mcp"), token));
            } catch (IOException unreadable) {
                return Optional.empty();
            }
        };
    }

    private static Path cacheRoot() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return Path.of(System.getenv("LOCALAPPDATA"), "BotMaker", ".cache");
        if (os.contains("mac")) return Path.of(System.getProperty("user.home"), "Library", "Caches", "botmaker");
        String xdg = System.getenv("XDG_CACHE_HOME");
        return xdg != null && !xdg.isEmpty() ? Path.of(xdg, "botmaker")
                : Path.of(System.getProperty("user.home"), ".cache", "botmaker");
    }

    // --- from the client ---

    private void receive(ObjectNode message) {
        String method = message.path("method").asText(null);
        boolean request = message.has("id") && method != null;
        try {
            if (!request) {
                // initialized is ours to send Studio, at each connect; the rest goes on while Studio runs.
                if ("notifications/initialized".equals(method)) initialized = true;
                else if (connection != null) send(message);
            } else if (method.equals("initialize")) {
                clientInit = message.path("params") instanceof ObjectNode p ? p : json.createObjectNode();
                Connection now = connect();
                reply(message, now != null ? announced(now.init()) : localInit());
            } else if (method.equals("ping")) {
                reply(message, json.createObjectNode());
            } else {
                forward(message);
            }
        } catch (IOException | RuntimeException failed) {
            if (request) error(message, failed.getMessage());
        }
    }

    private void forward(ObjectNode message) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            Connection now = connection != null ? connection : connect();
            if (now == null) {
                down(message);
                return;
            }
            try {
                JsonNode answer = send(now, message, null);
                if (answer == null) return;
                // The first page only: a later one would stand in for the whole list.
                if (message.path("method").asText().equals("tools/list") && !message.path("params").has("cursor")
                        && answer.path("result").path("tools") instanceof ArrayNode listed) {
                    tools = listed;
                }
                write(answer);
                return;
            } catch (SessionLost lost) {
                dropped(now);
                if (connect() != null) toolsChanged();
            } catch (IOException unreachable) {
                dropped(now);
                down(message);
                return;
            }
        }
        down(message);
    }

    /** What a request gets while Studio is down. */
    private void down(ObjectNode message) throws IOException {
        switch (message.path("method").asText()) {
            case "tools/list" -> reply(message, json.createObjectNode().set("tools", tools));
            case "tools/call" -> {
                ObjectNode result = json.createObjectNode();
                result.putArray("content").addObject().put("type", "text").put("text", DOWN);
                result.put("isError", true);
                reply(message, result);
            }
            default -> error(message, DOWN);
        }
    }

    private ObjectNode localInit() {
        ObjectNode result = json.createObjectNode();
        result.put("protocolVersion", clientInit.path("protocolVersion").asText(FALLBACK_PROTOCOL));
        result.putObject("capabilities").putObject("tools").put("listChanged", true);
        result.putObject("serverInfo").put("name", "botmaker-studio").put("version", "bridge");
        result.put("instructions", "BotMaker Studio is not running yet. Its tools appear here once it starts.");
        return result;
    }

    /** Studio's own answer, saying the tool list may change: it does each time Studio comes back. */
    private ObjectNode announced(JsonNode init) {
        ObjectNode result = init.deepCopy();
        result.withObjectProperty("capabilities").withObjectProperty("tools").put("listChanged", true);
        return result;
    }

    // --- to Studio ---

    /** Opens a session with Studio; null while it does not answer. */
    private synchronized Connection connect() {
        if (connection != null) return connection;
        Optional<Upstream> where = upstream.get();
        if (where.isEmpty()) return null;
        ObjectNode init = json.createObjectNode().put("jsonrpc", "2.0").put("id", "bridge-" + ids.incrementAndGet())
                .put("method", "initialize");
        ObjectNode params = clientInit != null ? clientInit.deepCopy() : json.createObjectNode();
        if (!params.has("protocolVersion")) params.put("protocolVersion", FALLBACK_PROTOCOL);
        if (!params.has("capabilities")) params.putObject("capabilities");
        if (!params.has("clientInfo")) params.putObject("clientInfo").put("name", "botmaker-mcp-bridge");
        init.set("params", params);
        try {
            HttpResponse<Stream<String>> response = http.send(post(where.get(), null, null, init, QUICK),
                    HttpResponse.BodyHandlers.ofLines());
            String session = response.headers().firstValue(SESSION).orElse(null);
            JsonNode answer = answerTo(init, response, null);
            if (response.statusCode() != 200 || answer == null || !answer.has("result")) return null;
            JsonNode result = answer.get("result");
            Connection now = new Connection(where.get(), session, result.path("protocolVersion")
                    .asText(FALLBACK_PROTOCOL), result);
            ObjectNode initialized = json.createObjectNode().put("jsonrpc", "2.0")
                    .put("method", "notifications/initialized");
            http.send(post(now.upstream(), now.session(), now.protocol(), initialized, QUICK),
                    HttpResponse.BodyHandlers.discarding());
            connection = now;
            Thread.ofVirtual().name("mcp-bridge-listen").start(() -> listen(now));
            return now;
        } catch (IOException unreachable) {
            return null;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Sends a notification or a response to Studio, answering nothing back. */
    private void send(ObjectNode message) throws IOException {
        Connection now = connection;
        if (now == null) return;
        try {
            http.send(post(now.upstream(), now.session(), now.protocol(), message, QUICK),
                    HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Studio's answer to {@code request}, its notifications on the way written to the client; {@code timeout} null
     * waits as long as the request runs.
     */
    private JsonNode send(Connection now, ObjectNode request, Duration timeout) throws IOException {
        try {
            HttpResponse<Stream<String>> response = http.send(post(now.upstream(), now.session(), now.protocol(),
                    request, timeout), HttpResponse.BodyHandlers.ofLines());
            int status = response.statusCode();
            if (status / 100 != 2) {
                response.body().close();
                if (status == 404 && now.session() != null) throw new SessionLost();
                // 401 after the token changed, or Studio failing: the next connect reads mcp.json again.
                throw new IOException("Studio answered HTTP " + status);
            }
            JsonNode answer = answerTo(request, response, null);
            if (answer == null && request.has("id")) {
                throw new IOException("Studio answered HTTP " + response.statusCode() + " and no result");
            }
            return answer;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new IOException("stopped");
        }
    }

    private HttpRequest post(Upstream to, String session, String protocol, JsonNode body, Duration timeout)
            throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(to.url())
                .header("Authorization", "Bearer " + to.token())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (session != null) request.header(SESSION, session);
        if (protocol != null) request.header("MCP-Protocol-Version", protocol);
        if (timeout != null) request.timeout(timeout);
        return request.build();
    }

    /**
     * The response to {@code request} in Studio's answer, plain JSON or an event stream; whatever else the stream
     * carries before it (progress, logs) goes to the client, only while {@code from} is the open session (null:
     * always).
     */
    private JsonNode answerTo(JsonNode request, HttpResponse<Stream<String>> response, Connection from)
            throws IOException {
        try (Stream<String> body = response.body()) {
            boolean events = response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream");
            if (!events) {
                String text = String.join("\n", (Iterable<String>) body::iterator);
                return text.isBlank() ? null : json.readTree(text);
            }
            JsonNode id = request.get("id");
            StringBuilder data = new StringBuilder();
            Iterator<String> lines = body.iterator();
            while (lines.hasNext()) {
                String line = lines.next();
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    data.append(line.substring(5).strip());
                } else if (line.isEmpty() && !data.isEmpty()) {
                    JsonNode message = json.readTree(data.toString());
                    data.setLength(0);
                    if (id != null && id.equals(message.get("id")) && !message.has("method")) return message;
                    if (from == null || connection == from) write(message);
                }
            }
            return data.isEmpty() ? null : json.readTree(data.toString());
        }
    }

    /**
     * Studio's own messages, outside any request (a plugin added, its tools changed), relayed while {@code now} is
     * the open session; a stream Studio closes is opened again, one a dropped session kept is no longer relayed.
     */
    private void listen(Connection now) {
        HttpRequest.Builder request = HttpRequest.newBuilder(now.upstream().url())
                .header("Authorization", "Bearer " + now.upstream().token())
                .header("Accept", "text/event-stream")
                .header("MCP-Protocol-Version", now.protocol())
                .GET();
        if (now.session() != null) request.header(SESSION, now.session());
        while (connection == now) {
            try {
                HttpResponse<Stream<String>> response = http.send(request.build(),
                        HttpResponse.BodyHandlers.ofLines());
                if (response.statusCode() != 200) {
                    response.body().close();
                    return;    // no such stream offered: requests' own streams carry what Studio says
                }
                answerTo(json.createObjectNode(), response, now);
                Thread.sleep(poll);
            } catch (IOException | RuntimeException ended) {
                return;    // Studio stopped: the watcher finds out
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // --- watching ---

    /** Looks for Studio while it is down, and checks it while it is up: a restart gets a new session. */
    private void watch() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(poll);
            } catch (InterruptedException stopped) {
                return;
            }
            if (!initialized) continue;    // the client has not started yet: it lists on its own
            try {
                check();
            } catch (RuntimeException failed) {
                // A bad mcp.json or an odd answer: looked at again on the next round.
            }
        }
    }

    private void check() {
        Connection now = connection;
        if (now == null) {
            if (connect() != null) toolsChanged();
            return;
        }
        ObjectNode ping = json.createObjectNode().put("jsonrpc", "2.0").put("id", "bridge-" + ids.incrementAndGet())
                .put("method", "ping");
        try {
            send(now, ping, QUICK);
        } catch (SessionLost lost) {
            dropped(now);
            if (connect() != null) toolsChanged();
        } catch (IOException unreachable) {
            dropped(now);
        }
    }

    private synchronized void dropped(Connection was) {
        if (connection == was) connection = null;
    }

    private void toolsChanged() {
        try {
            write(json.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/tools/list_changed"));
        } catch (IOException closed) {
            // The client went away; stdin's end stops the bridge.
        }
    }

    // --- to the client ---

    private void reply(JsonNode request, JsonNode result) throws IOException {
        ObjectNode answer = json.createObjectNode().put("jsonrpc", "2.0");
        answer.set("id", request.get("id"));
        answer.set("result", result);
        write(answer);
    }

    private void error(JsonNode request, String message) {
        ObjectNode answer = json.createObjectNode().put("jsonrpc", "2.0");
        answer.set("id", request.get("id"));
        answer.putObject("error").put("code", -32000).put("message", message == null ? DOWN : message);
        try {
            write(answer);
        } catch (IOException closed) {
            // The client went away.
        }
    }

    private void write(JsonNode message) throws IOException {
        byte[] line = (json.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            out.write(line);
            out.flush();
        }
    }
}
