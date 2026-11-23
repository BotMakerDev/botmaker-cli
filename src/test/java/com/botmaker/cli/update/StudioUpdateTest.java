// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.update;

import com.botmaker.cli.update.StudioHost.Platform;
import com.botmaker.cli.update.StudioInstall.Format;
import com.botmaker.cli.update.StudioUpdate.Caller;
import com.botmaker.cli.update.StudioUpdate.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioUpdateTest {

    private static final byte[] NEW = {4, 5, 6, 7};
    private static final String TAG = "v1.15.0";

    @TempDir
    Path dir;

    /** Release {@link #TAG}, every installer {@link #NEW}, its checksums {@code listed} for each. */
    private static final class FakeReleases implements SelfUpdate.Releases {
        final Map<String, String> assets = new LinkedHashMap<>();
        final List<String> downloads = new ArrayList<>();
        String checksums = "";

        FakeReleases(String listed) {
            for (String suffix : List.of("linux-x64.rpm", "linux-x64.deb", "linux-x64.AppImage", "windows-x64.msi",
                    "macos-arm64.dmg")) {
                String name = "BotMaker-Studio-1.15.0-" + suffix;
                assets.put(name, "https://example.com/" + name);
                checksums += listed + "  " + name + "\n";
            }
            assets.put(ReleaseChecksums.FILE_NAME, "https://example.com/sums");
        }

        @Override
        public Optional<SelfUpdate.Latest> latest() {
            return Optional.of(new SelfUpdate.Latest(TAG, assets));
        }

        @Override
        public void download(String url, Path to) throws IOException {
            downloads.add(url);
            Files.write(to, NEW);
        }

        @Override
        public Optional<String> text(String url) {
            return Optional.of(checksums);
        }
    }

    private static String sha256Of(byte[] bytes, Path dir) throws IOException {
        Path file = Files.write(dir.resolve("probe"), bytes);
        String sum = ReleaseChecksums.sha256(file);
        Files.delete(file);
        return sum;
    }

    /** A computer of {@code platform}: the paths in {@code files} exist, the programs in {@code path} are found. */
    private static final class FakeHost implements StudioHost {
        final Platform platform;
        String arch = "amd64";
        final Set<Path> files = new HashSet<>();
        final Set<String> path = new HashSet<>();
        final Map<String, String> env = new LinkedHashMap<>();
        Format packaged;
        boolean terminal;
        List<Long> running = List.of();
        final List<List<String>> ran = new ArrayList<>();
        final List<List<String>> started = new ArrayList<>();
        Optional<String> version = Optional.of("1.14.0");

        FakeHost(Platform platform) {
            this.platform = platform;
        }

        @Override public Platform platform() { return platform; }
        @Override public String arch() { return arch; }
        @Override public Optional<String> env(String name) { return Optional.ofNullable(env.get(name)); }
        @Override public boolean exists(Path p) { return files.contains(p) || Files.exists(p); }
        @Override public boolean writable(Path d) { return Files.isWritable(d); }
        @Override public boolean onPath(String command) { return path.contains(command); }
        @Override public boolean terminal() { return terminal; }
        @Override public boolean installed(Format format) { return format == packaged; }

        @Override
        public int run(List<String> argv) {
            ran.add(argv);
            return 0;
        }

        @Override
        public void start(List<String> argv) {
            started.add(argv);
        }

        @Override public Optional<String> version(StudioInstall install) { return version; }
        @Override public List<Long> runningStudios() { return running; }
    }

    private static FakeHost linuxPackage(Format format, boolean repository) {
        FakeHost host = new FakeHost(Platform.LINUX);
        host.files.add(StudioUpdate.OPT.resolve("bin").resolve(StudioUpdate.LAUNCHER));
        host.files.add(StudioUpdate.OPT.resolve("lib/app").resolve(StudioUpdate.LAUNCHER + ".cfg"));
        if (repository) {
            host.files.add(format == Format.RPM ? StudioUpdate.RPM_REPOSITORY : StudioUpdate.DEB_REPOSITORY);
        }
        host.packaged = format;
        return host;
    }

    @Test
    void findsEachInstallWhereItsInstallerPutIt() {
        assertEquals(new StudioInstall.Package(Format.RPM, true),
                StudioUpdate.locate(Optional.empty(), linuxPackage(Format.RPM, true)));
        assertEquals(new StudioInstall.Package(Format.DEB, false),
                StudioUpdate.locate(Optional.empty(), linuxPackage(Format.DEB, false)));

        Path image = Path.of("/home/me/Apps/BotMaker-Studio.AppImage");
        assertEquals(new StudioInstall.AppImage(image),
                StudioUpdate.locate(Optional.of(image), new FakeHost(Platform.LINUX)));

        FakeHost windows = new FakeHost(Platform.WINDOWS);
        Path root = Path.of("C:/Program Files/BotMaker Studio");
        windows.env.put("ProgramFiles", "C:/Program Files");
        windows.files.add(root.resolve("BotMaker Studio.exe"));
        windows.files.add(root.resolve("app").resolve("BotMaker Studio.cfg"));
        assertEquals(new StudioInstall.Msi(root), StudioUpdate.locate(Optional.empty(), windows));

        FakeHost mac = new FakeHost(Platform.MAC);
        Path bundle = Path.of("/Applications/BotMaker Studio.app");
        mac.files.add(bundle.resolve("Contents/MacOS/BotMaker Studio"));
        mac.files.add(bundle.resolve("Contents/app/BotMaker Studio.cfg"));
        assertInstanceOf(StudioInstall.Unmanaged.class, StudioUpdate.locate(Optional.empty(), mac), "an Intel Mac");
        mac.arch = "aarch64";
        assertEquals(new StudioInstall.AppBundle(bundle), StudioUpdate.locate(Optional.empty(), mac));

        assertInstanceOf(StudioInstall.Unmanaged.class,
                StudioUpdate.locate(Optional.empty(), new FakeHost(Platform.LINUX)), "nothing installed");
    }

    @Test
    void aRepositoryPackageIsUpgradedByItsManagerAsRoot() throws IOException {
        FakeHost host = linuxPackage(Format.RPM, true);
        host.path.addAll(List.of("sudo", "pkexec", "dnf"));
        host.terminal = true;
        FakeReleases releases = new FakeReleases("0".repeat(64));
        host.version = Optional.of("1.15.0");   // what dnf left

        Outcome outcome = new StudioUpdate(releases, host, Caller.COMMAND, StudioUpdate.locate(Optional.empty(), host),
                Optional.of("1.14.0")).update(f -> { });

        assertEquals(new Outcome.Updated(TAG), outcome);
        assertEquals(List.of(List.of("sudo", "dnf", "upgrade", "-y", "--refresh", "botmaker-studio")), host.ran);
        assertTrue(releases.downloads.isEmpty(), "the repository's package, not the release's");

        FakeHost deb = linuxPackage(Format.DEB, true);
        deb.path.addAll(List.of("sudo", "apt-get"));
        deb.version = Optional.of("1.14.0");   // the repository has not got it yet
        IOException stale = assertThrows(IOException.class, () -> new StudioUpdate(releases, deb, Caller.STUDIO,
                StudioUpdate.locate(Optional.empty(), deb), Optional.of("1.14.0")).update(f -> { }));
        assertEquals(List.of("sudo", "-n", "sh", "-c",
                "apt-get update && apt-get install -y --only-upgrade botmaker-studio"), deb.ran.getFirst(),
                "off a terminal without pkexec, sudo never asks");
        assertTrue(stale.getMessage().contains("does not serve Studio v1.15.0 yet"), stale.getMessage());
    }

    @Test
    void withoutItsManagerAPackageIsInstalledFromTheReleaseByTheLowLevelTool() throws IOException {
        FakeHost host = linuxPackage(Format.RPM, true);   // a repository file, but no dnf (zypper's rpm)
        host.path.add("sudo");
        host.terminal = true;
        FakeReleases releases = new FakeReleases(sha256Of(NEW, dir));

        assertEquals(new Outcome.Updated(TAG), new StudioUpdate(releases, host, Caller.COMMAND,
                StudioUpdate.locate(Optional.empty(), host), Optional.of("1.14.0")).update(f -> { }));
        assertEquals(List.of("sudo", "rpm", "-U", "--replacepkgs"), host.ran.getFirst().subList(0, 4));
        assertTrue(host.ran.getFirst().getLast().endsWith("-linux-x64.rpm"), host.ran.getFirst().toString());
    }

    @Test
    void anAppImageIsReplacedByTheCheckedOne() throws IOException {
        Path image = Files.write(dir.resolve("BotMaker-Studio.AppImage"), new byte[]{1, 2, 3});
        FakeHost host = new FakeHost(Platform.LINUX);
        StudioInstall install = StudioUpdate.locate(Optional.of(image), host);

        IOException corrupt = assertThrows(IOException.class, () -> new StudioUpdate(
                new FakeReleases("0".repeat(64)), host, Caller.COMMAND, install, Optional.empty()).update(f -> { }));
        assertTrue(corrupt.getMessage().contains("does not match"), corrupt.getMessage());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(image), "a refused download changes nothing");

        Outcome outcome = new StudioUpdate(new FakeReleases(sha256Of(NEW, dir)), host, Caller.COMMAND, install,
                Optional.empty()).update(f -> { });

        assertEquals(new Outcome.Updated(TAG), outcome);
        assertArrayEquals(NEW, Files.readAllBytes(image));
        assertTrue(Files.isExecutable(image));
        assertFalse(Files.exists(dir.resolve("BotMaker-Studio.AppImage.part")));
    }

    @Test
    void theMsiRunsQuietlyFromTheCommandAndInItsWindowFromStudio() throws IOException {
        FakeReleases releases = new FakeReleases(sha256Of(NEW, dir));
        StudioInstall msi = new StudioInstall.Msi(Path.of("C:/Program Files/BotMaker Studio"));

        FakeHost command = new FakeHost(Platform.WINDOWS);
        assertEquals(new Outcome.Updated(TAG),
                new StudioUpdate(releases, command, Caller.COMMAND, msi, Optional.of("1.14.0")).update(f -> { }));
        assertEquals(List.of("/passive", "/norestart"), command.ran.getFirst().subList(3, 5));

        FakeHost studio = new FakeHost(Platform.WINDOWS);
        assertEquals(new Outcome.Started(TAG),
                new StudioUpdate(releases, studio, Caller.STUDIO, msi, Optional.of("1.14.0")).update(f -> { }));
        List<String> started = studio.started.getFirst();
        assertEquals(List.of("msiexec", "/i"), started.subList(0, 2));
        assertTrue(Files.exists(Path.of(started.get(2))), "the installer still reads the msi");
        Files.delete(Path.of(started.get(2)));
        Files.delete(Path.of(started.get(2)).getParent());
    }

    @Test
    void theCommandRefusesWhileStudioRunsAndAnUpToDateStudioIsLeft() throws IOException {
        FakeHost host = linuxPackage(Format.RPM, true);
        host.path.add("pkexec");
        host.running = List.of(4242L);
        FakeReleases releases = new FakeReleases("0".repeat(64));
        StudioInstall install = StudioUpdate.locate(Optional.empty(), host);

        IOException running = assertThrows(IOException.class, () -> new StudioUpdate(releases, host, Caller.COMMAND,
                install, Optional.of("1.14.0")).update(f -> { }));
        assertTrue(running.getMessage().contains("running (process 4242)"), running.getMessage());
        assertTrue(host.ran.isEmpty());

        StudioUpdate.Check check = new StudioUpdate(releases, host, Caller.COMMAND, install, Optional.of("1.14.0"))
                .check();
        assertTrue(check.updatable());
        assertEquals(List.of(4242L), check.running());

        host.running = List.of();
        assertEquals(new Outcome.Current(TAG), new StudioUpdate(releases, host, Caller.COMMAND, install,
                Optional.of("1.15.0")).update(f -> { }));
        assertTrue(host.ran.isEmpty());
        assertFalse(new StudioUpdate(releases, host, Caller.STUDIO, new StudioInstall.Unmanaged("a dev build"),
                Optional.empty()).check().updatable());
    }

    @Test
    void readsTheVersionJpackageWroteInTheCfg() {
        assertEquals(Optional.of("1.14.0"), StudioUpdate.cfgVersion("""
                [Application]
                app.classpath=$APPDIR/botmaker-studio-1.14.0.jar

                [JavaOptions]
                java-options=-Djpackage.app-version=1.14.0
                java-options=--enable-native-access=ALL-UNNAMED,javafx.graphics
                """));
        assertEquals(Optional.empty(), StudioUpdate.cfgVersion("[Application]\n"));
    }
}
