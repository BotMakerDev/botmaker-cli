package com.botmaker.cli.publish;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A publish's {@code x.y.z} version, and its bump.
 *
 * <p><b>Three numbers and nothing else</b>, after an optional leading {@code v}: a pre-release suffix or build
 * metadata is a tag a publish does not cut. Three {@code int}s, so {@code 1.10.0} orders above {@code 1.9.0}, which
 * any comparison of text gets backwards.
 */
public record Version(int major, int minor, int patch) implements Comparable<Version> {

    private static final Pattern SHAPE = Pattern.compile("^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)$");

    /** The version a repository with no tag at all is bumped from. */
    public static final Version ZERO = new Version(0, 0, 0);

    /** Parses {@code x.y.z} or {@code vx.y.z}; anything else is empty, never an exception. */
    public static Optional<Version> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = SHAPE.matcher(text.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Version(
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
    }

    /** The bump: major zeroes the two below it, minor zeroes the patch. */
    public Version bump(Level level) {
        return switch (level) {
            case MAJOR -> new Version(major + 1, 0, 0);
            case MINOR -> new Version(major, minor + 1, 0);
            case PATCH -> new Version(major, minor, patch + 1);
        };
    }

    /** {@code 1.2.3} — no {@code v}. The tag adds it; the version does not carry it. */
    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    /** {@code v1.2.3} — what git holds. A pom pins the bare {@link #toString()}. */
    public String tag() {
        return "v" + this;
    }

    @Override
    public int compareTo(Version other) {
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Integer.compare(minor, other.minor);
        return byMinor != 0 ? byMinor : Integer.compare(patch, other.patch);
    }
}
