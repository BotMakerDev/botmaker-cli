// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.update;

import com.botmaker.cli.publish.Version;
import com.botmaker.cli.update.SelfUpdate.Latest;
import com.botmaker.cli.update.SelfUpdate.Releases;
import com.botmaker.cli.update.StudioHost.Platform;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;

/**
 * Updates the installed BotMaker Studio to its latest release, the way it was installed (2026-11-23): {@code botmaker
 * studio update} and Studio's own Check for Updates both run it. The rpm or deb is upgraded from its signed repository
 * by dnf or apt, or, without that repository, the release's package is installed; an AppImage is replaced in place; the
 * MSI's installer is run; the {@code .app} is replaced from the release's dmg. Every download is checked against the
 * release's {@link ReleaseChecksums#FILE_NAME} first, and root is asked for as {@code sluice update} asks: {@code sudo}
 * at a terminal, else {@code pkexec}, else {@code sudo -n}.
 *
 * <p>The command refuses while an installed Studio runs ({@link Caller#COMMAND}); Studio updating itself
 * ({@link Caller#STUDIO}) asks its user to reopen it after, and on Windows starts the MSI's own window and leaves it.
 *
 * <p>Silent, like {@code publish}: it prints nothing and knows no command line.
 */
public final class StudioUpdate {

    /** Studio's Linux package. */
    public static final String PACKAGE = "botmaker-studio";
    /** Studio's launcher, the program jpackage writes: {@code BotMaker Studio}, {@code BotMaker Studio.exe}. */
    public static final String LAUNCHER = "BotMaker Studio";
    /** Studio's release repository. */
    public static final String REPOSITORY = "botmaker-studio";

    /** Where the rpm and deb put Studio, a path jpackage fixes. */
    static final Path OPT = Path.of("/opt/botmaker-studio");
    static final Path RPM_REPOSITORY = Path.of("/etc/yum.repos.d/botmaker-studio.repo");
    static final Path DEB_REPOSITORY = Path.of("/etc/apt/sources.list.d/botmaker-studio.list");
    private static final String CFG = LAUNCHER + ".cfg";
    private static final String APP_VERSION = "-Djpackage.app-version=";
    /** msiexec's "done, a restart finishes it". */
    private static final int MSI_REBOOT = 3010;

    /** Who updates: the command, which a running Studio stops, or Studio updating itself. */
    public enum Caller { COMMAND, STUDIO }

    /** Where this Studio stands: its version, the latest release's tag and how it is installed. */
    public record Check(Optional<String> installed, Optional<String> latest, StudioInstall install,
                        List<Long> running) {

        /** The latest release is newer than this Studio; false when either version is unread. */
        public boolean newer() {
            Optional<Version> newest = latest.flatMap(Version::parse);
            Optional<Version> current = installed.flatMap(Version::parse);
            return newest.isPresent() && current.isPresent() && newest.get().compareTo(current.get()) > 0;
        }

        /** {@link #newer} and installed in a way this updater updates. */
        public boolean updatable() {
            return newer() && managed();
        }

        /** Installed in a way this updater updates. */
        public boolean managed() {
            return !(install instanceof StudioInstall.Unmanaged);
        }
    }

    /** How an update ended. */
    public sealed interface Outcome {
        /** This Studio is the latest release's, or newer. */
        record Current(String tag) implements Outcome {}

        /** Studio {@code tag} is installed; a Studio still running runs the old one until it is reopened. */
        record Updated(String tag) implements Outcome {}

        /** Studio {@code tag}'s installer is started and finishes once Studio is closed (Windows, from Studio). */
        record Started(String tag) implements Outcome {}
    }

    private final Releases releases;
    private final StudioHost host;
    private final Caller caller;
    private final StudioInstall install;
    private final Optional<String> installed;

    public StudioUpdate(Releases releases, StudioHost host, Caller caller, StudioInstall install,
                        Optional<String> installed) {
        this.releases = releases;
        this.host = host;
        this.caller = caller;
        this.install = install;
        this.installed = installed;
    }

