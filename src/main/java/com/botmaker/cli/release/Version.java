package com.botmaker.cli.release;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An {@code x.y.z} version, and the bump arithmetic {@code release.sh} does in {@code bump}.
 *
 * <p><b>Three numbers and nothing else.</b> The script's own filter is
 * {@code grep -E '^[0-9]+\.[0-9]+\.[0-9]+$'} after stripping a leading {@code v}, so pre-release
 * suffixes, build metadata and every other part of the semver grammar are not a narrower parse here — they
 * are tags this project does not cut, and a parser that accepted them would order them differently from the
 * {@code sort -V} the script uses.
 *
 * <p>{@link #compareTo} is where the port could go wrong invisibly: {@code sort -V} orders
 * {@code 1.9.0} before {@code 1.10.0}, and any comparison that treats the version as text gets that
 * backwards. It is the reason this is three {@code int}s rather than a string.
 */
public record Version(int major, int minor, int patch) implements Comparable<Version> {

    private static final Pattern SHAPE = Pattern.compile("^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)$");

    /** The version a module with no tag at all is bumped from — the script's {@code cur="0.0.0"}. */
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

    /** The script's {@code bump}: major zeroes the two below it, minor zeroes the patch. */
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
