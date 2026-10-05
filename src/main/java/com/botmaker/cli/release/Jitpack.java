package com.botmaker.cli.release;

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

/**
 * Nudging JitPack and waiting for a tag to build — {@code release.sh}'s {@code wait_for_jitpack}.
 *
 * <p><b>The waits were removed in 2026-08 and put back on 2026-09-05, and the reversal is the part to
 * carry.</b> Pinning each upstream ref exactly (see {@link DepsEnv}) removed the <i>requirement</i> that an
 * upstream be published and newest before a downstream is tagged — JitPack resolves and builds a pinned
 * dependency tag on demand. Every clause of that is true and it still loses a race: <b>builds on demand is
 * not queues and retries</b>. {@code botmaker-cli} v0.0.9 was tagged seconds after
 * {@code botmaker-plugin-host} v0.0.5, JitPack began the CLI build while the plugin-host build was still
 * running, and it died with {@code Could not find artifact …botmaker-plugin-host:jar:v0.0.5}. A build
 * result is <b>cached per tag</b>, so that is permanent and only a new tag repairs it.
 *
 * <p>The worst property of the bug is that it fails on <i>timing</i>: another module in the same chain
 * resolved cleanly, so it reads as a JitPack outage rather than as something here. Waiting costs a few
 * minutes per link once per release; losing the race costs a whole chain and burns a tag that cannot be
 * reused.
 */
public final class Jitpack {

    private static final Duration EVERY = Duration.ofSeconds(10);
    private static final int TRIES = 60;                          // ~10 minutes

    private Jitpack() {
    }

    /** The pom a consumer would download — its presence is what "built" means for a waiting downstream. */
    public static String pomUrl(Module module, Version version) {
        return "https://jitpack.io/com/github/" + CleanRoom.COORDINATE_OWNER + "/" + module.directory()
                + "/" + version.tag() + "/" + module.directory() + "-" + version.tag() + ".pom";
    }

    /** The endpoint that asks JitPack to start a build rather than waiting for somebody to request it. */
    public static String buildUrl(Module module, Version version) {
        return "https://jitpack.io/api/builds/com.github." + CleanRoom.COORDINATE_OWNER + "/"
                + module.directory() + "/" + version.tag();
    }

    /** How a wait ended. Only {@link #BUILT} lets the chain tag the next module. */
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
                    return body(buildUrl).flatMap(Jitpack::failureIn);
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
     * @return how it ended. <b>The script dies on a timeout</b>; this reports, because the tag is already
     *         pushed and the caller still has work (the log, the pointer commit) worth doing.
     */
    public static Waited waitFor(Runner runner, Module module, Version version, Sleeper sleeper) {
        return waitFor(runner, module, version, sleeper, Probe.real());
    }

    static Waited waitFor(Runner runner, Module module, Version version, Sleeper sleeper, Probe probe) {
        String what = module.directory() + ":" + version.tag();
        if (runner.dryRun()) {
            runner.say("    (dry-run) would poll " + pomUrl(module, version) + " until built");
            return new Waited(Build.BUILT, "");
        }
        runner.say("waiting for JitPack to build " + what + " ...");
        for (int attempt = 0; attempt < TRIES; attempt++) {
            // The build API first: asking it is also the nudge that starts a build nobody requested yet.
            Optional<String> failure = probe.failure(buildUrl(module, version));
            if (failure.isPresent()) {
                // No "error: " prefix: that line closes a module's segment for the dashboard's board, which
                // would then never see this one. The chain's own error line follows.
                runner.say("JitPack failed to build " + what + ": " + failure.get());
                return new Waited(Build.FAILED, failure.get());
            }
            if (probe.built(pomUrl(module, version))) {
                runner.say("JitPack build of " + what + " is ready.");
                return new Waited(Build.BUILT, "");
            }
            if (runner.stopping() || !sleeper.sleep(EVERY)) {
                runner.say("Stopped waiting for JitPack to build " + what + ".");
                return new Waited(Build.STOPPED, "");
            }
        }
        runner.say("warn: " + what + " not built on JitPack after 10 min"
                + " — check https://jitpack.io/#" + CleanRoom.COORDINATE_OWNER + "/" + module.directory());
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