    /**
     * The command's: the Studio whose launcher {@code given} is, else the one installed where its installers put
     * it, its version read from the install.
     */
    public static StudioUpdate forCommand(Optional<Path> given) {
        StudioHost host = new SystemStudioHost(true);
        StudioInstall install = locate(given, host);
        return new StudioUpdate(new GitHubReleases(REPOSITORY), host, Caller.COMMAND, install, host.version(install));
    }

    /** Studio's own, for the running Studio whose version is {@code version}, empty for a development build. */
    public static StudioUpdate forStudio(Optional<String> version) {
        StudioHost host = new SystemStudioHost(false);
        Optional<String> command = ProcessHandle.current().info().command();
        // $APPIMAGE only when this process runs from that AppImage's mount: a shell started by another AppImage
        // passes its own on.
        Optional<Path> launcher = host.env("APPIMAGE")
                .filter(image -> host.env("APPDIR").filter(dir -> command.filter(c -> c.startsWith(dir)).isPresent())
                        .isPresent())
                .or(() -> command)
                .map(Path::of);
        StudioInstall install = version.isEmpty()
                ? new StudioInstall.Unmanaged("this is a development build of Studio, which has no release version")
                : launcher.map(at -> install(at, host)).orElseGet(() -> new StudioInstall.Unmanaged(
                        "this Studio's program could not be found"));
        return new StudioUpdate(new GitHubReleases(REPOSITORY), host, Caller.STUDIO, install, version);
    }

