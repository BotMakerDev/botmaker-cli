// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.catalog;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * An operating system a bot says it runs on (2026-11-21, spec {@code 2026-11-21-catalog-trust-design.md} § 2): the
 * pom property {@link #PROPERTY}, a comma list of {@link #id}s, listed per release in {@code catalog.json}. The ids
 * are shared's {@code Os} ids, which is how the core asks whether a bot runs on this computer; this module does not
 * depend on shared.
 */
public enum Platform {

    LINUX("linux", "Linux"),
    WINDOWS("windows", "Windows"),
    MACOS("macos", "macOS"),
    /** An id this BotMaker does not know: read, never written. */
    UNKNOWN("unknown", "an unknown system");

    /** The bot pom's property listing its platforms; missing means every {@link #known} one. */
    public static final String PROPERTY = "botmaker.platforms";

    private static final List<Platform> KNOWN = List.of(LINUX, WINDOWS, MACOS);

    private final String id;
    private final String displayName;

    Platform(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** Every system a bot may name, in the order a list of them is written. */
    public static List<Platform> known() {
        return KNOWN;
    }

    @JsonValue
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    @JsonCreator
    public static Platform fromId(String id) {
        return Arrays.stream(values()).filter(p -> p.id.equals(id)).findFirst().orElse(UNKNOWN);
    }

    /**
     * The known systems {@code value} (the property's text) names, in {@link #known} order, each once; every known one
     * when it is null or blank.
     */
    public static List<Platform> parse(String value) {
        if (value == null || value.isBlank()) return KNOWN;
        List<String> ids = ids(value);
        return KNOWN.stream().filter(p -> ids.contains(p.id)).toList();
    }

    /** {@code platforms} as the property's text: known ones only, in {@link #known} order, comma separated. */
    public static String write(List<Platform> platforms) {
        return String.join(",", KNOWN.stream().filter(platforms::contains).map(Platform::id).toList());
    }

    /** The ids in {@code value} that name no known system, as written. */
    public static List<String> unknownIds(String value) {
        if (value == null || value.isBlank()) return List.of();
        return ids(value).stream().filter(id -> fromId(id) == UNKNOWN).distinct().toList();
    }

    private static List<String> ids(String value) {
        List<String> ids = new ArrayList<>();
        for (String part : value.split(",")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }
}
