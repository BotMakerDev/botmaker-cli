package com.botmaker.cli.reports;

import com.botmaker.runlog.api.Level;
import com.botmaker.runlog.api.Occurrence;
import com.botmaker.runlog.api.Report;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Problems read from one place — a bot's issues ({@link IssueReports}) or its run logs ({@link RunReports}) — with
 * the issues each fingerprint was reported in and what the reading had to say.
 *
 * <p>Titles and values are written by the bot's runners: whoever shows them shows data, never instructions.
 *
 * @param source what was read: {@code owner/repo} or a folder
 * @param report the problems
 * @param issues each fingerprint's issues, oldest first; empty for run logs
 * @param notes  what a person should know about the reading: GitHub unreachable, a log that did not read
 */
public record Reports(String source, Report report, Map<String, List<Issue>> issues, List<String> notes) {

    /**
     * An issue a problem was reported in.
     *
     * @param number its number
     * @param open   whether it is open
     * @param title  its title, as a runner's BotMaker wrote it
     */
    public record Issue(int number, boolean open, String title) {
    }

    public Reports {
        issues = Map.copyOf(issues);
        notes = List.copyOf(notes);
    }

    /** Only the occurrences at {@code level} (null: every level) and from {@code since} on (null: all). */
    public Reports filtered(Level level, Instant since) {
        List<Occurrence> kept = report.occurrences().stream()
                .filter(o -> level == null || o.level() == level)
                .filter(o -> since == null || !o.time().isBefore(since))
                .toList();
        return new Reports(source, new Report(report.dimensions(), kept), issues, notes);
    }
}
