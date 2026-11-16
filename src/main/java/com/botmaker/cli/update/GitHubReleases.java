// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
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

/** {@link SelfUpdate.Releases} over GitHub's REST API, anonymous: {@code BotMakerDev/botmaker-cli}'s latest release. */
public final class GitHubReleases implements SelfUpdate.Releases {

    private static final String LATEST = "https://api.github.com/repos/BotMakerDev/botmaker-cli/releases/latest";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Override
    public Optional<SelfUpdate.Latest> latest() throws IOException {
        HttpResponse<String> response = send(request(LATEST).header("Accept", "application/vnd.github+json").build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) {
            throw new IOException("GitHub answered " + response.statusCode() + " for botmaker-cli's latest release.");
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
    public Optional<String> text(String url) {
        try {
            HttpResponse<String> response = send(request(url).build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
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
