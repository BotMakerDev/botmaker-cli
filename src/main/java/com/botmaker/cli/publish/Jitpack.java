package com.botmaker.cli.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nudging JitPack and waiting for a tag to build: what {@code plugin publish} and Studio's Publish wait on after
 * tagging an author's plugin or bot, before its listing is opened.
 *
 * <p>A build result is <b>cached per tag</b>: a build that failed, or that began before something it resolves was
 * there, is failed for good, and only a new tag repairs it. The release tool that once owned this wait (2026-09-05)
 * is sluice now; this is the publish's own copy.
 */
public final class Jitpack {

    private static final Duration EVERY = Duration.ofSeconds(10);
    private static final int TRIES = 60;                          // ~10 minutes

    private Jitpack() {
    }

    /** The pom a consumer would download — its presence is what "built" means. */
    public static String pomUrl(String groupId, String artifactId, String tag) {
        return SITE + groupId.replace('.', '/') + "/" + artifactId + "/" + tag + "/" + artifactId + "-" + tag + ".pom";
    }

    /** The endpoint that asks JitPack to start a build rather than waiting for somebody to request it. */
    public static String buildUrl(String groupId, String artifactId, String tag) {
        return SITE + "api/builds/" + groupId + "/" + artifactId + "/" + tag;
    }

    /** How a wait ended. Only {@link #BUILT} lets the publish go on to the listing. */
    public enum Build {
        /** The pom is downloadable. */
        BUILT,
        /** JitPack answered {@code "status": "Error"}: the build ran and failed, and its result is cached. */
        FAILED,
        /** Neither, after ten minutes. */
        TIMEOUT,
        /** The operator asked the release to stop, or the thread was interrupted. */
        STOPPED
    }

    /**
     * What the wait saw.
     *
     * @param detail JitPack's own message for {@link Build#FAILED}, empty otherwise
     */
    public record Waited(Build build, String detail) {

        /** The build log a {@link Build#FAILED} detail names, as a URL a reader opens; empty when it names none. */
        public Optional<String> logUrl() {
            return logPath(detail).map(path -> SITE + path);
        }
    }

    /**
     * What the wait asks JitPack, behind an interface so a test answers instead of the network.
     */
    public interface Probe {

        /** Whether the pom at {@code pomUrl} is downloadable. */
        boolean built(String pomUrl);

        /**
         * JitPack's message when the build API at {@code buildUrl} says the build failed; empty while it is
         * queued, running, done, or unreachable. Asking also starts the build when nobody had.
         */
        Optional<String> failure(String buildUrl);

        static Probe real() {
            return new Probe() {
                @Override
                public boolean built(String pomUrl) {
                    return head(pomUrl);
                }

                @Override
                public Optional<String> failure(String buildUrl) {
                    return body(buildUrl).flatMap(Jitpack::failureIn)
                            .map(message -> withCause(message, logPath(message).flatMap(path -> body(SITE + path))));
                }
            };
        }
    }

    /**
     * Polls until the tag's pom is downloadable, JitPack says the build failed, or the operator stops it.
     *
     * <p><b>The build API is read on every try, since 2026-10-05.</b> Until then the wait polled for the pom
     * only, so a build that had failed in its first minute was waited on for the full ten: studio-api v0.4.1
     * failed on Maven Central's {@code 429 Too Many Requests}, and the release waited, then tagged two more
     * modules on top of it.
     *
     * @param sleeper how to wait between tries — a parameter so a test is not a ten-minute test
     * @param say      each progress line
     * @param stopping whether the caller asked to stop
     * @param sleeper  how to wait between tries — a parameter so a test is not a ten-minute test
     * @return how it ended; a timeout is reported, never thrown, since the tag is already pushed
     */
    public static Waited waitFor(String groupId, String artifactId, String tag, Consumer<String> say,
                                 BooleanSupplier stopping, Sleeper sleeper, Probe probe) {
        String what = artifactId + ":" + tag;
        say.accept("waiting for JitPack to build " + what + " ...");
        for (int attempt = 0; attempt < TRIES; attempt++) {
            // The build API first: asking it is also the nudge that starts a build nobody requested yet.
            Optional<String> failure = probe.failure(buildUrl(groupId, artifactId, tag));
            if (failure.isPresent()) {
                say.accept("JitPack failed to build " + what + ": " + failure.get());
                return new Waited(Build.FAILED, failure.get());
            }
            if (probe.built(pomUrl(groupId, artifactId, tag))) {
                say.accept("JitPack build of " + what + " is ready.");
                return new Waited(Build.BUILT, "");
            }
            if (stopping.getAsBoolean() || !sleeper.sleep(EVERY)) {
                say.accept("Stopped waiting for JitPack to build " + what + ".");
                return new Waited(Build.STOPPED, "");
            }
        }
        say.accept("warn: " + what + " not built on JitPack after 10 min"
                + " — check " + SITE + "#" + groupId.substring(groupId.lastIndexOf('.') + 1) + "/" + artifactId);
        return new Waited(Build.TIMEOUT, "");
    }

