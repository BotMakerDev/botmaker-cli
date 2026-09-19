package com.botmaker.cli.gallery;

import com.botmaker.cli.registry.Registry;
import com.botmaker.cli.registry.RegistryEntry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What {@link GalleryGate} has to ask the network, as an interface so the rules can be tested without it.
 *
 * <p>Three questions and no more — the same split {@code PluginSubject} makes for the validator: the gate
 * reasons over facts, and one class goes and fetches them.
 */
public interface GalleryFacts {

    /** The repository's newest release tag, or empty when it has none. */
    Optional<String> latestReleaseTag(String owner, String repo) throws IOException;

    /** Whether the archive an install fetches for {@code tag} downloads. */
    boolean archiveExists(String owner, String repo, String tag) throws IOException;

    /**
     * Every entry the plugin registry lists, or empty when the registry could not be read — in which case
     * nothing about {@code requires} is reported or composed, because an unreachable registry is not the
     * author's fault.
     */
    Optional<List<RegistryEntry>> registry();

    /** The gallery's own copy of the entry at {@code path} today, or empty when it holds none. */
    Optional<GalleryEntry> listedEntry(String path) throws IOException;

    /** The registry's plugin ids — {@link #registry()} reduced to what {@code requires} names. */
    default Optional<Set<String>> pluginIds() {
        return registry().map(entries -> {
            Set<String> ids = new LinkedHashSet<>();
            entries.forEach(entry -> ids.add(entry.id()));
            return ids;
        });
    }

    /** The real network: GitHub's API through {@link Templates}, and the two raw-CDN files. */
    static GalleryFacts github() {
        return new GalleryFacts() {
            @Override
            public Optional<String> latestReleaseTag(String owner, String repo) throws IOException {
                return Templates.findLatestReleaseTag(owner, repo);
            }

            @Override
            public boolean archiveExists(String owner, String repo, String tag) throws IOException {
                return Templates.archiveExists(owner, repo, tag);
            }

            @Override
            public Optional<List<RegistryEntry>> registry() {
                try {
                    return raw("https://raw.githubusercontent.com/BotMakerDev/botmaker-plugin-registry/main/"
                            + Registry.INDEX)
                            .map(body -> {
                                try {
                                    return List.of(Registry.mapper().readValue(body, RegistryEntry[].class));
                                } catch (IOException e) {
                                    return null;
                                }
                            });
                } catch (IOException e) {
                    return Optional.empty();
                }
            }

            @Override
            public Optional<GalleryEntry> listedEntry(String path) throws IOException {
                Optional<byte[]> body = raw("https://raw.githubusercontent.com/BotMakerDev/botmaker-gallery/main/"
                        + path);
                return body.isEmpty() ? Optional.empty()
                        : Optional.of(Registry.mapper().readValue(body.get(), GalleryEntry.class));
            }

            /** A 2xx body, empty on 404; anything else is an {@link IOException}. */
            private Optional<byte[]> raw(String url) throws IOException {
                try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()) {
                    HttpResponse<byte[]> response = client.send(
                            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() == 404) {
                        return Optional.empty();
                    }
                    if (response.statusCode() / 100 != 2) {
                        throw new IOException("GitHub answered " + response.statusCode() + " for " + url);
                    }
                    return Optional.of(response.body());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while fetching " + url, e);
                }
            }
        };
    }
}
