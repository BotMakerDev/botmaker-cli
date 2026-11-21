// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.core;

import com.botmaker.cli.mcp.McpBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The core's dashboard feed, {@code /dashboard/events}, read with the token the core saved (core dashboard plan, phase
 * 7): its server-sent frames {@code inside}, {@code seen} and {@code ended}, each as its kind and its JSON. The cli links
 * no core class, so it reads the JSON by field name; the core's {@code DashboardEvents} writes it.
 */
public final class CoreFeed implements AutoCloseable {

    /** What {@code botmaker core} says when no core answers. */
    public static final String NONE = "No BotMaker core runs on this computer: botmaker core start.";
    static final String EVENTS = "/dashboard/events";
    static final String KEY = "/dashboard-key";
    /** The core's {@code DashboardAccess.PATH}: the one path a dashboard link may name. */
    static final String DASHBOARD = "/dashboard";
    private static final Duration CONNECT = Duration.ofSeconds(3);
    /** How long a core that took the connection has to answer. */
    private static final Duration ANSWER = Duration.ofSeconds(5);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(CONNECT).build();

    /** A frame's kind, as its {@code event:} line names it. */
    public enum Kind {
        INSIDE("inside"), SEEN("seen"), ENDED("ended"), UNKNOWN("");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Kind fromId(String id) {
            for (Kind kind : values()) if (kind != UNKNOWN && kind.id.equals(id)) return kind;
            return UNKNOWN;
        }
    }

    /** One frame and its JSON. */
    public record Frame(Kind kind, JsonNode data) {}

    /** Where a core answers: its root ({@code http://127.0.0.1:<port>/}) and its token. */
    public record At(URI root, String token) {
        URI resolve(String path) {
            return root.resolve(path);
        }
    }

    private final InputStream body;
    private final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();

    private CoreFeed(InputStream body) {
        this.body = body;
        Thread.ofVirtual().name("core-feed").start(this::pump);
    }

    /** The core the user's cache folder names, as {@code botmaker mcp-bridge} finds it; empty when none was saved. */
    public static Optional<At> onThisComputer() {
        return at(McpBridge.coreDir());
    }

    static Optional<At> at(Path dir) {
        return McpBridge.fromStudioConfig(dir).get().map(up -> new At(up.url().resolve("/"), up.token()));
    }

    /**
     * The feed of the core at {@code at}; empty when nothing answers there.
     *
     * @throws IOException when the core refuses the feed, or takes the connection and says nothing
     */
    public static Optional<CoreFeed> open(At at) throws IOException {
        if (!answers(at)) return Optional.empty();
        HttpRequest request = HttpRequest.newBuilder(at.resolve(EVENTS)).header("Authorization", "Bearer " + at.token())
                .GET().build();
        // The stream has no end, so no request timeout: only its headers are waited for, ANSWER at most.
        CompletableFuture<HttpResponse<InputStream>> sent = HTTP.sendAsync(request,
                HttpResponse.BodyHandlers.ofInputStream());
        try {
            HttpResponse<InputStream> answer = sent.get(ANSWER.toMillis(), TimeUnit.MILLISECONDS);
            if (answer.statusCode() != 200) {
                answer.body().close();
                throw new IOException("The core refused its feed: HTTP " + answer.statusCode());
            }
            return Optional.of(new CoreFeed(answer.body()));
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof ConnectException) return Optional.empty();
            if (failed.getCause() instanceof IOException io) throw io;
            throw new IOException(failed.getCause());
        } catch (TimeoutException silent) {
            sent.cancel(true);
            throw new IOException(silence(at));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", interrupted);
        }
    }

    /**
     * A fresh link to the dashboard for a browser, its one-time key good once.
     *
     * @throws IOException when the core gives none; a {@link ConnectException} when nothing answers
     */
    public static URI dashboardLink(At at) throws IOException {
        if (!answers(at)) throw new ConnectException(NONE);
        HttpRequest request = HttpRequest.newBuilder(at.resolve(KEY)).header("Authorization", "Bearer " + at.token())
                .timeout(ANSWER).POST(HttpRequest.BodyPublishers.noBody()).build();
        try {
            HttpResponse<String> answer;
            try {
                answer = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException silent) {
                throw new IOException(silence(at), silent);
            }
            if (answer.statusCode() != 200) throw new IOException("The core gave no dashboard link: HTTP "
                    + answer.statusCode());
            URI link = URI.create(JSON.readTree(answer.body()).path("url").asText());
            // The link goes to the system's opener: only the core's own page, never another scheme, host or path.
            if (!"http".equals(link.getScheme()) || !at.root().getHost().equals(link.getHost())
                    || at.root().getPort() != link.getPort() || !DASHBOARD.equals(link.getPath())) {
                throw new IOException("The core gave a link that is not its dashboard: " + link.getScheme() + "://"
                        + link.getHost() + ":" + link.getPort() + link.getPath());
            }
            return link;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", interrupted);
        }
    }

    /**
     * Whether a core answers at {@code at} and proves it holds the token (the bridge's {@code /mcp-hello} proof),
     * asked before the token is sent: a process that took the port while the core was down is never handed it.
     *
     * @throws IOException when what answers is not the core
     */
    public static boolean answers(At at) throws IOException {
        try {
            McpBridge.proven(new McpBridge.Upstream(at.resolve("/mcp"), at.token()));
            return true;
        } catch (ConnectException nobody) {
            return false;
        }
    }

    private static String silence(At at) {
        return "The core at " + at.root() + " took the connection but did not answer in " + ANSWER.toSeconds()
                + " s.";
    }

    /**
     * The next frame, waiting at most {@code wait}; null when none came. A stream that stops, with or without the
     * core's {@code ended}, gives one {@code ended} and nothing after.
     */
    public Frame next(Duration wait) throws InterruptedException {
        return frames.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() throws IOException {
        body.close();
    }

    private void pump() {
        boolean ended = false;
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            ended = read(lines, frames::add);
        } catch (IOException closed) {
            // Closed by us, or the core went: either way the feed has ended.
        }
        if (!ended) frames.add(new Frame(Kind.ENDED, MissingNode.getInstance()));
    }

    /**
     * Each frame of an event stream, as {@code event:} and {@code data:} lines up to a blank line; several
     * {@code data:} lines join with a line break. A frame whose data is not JSON is skipped; one {@code ended} stops it.
     *
     * @return whether it stopped on {@code ended}
     */
    static boolean read(BufferedReader lines, Consumer<Frame> heard) throws IOException {
        String kind = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = lines.readLine()) != null) {
            if (line.isEmpty()) {
                if (kind != null && !data.isEmpty()) {
                    JsonNode node;
                    try {
                        node = JSON.readTree(data.toString());
                    } catch (IOException unreadable) {
                        node = null;
                    }
                    Kind known = Kind.fromId(kind);
                    if (node != null && known != Kind.UNKNOWN) {
                        heard.accept(new Frame(known, node));
                        if (known == Kind.ENDED) return true;
                    }
                }
                kind = null;
                data.setLength(0);
            } else if (line.startsWith("event:")) {
                kind = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5).stripLeading());
            }
        }
        return false;
    }
}