    /**
     * The message of a build-API answer whose {@code status} is {@code Error}, as JitPack writes it — it names
     * the build log, e.g. {@code No build artifacts found. See the log: com/github/…/build.log}.
     */
    static Optional<String> failureIn(String json) {
        try {
            JsonNode answer = new ObjectMapper().readTree(json);
            if (answer == null || !"error".equalsIgnoreCase(answer.path("status").asText())) {
                return Optional.empty();
            }
            String message = answer.path("message").asText("").strip();
            return Optional.of(message.isEmpty() ? "the build failed" : message);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The build log a failure message names ({@code See the log: com/github/…/build.log}), relative to the site. */
    static Optional<String> logPath(String message) {
        Matcher log = LOG_PATH.matcher(message);
        return log.find() ? Optional.of(log.group(1)) : Optional.empty();
    }

    /**
     * The failure message, with the cause said when the log shows one the operator would otherwise misread:
     * Maven Central's rate limit, which is nothing in the module (shared v0.2.4, studio-api v0.4.1).
     */
    static String withCause(String message, Optional<String> log) {
        return log.filter(text -> RATE_LIMIT.matcher(text).find()).isPresent()
                // One line: a listener reads the wait's output line by line.
                ? message + " — the log shows Maven Central's " + RATE_LIMITED + ": a rate limit on JitPack's "
                        + "builders, not this module. A new patch of it repairs the tag."
                : message;
    }

    /** How the wait passes time; the real one sleeps, a test's does not. */
    @FunctionalInterface
    public interface Sleeper {

        /** @return false to give up (the thread was interrupted, or the operator stopped the release) */
        boolean sleep(Duration duration);

        static Sleeper real() {
            return real(() -> false);
        }

        /**
         * Sleeps in half-second slices and gives up as soon as {@code stop} says so, so a Stop pressed in a
         * ten-second gap does not wait out the gap.
         */
        static Sleeper real(BooleanSupplier stop) {
            return duration -> {
                long until = System.nanoTime() + duration.toNanos();
                try {
                    while (System.nanoTime() < until) {
                        if (stop.getAsBoolean()) {
                            return false;
                        }
                        Thread.sleep(Math.min(SLICE.toMillis(), Math.max(1, (until - System.nanoTime()) / 1_000_000)));
                    }
                    return !stop.getAsBoolean();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            };
        }
    }

    private static final Duration SLICE = Duration.ofMillis(500);
    /** JitPack's front page. */
    static final String SITE = "https://jitpack.io/";
    private static final String RATE_LIMITED = "429 Too Many Requests";
    /**
     * Each way a build log words a 429: Maven 3.6's wagon ({@code 429 Too Many Requests}), Maven 3.9's resolver
     * ({@code status code: 429, reason phrase: Too Many Requests (429)}), curl over HTTP/2 ({@code returned
     * error: 429}, no reason phrase).
     */
    private static final Pattern RATE_LIMIT =
            Pattern.compile("429 Too Many Requests|status code: 429|Too Many Requests \\(429\\)|error: 429\\b");
    private static final Pattern LOG_PATH = Pattern.compile("See the log: (\\S+/build\\.log)");

    private static Optional<String> body(String url) {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static boolean head(String url) {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()) {
            HttpResponse<Void> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
