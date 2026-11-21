// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.core;

import com.botmaker.cli.core.CoreFeed.Frame;
import com.botmaker.cli.core.CoreFeed.Kind;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreFeedTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String TOKEN = "t0ken";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** A frame cut anywhere, even inside its kind or its JSON, is read whole; {@code ended} is the last. */
    @Test
    void framesSplitAcrossChunksAreReadWhole() throws Exception {
        CoreFeed.At at = serve(List.of("event: ins", "ide\ndata: {\"atMil", "lis\":7}\n", "\nevent: seen\ndata: {\"type\":"
                + "\"client\"}\n\n: a comment\n\nevent: ended\ndata: {}\n\n"));
        try (CoreFeed feed = CoreFeed.open(at).orElseThrow()) {
            Frame inside = feed.next(WAIT);
            assertEquals(Kind.INSIDE, inside.kind());
            assertEquals(7, inside.data().path("atMillis").asLong());
            Frame seen = feed.next(WAIT);
            assertEquals(Kind.SEEN, seen.kind());
            assertEquals("client", seen.data().path("type").asText());
            assertEquals(Kind.ENDED, feed.next(WAIT).kind());
            assertNull(feed.next(Duration.ofMillis(200)), "nothing after ended");
        }
    }

    /** A core that goes without saying {@code ended} has ended all the same. */
    @Test
    void aStreamThatStopsEnds() throws Exception {
        CoreFeed.At at = serve(List.of("event: inside\ndata: {}\n\n"));
        try (CoreFeed feed = CoreFeed.open(at).orElseThrow()) {
            assertEquals(Kind.INSIDE, feed.next(WAIT).kind());
            assertEquals(Kind.ENDED, feed.next(WAIT).kind());
        }
    }

    /** A core that takes the connection and says nothing is told apart from one that refuses, and waited for briefly. */
    @Test
    void aSilentCoreIsNotWaitedForForever() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(CoreFeed.EVENTS, exchange -> {
            try {
                // Past the feed's 5 s wait; the server's stop waits for this handler.
                Thread.sleep(7_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        CoreFeed.At at = new CoreFeed.At(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), TOKEN);
        IOException silent = assertThrows(IOException.class, () -> CoreFeed.open(at));
        assertTrue(silent.getMessage().contains("did not answer"), silent.getMessage());
    }

    @Test
    void aWrongTokenIsRefused() throws Exception {
        CoreFeed.At at = serve(List.of());
        IOException refused = assertThrows(IOException.class,
                () -> CoreFeed.open(new CoreFeed.At(at.root(), "forged")));
        assertTrue(refused.getMessage().contains("HTTP 401"), refused.getMessage());
    }

    @Test
    void nothingListeningIsNoCore() throws Exception {
        CoreFeed.At at = serve(List.of());
        server.stop(0);
        server = null;
        assertEquals(Optional.empty(), CoreFeed.open(at));
    }

    /** The port is the running core's ({@code core.json}), the token the one {@code mcp.json} saved. */
    @Test
    void theCoreIsFoundWhereTheBridgeFindsIt(@TempDir Path dir) throws Exception {
        assertEquals(Optional.empty(), CoreFeed.at(dir), "nothing saved");
        Files.writeString(dir.resolve("mcp.json"), "{\"port\":7431,\"token\":\"" + TOKEN + "\"}");
        Files.writeString(dir.resolve("core.json"), "{\"pid\":1,\"port\":45678,\"version\":\"1.0\"}");
        assertEquals(new CoreFeed.At(URI.create("http://127.0.0.1:45678/"), TOKEN), CoreFeed.at(dir).orElseThrow());
    }

    @Test
    void theDashboardLinkIsTheCoresAnswer() throws Exception {
        CoreFeed.At at = serve(List.of());
        assertEquals(URI.create("http://127.0.0.1:1/dashboard?key=ab"), CoreFeed.dashboardLink(at));
    }

    /** Serves {@code chunks} on the feed, each flushed on its own a moment apart, then closes it. */
    private CoreFeed.At serve(List<String> chunks) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(CoreFeed.EVENTS, exchange -> {
            if (!authorized(exchange)) return;
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                for (String chunk : chunks) {
                    body.write(chunk.getBytes(StandardCharsets.UTF_8));
                    body.flush();
                    Thread.sleep(30);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.createContext(CoreFeed.KEY, exchange -> {
            if (!authorized(exchange)) return;
            byte[] answer = "{\"url\":\"http://127.0.0.1:1/dashboard?key=ab\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, answer.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(answer);
            }
        });
        server.start();
        return new CoreFeed.At(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), TOKEN);
    }

    private static boolean authorized(HttpExchange exchange) throws IOException {
        if (("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) return true;
        exchange.sendResponseHeaders(401, -1);
        exchange.close();
        return false;
    }
}
