package com.botmaker.cli.release;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What GitHub Actions made of a tag — {@code release.sh}'s {@code poll_actions}.
 *
 * <p><b>Each module publishes its own GitHub Release from its own {@code ci.yml}, on the tag</b>, and until
 * 2026-09-04 nothing here ever looked at whether that job passed. A tag can be pushed and JitPack perfectly
 * green while the release notes simply do not exist, because the workflow died on a missing secret.
 *
 * <p><b>{@code --branch <tag>} is not a mistake.</b> A tag-triggered run records the tag in
 * {@code headBranch}, and it is the only filter {@code gh run list} offers that isolates one release's runs.
 * Several workflows can fire on one tag — Studio's package matrix, its pages deploy and its JReleaser step
 * are three — so <b>the verdict is the worst of them</b> and the failures are named.
 */
public final class Actions {

    /**
     * @param verdict the cell for the log
     * @param error   the failing runs and their URLs, empty when nothing failed
     */
    public record Poll(String verdict, String error) {
    }

    private Actions() {
    }

    public static Poll poll(Module module, Version version) {
        if (!Proc.onPath("gh")) {
            return new Poll("unknown (no gh on PATH)", "");
        }
        String repo = CleanRoom.OWNER + "/" + module.directory();
        Proc.Result run = Proc.run(Path.of("."), "gh", "run", "list",
                "--repo", repo,
                "--branch", version.tag(), "--limit", "20",
                "--json", "name,status,conclusion,url,databaseId",
                "--jq", ".[] | [.name, .status, .conclusion, .url, .databaseId] | @tsv");
        String tsv = run.ok() ? run.out() : "";
        Poll poll = verdict(tsv, version);
        if (poll.error().isBlank()) {
            return poll;
        }
        // A URL says where the failure is; what a reader wants from the log six weeks later is what it was.
        StringBuilder error = new StringBuilder(poll.error());
        for (String id : failedRunIds(tsv)) {
            Proc.Result log = Proc.run(Path.of("."), "gh", "run", "view", id, "--repo", repo, "--log-failed");
            String excerpt = log.ok() ? excerpt(log.out()) : "";
            if (!excerpt.isBlank()) {
                error.append("\n\n").append(excerpt);
            }
        }
        return new Poll(poll.verdict(), error.toString());
    }

    /** At most this many lines of a failed run's log go into the release log. */
    static final int EXCERPT_LINES = 15;

    /** Maven's advice after every failure, identical each time and never the reason. */
    private static final List<String> BOILERPLATE = List.of(
            "Please refer to", "-> [Help", "To see the full stack trace", "Re-run Maven using",
            "For more information about the errors", "[Help 1] http");

    /** The ids of the completed runs that did not succeed, from the fifth column {@link #poll} asks for. */
    static List<String> failedRunIds(String tsv) {
        List<String> ids = new ArrayList<>();
        for (String line : tsv.lines().filter(l -> !l.isBlank()).toList()) {
            String[] cells = line.split("\t", -1);
            if (cells.length > 4 && "completed".equals(cells[1].strip())
                    && !"success".equals(cells[2].strip()) && !"skipped".equals(cells[2].strip())) {
                ids.add(cells[4].strip());
            }
        }
        return ids;
    }

    /**
     * The lines of {@code gh run view --log-failed} worth keeping: Maven's {@code [ERROR]} lines and the
     * runner's {@code ##[error]} lines, each with the command that ran just before it.
     *
     * <p>The command matters for the second kind. {@code ##[error]The process '/usr/bin/git' failed with exit
     * code 1} is all Studio v1.1.0's package job said, and the {@code [command]} line above it is the one
     * that names the ref it could not fetch. Each line is {@code <job>\t<step>\t<timestamp> <text>}; the job
     * is kept, the step and timestamp are dropped, and a message repeated across a matrix is kept once.
     */
    static String excerpt(String log) {
        java.util.LinkedHashSet<String> kept = new java.util.LinkedHashSet<>();
        String lastCommand = "";
        for (String line : log.lines().toList()) {
            String[] cells = line.split("\t", 3);
            String job = cells.length == 3 ? cells[0].strip() : "";
            String text = cells.length == 3 ? cells[2] : line;
            // "﻿2026-09-16T10:25:31.6610499Z message" — a BOM on the first line, then an ISO instant.
            text = text.replaceFirst("^﻿?\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z ?", "");
            if (text.startsWith("[command]")) {
                lastCommand = text.substring("[command]".length());
                continue;
            }
            boolean maven = text.startsWith("[ERROR]");
            boolean runner = text.startsWith("##[error]");
            if (!maven && !runner) {
                continue;
            }
            String message = text.substring(maven ? "[ERROR]".length() : "##[error]".length()).strip();
            if (message.isEmpty() || BOILERPLATE.stream().anyMatch(message::startsWith)) {
                continue;
            }
            String prefix = job.isEmpty() ? "" : job + ": ";
            if (runner && !lastCommand.isBlank()) {
                kept.add(prefix + "$ " + lastCommand);
            }
            kept.add(prefix + message);
        }
        return kept.stream().limit(EXCERPT_LINES).collect(java.util.stream.Collectors.joining("\n"));
    }

    /** The rule over {@code gh}'s tab-separated output — pure, so every verdict is testable offline. */
    static Poll verdict(String tsv, Version version) {
        List<String> failed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int running = 0;
        int total = 0;
        for (String line : tsv.lines().filter(l -> !l.isBlank()).toList()) {
            String[] cells = line.split("\t", -1);
            String name = cells[0].strip();
            if (name.isEmpty()) {
                continue;
            }
            total++;
            String status = cells.length > 1 ? cells[1].strip() : "";
            if (!"completed".equals(status)) {
                running++;
                continue;
            }
            String conclusion = cells.length > 2 ? cells[2].strip() : "";
            String url = cells.length > 3 ? cells[3].strip() : "";
            // `skipped` counts as fine: a job that correctly did not apply to this tag is not a failure.
            if (!"success".equals(conclusion) && !"skipped".equals(conclusion)) {
                failed.add(name);
                errors.add(name + ": " + conclusion + " — " + url);
            }
        }
        if (total == 0) {
            // NOT the same as a pull request with no check run yet: a tag is finished, so nothing more will
            // fire and this is a finding rather than a state on the way to one.
            return new Poll("no run on " + version.tag(), "");
        }
        if (!failed.isEmpty()) {
            return new Poll("FAILED — " + String.join(", ", failed), String.join("\n", errors));
        }
        if (running > 0) {
            return new Poll("running (" + running + " of " + total + ")", "");
        }
        return new Poll("success (" + total + ")", "");
    }
}
