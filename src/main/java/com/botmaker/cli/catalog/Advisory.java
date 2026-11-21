// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.catalog;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A known problem in some releases of one entry (spec 2026-11-21 § 5): {@code advisories/<id>.json}, filed by an
 * admin, never deleted, only withdrawn. Studio refuses to load or run an affected release unless its user ticks
 * <i>Run anyway</i> for it. An advisory says a problem is known, never that any other release is safe.
 *
 * @param id        {@code BMA-<year>-<4 digits>}, in sequence per year, the file's name
 * @param repo      the entry's {@code owner/name}, or one it had {@code formerly}
 * @param releases  the affected tags, each an approved release of the entry; a change only appends
 * @param url       where the problem is described, {@code null} for none
 * @param published the day it was filed, {@code YYYY-MM-DD}
 * @param withdrawn why it no longer holds, {@code null} while it does
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "repo", "releases", "severity", "summary", "url", "published", "withdrawn"})
public record Advisory(String id, String repo, List<String> releases, Severity severity, String summary, String url,
                       String published, String withdrawn) {

    /** The folder advisories live in, at the catalog's root. */
    public static final String DIRECTORY = "advisories/";

    /** An advisory's id: the year it was filed and its number in that year. */
    public static final Pattern ID = Pattern.compile("BMA-(\\d{4})-(\\d{4})");

    /** The longest summary or withdrawal reason, shown whole in Studio's refusal. */
    public static final int MAX_TEXT = 300;

    /** How bad the problem is: a closed set, {@link #UNKNOWN} for a word a newer catalog may use. */
    public enum Severity {
        LOW("low", "Low"),
        MEDIUM("medium", "Medium"),
        HIGH("high", "High"),
        CRITICAL("critical", "Critical"),
        UNKNOWN("unknown", "Unknown");

        private final String id;
        private final String displayName;

        Severity(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        @JsonValue
        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        @JsonCreator
        public static Severity fromId(String id) {
            if (id != null) {
                String wanted = id.strip().toLowerCase(Locale.ROOT);
                for (Severity severity : values()) {
                    if (severity.id.equals(wanted)) return severity;
                }
            }
            return UNKNOWN;
        }
    }

    public Advisory {
        releases = releases == null ? List.of() : List.copyOf(releases);
        severity = severity == null ? Severity.UNKNOWN : severity;
    }

    /** The path advisory {@code id} lives at. */
    public static String path(String id) {
        return DIRECTORY + id + ".json";
    }

    /** The id {@code path} names, when it is an advisory's path. */
    public static Optional<String> idOf(String path) {
        if (path == null || !path.startsWith(DIRECTORY) || !path.endsWith(".json")) return Optional.empty();
        String id = path.substring(DIRECTORY.length(), path.length() - ".json".length());
        return ID.matcher(id).matches() ? Optional.of(id) : Optional.empty();
    }

    /** Why its own fields break the schema's rules, or {@code null}; what the catalog's check and the cli both ask. */
    public String problem() {
        if (!ID.matcher(String.valueOf(id)).matches()) return "\"id\" is BMA-<year>-<4 digits>; " + id + " is not.";
        if (!Entry.isSlug(repo)) return "\"repo\" is owner/name on GitHub; " + repo + " is not.";
        if (releases.isEmpty()) return "an advisory names at least one release.";
        if (new HashSet<>(releases).size() != releases.size()) return "an advisory names each release once.";
        for (String tag : releases) {
            if (tag == null || !EntryGate.TAG.matcher(tag).matches()) return "\"" + tag + "\" is not a release tag.";
        }
        if (severity == Severity.UNKNOWN) return "\"severity\" is one of low, medium, high, critical.";
        if (!fits(summary)) return "the summary is 1 to " + MAX_TEXT + " characters.";
        if (url != null && !url.matches("https://[^\\s\"<>]{1,500}")) return "\"url\" is an https link, or left out.";
        try {
            LocalDate.parse(String.valueOf(published));
        } catch (DateTimeParseException e) {
            return "\"published\" is the day it was filed, YYYY-MM-DD.";
        }
        if (withdrawn != null && !fits(withdrawn)) {
            return "\"withdrawn\" says why, in 1 to " + MAX_TEXT + " characters.";
        }
        return null;
    }

    private static boolean fits(String text) {
        return text != null && !text.isBlank() && text.strip().length() <= MAX_TEXT;
    }

    /** Whether it still holds. */
    public boolean active() {
        return withdrawn == null;
    }

    /** Whether it names {@code tag} of the entry at {@code slug}, ignoring case in the slug as GitHub does. */
    public boolean affects(String slug, String tag) {
        return active() && repo != null && repo.equalsIgnoreCase(slug) && releases.contains(tag);
    }

    /** This advisory withdrawn for {@code why}. */
    public Advisory withdraw(String why) {
        return new Advisory(id, repo, releases, severity, summary, url, published, why);
    }

    /** This advisory naming {@code more} after the releases it names. */
    public Advisory extend(List<String> more) {
        List<String> all = new ArrayList<>(releases);
        more.stream().filter(t -> !all.contains(t)).forEach(all::add);
        return new Advisory(id, repo, all, severity, summary, url, published, withdrawn);
    }

    /** The id after {@code ids} for {@code year}: {@code BMA-<year>-0001} when none of that year is filed. */
    public static String next(int year, List<String> ids) {
        int last = ids.stream().map(ID::matcher).filter(Matcher::matches)
                .filter(m -> Integer.parseInt(m.group(1)) == year).mapToInt(m -> Integer.parseInt(m.group(2)))
                .max().orElse(0);
        return String.format(Locale.ROOT, "BMA-%04d-%04d", year, last + 1);
    }

    public String toJson() {
        try {
            return CatalogJson.strict().writeValueAsString(this) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the advisory could not be written", e);
        }
    }

    /** The advisory in {@code file}, read strictly. */
    public static Advisory read(Path file) throws IOException {
        return CatalogJson.strict().readValue(Files.readString(file), Advisory.class);
    }

    /**
     * Every advisory under {@code root}'s {@link #DIRECTORY} whose file is a plain file named after its id, by id;
     * any other file is skipped and said.
     */
    public static List<Advisory> readAll(Path root) throws IOException {
        Path dir = root.resolve(DIRECTORY);
        if (!Files.isDirectory(dir)) return List.of();
        List<Advisory> advisories = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String path = DIRECTORY + file.getFileName();
                Optional<String> id = idOf(path);
                if (id.isEmpty() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    System.err.println("skipped " + path + ": not an advisory file");
                    continue;
                }
                try {
                    Advisory advisory = read(file);
                    if (id.get().equals(advisory.id())) advisories.add(advisory);
                    else System.err.println("skipped " + path + ": its name is not its id");
                } catch (IOException | IllegalArgumentException e) {
                    System.err.println("skipped " + path + ": not an advisory of this schema");
                }
            }
        }
        advisories.sort(Comparator.comparing(Advisory::id));
        return advisories;
    }
}
