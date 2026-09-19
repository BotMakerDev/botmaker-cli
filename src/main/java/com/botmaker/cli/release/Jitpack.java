package com.botmaker.cli.release;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

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

    /**
     * Polls until the tag's pom is downloadable.
     *
     * @param sleeper how to wait between tries — a parameter so a test is not a ten-minute test
     * @return true when it built, false on timeout. <b>The script dies here</b>; this reports, because by
     *         the time a wait times out the tag is already pushed and the caller may still have work
     *         (the log, the pointer commit) that is worth doing.
     */
    public static boolean waitFor(Runner runner, Module module, Version version, Sleeper sleeper) {
        if (runner.dryRun()) {
            runner.say("    (dry-run) would poll " + pomUrl(module, version) + " until built");
            return true;
        }
        runner.say("waiting for JitPack to build " + module.directory() + ":" + version.tag() + " ...");
        get(buildUrl(module, version));                            // best-effort nudge, as the script's does
        for (int attempt = 0; attempt < TRIES; attempt++) {
            if (get(pomUrl(module, version))) {
                runner.say("JitPack build of " + module.directory() + ":" + version.tag() + " is ready.");
                return true;
            }
            if (!sleeper.sleep(EVERY)) {
                return false;
            }
        }
        runner.say("warn: " + module.directory() + ":" + version.tag() + " not built on JitPack after 10 min"
                + " — check https://jitpack.io/#" + CleanRoom.COORDINATE_OWNER + "/" + module.directory());
        return false;
    }

    /** How the wait passes time; the real one sleeps, a test's does not. */
    @FunctionalInterface
    public interface Sleeper {

        /** @return false to give up (the thread was interrupted) */
        boolean sleep(Duration duration);

        static Sleeper real() {
            return duration -> {
                try {
                    Thread.sleep(duration);
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            };
        }
    }

    private static boolean get(String url) {
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
