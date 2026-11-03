package com.botmaker.cli.reports;

import com.botmaker.runlog.IssueText;
import com.botmaker.runlog.Problem;
import com.botmaker.runlog.ReportTarget;
import com.botmaker.runlog.RunFacts;
import com.botmaker.runlog.Scrubber;
import com.botmaker.runlog.api.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A bot's issues read once, cached, refreshed by what changed, rebuilt when the cache is gone. */
class IssueReportsTest {

    @TempDir
    Path cache;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ReportTarget BOT = new ReportTarget("author", "bot");
    private static final Scrubber SCRUB = Scrubber.of(null, null, List.of());
    private static final Problem PROBLEM = new Problem("com.example.Collect:3|x.Screens.goTo|warn", Level.WARN,
            "com.example.Collect", 3, "x.Screens", "goTo", "Home", "stuck", 2, 0, 0, List.of(), 0);

    private static RunFacts facts(String tag) {
        return new RunFacts("com.example:bot", tag, "abc", false, Map.of(), "Linux", 0, Map.of("Flow", "Daily"));
    }

    /** GitHub as a map of paths; any other path is an empty list, {@code down} fails every call. */
    private static final class FakeGitHub implements GitHubGet {
        final Map<String, String> answers = new HashMap<>();
        final List<String> asked = new ArrayList<>();
        boolean down;

        @Override
        public String get(String path) throws IOException {
            asked.add(path);
            if (down) throw new IOException("GitHub could not be reached");
            return answers.entrySet().stream().filter(e -> path.startsWith(e.getKey())).map(Map.Entry::getValue)
                    .findFirst().orElse("[]");
        }
    }

    private static String issues() {
        ArrayNode list = JSON.createArrayNode();
        list.addObject().put("number", 1).put("state", "open").put("title", IssueText.title(PROBLEM, SCRUB))
                .put("body", IssueText.body(PROBLEM, facts("v1"), SCRUB)).put("comments", 1)
                .put("created_at", "2026-11-01T10:00:00Z").put("updated_at", "2026-11-02T10:00:00Z");
        list.addObject().put("number", 2).put("state", "open").put("title", "Feature request")
                .put("body", "Please add a flow.").put("comments", 0)
                .put("created_at", "2026-11-01T11:00:00Z").put("updated_at", "2026-11-01T11:00:00Z");
        list.addObject().put("number", 3).put("state", "open").put("title", "A pull request").put("body", "")
                .put("created_at", "2026-11-01T12:00:00Z").put("updated_at", "2026-11-03T12:00:00Z")
                .putObject("pull_request");
        return list.toString();
    }

    private static String comments() {
        ArrayNode list = JSON.createArrayNode();
        list.addObject().put("body", IssueText.comment(PROBLEM, facts("v2"), SCRUB))
                .put("created_at", "2026-11-02T10:00:00Z");
        list.addObject().put("body", "Same here!").put("created_at", "2026-11-02T11:00:00Z");
        return list.toString();
    }

    private static FakeGitHub github() {
        FakeGitHub github = new FakeGitHub();
        github.answers.put("repos/author/bot/issues?", issues());
        github.answers.put("repos/author/bot/issues/1/comments", comments());
        return github;
    }

    @Test
    void readsReportIssuesAndTheirCommentsCachesThemAndRefreshesWhatChanged() {
        FakeGitHub github = github();
        Reports first = new IssueReports(github, cache).read(BOT);
        assertEquals(2, first.report().occurrences().size(), "the issue and its BotMaker comment, nothing else");
        assertEquals(4, first.report().occurrences().stream().mapToInt(o -> o.count()).sum());
        assertEquals(List.of(new Reports.Issue(1, true, IssueText.title(PROBLEM, SCRUB))),
                first.issues().get(PROBLEM.fingerprint()));
        assertEquals(List.of(), first.notes());
        assertTrue(github.asked.stream().noneMatch(p -> p.contains("/issues/2/")), "no comments of a non-report");

        github.asked.clear();
        github.answers.put("repos/author/bot/issues?", "[]");
        Reports again = new IssueReports(github, cache).read(BOT);
        assertTrue(github.asked.getFirst().contains("&since=2026-11-02T10%3A00%3A00Z"), github.asked.getFirst());
        assertEquals(first.report().occurrences(), again.report().occurrences(), "the cache answers");
    }

    @Test
    void aDeletedCacheIsRebuiltAndAnUnreachableGitHubLeavesTheCache() throws IOException {
        FakeGitHub github = github();
        new IssueReports(github, cache).read(BOT);
        try (var files = Files.walk(cache)) {
            for (Path p : files.sorted((a, b) -> b.compareTo(a)).toList()) {
                if (!p.equals(cache)) Files.delete(p);
            }
        }
        github.asked.clear();
        Reports rebuilt = new IssueReports(github, cache).read(BOT);
        assertTrue(!github.asked.getFirst().contains("since="), github.asked.getFirst());
        assertEquals(2, rebuilt.report().occurrences().size());

        github.down = true;
        Reports offline = new IssueReports(github, cache).read(BOT);
        assertEquals(rebuilt.report().occurrences(), offline.report().occurrences());
        assertTrue(offline.notes().getFirst().startsWith("GitHub could not be read"), offline.notes().toString());
    }

    @Test
    void theJsonAnswerIsTheGoldenOne() throws IOException {
        Reports reports = new IssueReports(github(), cache).read(BOT);
        String json = ReportsText.json(reports, "details.Flow").replace("\r\n", "\n");
        try (InputStream in = IssueReportsTest.class.getResourceAsStream("reports.json")) {
            assertEquals(new String(in.readAllBytes(), StandardCharsets.UTF_8), json);
        }
        assertTrue(ReportsText.text(reports, null).contains("#1 open"));
        assertEquals(0, reports.filtered(Level.ERROR, null).report().occurrences().size());
    }
}
