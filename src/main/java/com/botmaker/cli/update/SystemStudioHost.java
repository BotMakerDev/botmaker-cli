// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.update;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** This computer, for {@link StudioUpdate}. */
public final class SystemStudioHost implements StudioHost {

    private final boolean terminalAsks;

    /** {@code terminalAsks}: {@code sudo} may ask its password at this process's terminal (the command, not Studio). */
    public SystemStudioHost(boolean terminalAsks) {
        this.terminalAsks = terminalAsks;
    }

    @Override
    public Platform platform() {
        return Platform.of(System.getProperty("os.name", ""));
    }

    @Override
    public String arch() {
        return System.getProperty("os.arch", "");
    }

    @Override
    public Optional<String> env(String name) {
        return Optional.ofNullable(System.getenv(name)).filter(v -> !v.isBlank());
    }

    @Override
    public boolean exists(Path path) {
        return Files.exists(path);
    }

    @Override
    public boolean writable(Path dir) {
        return Files.isDirectory(dir) && Files.isWritable(dir);
    }

    @Override
    public boolean onPath(String command) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            try {
                if (!dir.isBlank() && Files.isExecutable(Path.of(dir, command))) return true;
            } catch (InvalidPathException skipped) {
                // A PATH entry that is no path.
            }
        }
        return false;
    }

    @Override
    public boolean terminal() {
        return terminalAsks && System.console() != null && System.console().isTerminal();
    }

    @Override
    public boolean installed(StudioInstall.Format format) {
        List<String> query = format == StudioInstall.Format.RPM
                ? List.of("rpm", "-q", "--quiet", StudioUpdate.PACKAGE)
                : List.of("dpkg-query", "-W", StudioUpdate.PACKAGE);
        if (!onPath(query.getFirst())) return false;
        try {
            Process process = new ProcessBuilder(query).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return wait(process, 30) == 0;
        } catch (IOException unstarted) {
            return false;
        }
    }

    @Override
    public int run(List<String> argv) throws IOException {
        Process process = new ProcessBuilder(argv).inheritIO().start();
        try {
            return process.waitFor();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while " + argv.getFirst() + " ran.", stopped);
        }
    }

    @Override
    public void start(List<String> argv) throws IOException {
        new ProcessBuilder(argv).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    @Override
    public Optional<String> version(StudioInstall install) {
        Path cfg = Path.of("lib", "app", StudioUpdate.LAUNCHER + ".cfg");
        return switch (install) {
            case StudioInstall.Package ignored -> read(StudioUpdate.OPT.resolve(cfg));
            case StudioInstall.Msi msi -> read(msi.root().resolve("app").resolve(StudioUpdate.LAUNCHER + ".cfg"));
            case StudioInstall.AppBundle app -> read(app.bundle().resolve("Contents").resolve("app")
                    .resolve(StudioUpdate.LAUNCHER + ".cfg"));
            case StudioInstall.AppImage image -> fromAppImage(image.file(), "usr/" + cfg.toString().replace('\\', '/'));
            case StudioInstall.Unmanaged ignored -> Optional.empty();
        };
    }

    private static Optional<String> read(Path cfg) {
        try {
            return StudioUpdate.cfgVersion(Files.readString(cfg));
        } catch (IOException unread) {
            return Optional.empty();
        }
    }

    /** The AppImage's own {@code .cfg}, unpacked alone by its {@code --appimage-extract}, which needs no FUSE. */
    private static Optional<String> fromAppImage(Path appImage, String inside) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("botmaker-studio-appimage");
            Process process = new ProcessBuilder(appImage.toAbsolutePath().toString(), "--appimage-extract", inside)
                    .directory(dir.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (wait(process, 60) != 0) return Optional.empty();
            return read(dir.resolve("squashfs-root").resolve(inside));
        } catch (IOException unread) {
            return Optional.empty();
        } finally {
            if (dir != null) delete(dir);
        }
    }

    @Override
    public List<Long> runningStudios() {
        long self = ProcessHandle.current().pid();
        try (Stream<ProcessHandle> all = ProcessHandle.allProcesses()) {
            return all.filter(p -> p.pid() != self)
                    .filter(p -> p.info().command().map(SystemStudioHost::isLauncher).orElse(false))
                    .map(ProcessHandle::pid)
                    .toList();
        }
    }

    private static boolean isLauncher(String command) {
        String name = Path.of(command).getFileName().toString();
        return name.equals(StudioUpdate.LAUNCHER) || name.equalsIgnoreCase(StudioUpdate.LAUNCHER + ".exe");
    }

    /** {@code process}'s exit code, or -1 when it did not end within {@code seconds} (it is then killed). */
    private static int wait(Process process, int seconds) {
        try {
            if (process.waitFor(seconds, TimeUnit.SECONDS)) return process.exitValue();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
        process.destroyForcibly();
        return -1;
    }

    private static void delete(Path dir) {
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // A temporary folder left behind.
        }
    }
}
