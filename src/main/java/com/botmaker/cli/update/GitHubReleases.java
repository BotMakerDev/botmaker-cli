// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleConsumer;

/**
 * {@link SelfUpdate.Releases} over GitHub's REST API, anonymous: a {@code BotMakerDev} repository's latest release,
 * {@code botmaker-cli}'s unless another is named ({@code botmaker-studio}'s for {@link StudioUpdate}).
 */
public final class GitHubReleases implements SelfUpdate.Releases {

    private final String repository;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public GitHubReleases() {
        this("botmaker-cli");
    }

    /** {@code BotMakerDev/<repository>}'s releases. */
    public GitHubReleases(String repository) {
        this.repository = repository;
    }

    @Override
    public Optional<SelfUpdate.Latest> latest() throws IOException {
        String latest = "https://api.github.com/repos/BotMakerDev/" + repository + "/releases/latest";
        HttpResponse<String> response = send(request(latest).header("Accept", "application/vnd.github+json").build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) {
            throw new IOException("GitHub answered " + response.statusCode() + " for " + repository
                    + "'s latest release.");
        }
        JsonNode json = new ObjectMapper().readTree(response.body());
        Map<String, String> assets = new LinkedHashMap<>();
        for (JsonNode asset : json.path("assets")) {
            assets.put(asset.path("name").asText(), asset.path("browser_download_url").asText());
        }
        return Optional.of(new SelfUpdate.Latest(json.path("tag_name").asText(""), assets));
    }

    @Override
    public void download(String url, Path to) throws IOException {
        HttpResponse<Path> response = send(request(url).build(), HttpResponse.BodyHandlers.ofFile(to));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(to);
            throw new IOException("Downloading " + url + " answered " + response.statusCode() + ".");
        }
    }

    @Override
    public void download(String url, Path to, DoubleConsumer progress) throws IOException {
        HttpResponse<InputStream> response = send(request(url).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Downloading " + url + " answered " + response.statusCode() + ".");
        }
        long total = response.headers().firstValueAsLong("content-length").orElse(-1L);
        progress.accept(total > 0 ? 0 : -1);
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            long read = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                read += n;
                if (total > 0) progress.accept((double) read / total);
            }
        } catch (IOException e) {
            Files.deleteIfExists(to);
            throw new IOException("Downloading " + url + " stopped: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> text(String url) {
        try {
            HttpResponse<String> response = send(request(url).build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10))
                .header("User-Agent", "botmaker-cli");
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> body) throws IOException {
        try {
            return http.send(request, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reaching " + request.uri() + ".", e);
        }
    }
}
