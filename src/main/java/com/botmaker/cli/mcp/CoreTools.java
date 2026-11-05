package com.botmaker.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The core's MCP tools, called by name from the terminal ({@code botmaker bot run}, plan phase 12): a tool's text
 * answer, and whether it was a refusal.
 */
public interface CoreTools {

    /** A tool's answer: its text, and whether the tool refused or failed. */
    record Answer(String text, boolean error) {}

    /** Calls {@code tool} with {@code arguments} and waits for its answer. */
    Answer call(String tool, Map<String, Object> arguments) throws IOException;

    /**
     * The tools of BotMaker's core on this computer, through an in-process {@link McpBridge}: the core is found, or
     * started as the last Studio started it, exactly as {@code botmaker mcp-bridge} does it for an MCP client.
     */
    static CoreTools onThisComputer() {
        return new Bridged(McpBridge::onThisComputer);
    }

    /** How a {@link Bridged} makes its bridge, given the streams it talks over. */
    @FunctionalInterface
    interface BridgeFactory {
        McpBridge make(Optional<McpBridge.Launch> launch, InputStream in, OutputStream out, Duration poll);
    }

    /**
     * A bridge fed JSON-RPC lines through a queue and read back line by line, no pipe: a piped stream breaks once the
     * thread that wrote last ends, and the bridge answers from short-lived threads.
     */
    final class Bridged implements CoreTools {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final LinkedBlockingQueue<byte[]> lines = new LinkedBlockingQueue<>();
        private final Map<String, CompletableFuture<JsonNode>> waiting = new ConcurrentHashMap<>();
        private final AtomicLong ids = new AtomicLong();
        /**
         * The longest a call waits: a core being started takes up to 30 s ({@code CoreProcess.WAIT}), and the run
         * tools this calls answer at once.
         */
        private static final Duration CALL_WAIT = Duration.ofSeconds(60);
        private volatile boolean closed;

        Bridged(BridgeFactory factory) {
            McpBridge bridge = factory.make(Optional.empty(), new QueueInput(lines), new LineOutput(this::answered),
                    Duration.ofSeconds(2));
            Thread.ofVirtual().name("bot-run-bridge").start(() -> {
                try {
                    bridge.run();
                } catch (IOException | RuntimeException ended) {
                    // Told to every call below: none would be answered now.
                } finally {
                    closed = true;
                    waiting.values().forEach(call -> call.completeExceptionally(
                            new IOException("the bridge to BotMaker's core ended")));
                }
            });
        }

        @Override
        public Answer call(String tool, Map<String, Object> arguments) throws IOException {
            String id = "run-" + ids.incrementAndGet();
            ObjectNode request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", "tools/call");
            request.putObject("params").put("name", tool).set("arguments", JSON.valueToTree(arguments));
            CompletableFuture<JsonNode> answer = new CompletableFuture<>();
            waiting.put(id, answer);
            if (closed) throw new IOException("the bridge to BotMaker's core ended");
            lines.add((JSON.writeValueAsString(request) + "\n").getBytes(StandardCharsets.UTF_8));
            JsonNode reply;
            try {
                reply = answer.get(CALL_WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new IOException(tool + " got no answer within " + CALL_WAIT.toSeconds() + " s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for " + tool);
            } catch (ExecutionException e) {
                throw new IOException(e.getCause());
            } finally {
                waiting.remove(id);
            }
            if (reply.has("error")) return new Answer(reply.at("/error/message").asText("failed"), true);
            StringBuilder text = new StringBuilder();
            for (JsonNode part : reply.at("/result/content")) {
                if (!text.isEmpty()) text.append('\n');
                text.append(part.path("text").asText(""));
            }
            return new Answer(text.toString(), reply.at("/result/isError").asBoolean(false));
        }

        private void answered(String line) {
            try {
                JsonNode message = JSON.readTree(line);
                CompletableFuture<JsonNode> answer = waiting.get(message.path("id").asText(""));
                if (answer != null && !message.has("method")) answer.complete(message);
            } catch (IOException unreadable) {
                // A notification or a line that is not JSON: no call waits on it.
            }
        }
    }

    /** What the bridge reads: each queued line, blocking while none is queued. */
    final class QueueInput extends InputStream {
        private final LinkedBlockingQueue<byte[]> lines;
        private byte[] current = new byte[0];
        private int at;

        QueueInput(LinkedBlockingQueue<byte[]> lines) {
            this.lines = lines;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] into, int offset, int length) throws IOException {
            if (length == 0) return 0;
            while (at >= current.length) {
                try {
                    current = lines.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
                at = 0;
            }
            int count = Math.min(length, current.length - at);
            System.arraycopy(current, at, into, offset, count);
            at += count;
            return count;
        }
    }

    /** What the bridge writes, handed on a line at a time. */
    final class LineOutput extends OutputStream {
        private final Consumer<String> line;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        LineOutput(Consumer<String> line) {
            this.line = line;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                String text = pending.toString(StandardCharsets.UTF_8);
                pending.reset();
                if (!text.isBlank()) line.accept(text);
            } else {
                pending.write(b);
            }
        }
    }
}
