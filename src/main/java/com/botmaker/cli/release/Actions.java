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
     * @param url     the one run worth opening for this tag, or empty when none was seen — see
     *                {@link #bestRun}. It is what the dashboard's <b>Actions ↗</b> chip goes to, and what
     *                the log records beside the verdict so a release read months later still reaches it.
     */
    public record Poll(String verdict, String error, String url) {

        /** The two-argument shape from before a run had a URL, for a verdict that names no run. */
        public Poll(String verdict, String error) {
            this(verdict, error, "");
        }
    }

    private Actions() {
    }

    /** How long a tag is given to grow a run before {@code no run on <tag>} is believed, and how often it is asked. */
    static final java.time.Duration APPEAR_WINDOW = java.time.Duration.ofSeconds(60);
    static final java.time.Duration APPEAR_INTERVAL = java.time.Duration.ofSeconds(5);

    /** What the loop waits with, so a test drives the window without spending it. */
    @FunctionalInterface
    interface Waiter {
        void await(java.time.Duration duration);
    }

    private static void sleep(java.time.Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static Poll poll(Module module, Version version) {
        if (!Proc.onPath("gh")) {
            return new Poll("unknown (no gh on PATH)", "");
        }
        String repo = CleanRoom.OWNER + "/" + module.directory();
        return poll(module, version, () -> runs(repo, version), Actions::sleep);
    }

    private static String runs(String repo, Version version) {
        Proc.Result run = Proc.run(Path.of("."), "gh", "run", "list",
                "--repo", repo,
                "--branch", version.tag(), "--limit", "20",
                "--json", "name,status,conclusion,url,databaseId",
                "--jq", ".[] | [.name, .status, .conclusion, .url, .databaseId] | @tsv");
        return run.ok() ? run.out() : "";
    }

    /**
     * One module's verdict, with the seam the grace window is tested through.
     *
     * <p><b>A run that has not appeared yet is not a run that failed.</b> The chain polls seconds after the
     * last tag is pushed, and GitHub takes a moment to register a tag-triggered run — so an empty answer
     * meant {@code no run on <tag>}, which {@code ReleaseLog.Health} reads as broken, with no error text to
     * show for it. Both botmaker-remote-server tags of 2026-09-17 read {@code FAILED} in the dashboard while
     * their workflows were starting, and both then passed. The classification is right and stays; what is
     * added is the wait that makes the sentence true. Only the <i>empty</i> answer is retried: a run that is
     * {@code in_progress} already answers {@code running (n of m)}, which is pending and needs no wait.
     */
    static Poll poll(Module module, Version version, java.util.function.Supplier<String> runs, Waiter waiter) {
        String repo = CleanRoom.OWNER + "/" + module.directory();
        String tsv = runs.get();
        for (java.time.Duration waited = java.time.Duration.ZERO;
             tsv.isBlank() && waited.compareTo(APPEAR_WINDOW) < 0;
             waited = waited.plus(APPEAR_INTERVAL)) {
            waiter.await(APPEAR_INTERVAL);
            tsv = runs.get();
        }
        Poll poll = verdict(tsv, version);
        if (poll.error().isBlank()) {
            return poll;
        }
        // A URL says where the failure is; what a reader wants from the log six weeks later is what it was.
        StringBuilder error = new StringBuilder(poll.error());
        for (String id : failedRunIds(tsv)) {
            Proc.Result log = Proc.run(Path.of("."), "gh", "run", "view", id, "--repo", repo, "--log-failed");
            String excerpt = log.ok() ? excerpt(log.out()) : "";
            if (excerpt.isBlank()) {
                // No log line said anything: a job that failed before a step ran has no log at all, and
                // what it has instead is a failure annotation. botmaker-remote-server v0.0.1's pages job:
                // "Tag "v0.0.1" is not allowed to deploy to github-pages due to environment protection
                // rules." — nowhere but there.
                excerpt = annotations(repo, id);
            }
            if (!excerpt.isBlank()) {
                error.append("\n\n").append(excerpt);
            }
        }
        return new Poll(poll.verdict(), error.toString(), poll.url());
    }

    /** The failure annotations of a run's failed jobs, {@code <job>: <message>} per line; "" when none. */
    static String annotations(String repo, String runId) {
        Proc.Result jobs = Proc.run(Path.of("."), "gh", "api",
                "repos/" + repo + "/actions/runs/" + runId + "/jobs",
                "--jq", ".jobs[] | select(.conclusion == \"failure\") | [.id, .name] | @tsv");
        if (!jobs.ok()) {
            return "";
        }
        java.util.LinkedHashSet<String> kept = new java.util.LinkedHashSet<>();
        for (String line : jobs.out().lines().filter(l -> !l.isBlank()).toList()) {
            String[] cells = line.split("\t", 2);
            String job = cells.length == 2 ? cells[1].strip() : "";
            Proc.Result notes = Proc.run(Path.of("."), "gh", "api",
                    "repos/" + repo + "/check-runs/" + cells[0].strip() + "/annotations",
                    "--jq", ".[] | select(.annotation_level == \"failure\") | .message");
            if (!notes.ok()) {
                continue;
            }
            notes.out().lines().map(String::strip).filter(m -> !m.isEmpty())
                    .forEach(m -> kept.add((job.isEmpty() ? "" : job + ": ") + m));
        }
        return kept.stream().limit(EXCERPT_LINES).collect(java.util.stream.Collectors.joining("\n"));
    }

    /** At most this many lines of a failed run's log go into the release log. */
    static final int EXCERPT_LINES = 15;

    /** Maven's advice after every failure, identical each time and never the reason. */
    private static final List<String> BOILERPLATE = List.of(
            "Please refer to", "-> [Help", "To see the full stack trace", "Re-run Maven using",
            "For more information about the errors", "[Help 1] http");

    /**
     * A thrown Java exception as a JVM prints it: a qualified class ending {@code Exception} or {@code Error},
     * optionally its message, optionally behind {@code Caused by: } (kept — it is usually the real reason) or
     * {@code Exception in thread "…" } (dropped). The stack's {@code at …} lines never match: they start with
     * {@code at}, and a class name needs a package.
     */
    private static final java.util.regex.Pattern EXCEPTION = java.util.regex.Pattern.compile(
            "(?:Exception in thread \"[^\"]*\" )?((?:Caused by: )?(?:[a-z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error)(?::.*)?)");

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
     * The lines of {@code gh run view --log-failed} worth keeping: Maven's {@code [ERROR]} lines, the
     * runner's {@code ##[error]} lines and a Node action's {@code Error:} line (the first line of the stack
     * {@code android-actions/setup-android} died with on botmaker-remote v0.0.1, and the only one that says
     * which process failed), each with the command that ran just before it.
     *
     * <p>The command matters for the second kind. {@code ##[error]The process '/usr/bin/git' failed with exit
     * code 1} is all Studio v1.1.0's package job said, and the {@code [command]} line above it is the one
     * that names the ref it could not fetch. Each line is {@code <job>\t<step>\t<timestamp> <text>}; the job
     * is kept, the step and timestamp are dropped, and a message repeated across a matrix is kept once.
     *
     * <p>Two more since 2026-09-18, both from botmaker-session v0.0.15, whose JReleaser download died on a
     * 504. A <b>Java exception line</b> is kept ({@link #EXCEPTION}): it was the only line naming the cause,
     * and none of the three prefixes matched it. And the command is <b>forgotten at each {@code ##[group]} or
     * {@code ##[start-action}</b>: the record quoted a {@code tar} an earlier action had run, as if it had
     * failed.
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
            // A new group or action starts, so a command seen before it did not cause what follows. The step
            // column cannot say this: a composite action's steps all log under the step that called it.
            if (text.startsWith("##[group]") || text.startsWith("##[start-action")) {
                lastCommand = "";
                continue;
            }
            String prefix = job.isEmpty() ? "" : job + ": ";
            boolean maven = text.startsWith("[ERROR]");
            boolean runner = text.startsWith("##[error]");
            boolean node = text.startsWith("Error: ");
            if (!maven && !runner && !node) {
                java.util.regex.Matcher exception = EXCEPTION.matcher(text.strip());
                if (exception.matches()) {
                    kept.add(prefix + exception.group(1));
                }
                continue;
            }
            String message = text.substring(maven ? "[ERROR]".length()
                    : runner ? "##[error]".length() : "Error: ".length()).strip();
            if (message.isEmpty() || BOILERPLATE.stream().anyMatch(message::startsWith)) {
                continue;
            }
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
        String best = bestRun(tsv);
        if (total == 0) {
            // NOT the same as a pull request with no check run yet: a tag is finished, so nothing more will
            // fire and this is a finding rather than a state on the way to one.
            return new Poll("no run on " + version.tag(), "");
        }
        if (!failed.isEmpty()) {
            return new Poll("FAILED — " + String.join(", ", failed), String.join("\n", errors), best);
        }
        if (running > 0) {
            return new Poll("running (" + running + " of " + total + ")", "", best);
        }
        return new Poll("success (" + total + ")", "", best);
    }

    /**
     * The one run of this tag worth a click, out of the several a tag can fire.
     *
     * <p>In order: a run that <b>failed</b>, because that is the one an operator is opening the page to
     * read; then one still <b>running</b>, because that is the one whose answer is not in yet; then simply
     * the first, which {@code gh run list} returns newest first. A tag with no run at all has no URL, and
     * the caller falls back to the repository's filtered run list.
     */
    static String bestRun(String tsv) {
        String running = "";
        String latest = "";
        for (String line : tsv.lines().filter(l -> !l.isBlank()).toList()) {
            String[] cells = line.split("\t", -1);
            if (cells.length < 4 || cells[0].strip().isEmpty()) {
                continue;
            }
            String status = cells[1].strip();
            String conclusion = cells[2].strip();
            String url = cells[3].strip();
            if (url.isEmpty()) {
                continue;
            }
            if ("completed".equals(status) && !"success".equals(conclusion) && !"skipped".equals(conclusion)) {
                return url;
            }
            if (!"completed".equals(status) && running.isEmpty()) {
                running = url;
            }
            if (latest.isEmpty()) {
                latest = url;
            }
        }
        return running.isEmpty() ? latest : running;
    }
}
