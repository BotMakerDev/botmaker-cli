// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli;

import com.botmaker.cli.core.CoreCards;
import com.botmaker.cli.core.CoreFeed;
import com.botmaker.cli.core.CoreHello;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreCommandTest {

    /** A snapshot at 01:00:00, an event heard before it (the replay), two after, then the core's end. */
    private static final String FEED = """
            event: inside
            data: {"atMillis":3600000,"uptimeMillis":1000,"pid":1,"version":"1.0","ending":false,"heapUsed":0,\
            "heapMax":0,"threads":1,"threadsByPrefix":{},"rebuiltJars":[],"project":null,"lockHolder":null,\
            "devMode":false,"clients":[],"running":null,"companions":[],"plugins":[],"idleLeftMillis":null}

            event: seen
            data: {"type":"told","atMillis":3599000,"source":"project","dropped":0,\
            "event":{"event":"runState","phase":"ENDED","detail":null}}

            event: seen
            data: {"type":"told","atMillis":3601000,"source":"project","dropped":0,\
            "event":{"event":"runState","phase":"STARTING","detail":null}}

            event: seen
            data: {"type":"client","atMillis":3602000,"id":"c1","kind":"studio","opened":true}

            event: ended
            data: {}

            """;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final CoreCards cards = new CoreCards(ZoneOffset.UTC);
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** {@code watch} prints what comes after the snapshot, of the kinds asked, and ends with the core. */
    @Test
    void watchPrintsLiveEventsOfTheKindsAsked() throws Exception {
        try (CoreFeed feed = feed()) {
            assertEquals(0, CoreCommand.watch(feed, Set.of("runState"), cards, print(out), print(err)));
        }
        assertEquals("01:00:01  project  runState  STARTING\nThe core ended.\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void watchWithNoKindPrintsEveryKind() throws Exception {
        try (CoreFeed feed = feed()) {
            assertEquals(0, CoreCommand.watch(feed, Set.of(), cards, print(out), print(err)));
        }
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("01:00:02  c1  client  studio c1 opened\n"),
                out::toString);
    }

    /** {@code status} prints the cards from the snapshot and the replay; the ended run is the Runs card's. */
    @Test
    void statusPrintsTheCards() throws Exception {
        try (CoreFeed feed = feed()) {
            assertEquals(0, CoreCommand.status(feed, false, cards, print(out), print(err)));
        }
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.startsWith("Core\n"), text);
        assertTrue(text.contains("Runs\n  running a run\n  STARTING\n"), text);
    }

    @Test
    void statusJsonIsTheSnapshotAsWritten() throws Exception {
        try (CoreFeed feed = feed()) {
            assertEquals(0, CoreCommand.status(feed, true, cards, print(out), print(err)));
        }
        assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("{\"atMillis\":3600000,"), out::toString);
    }

    /**
     * A core that could not say what it is on connecting sends the replay first and its snapshot on the next tick, and
     * a tick can land inside the replay: status waits for the one and reads past the other.
     */
    @Test
    void statusWaitsForTheSnapshotAndReadsPastATick() throws Exception {
        String inside = FEED.substring(0, FEED.indexOf("\n\n") + 2);
        String[] seen = FEED.substring(inside.length(), FEED.indexOf("event: ended")).split("(?<=\n\n)");
        try (CoreFeed feed = feed(seen[0] + inside + inside + seen[1] + seen[2])) {
            assertEquals(0, CoreCommand.status(feed, false, cards, print(out), print(err)));
        }
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Runs\n  running a run\n  STARTING\n"), text);
        assertTrue(text.contains("Clients\n  0 channels\n"), text);
    }

    private CoreFeed feed() throws IOException {
        return feed(FEED);
    }

    private CoreFeed feed(String frames) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/dashboard/events", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(frames.getBytes(StandardCharsets.UTF_8));
            }
        });
        CoreHello.serve(server, "t");
        server.start();
        URI root = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        return CoreFeed.open(new CoreFeed.At(root, "t")).orElseThrow();
    }

    private static PrintStream print(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }
}