    /**
     * How the Studio whose launcher is {@code given} (past any link) is installed, else the first one installed where
     * an installer puts it: a given wrapper script that is no launcher falls back to those.
     */
    public static StudioInstall locate(Optional<Path> given, StudioHost host) {
        Optional<StudioInstall> named = given.map(StudioUpdate::real).map(at -> install(at, host));
        if (named.isPresent() && !(named.get() instanceof StudioInstall.Unmanaged)) return named.get();
        Optional<StudioInstall> found = launchers(host).stream().filter(host::exists).findFirst()
                .map(at -> install(at, host));
        return found.or(() -> named).orElseGet(() -> new StudioInstall.Unmanaged(
                "no installed Studio was found; say where its program is with "
                        + "`botmaker studio set --command <path>` or --studio"));
    }

    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | SecurityException missing) {
            return path;
        }
    }

    /** Where each installer puts Studio's launcher on this platform, in the order they are looked for. */
    static List<Path> launchers(StudioHost host) {
        List<Path> at = new ArrayList<>();
        switch (host.platform()) {
            case LINUX -> at.add(OPT.resolve("bin").resolve(LAUNCHER));
            case WINDOWS -> {
                for (String root : List.of("ProgramFiles", "LOCALAPPDATA")) {
                    host.env(root).map(Path::of).map(dir -> root.equals("LOCALAPPDATA") ? dir.resolve("Programs") : dir)
                            .ifPresent(dir -> at.add(dir.resolve(LAUNCHER).resolve(LAUNCHER + ".exe")));
                }
            }
            case MAC -> {
                at.add(bundleLauncher(Path.of("/Applications")));
                host.env("HOME").ifPresent(home -> at.add(bundleLauncher(Path.of(home, "Applications"))));
            }
            case OTHER -> { }
        }
        return at;
    }

    private static Path bundleLauncher(Path applications) {
        return applications.resolve(LAUNCHER + ".app").resolve("Contents").resolve("MacOS").resolve(LAUNCHER);
    }

    /** How the Studio whose launcher (or AppImage) is {@code launcher} is installed. */
    public static StudioInstall install(Path launcher, StudioHost host) {
        String name = launcher.getFileName() == null ? "" : launcher.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".appimage") && host.platform() == Platform.LINUX) {
            return new StudioInstall.AppImage(launcher);
        }
        String notOne = launcher + " is not an installed Studio's program";
        switch (host.platform()) {
            case LINUX -> {
                Path root = parent(launcher, 2);
                if (!OPT.equals(root) || !host.exists(OPT.resolve("lib").resolve("app").resolve(CFG))) {
                    return new StudioInstall.Unmanaged(notOne + " (the rpm, the deb or an AppImage); an unpacked "
                            + "Studio is updated by unpacking the release's archive again");
                }
                for (StudioInstall.Format format : StudioInstall.Format.values()) {
                    if (host.installed(format)) {
                        Path repository = format == StudioInstall.Format.RPM ? RPM_REPOSITORY : DEB_REPOSITORY;
                        return new StudioInstall.Package(format, host.exists(repository));
                    }
                }
                return new StudioInstall.Unmanaged(OPT + " holds a Studio that neither rpm nor dpkg lists");
            }
            case WINDOWS -> {
                Path root = launcher.getParent();
                if (!name.equalsIgnoreCase(LAUNCHER + ".exe") || root == null
                        || !host.exists(root.resolve("app").resolve(CFG))) {
                    return new StudioInstall.Unmanaged(notOne);
                }
                return new StudioInstall.Msi(root);
            }
            case MAC -> {
                Path bundle = parent(launcher, 3);
                if (bundle == null || bundle.getFileName() == null || !bundle.getFileName().toString().endsWith(".app")
                        || !host.exists(bundle.resolve("Contents").resolve("app").resolve(CFG))) {
                    return new StudioInstall.Unmanaged(notOne);
                }
                if (!host.arch().equals("aarch64")) {
                    return new StudioInstall.Unmanaged("only an Apple Silicon Studio is released, and this Mac is "
                            + "an Intel one");
                }
                return new StudioInstall.AppBundle(bundle);
            }
            default -> {
                return new StudioInstall.Unmanaged("Studio is released for Linux, Windows and macOS only");
            }
        }
    }

    private static Path parent(Path path, int levels) {
        Path at = path.toAbsolutePath().normalize();
        for (int i = 0; i < levels && at != null; i++) at = at.getParent();
        return at;
    }

    /** The Studio version a jpackage {@code .cfg} names ({@code java-options=-Djpackage.app-version=1.14.0}). */
    public static Optional<String> cfgVersion(String cfg) {
        return cfg.lines().map(String::strip)
                .filter(line -> line.startsWith("java-options=" + APP_VERSION))
                .map(line -> line.substring(("java-options=" + APP_VERSION).length()).strip())
                .filter(v -> !v.isEmpty())
                .findFirst();
    }

    public StudioInstall install() {
        return install;
    }

    /** Where this Studio stands; the latest tag is empty when the release could not be read. */
    public Check check() {
        Optional<String> latest;
        try {
            latest = releases.latest().map(Latest::tag);
        } catch (IOException unreached) {
            latest = Optional.empty();
        }
        return new Check(installed, latest, install, caller == Caller.COMMAND ? host.runningStudios() : List.of());
    }

    /**
     * Updates this Studio to the latest release, reporting the download's fraction done to {@code progress} (or
     * {@code -1} when its size is unknown). Throws why when it cannot: nothing is installed unchecked.
     */
    public Outcome update(DoubleConsumer progress) throws IOException {
        if (install instanceof StudioInstall.Unmanaged unmanaged) {
            throw new IOException("This Studio is not updated from here: " + unmanaged.why() + ".");
        }
        if (caller == Caller.COMMAND) {
            List<Long> running = host.runningStudios();
            if (!running.isEmpty()) {
                throw new IOException("BotMaker Studio is running (process " + running.getFirst() + "): close it, "
                        + "then update.");
            }
        }
        Latest latest = releases.latest()
                .orElseThrow(() -> new IOException("botmaker-studio has no published release."));
        Version newest = Version.parse(latest.tag()).orElseThrow(() -> new IOException(
                "The latest release's tag " + latest.tag() + " is not a version."));
        Optional<Version> current = installed.flatMap(Version::parse);
        if (current.isPresent() && newest.compareTo(current.get()) <= 0) return new Outcome.Current(latest.tag());

        sweepStale();
        if (install instanceof StudioInstall.Package pkg && pkg.repository() && host.onPath(manager(pkg.format()))) {
            run(elevated(upgrade(pkg.format())), pkg.format().manager() + " could not upgrade " + PACKAGE);
            Optional<Version> now = host.version(install).flatMap(Version::parse);
            if (now.isPresent() && now.get().compareTo(newest) < 0) {
                throw new IOException("The " + pkg.format().manager() + " repository does not serve Studio "
                        + latest.tag() + " yet; try again later.");
            }
            return new Outcome.Updated(latest.tag());
        }
        Optional<Path> replaced = switch (install) {
            case StudioInstall.AppImage image -> Optional.of(real(image.file()).toAbsolutePath().getParent());
            case StudioInstall.AppBundle app -> Optional.of(app.bundle().toAbsolutePath().getParent());
            default -> Optional.empty();
        };
        if (replaced.isPresent() && !host.writable(replaced.get())) {
            throw new IOException(replaced.get() + " is not writable, so Studio in it cannot be replaced.");
        }

        String asset = asset(latest, install.assetSuffix());
        String checksumsUrl = latest.assets().get(ReleaseChecksums.FILE_NAME);
        if (checksumsUrl == null) {
            throw new IOException("Release " + latest.tag() + " has no " + ReleaseChecksums.FILE_NAME
                    + ", so its installer cannot be checked. Nothing was downloaded.");
        }
        Path dir = Files.createTempDirectory("botmaker-studio-update");
        boolean keep = false;
        try {
            Path file = dir.resolve(asset);
            releases.download(latest.assets().get(asset), file, progress);
            String checksums = releases.text(checksumsUrl).orElseThrow(() -> new IOException(
                    "Could not fetch the release's " + ReleaseChecksums.FILE_NAME + " to check " + asset
                            + "; try again later."));
            ReleaseChecksums.verify(file, asset, checksums);
            Outcome outcome = installFrom(file, latest.tag(), dir);
            keep = outcome instanceof Outcome.Started;   // the installer still reads it
            return outcome;
        } finally {
            if (!keep) delete(dir);
        }
    }

    private Outcome installFrom(Path file, String tag, Path dir) throws IOException {
        String path = file.toAbsolutePath().toString();
        switch (install) {
            case StudioInstall.Package pkg -> {
                // The package manager when it is here, else the low-level tool (zypper's rpm, a bare dpkg).
                boolean managerHere = host.onPath(manager(pkg.format()));
                List<String> argv = pkg.format() == StudioInstall.Format.RPM
                        ? managerHere ? List.of("dnf", "install", "-y", path) : List.of("rpm", "-U", "--replacepkgs", path)
                        : managerHere ? List.of("apt-get", "install", "-y", path) : List.of("dpkg", "-i", path);
                run(elevated(argv), argv.getFirst() + " could not install " + file.getFileName());
            }
            case StudioInstall.AppImage image -> {
                Path target = image.file().toRealPath();   // a link to the AppImage: the file itself is replaced
                Path part = target.resolveSibling(target.getFileName() + ".part");
                try {
                    Files.copy(file, part, StandardCopyOption.REPLACE_EXISTING);
                    if (!part.toFile().setExecutable(true, false)) {
                        throw new IOException(part + " could not be made executable.");
                    }
                    move(part, target);   // the running AppImage keeps its own copy until it is closed
                } finally {
                    Files.deleteIfExists(part);
                }
            }
            case StudioInstall.Msi ignored -> {
                if (caller == Caller.STUDIO) {
                    // Studio's own files are in use: the MSI's window asks to close it, and finishes after.
                    host.start(List.of("msiexec", "/i", path));
                    return new Outcome.Started(tag);
                }
                int code = host.run(List.of("msiexec", "/i", path, "/passive", "/norestart"));
                if (code != 0 && code != MSI_REBOOT) throw new IOException("The MSI's installer exited " + code + ".");
            }
            case StudioInstall.AppBundle app -> {
                if (!replaceBundle(file, app.bundle(), dir)) return new Outcome.Started(tag);
            }
            case StudioInstall.Unmanaged unmanaged -> throw new IOException(unmanaged.why());
        }
        return new Outcome.Updated(tag);
    }

    /**
     * Copies the dmg's app beside {@code bundle}, then puts it in its place and deletes the old one. From Studio,
     * whose own bundle that is, the swap waits for Studio to end (false); from the command it is done at once.
     */
    private boolean replaceBundle(Path dmg, Path bundle, Path dir) throws IOException {
        Path mount = Files.createDirectories(dir.resolve("mount"));
        Path staged = bundle.resolveSibling(bundle.getFileName() + ".new");
        Path old = bundle.resolveSibling(bundle.getFileName() + ".old");
        delete(staged);
        run(List.of("hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint", mount.toString(), dmg.toString()),
                "The dmg could not be opened");
        try {
            run(List.of("ditto", mount.resolve(LAUNCHER + ".app").toString(), staged.toString()),
                    "The app could not be copied out of the dmg");
        } finally {
            host.run(List.of("hdiutil", "detach", "-quiet", mount.toString()));
        }
        delete(old);
        if (caller == Caller.STUDIO) {
            // The running Studio still loads classes and libraries from its bundle: swap once it has ended.
            host.start(List.of("sh", "-c", "while kill -0 \"$0\" 2>/dev/null; do sleep 1; done; "
                    + "mv \"$1\" \"$3\" && mv \"$2\" \"$1\" && rm -rf \"$3\"",
                    String.valueOf(ProcessHandle.current().pid()), bundle.toString(), staged.toString(),
                    old.toString()));
            return false;
        }
        Files.move(bundle, old);
        try {
            Files.move(staged, bundle);
        } catch (IOException e) {
            Files.move(old, bundle);
            throw e;
        }
        try {
            delete(old);
        } catch (IOException left) {
            // The new Studio is in place; only the old copy beside it stays.
        }
        return true;
    }

    /** The package manager of {@code format}'s packages: dnf, apt-get. */
    private static String manager(StudioInstall.Format format) {
        return format == StudioInstall.Format.RPM ? "dnf" : "apt-get";
    }

    /** Deletes the downloads earlier updates kept for an installer that has long ended (Windows' MSI). */
    private static void sweepStale() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        try (Stream<Path> dirs = Files.list(tmp)) {
            for (Path dir : dirs.filter(d -> d.getFileName().toString().startsWith("botmaker-studio-update")).toList()) {
                if (Files.getLastModifiedTime(dir).toMillis() < dayAgo) delete(dir);
            }
        } catch (IOException | RuntimeException ignored) {
            // A folder left in the temporary folder.
        }
    }

    /** The release asset whose name is Studio's and ends in {@code suffix}. */
    private static String asset(Latest latest, String suffix) throws IOException {
        return latest.assets().keySet().stream()
                .filter(name -> name.startsWith("BotMaker-Studio-") && name.endsWith(suffix))
                .findFirst()
                .orElseThrow(() -> new IOException("Release " + latest.tag() + " has no Studio installer ending in "
                        + suffix + "."));
    }

    /** The package manager's upgrade of {@link #PACKAGE} from its repository. */
    static List<String> upgrade(StudioInstall.Format format) {
        return format == StudioInstall.Format.RPM
                ? List.of("dnf", "upgrade", "-y", "--refresh", PACKAGE)
                : List.of("sh", "-c", "apt-get update && apt-get install -y --only-upgrade " + PACKAGE);
    }

    /**
     * {@code argv} as root: by {@code sudo} when a person at a terminal answers it, else {@code pkexec}'s own window,
     * else {@code sudo -n}, which never asks.
     */
    List<String> elevated(List<String> argv) throws IOException {
        boolean asks = caller == Caller.COMMAND && host.terminal();
        List<List<String>> ways = asks
                ? List.of(List.of("sudo"), List.of("pkexec"))
                : List.of(List.of("pkexec"), List.of("sudo", "-n"));
        for (List<String> way : ways) {
            if (host.onPath(way.getFirst())) {
                return Stream.concat(way.stream(), argv.stream()).toList();
            }
        }
        throw new IOException("Installing Studio needs root, and neither sudo nor pkexec is here.");
    }

    private void run(List<String> argv, String failed) throws IOException {
        int code = host.run(argv);
        if (code != 0) throw new IOException(failed + " (exit " + code + ").");
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> all = Files.walk(path)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
