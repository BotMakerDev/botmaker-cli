package com.botmaker.cli.bmk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Optional;

/**
 * A {@code .bmk}'s {@code bot.json} (spec 2026-10-24 § 2): who made the bot, which commit it is, how to start it, and
 * every jar it runs with. Written in this field order, so two writes of one bot are the same bytes.
 *
 * @param format {@link #FORMAT}; a file with a higher one was made by a newer BotMaker
 * @param java   the pom's {@code maven.compiler.release}, the {@code --release} the bot is compiled with
 * @param main   the class holding {@code public static void main(String[])}
 */
@JsonPropertyOrder({"format", "name", "owner", "repo", "tag", "commit", "java", "main", "libs"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record BotJson(int format, String name, String owner, String repo, String tag, String commit, int java,
                      String main, List<Lib> libs) {

    /** The format this BotMaker writes and reads. */
    public static final int FORMAT = 1;

    public BotJson {
        libs = List.copyOf(libs);
    }

    /** Everything but the jars: what the caller knows about the bot before its classpath is resolved. */
    public record Head(String name, String owner, String repo, String tag, String commit, int java, String main) {}

    static BotJson of(Head head, List<Lib> libs) {
        return new BotJson(FORMAT, head.name(), head.owner(), head.repo(), head.tag(), head.commit(), head.java(),
                head.main(), libs);
    }

    /**
     * One jar the bot runs with.
     *
     * @param path       the jar's entry in the file, {@code lib/<artifactId>-<version>.jar}; {@code null} for a jar
     *                   from Maven Central, which is listed only and fetched on first open (the user's call,
     *                   2026-10-24)
     * @param coordinate {@code groupId:artifactId:version}, or {@code groupId:artifactId:version:classifier}
     * @param sha256     the jar's bytes, lowercase hex
     */
    @JsonPropertyOrder({"path", "coordinate", "sha256"})
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lib(@JsonInclude(JsonInclude.Include.NON_NULL) String path, String coordinate, String sha256) {

        /** The jar's entry in the file, when it is packed there. */
        public Optional<String> packed() {
            return Optional.ofNullable(path);
        }

        public String groupId() {
            return part(0);
        }

        public String artifactId() {
            return part(1);
        }

        public String version() {
            return part(2);
        }

        /** The classifier, when the coordinate has one. */
        public Optional<String> classifier() {
            String[] parts = coordinate.split(":");
            return parts.length > 3 ? Optional.of(parts[3]) : Optional.empty();
        }

        private String part(int i) {
            String[] parts = coordinate.split(":");
            return parts.length > i ? parts[i] : "";
        }
    }
}
