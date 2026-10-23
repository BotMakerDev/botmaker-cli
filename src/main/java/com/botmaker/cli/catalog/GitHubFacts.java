package com.botmaker.cli.catalog;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * {@link EntryFacts} and poms over GitHub's REST API, raw files and JitPack, read as data.
 *
 * <p>Anonymous, or with {@code GH_TOKEN} when the environment holds one (a workflow's read-only token, for the
 * higher rate limit). A 404 is "none"; any other failure is an {@link UncheckedIOException}, so a rate limit or an
 * outage stops the run instead of reading as "the repository is gone" and unlisting it.
 */
public final class GitHubFacts implements EntryFacts, PomFacts.Source {

    private static final String API = "https://api.github.com/repos/";
    private static final int MAX_POM_BYTES = 2 * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final String token;
    private final Map<String, Optional<Repo>> repos = new HashMap<>();
    private final Map<String, Optional<Release>> releases = new HashMap<>();

    private GitHubFacts(String token) {
        this.token = token == null || token.isBlank() ? null : token.trim();
    }

    /** With {@code GH_TOKEN} when it is set. */
    public static GitHubFacts anonymousOrToken() {
        return new GitHubFacts(System.getenv("GH_TOKEN"));
    }

    @Override
    public Optional<Repo> repo(String repo) {
        return repos.computeIfAbsent(repo, r -> api(API + r).map(json -> new Repo(
                json.path("owner").path("id").asLong(), json.path("owner").path("login").asText(),
                json.path("name").asText(), json.path("description").asText(""),
                StreamSupport.stream(json.path("topics").spliterator(), false).map(JsonNode::asText).toList(),
                json.path("stargazers_count").asInt(),
                Optional.ofNullable(json.path("parent").path("full_name").textValue()))));
    }

    @Override
    public Optional<Release> release(String repo, String tag) {
        return releases.computeIfAbsent(repo + "@" + tag, k -> api(API + repo + "/releases/tags/" + encode(tag))
                .map(json -> new Release(json.path("author").path("login").asText(),
                        json.path("draft").asBoolean(), json.path("prerelease").asBoolean())));
    }

    @Override
    public boolean jitpackServes(String coordinate, String tag) {
        int colon = coordinate.indexOf(':');
        String group = coordinate.substring(0, colon).replace('.', '/');
        String artifact = coordinate.substring(colon + 1);
        String version = encode(tag);
        try {
            // JitPack builds a tag the first time it is asked for, which can take minutes.
            return get("https://jitpack.io/" + group + "/" + artifact + "/" + version + "/" + artifact + "-"
                    + version + ".pom", Duration.ofMinutes(10), false).statusCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public Optional<String> pom(String repo, String tag) {
        return raw(repo, tag, "pom.xml");
    }

    /** One file of {@code repo} at {@code ref}, at most 2 MB; empty when it is absent or GitHub did not answer. */
    public Optional<String> raw(String repo, String ref, String path) {
        try {
            HttpResponse<byte[]> response = get("https://raw.githubusercontent.com/" + repo + "/" + encode(ref)
                    + "/" + path, Duration.ofSeconds(30), false);
            if (response.statusCode() != 200 || response.body().length > MAX_POM_BYTES) return Optional.empty();
            return Optional.of(new String(response.body(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<JsonNode> api(String url) {
        try {
            HttpResponse<byte[]> response = get(url, Duration.ofSeconds(30), true);
            if (response.statusCode() == 404) return Optional.empty();
            if (response.statusCode() / 100 != 2) {
                throw new IOException("GitHub answered " + response.statusCode() + " for " + url);
            }
            return Optional.of(CatalogJson.lenient().readTree(response.body()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private HttpResponse<byte[]> get(String url, Duration timeout, boolean api) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
        if (api) {
            request.header("Accept", "application/vnd.github+json");
            if (token != null) request.header("Authorization", "Bearer " + token);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching " + url, e);
        }
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
