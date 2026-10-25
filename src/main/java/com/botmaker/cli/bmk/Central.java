package com.botmaker.cli.bmk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether Maven Central serves a jar, byte for byte: what decides that a {@code .bmk} lists it rather than packs it
 * (2026-10-25). The groupId says nothing: {@code com.github.jai-imageio} is a Central jar, and JitPack builds any
 * {@code com.github.*} repository. A jar Central serves with the same bytes is fetched from Central on first open;
 * every other is packed in {@code lib/}.
 */
@FunctionalInterface
public interface Central {

    String REPOSITORY = "https://repo1.maven.org/maven2/";

    /** Whether Central serves {@code lib}'s coordinate as exactly {@code bytes}. */
    boolean serves(BotJson.Lib lib, byte[] bytes) throws IOException;

    /**
     * Central over HTTP, each coordinate asked once by this instance: its {@code .jar.sha256}, else its {@code .sha1}.
     * Neither file means Central has no such jar. Central not answering refuses: a guess would pack a jar the
     * catalog's rebuild lists, and the two files would differ.
     */
    static Central maven() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        Map<String, Optional<String[]>> asked = new ConcurrentHashMap<>();
        return (lib, bytes) -> {
            Optional<String[]> sums = asked.get(lib.coordinate());
            if (sums == null) {
                sums = sums(http, jarUrl(lib));
                asked.put(lib.coordinate(), sums);
            }
            return sums.map(s -> matches(s, bytes)).orElse(false);
        };
    }

    /** {@code lib}'s jar on Central. */
    static String jarUrl(BotJson.Lib lib) {
        return REPOSITORY + lib.groupId().replace('.', '/') + "/" + lib.artifactId() + "/" + lib.version() + "/"
                + lib.artifactId() + "-" + lib.version() + lib.classifier().map(c -> "-" + c).orElse("") + ".jar";
    }

    /** Whether {@code bytes} have the checksum Central gave: {@code {"sha256", hex}} or {@code {"sha1", hex}}. */
    static boolean matches(String[] sum, byte[] bytes) {
        String algorithm = sum[0].equals("sha256") ? "SHA-256" : "SHA-1";
        try {
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
            return hex.equals(sum[1]);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has " + algorithm, e);
        }
    }

    private static Optional<String[]> sums(HttpClient http, String jar) throws IOException {
        for (String kind : new String[] {"sha256", "sha1"}) {
            Optional<String> hex = checksum(http, jar + "." + kind);
            if (hex.isPresent()) return Optional.of(new String[] {kind, hex.get()});
        }
        return Optional.empty();
    }

    /** The checksum file's hex, empty when Central has none (404). */
    private static Optional<String> checksum(HttpClient http, String url) throws IOException {
        HttpResponse<String> got;
        try {
            got = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BotFile.Refused("stopped while asking Maven Central about " + url + ".");
        } catch (IOException e) {
            throw new BotFile.Refused("could not reach Maven Central (" + e.getMessage() + "): a .bmk lists only the "
                    + "jars Central serves, so it needs to ask.");
        }
        if (got.statusCode() == 404) return Optional.empty();
        if (got.statusCode() != 200) {
            throw new BotFile.Refused("Maven Central answered " + got.statusCode() + " for " + url + "; try again.");
        }
        // The file is the hex, sometimes followed by the jar's name.
        // Anything but a checksum (a proxy's page) refuses: read as one, it would pack a jar the rebuild lists.
        String hex = got.body().strip().split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (!hex.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            throw new BotFile.Refused(url + " is not a checksum; is something between you and Maven Central?");
        }
        return Optional.of(hex);
    }
}
