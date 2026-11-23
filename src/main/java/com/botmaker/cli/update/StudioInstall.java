// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.update;

import java.nio.file.Path;

/**
 * How BotMaker Studio is installed on this computer, which decides how {@link StudioUpdate} updates it and which
 * release asset it takes (2026-11-23).
 */
public sealed interface StudioInstall {

    /** The release asset's name ends so: {@code BotMaker-Studio-1.9.0-linux-x64.rpm}. Empty when none is taken. */
    String assetSuffix();

    /** What a person reads: "the rpm package botmaker-studio, from its dnf repository". */
    String describe();

    /** The Linux package formats Studio ships. */
    enum Format {
        RPM("rpm", "dnf"),
        DEB("deb", "apt");

        private final String id;
        private final String manager;

        Format(String id, String manager) {
            this.id = id;
            this.manager = manager;
        }

        public String id() {
            return id;
        }

        /** The package manager that installs it: dnf, apt. */
        public String manager() {
            return manager;
        }
    }

    /**
     * The rpm or deb {@code botmaker-studio} under {@code /opt/botmaker-studio}; {@code repository} when the signed
     * repository is registered ({@code /etc/yum.repos.d/botmaker-studio.repo}, {@code
     * /etc/apt/sources.list.d/botmaker-studio.list}), whose package manager then upgrades it.
     */
    record Package(Format format, boolean repository) implements StudioInstall {
        @Override
        public String assetSuffix() {
            return "-linux-x64." + format.id();
        }

        @Override
        public String describe() {
            return "the " + format.id() + " package " + StudioUpdate.PACKAGE
                    + (repository ? ", from its " + format.manager() + " repository" : ", installed from a file");
        }
    }

    /** A Studio AppImage, the one file {@code file}, replaced in place. */
    record AppImage(Path file) implements StudioInstall {
        @Override
        public String assetSuffix() {
            return "-linux-x64.AppImage";
        }

        @Override
        public String describe() {
            return "the AppImage " + file;
        }
    }

    /** Studio installed by its MSI into {@code root} (the folder holding {@code BotMaker Studio.exe}). */
    record Msi(Path root) implements StudioInstall {
        @Override
        public String assetSuffix() {
            return "-windows-x64.msi";
        }

        @Override
        public String describe() {
            return "installed by its MSI in " + root;
        }
    }

    /** Studio's macOS bundle {@code bundle} ({@code /Applications/BotMaker Studio.app}), replaced from the dmg. */
    record AppBundle(Path bundle) implements StudioInstall {
        @Override
        public String assetSuffix() {
            return "-macos-arm64.dmg";
        }

        @Override
        public String describe() {
            return "the app " + bundle;
        }
    }

    /** A Studio this updater does not update, and why. */
    record Unmanaged(String why) implements StudioInstall {
        @Override
        public String assetSuffix() {
            return "";
        }

        @Override
        public String describe() {
            return "not updated from here: " + why;
        }
    }
}
