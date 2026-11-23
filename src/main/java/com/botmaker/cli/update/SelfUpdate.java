// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.update;

import com.botmaker.cli.publish.Version;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleConsumer;

/**
 * {@code botmaker update}'s work (2026-11-17): replaces the jar this command runs from with the latest release's
 * {@code botmaker-cli-all.jar}, checked against the release's {@link ReleaseChecksums#FILE_NAME} first.
 *
 * <p>The new jar is downloaded beside the old one and named {@code <jar>.new} once it is checked. Where the running
 * jar can be replaced (macOS, Linux) it is swapped at once; Windows keeps a running jar locked, so there the swap
 * waits until the process ends ({@link #swap} reports false and the command hands it over).
 *
 * <p>Only a jar the user downloaded is replaced. A package manager's copy, JBang's and the Claude plugin's (which
 * its {@code cli.json} pins by sha256) are refused with who updates them instead ({@link #refusal}).
 *
 * <p>Silent, like {@code publish}: it prints nothing and knows no command line.
 */
public final class SelfUpdate {

    /** The release asset that is the whole command. */
    public static final String ASSET = "botmaker-cli-all.jar";

    /** Where the Linux packages install the jar: their package manager updates it. */
    static final Path PACKAGED = Path.of("/usr/share/botmaker");

    /** The latest release: its tag and its assets' download URLs by name. */
    public record Latest(String tag, Map<String, String> assets) {}

    /** What reading the release over the network does; GitHub's in {@link GitHubReleases}. */
    public interface Releases {
        /** The latest published release, or empty when there is none. */
        Optional<Latest> latest() throws IOException;

        /** Saves {@code url}'s body to {@code to}. */
        void download(String url, Path to) throws IOException;

        /**
         * {@link #download(String, Path)}, reporting the fraction done to {@code progress} as bytes arrive, or
         * {@code -1} when the size is unknown; called on the downloading thread.
         */
        default void download(String url, Path to, DoubleConsumer progress) throws IOException {
            progress.accept(-1);
            download(url, to);
        }

        /** {@code url}'s body as text, or empty when it could not be fetched. */
        Optional<String> text(String url);
    }

    /** How an update ended. */
    public sealed interface Outcome {
        /** The installed version is the latest's, or newer. */
        record Current(String tag) implements Outcome {}

        /**
         * The latest jar is checked and staged beside {@code jar} (the real file, past any link); {@code swapped}
         * when it already replaced it.
         */
        record Staged(String tag, Path jar, boolean swapped) implements Outcome {}
    }

    private final Releases releases;

    public SelfUpdate(Releases releases) {
        this.releases = releases;
    }

    /**
     * Why {@code jar} is not this command's to replace, or empty when it is: the cli does not run from a jar, or
     * someone else keeps the jar current.
     */
    public static Optional<String> refusal(Path jar) {
        if (jar == null || !jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                || !Files.isRegularFile(jar)) {
            return Optional.of("botmaker is not running from a jar (an IDE or a test run), so there is nothing to "
                    + "replace.");
        }
        Path dir = jar.toAbsolutePath().getParent();
        if (dir.equals(PACKAGED)) {
            return Optional.of("This botmaker was installed by your package manager, which updates it: "
                    + "`sudo dnf upgrade botmaker` or `sudo apt-get install --only-upgrade botmaker`.");
        }
        String path = jar.toAbsolutePath().toString().replace('\\', '/');
        if (path.contains("/.m2/repository/") || path.contains("/.jbang/")) {
            return Optional.of("This botmaker runs from a Maven or JBang cache, which this command does not manage: "
                    + "JBang keeps its copy current, and a copy of your own is the release's " + ASSET + ".");
        }
        // The plugin's launcher keeps botmaker-cli-<version>-all.jar and writes <jar>.sha256 once it checked it.
        if (jar.getFileName().toString().matches("botmaker-cli-\\d+\\.\\d+\\.\\d+-all\\.jar")
                && Files.exists(jar.resolveSibling(jar.getFileName() + ".sha256"))) {
            return Optional.of("This is the Claude plugin's copy of botmaker, pinned by the plugin's cli.json; it "
                    + "follows the plugin's releases.");
        }
        if (!Files.isWritable(dir)) {
            return Optional.of(dir + " is not writable, so the jar in it cannot be replaced.");
        }
        return Optional.empty();
    }

    /** {@code <jar>.new}: the checked jar waiting to replace {@code jar}. */
    public static Path pending(Path jar) {
        return jar.resolveSibling(jar.getFileName() + ".new");
    }

    /**
     * Updates {@code jar}, whose version is {@code installed} ({@code 0.13.1}; empty for a development build), to
     * the latest release. Throws why when it cannot: nothing is staged unchecked, and a refused download is deleted.
     */
    public Outcome update(Path jar, Optional<String> installed) throws IOException {
        Optional<String> refused = refusal(jar);
        if (refused.isPresent()) throw new IOException(refused.get());
        jar = jar.toRealPath();   // a link to the jar: the jar itself is replaced, never the link
        Version current = installed.flatMap(Version::parse).orElseThrow(() -> new IOException(
                "This is a development build of botmaker, which has no release version to update from."));

        Latest latest = releases.latest()
                .orElseThrow(() -> new IOException("botmaker-cli has no published release."));
        Version newest = Version.parse(latest.tag()).orElseThrow(() -> new IOException(
                "The latest release's tag " + latest.tag() + " is not a version."));
        if (newest.compareTo(current) <= 0) return new Outcome.Current(latest.tag());

        String jarUrl = latest.assets().get(ASSET);
        if (jarUrl == null) throw new IOException("Release " + latest.tag() + " has no " + ASSET + ".");
        String checksumsUrl = latest.assets().get(ReleaseChecksums.FILE_NAME);
        if (checksumsUrl == null) {
            throw new IOException("Release " + latest.tag() + " has no " + ReleaseChecksums.FILE_NAME
                    + ", so its jar cannot be checked. Nothing was downloaded.");
        }

        Path part = jar.resolveSibling(jar.getFileName() + ".part");
        try {
            Files.deleteIfExists(part);   // one a killed run left would keep its tail past the new bytes
            releases.download(jarUrl, part);
            String checksums = releases.text(checksumsUrl).orElseThrow(() -> new IOException(
                    "Could not fetch the release's " + ReleaseChecksums.FILE_NAME + " to check " + ASSET
                            + "; try again later."));
            ReleaseChecksums.verify(part, ASSET, checksums);
            move(part, pending(jar));
        } finally {
            Files.deleteIfExists(part);
        }
        return new Outcome.Staged(latest.tag(), jar, swap(jar));
    }

    /**
     * Moves a staged {@code <jar>.new} over {@code jar}. False when nothing is staged or the running jar is locked
     * (Windows): the staged jar then stays for a later swap.
     */
    public static boolean swap(Path jar) {
        Path staged = pending(jar);
        if (!Files.isRegularFile(staged)) return false;
        try {
            move(staged, jar);
            return true;
        } catch (IOException locked) {
            return false;
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
