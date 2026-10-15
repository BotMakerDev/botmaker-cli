package com.botmaker.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
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

    /**
     * How start_studio starts Studio: {@code command} run in {@code dir}, and {@code projectArg} the argument it takes
     * for a project to open, {@code %s} standing for the project's name; what it prints goes to {@code log}.
     */
    public record Launch(List<String> command, Path dir, String projectArg, Path log) {
        public Launch {
            command = List.copyOf(command);
        }

        /** {@code command} with its output in Studio's cache folder, beside its {@code mcp.json}. */
        public static Launch of(List<String> command, Path dir, String projectArg) {
            return new Launch(command, dir, projectArg, cacheRoot().resolve("studio-run.log"));
        }
    }

    /** The bridge's own tool, listed beside Studio's: it answers while Studio is down. */
    static final String START = "start_studio";
    /** How long start_studio waits for Studio's endpoint; a dev Studio compiles first. */
    private static final Duration STARTING = Duration.ofMinutes(3);

    /** What the client was told while Studio is down. */
    static final String DOWN = "BotMaker Studio is not running, or its MCP endpoint is off. start_studio starts "
            + "it; its tools come back by themselves.";
    private static final String SESSION = "Mcp-Session-Id";
    /** Studio's {@code McpEndpoint.HELLO}. */
    static final String HELLO = "/mcp-hello";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String FALLBACK_PROTOCOL = "2025-06-18";

    private final Supplier<Optional<Upstream>> upstream;
    private final Optional<Launch> launch;
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

    /**
     * @param launch how start_studio starts Studio; empty, it says how to tell the bridge
     * @param poll how often Studio is looked for while it is down, and checked while it is up
     */
    public McpBridge(Supplier<Optional<Upstream>> upstream, Optional<Launch> launch, InputStream in, OutputStream out,
            Duration poll) {
        this.upstream = upstream;
        this.launch = launch;
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
            } else if (method.equals("tools/call") && START.equals(message.path("params").path("name").asText())) {
                reply(message, start(message.path("params").path("arguments").path("project").asText("")));
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
                    tools = listed.deepCopy();
                    listed.add(startTool());
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
            case "tools/list" -> reply(message, json.createObjectNode().set("tools", tools.deepCopy().add(startTool())));
            case "tools/call" -> reply(message, toolText(DOWN, true));
            default -> error(message, DOWN);
        }
    }

    private ObjectNode localInit() {
        ObjectNode result = json.createObjectNode();
        result.put("protocolVersion", clientInit.path("protocolVersion").asText(FALLBACK_PROTOCOL));
        result.putObject("capabilities").putObject("tools").put("listChanged", true);
        result.putObject("serverInfo").put("name", "botmaker-studio").put("version", "bridge");
        result.put("instructions", "BotMaker Studio is not running yet. start_studio starts it; its tools appear "
                + "here once it runs.");
        return result;
    }

    /** Studio's own answer, saying the tool list may change: it does each time Studio comes back. */
    private ObjectNode announced(JsonNode init) {
        ObjectNode result = init.deepCopy();
        result.withObjectProperty("capabilities").withObjectProperty("tools").put("listChanged", true);
        return result;
    }

    // --- start_studio ---

    /** start_studio as the client lists it. */
    private ObjectNode startTool() {
        ObjectNode tool = json.createObjectNode().put("name", START).put("description", "Start BotMaker Studio on "
                + "this computer and wait until its tools answer; quit_studio closes it. Says so when it runs already.");
        ObjectNode schema = tool.putObject("inputSchema").put("type", "object");
        schema.putObject("properties").putObject("project").put("type", "string")
                .put("description", "a project under ~/BotMakerProjects to open; left out, Studio opens as it would");
        return tool;
    }

    /** start_studio: Studio started, or found running, and its tools listed again once its endpoint answers. */
    private ObjectNode start(String project) {
        if (connect() != null) return toolText("Studio runs already.", false);
        if (launch.isEmpty()) {
            return toolText("The bridge was not told how to start Studio: give mcp-bridge --umbrella <checkout> (a "
                    + "dev Studio, through javafx:run) or --studio <command>, or set BOTMAKER_UMBRELLA or "
                    + "BOTMAKER_STUDIO.", true);
        }
        Launch how = launch.get();
        List<String> argv = new ArrayList<>(how.command());
        if (!project.isBlank()) argv.add(how.projectArg().formatted(project.strip()));
        Path log = how.log();
        Process process;
        try {
            Files.createDirectories(log.getParent());
            // Never this process's stdout: that is the protocol.
            process = new ProcessBuilder(argv).directory(how.dir() == null ? null : how.dir().toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        } catch (IOException failed) {
            return toolText("Studio did not start: " + failed.getMessage(), true);
        }
        long until = System.nanoTime() + STARTING.toNanos();
        while (System.nanoTime() < until) {
            if (connect() != null) {
                toolsChanged();
                return toolText("Studio runs; its tools are listed again.", false);
            }
            if (!process.isAlive()) {
                return toolText("Studio stopped as it started (exit " + process.exitValue() + "); what it printed "
                        + "is in " + log + ".", true);
            }
            try {
                Thread.sleep(poll);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return toolText("Studio started, but its endpoint has not answered yet; what it printed is in " + log
                + ". Its tools come back by themselves once it does.", true);
    }

    private ObjectNode toolText(String text, boolean error) {
        ObjectNode result = json.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        if (error) result.put("isError", true);
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

    /**
     * Refuses unless what answers on {@code to}'s port proves it holds the token: Studio's {@code /mcp-hello} signs a
     * fresh nonce with it. Asked before each request, so a process that took the port while Studio was down is
     * never handed the token.
     */
    private void provenStudio(Upstream to) throws IOException {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String nonce = HexFormat.of().formatHex(bytes);
        HttpRequest hello = HttpRequest.newBuilder(to.url().resolve(HELLO + "?nonce=" + nonce)).timeout(QUICK).GET()
                .build();
        try {
            HttpResponse<String> answer = http.send(hello, HttpResponse.BodyHandlers.ofString());
            byte[] expected = proof(to.token(), nonce).getBytes(StandardCharsets.UTF_8);
            if (answer.statusCode() != 200
                    || !MessageDigest.isEqual(expected, answer.body().strip().getBytes(StandardCharsets.UTF_8))) {
                throw new IOException("What answers on " + to.url().getPort() + " is not this computer's Studio");
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new IOException("stopped");
        }
    }

    /** HMAC-SHA256 of {@code nonce} under {@code token}, in hex, as Studio's {@code McpEndpoint.proof} makes it. */
    static String proof(String token, String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(nonce.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is missing from this Java", e);
        }
    }

    private HttpRequest post(Upstream to, String session, String protocol, JsonNode body, Duration timeout)
            throws IOException {
        provenStudio(to);
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
                provenStudio(now.upstream());
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
