// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.update;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** What {@link StudioUpdate} asks of the computer; {@link SystemStudioHost} is the real one, a test's is a fake. */
public interface StudioHost {

    /** The operating systems Studio is released for, and the rest. */
    enum Platform {
        LINUX, WINDOWS, MAC, OTHER;

        /** {@code os.name}'s platform. */
        public static Platform of(String osName) {
            String os = osName.toLowerCase(Locale.ROOT);
            if (os.startsWith("windows")) return WINDOWS;
            if (os.contains("mac")) return MAC;
            if (os.contains("linux")) return LINUX;
            return OTHER;
        }
    }

    Platform platform();

    /** {@code os.arch}: {@code amd64}, {@code aarch64}. */
    String arch();

    Optional<String> env(String name);

    /** {@code path} is a file or a folder. */
    boolean exists(Path path);

    /** Files can be made and replaced in {@code dir}. */
    boolean writable(Path dir);

    /** {@code command} is a program on {@code PATH}. */
    boolean onPath(String command);

    /** A person at a terminal can answer {@code sudo}'s password prompt. */
    boolean terminal();

    /** {@code format}'s package manager lists {@link StudioUpdate#PACKAGE} as installed. */
    boolean installed(StudioInstall.Format format);

    /** Runs {@code argv} to its end, its output this process's; its exit code. */
    int run(List<String> argv) throws IOException;

    /** Starts {@code argv} and returns at once. */
    void start(List<String> argv) throws IOException;

    /** The Studio version {@code install} holds ({@code 1.14.0}), empty when it cannot be read. */
    Optional<String> version(StudioInstall install);

    /** The process ids of the installed Studios running now, other than this process. */
    List<Long> runningStudios();
}
