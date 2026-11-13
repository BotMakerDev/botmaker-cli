package com.botmaker.cli.publish;

import java.util.Optional;

/**
 * A publish's bump level: {@code patch}, {@code minor} or {@code major}, spelled lower-case and matched exactly
 * ({@code Patch} and {@code PATCH} are refused). {@code patch} is what a publish with no level means.
 */
public enum Level {

    PATCH, MINOR, MAJOR;

    /** The level of a publish that names none. */
    public static final Level DEFAULT = PATCH;

    /** The word as the command line spells it. */
    public String spelling() {
        return name().toLowerCase();
    }

    /** The level a word names, or empty — the caller turns that into its own refusal. */
    public static Optional<Level> of(String word) {
        for (Level level : values()) {
            if (level.spelling().equals(word)) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }
}
