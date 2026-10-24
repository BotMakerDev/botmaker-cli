package com.botmaker.cli.catalog;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
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

    /** With a host's own sign-in, Studio's: anonymous when {@code token} is blank. */
    public static GitHubFacts withToken(String token) {
        return new GitHubFacts(token);
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
        // Only a published release is kept: a publish waits for one its CI is still making (Publish.listRelease),
        // and a cached "none" or draft would answer every later ask.
        Optional<Release> known = releases.get(repo + "@" + tag);
        if (known != null) return known;
        Optional<Release> found = api(API + repo + "/releases/tags/" + encode(tag))
                .map(json -> new Release(json.path("author").path("login").asText(),
                        json.path("draft").asBoolean(), json.path("prerelease").asBoolean()));
        found.filter(release -> !release.draft()).ifPresent(release -> releases.put(repo + "@" + tag, found));
        return found;
    }

    @Override
    public boolean jitpackServes(String coordinate, String tag) {
        try {
            // JitPack builds a tag the first time it is asked for, which can take minutes.
            return get(jitpackUrl(coordinate, tag, Artifact.POM), Duration.ofMinutes(10), false).statusCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public Optional<String> tagCommit(String repo, String tag) {
        Optional<JsonNode> object = api(API + repo + "/git/ref/tags/" + encode(tag)).map(json -> json.path("object"));
        // An annotated tag is a tag object naming the commit (or, rarely, another tag): peeled a few levels.
        for (int depth = 0; depth < 4 && object.isPresent(); depth++) {
            String type = object.get().path("type").asText();
            String sha = object.get().path("sha").asText();
            if (type.equals("commit")) return ApprovedRelease.COMMIT.matcher(sha).matches() ? Optional.of(sha) : Optional.empty();
            if (!type.equals("tag")) return Optional.empty();
            object = api(API + repo + "/git/tags/" + sha).map(json -> json.path("object"));
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> jitpackSha256(String coordinate, String tag, Artifact artifact) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(jitpackUrl(coordinate, tag, artifact)))
                .timeout(Duration.ofMinutes(10)).GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) return Optional.empty();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = body.read(buffer)) > 0; ) digest.update(buffer, 0, n);
                return Optional.of(HexFormat.of().formatHex(digest.digest()));
            }
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("no SHA-256 in this JDK", e);
        }
    }

    /** {@code coordinate}'s {@code artifact} at {@code tag} on JitPack. */
    private static String jitpackUrl(String coordinate, String tag, Artifact artifact) {
        int colon = coordinate.indexOf(':');
        String group = coordinate.substring(0, colon).replace('.', '/');
        String name = coordinate.substring(colon + 1);
        String version = encode(tag);
        return "https://jitpack.io/" + group + "/" + name + "/" + version + "/" + name + "-" + version + "."
                + artifact.extension();
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
