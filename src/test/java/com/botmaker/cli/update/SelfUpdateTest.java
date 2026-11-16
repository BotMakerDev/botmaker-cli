// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfUpdateTest {

    private static final byte[] OLD = {1, 2, 3};
    private static final byte[] NEW = {4, 5, 6, 7};
    private static final String JAR_URL = "https://example.com/botmaker-cli-all.jar";
    private static final String SUMS_URL = "https://example.com/checksums_sha256.txt";

    @TempDir
    Path dir;

    /** A release whose jar is {@link #NEW} and whose checksums list {@code listed} for it. */
    private class FakeReleases implements SelfUpdate.Releases {
        final String tag;
        final Map<String, String> assets = new HashMap<>();
        String checksums;
        final List<String> downloads = new ArrayList<>();

        FakeReleases(String tag, String listed) {
            this.tag = tag;
            assets.put(SelfUpdate.ASSET, JAR_URL);
            assets.put(ReleaseChecksums.FILE_NAME, SUMS_URL);
            checksums = listed + "  " + SelfUpdate.ASSET + "\n";
        }

        @Override
        public Optional<SelfUpdate.Latest> latest() {
            return Optional.of(new SelfUpdate.Latest(tag, assets));
        }

        @Override
        public void download(String url, Path to) throws IOException {
            downloads.add(url);
            Files.write(to, NEW);
        }

        @Override
        public Optional<String> text(String url) {
            return Optional.ofNullable(checksums);
        }
    }

    private Path jar() throws IOException {
        return Files.write(dir.resolve("botmaker-cli-all.jar"), OLD);
    }

    private String newSha() throws IOException {
        return ReleaseChecksums.sha256(Files.write(dir.resolve("expected.bin"), NEW));
    }

    @Test
    void aNewerReleaseIsCheckedAndSwappedIn() throws IOException {
        Path jar = jar();
        FakeReleases releases = new FakeReleases("v0.14.0", newSha());

        SelfUpdate.Outcome outcome = new SelfUpdate(releases).update(jar, Optional.of("0.13.1"));

        assertEquals(new SelfUpdate.Outcome.Staged("v0.14.0", jar.toRealPath(), true), outcome);
        assertArrayEquals(NEW, Files.readAllBytes(jar));
        assertFalse(Files.exists(SelfUpdate.pending(jar)));
        assertFalse(Files.exists(dir.resolve("botmaker-cli-all.jar.part")));
    }

    @Test
    void aJarNotMatchingTheChecksumIsRefusedAndTheOldOneKept() throws IOException {
        Path jar = jar();
        FakeReleases releases = new FakeReleases("v0.14.0", "0".repeat(64));

        IOException refused = assertThrows(IOException.class,
                () -> new SelfUpdate(releases).update(jar, Optional.of("0.13.1")));

        assertTrue(refused.getMessage().contains("does not match"), refused.getMessage());
        assertArrayEquals(OLD, Files.readAllBytes(jar));
        assertFalse(Files.exists(SelfUpdate.pending(jar)));
        assertFalse(Files.exists(dir.resolve("botmaker-cli-all.jar.part")));
    }

    @Test
    void anUpdateAlreadyCurrentDownloadsNothing() throws IOException {
        Path jar = jar();
        FakeReleases releases = new FakeReleases("v0.13.1", newSha());

        SelfUpdate.Outcome outcome = new SelfUpdate(releases).update(jar, Optional.of("0.13.1"));

        assertInstanceOf(SelfUpdate.Outcome.Current.class, outcome);
        assertTrue(releases.downloads.isEmpty());
        assertArrayEquals(OLD, Files.readAllBytes(jar));
    }

    @Test
    void aReleaseWithoutChecksumsDownloadsNothing() throws IOException {
        Path jar = jar();
        FakeReleases releases = new FakeReleases("v0.14.0", newSha());
        releases.assets.remove(ReleaseChecksums.FILE_NAME);

        IOException refused = assertThrows(IOException.class,
                () -> new SelfUpdate(releases).update(jar, Optional.of("0.13.1")));

        assertTrue(refused.getMessage().contains("Nothing was downloaded"), refused.getMessage());
        assertTrue(releases.downloads.isEmpty());
    }

    @Test
    void checksumsThatCannotBeFetchedLeaveNothingBehind() throws IOException {
        Path jar = jar();
        FakeReleases releases = new FakeReleases("v0.14.0", newSha());
        releases.checksums = null;

        IOException refused = assertThrows(IOException.class,
                () -> new SelfUpdate(releases).update(jar, Optional.of("0.13.1")));

        assertTrue(refused.getMessage().contains("try again later"), refused.getMessage());
        assertArrayEquals(OLD, Files.readAllBytes(jar));
        assertFalse(Files.exists(dir.resolve("botmaker-cli-all.jar.part")));
    }

    @Test
    void theClaudePluginsPinnedCopyIsNotReplaced() throws IOException {
        Path jar = Files.write(dir.resolve("botmaker-cli-0.12.0-all.jar"), OLD);
        Files.writeString(jar.resolveSibling(jar.getFileName() + ".sha256"), "x");

        assertTrue(SelfUpdate.refusal(jar).orElseThrow().contains("Claude plugin"));
    }

    @Test
    void aChecksumTheUserKeptBesideTheirOwnJarIsNoRefusal() throws IOException {
        Path jar = jar();
        Files.writeString(jar.resolveSibling(jar.getFileName() + ".sha256"), "x");

        assertTrue(SelfUpdate.refusal(jar).isEmpty());
    }

    @Test
    void aLeftoverPartialDownloadDoesNotSpoilTheNextOne() throws IOException {
        Path jar = jar();
        Files.write(dir.resolve("botmaker-cli-all.jar.part"), new byte[100]);
        FakeReleases releases = new FakeReleases("v0.14.0", newSha()) {
            @Override
            public void download(String url, Path to) throws IOException {
                downloads.add(url);
                Files.write(to, NEW, StandardOpenOption.CREATE, StandardOpenOption.WRITE);   // no truncation
            }
        };

        new SelfUpdate(releases).update(jar, Optional.of("0.13.1"));

        assertArrayEquals(NEW, Files.readAllBytes(jar));
    }

    @Test
    void aDevelopmentBuildIsNotUpdated() throws IOException {
        Path jar = jar();
        FakeReleases releases = new FakeReleases("v0.14.0", newSha());

        assertThrows(IOException.class, () -> new SelfUpdate(releases).update(jar, Optional.empty()));
        assertTrue(releases.downloads.isEmpty());
    }

    @Test
    void aStagedJarIsSwappedInLater() throws IOException {
        Path jar = jar();
        Files.write(SelfUpdate.pending(jar), NEW);

        assertTrue(SelfUpdate.swap(jar));
        assertArrayEquals(NEW, Files.readAllBytes(jar));
        assertFalse(SelfUpdate.swap(jar), "nothing is staged any more");
    }
}
