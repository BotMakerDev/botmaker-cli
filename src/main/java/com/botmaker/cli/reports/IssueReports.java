package com.botmaker.cli.reports;

import com.botmaker.runlog.IssueComment;
import com.botmaker.runlog.ReportTarget;
import com.botmaker.runlog.RunLogs;
import com.botmaker.runlog.api.Dimension;
import com.botmaker.runlog.api.Level;
import com.botmaker.runlog.api.Occurrence;
import com.botmaker.runlog.api.Report;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A bot's reports as its GitHub issues hold them (run reports spec §4): every issue, open or closed, with its
 * comments, read by {@link RunLogs#readIssue} whatever format wrote it, and cached as occurrences in
 * {@code <cache>/<owner>/<repo>/}: one {@code issues/<number>.jsonl} per report, and {@code state.json} with the
 * newest {@code updated_at} read. A read asks GitHub only for what changed since; a cache deleted, or of another
 * shape, is rebuilt from GitHub. GitHub unreachable: the cache answers, and the notes say so.
 *
 * <p>Prints nothing; GitHub is read through the host's {@link GitHubGet}.
 */
public final class IssueReports {

    /** The cache's own shape; another one is rebuilt. */
    static final int CACHE = 1;
    private static final int PAGE = 100;
    /** 100 pages of 100: a repository with more issues than that is read in its first ten thousand. */
    private static final int MOST_PAGES = 100;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final GitHubGet github;
    private final Path cache;

    /** Reads through {@code github}, caching under {@code cache} ({@link #defaultCache()}). */
    public IssueReports(GitHubGet github, Path cache) {
        this.github = github;
        this.cache = cache;
    }

    /** {@code $XDG_CACHE_HOME/botmaker/reports}, else {@code ~/.cache/botmaker/reports}. */
    public static Path defaultCache() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("botmaker").resolve("reports");
    }

    /** {@code target}'s reports: refreshed from GitHub, then read from the cache. */
    public Reports read(ReportTarget target) {
        Path dir = cache.resolve(target.owner()).resolve(target.repo());
        List<String> notes = new ArrayList<>();
        try {
            refresh(target, dir);
        } catch (IOException | RuntimeException e) {
            notes.add("GitHub could not be read (" + e.getMessage() + "); these are the reports cached before.");
        }
        return cached(target, dir, notes);
    }

    private void refresh(ReportTarget target, Path dir) throws IOException {
        Path issues = dir.resolve("issues");
        Path state = dir.resolve("state.json");
        JsonNode was = readJson(state);
        String since = was != null && was.path("cache").asInt() == CACHE && Files.isDirectory(issues)
                ? was.path("updated").asText(null) : null;
        if (since == null) clear(issues);
        Files.createDirectories(issues);
        String newest = since;
        for (int page = 1; page <= MOST_PAGES; page++) {
            JsonNode items = JSON.readTree(github.get("repos/" + target + "/issues?state=all&sort=updated"
                    + "&direction=asc&per_page=" + PAGE + "&page=" + page
                    + (since == null ? "" : "&since=" + URLEncoder.encode(since, StandardCharsets.UTF_8))));
            if (items == null || !items.isArray()) throw new IOException("GitHub's issue list was not a list");
            for (JsonNode item : items) {
                if (item.has("pull_request")) continue;
                store(target, issues, item);
                String updated = item.path("updated_at").asText("");
                if (newest == null || updated.compareTo(newest) > 0) newest = updated;
            }
            if (items.size() < PAGE) break;
        }
        ObjectNode now = JSON.createObjectNode().put("cache", CACHE);
        if (newest != null) now.put("updated", newest);
        write(state, now.toString());
    }

    /** {@code item}'s occurrences in its file, when it is a report; its file gone when it no longer is one. */
    private void store(ReportTarget target, Path issues, JsonNode item) throws IOException {
        int number = item.path("number").asInt(-1);
        if (number < 1) return;
        Path file = issues.resolve(number + ".jsonl");
        String body = item.path("body").asText("");
        Instant created = instant(item.path("created_at"));
        if (!(RunLogs.readIssue(body, created, List.of()) instanceof RunLogs.ReadableIssue)) {
            Files.deleteIfExists(file);
            return;
        }
        List<IssueComment> comments = item.path("comments").asInt(0) > 0 ? comments(target, number) : List.of();
        if (!(RunLogs.readIssue(body, created, comments) instanceof RunLogs.ReadableIssue read)) {
            Files.deleteIfExists(file);
            return;
        }
        StringBuilder out = new StringBuilder();
        ObjectNode head = JSON.createObjectNode().put("number", number)
                .put("open", "open".equals(item.path("state").asText()))
                .put("title", item.path("title").asText(""))
                .put("fingerprint", read.fingerprint());
        var dims = head.putArray("dimensions");
        read.report().dimensions().forEach(d -> dims.addObject().put("id", d.id()).put("label", d.label()));
        out.append(head).append('\n');
        for (Occurrence o : read.report().occurrences()) {
            ObjectNode line = JSON.createObjectNode().put("time", o.time().toString()).put("level", o.level().id())
                    .put("fingerprint", o.fingerprint()).put("count", o.count());
            ObjectNode values = line.putObject("values");
            o.values().forEach(values::put);
            out.append(line).append('\n');
        }
        write(file, out.toString());
    }

    private List<IssueComment> comments(ReportTarget target, int number) throws IOException {
        List<IssueComment> all = new ArrayList<>();
        for (int page = 1; page <= MOST_PAGES; page++) {
            JsonNode items = JSON.readTree(github.get("repos/" + target + "/issues/" + number
                    + "/comments?per_page=" + PAGE + "&page=" + page));
            if (items == null || !items.isArray()) throw new IOException("GitHub's comment list was not a list");
            items.forEach(c -> all.add(new IssueComment(c.path("body").asText(""), instant(c.path("created_at")))));
            if (items.size() < PAGE) break;
        }
        return all;
    }

    private Reports cached(ReportTarget target, Path dir, List<String> notes) {
        Report report = Report.EMPTY;
        Map<String, List<Reports.Issue>> issues = new LinkedHashMap<>();
        List<Path> files;
        try (Stream<Path> list = Files.list(dir.resolve("issues"))) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparingInt(IssueReports::number)).toList();
        } catch (IOException e) {
            files = List.of();
        }
        for (Path file : files) {
            try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonNode head = JSON.readTree(in.readLine());
                List<Dimension> dims = new ArrayList<>();
                head.path("dimensions").forEach(d -> dims.add(new Dimension(d.path("id").asText(),
                        d.path("label").asText())));
                List<Occurrence> found = new ArrayList<>();
                for (String text = in.readLine(); text != null; text = in.readLine()) {
                    JsonNode o = JSON.readTree(text);
                    Map<String, String> values = new LinkedHashMap<>();
                    o.path("values").properties().forEach(e -> values.put(e.getKey(), e.getValue().asText()));
                    found.add(new Occurrence(Instant.parse(o.path("time").asText()),
                            Level.fromId(o.path("level").asText()), o.path("fingerprint").asText(),
                            o.path("count").asInt(1), values));
                }
                report = report.plus(new Report(dims, found));
                issues.computeIfAbsent(head.path("fingerprint").asText(), k -> new ArrayList<>())
                        .add(new Reports.Issue(head.path("number").asInt(), head.path("open").asBoolean(),
                                head.path("title").asText("")));
            } catch (IOException | RuntimeException e) {
                // A cache file that does not read: the next read rebuilds the whole cache from GitHub.
                notes.add("The cached " + file.getFileName() + " did not read; the next read rebuilds the cache.");
                try {
                    Files.deleteIfExists(dir.resolve("state.json"));
                } catch (IOException ignored) {
                    // the note stands
                }
            }
        }
        return new Reports(target.toString(), report, issues, notes);
    }

    private static int number(Path file) {
        String name = file.getFileName().toString();
        try {
            return Integer.parseInt(name.substring(0, name.length() - ".jsonl".length()));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static Instant instant(JsonNode node) {
        try {
            return Instant.parse(node.asText(""));
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    private static JsonNode readJson(Path file) {
        try {
            return Files.isRegularFile(file) ? JSON.readTree(file.toFile()) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void clear(Path issues) throws IOException {
        if (!Files.isDirectory(issues)) return;
        try (Stream<Path> list = Files.list(issues)) {
            for (Path p : list.toList()) Files.deleteIfExists(p);
        }
    }

    /**
     * Written whole or not at all: through a {@code .part} file of its own and a move, so the CLI and Studio
     * refreshing one cache at once never mix their writes.
     */
    private static void write(Path file, String text) throws IOException {
        Path part = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".part");
        try {
            Files.writeString(part, text, StandardCharsets.UTF_8);
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(part);
        }
    }
}
