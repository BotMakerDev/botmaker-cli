package com.botmaker.cli.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code releases/<YYYY-MM-DD-HHMM>.md} — what a release did, committed in the umbrella beside the
 * submodule pointers. {@code release.sh}'s {@code write_release_log} / {@code render_release_log}.
 *
 * <p><b>Everything in it was already computed and already printed; what was missing was a record.</b>
 * {@code verify_jitpack} had said {@code UNVERIFIED} on three consecutive releases, each read as a slow
 * JitPack queue, while {@code botmaker-studio-api} and {@code botmaker-session} had in fact stopped
 * <i>compiling</i> on JitPack — six of eight published modules unresolvable for two days, every release
 * "successful". A terminal scrollback is not a record; a committed file is.
 *
 * <p><b>It carries GitHub Actions too, and that column is the genuinely new one.</b> Every released module
 * publishes its own GitHub Release from its own {@code ci.yml} on the tag, and nothing here had ever looked
 * at whether that job passed: a tag can be pushed and JitPack perfectly green while the notes do not exist
 * because a workflow died on a missing secret.
 *
 * <p><b>It is written before the first tag, since 2026-09-16</b>, and rewritten after each module's tag and
 * JitPack wait — the {@code stage} column says how far each row got. Until then it was written once the
 * <i>last</i> tag was pushed, which had the right reason (a log that only appears after the poll is missing
 * when the poll is interrupted) and stopped one step short of it: on 2026-09-16 a release died after four
 * tags, and there was no log at all. The four tags were out, nothing recorded them, and the Releases tab
 * showed 09-05 as the newest release.
 *
 * <p>The rendering always rewrites the whole file, so the two writers (a release, and {@code --status}) can
 * never leave a half-updated table behind.
 */
public final class ReleaseLog {

    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");
    private static final DateTimeFormatter HEADING = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * How far one module's release got.
     *
     * <p>The cell text is fixed, because {@link #read} parses it back: {@code --status} keeps a stage it
     * cannot re-derive, and the dashboard draws its stepper from it. A cell nothing recognises reads as
     * {@link #TAGGED} — every log written before this column existed was a whole release, where every row
     * was.
     */
    public enum Stage {
        /** Not reached yet — the state of every row in the log written before the first tag. */
        PENDING("pending"),
        /** Committed, tagged and pushed. For a module JitPack does not build, that is the end of it. */
        TAGGED("tagged"),
        /** Tagged, and its pom was downloadable before the next module was tagged. */
        BUILT("built on jitpack"),
        /** Tagged, and the JitPack wait gave up. The chain went on, as it always has. */
        TIMEOUT("jitpack timeout"),
        /** Something threw while releasing this module; the whole message is under {@code ## Errors}. */
        FAILED("FAILED"),
        /** The run stopped at an earlier module, so this one was never started. */
        NOT_REACHED("not reached");

        private final String cell;

        Stage(String cell) {
            this.cell = cell;
        }

        public String cell() {
            return cell;
        }

        /** Whether a tag was pushed, and so whether JitPack and Actions have anything to answer about. */
        public boolean tagged() {
            return this == TAGGED || this == BUILT || this == TIMEOUT;
        }

        static Stage fromCell(String cell) {
            for (Stage stage : values()) {
                if (stage.cell.equals(cell)) {
                    return stage;
                }
            }
            return TAGGED;
        }
    }

    /**
     * One module's line, plus whatever the release and the two pollers have filled in so far.
     *
     * @param failure {@code "<step>: <message>"} when {@link Stage#FAILED}, else empty
     * @param elapsed how long this module's own turn in the chain took, {@link #elapsed(Duration)}-formatted,
     *                or empty for a row nothing timed — every log written before 2026-09-19, and every row a
     *                run never reached
     * @param actionsUrl the run {@link Actions#bestRun} picked, or empty. It is written into the cell as a
     *                   markdown link so one click reaches the run itself months later, rather than the
     *                   repository's run list filtered by a tag
     */
    public record Row(Module module, Version version, Stage stage, String failure, String jitpack,
                      String actions, String jitpackError, String actionsError, String elapsed,
                      String actionsUrl) {

        public Row {
            actionsUrl = actionsUrl == null ? "" : actionsUrl;
        }

        /** The nine-argument shape from before a row carried the run's URL. */
        public Row(Module module, Version version, Stage stage, String failure, String jitpack,
                   String actions, String jitpackError, String actionsError, String elapsed) {
            this(module, version, stage, failure, jitpack, actions, jitpackError, actionsError, elapsed, "");
        }

        public Row(Module module, Version version) {
            this(module, version, Stage.PENDING, "", "", "", "", "", "", "");
        }

        public Row withStage(Stage next) {
            return new Row(module, version, next, failure, jitpack, actions, jitpackError, actionsError,
                    elapsed, actionsUrl);
        }

        public Row failed(String step, String message) {
            return new Row(module, version, Stage.FAILED, step + ": " + message, jitpack, actions,
                    jitpackError, actionsError, elapsed, actionsUrl);
        }

        public Row withJitpack(String verdict, String error) {
            return new Row(module, version, stage, failure, verdict, actions, error, actionsError, elapsed,
                    actionsUrl);
        }

        public Row withActions(String verdict, String error) {
            return withActions(verdict, error, actionsUrl);
        }

        /** The same, with the run the verdict is about. */
        public Row withActions(String verdict, String error, String url) {
            return new Row(module, version, stage, failure, jitpack, verdict, jitpackError, error, elapsed,
                    url);
        }

        /**
         * How long this module took, as {@link ReleaseLog#elapsed(Duration)} spells it.
         *
         * <p>Qualified, because inside a record the component accessor {@code elapsed()} hides the enclosing
         * class's static method of the same name.
         */
        public Row withElapsed(Duration took) {
            return new Row(module, version, stage, failure, jitpack, actions, jitpackError, actionsError,
                    ReleaseLog.elapsed(took), actionsUrl);
        }

        /**
         * The JitPack cell: {@code pending} until polled, {@code n/a} for the modules nobody resolves —
         * the two APKs, Studio, remote-server and the templates — and
         * {@code not tagged} for a row the run never tagged, which no poll can change.
         */
        String jitpackCell() {
            if (!jitpack.isBlank()) {
                return jitpack;
            }
            if (!onJitpack(module)) {
                return "n/a (not a Maven artifact)";
            }
            return untagged() ? "not tagged" : "pending";
        }

        /**
         * The Actions cell: the verdict, and — when the poll saw a run — the verdict as a markdown link to
         * it. A reader of the file gets a click, and every parser that knows the spelling (this one and the
         * dashboard's) reads the verdict back out of it; one that does not simply shows the link text.
         */
        String actionsCell() {
            if (!actions.isBlank()) {
                return actionsUrl.isBlank() ? actions : "[" + actions + "](" + actionsUrl + ")";
            }
            if (module.template()) {
                return "n/a (no workflows)";
            }
            return untagged() ? "not tagged" : "pending";
        }

        private boolean untagged() {
            return stage == Stage.FAILED || stage == Stage.NOT_REACHED;
        }

        /** The APKs are the modules the changelog gate exempts, and so the ones with none. */
        String changelogCell() {
            if (!module.hasChangelog()) {
                return "n/a (no CHANGELOG.md)";
            }
            // A failed row may have failed before or after its stamp; the commit is what would say, and an
            // uncommitted stamp is not one. So only a pushed tag vouches for it.
            return stage.tagged() ? "stamped" : "—";
        }
    }

    /**
     * The two durations that belong to the run rather than to a module.
     *
     * <p>Both empty is the ordinary state of a log until its release finishes, and of every log written
     * before this section existed.
     *
     * @param verifyPass the clean-room resolves and the Actions polls, which run after every tag is pushed
     * @param total      the whole run, decide pass included
     */
    public record Timing(String verifyPass, String total) {

        public static final Timing NONE = new Timing("", "");

        boolean isEmpty() {
            return verifyPass.isBlank() && total.isBlank();
        }
    }

    private ReleaseLog() {
    }

    /**
     * A duration as the log spells it — {@code 41s}, {@code 3m41s}, {@code 1h04m}.
     *
     * <p>One formatter for the cells and the summary line, so two numbers a reader compares are written the
     * same way. Seconds are dropped past an hour: at that scale they are noise, and the column is read to
     * find the module worth looking at.
     */
    public static String elapsed(Duration took) {
        long seconds = Math.max(0, took.toSeconds());
        if (seconds >= 3600) {
            return String.format("%dh%02dm", seconds / 3600, (seconds % 3600) / 60);
        }
        return seconds >= 60 ? String.format("%dm%02ds", seconds / 60, seconds % 60) : seconds + "s";
    }

    /** Whether anybody resolves this module as a Maven artifact — {@code on_jitpack}; {@link Module#onJitpack}. */
    public static boolean onJitpack(Module module) {
        return module.onJitpack();
    }

    /** The file this run writes, named for the minute it was cut. */
    public static Path path(Path umbrella, LocalDateTime when) {
        return umbrella.resolve("releases").resolve(FILE.format(when) + ".md");
    }

    /** The whole file, with whatever timings the rows carry and none of the run's own. */
    public static String render(LocalDateTime when, List<Row> rows) {
        return render(when, rows, Timing.NONE);
    }

    /**
     * The whole file. Pure: the same rows always render the same bytes, which is what makes it diffable.
     *
     * <p><b>The timings are a section under the table and not a column in it</b>, and that is not a layout
     * preference. {@code botmaker-dashboard}'s own reader takes a row of exactly six or seven cells and
     * drops anything else, so an eighth column would make every dashboard already installed draw a release
     * with no lanes at all. A section is invisible to a parser that does not know it.
     */
    public static String render(LocalDateTime when, List<Row> rows, Timing timing) {
        StringBuilder out = new StringBuilder("# Release " + HEADING.format(when) + "\n\n");
        out.append("| module | version | tag | stage | changelog | jitpack | actions |\n");
        out.append("|---|---|---|---|---|---|---|\n");
        for (Row row : rows) {
            out.append("| ").append(row.module().directory())
                    .append(" | ").append(row.version())
                    .append(" | ").append(row.version().tag())
                    .append(" | ").append(row.stage().cell())
                    .append(" | ").append(row.changelogCell())
                    .append(" | ").append(row.jitpackCell())
                    .append(" | ").append(row.actionsCell())
                    .append(" |\n");
        }
        // The errors go in full UNDER the table rather than in it: a cell holds a verdict, and what a
        // reader needs six weeks later is the message Maven or Actions actually printed.
        if (rows.stream().anyMatch(row -> !row.failure().isBlank() || !row.jitpackError().isBlank()
                || !row.actionsError().isBlank())) {
            out.append("\n## Errors\n");
            for (Row row : rows) {
                append(out, row.module(), "release", row.failure());
                append(out, row.module(), "jitpack", row.jitpackError());
                append(out, row.module(), "actions", row.actionsError());
            }
        }
        out.append(timingSection(rows, timing));
        return out.toString();
    }

    /**
     * {@code ## Timing}, or nothing at all when there is nothing to say.
     *
     * <p>The first column is spelled {@code step} rather than {@code module} so that neither this table's
     * header nor its rows can be mistaken for the release table's by a parser looking for one — the rows
     * name modules, and the two tables would otherwise be one.
     */
    private static String timingSection(List<Row> rows, Timing timing) {
        List<Row> timed = rows.stream().filter(row -> !row.elapsed().isBlank()).toList();
        if (timed.isEmpty() && timing.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n## Timing\n\n| step | elapsed |\n|---|---|\n");
        for (Row row : timed) {
            out.append("| ").append(row.module().directory())
                    .append(" | ").append(row.elapsed()).append(" |\n");
        }
        if (!timing.verifyPass().isBlank()) {
            out.append("| verify pass | ").append(timing.verifyPass()).append(" |\n");
        }
        if (!timing.total().isBlank()) {
            out.append("| total | ").append(timing.total()).append(" |\n");
        }
        return out.toString();
    }

    private static void append(StringBuilder out, Module module, String column, String error) {
        if (!error.isBlank()) {
            out.append("\n**").append(module.directory()).append(" — ").append(column).append("**\n")
                    .append("```\n").append(error).append("\n```\n");
        }
    }

    /** The rows a run releases, in the order they are tagged, every one {@link Stage#PENDING}. */
    public static List<Row> rows(Map<Module, Version> released) {
        List<Row> rows = new ArrayList<>();
        for (Module module : Order.TAG) {
            Version version = released.get(module);
            if (version != null) {
                rows.add(new Row(module, version));
            }
        }
        return List.copyOf(rows);
    }

    /** Writes it, or says what it would have written. */
    public static Path write(Runner runner, Path umbrella, LocalDateTime when, List<Row> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        Path file = path(umbrella, when);
        if (runner.dryRun()) {
            runner.say("    (dry-run) would write " + file + " (" + rows.size() + " modules)");
            return null;
        }
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new ReleaseRefusal(file.getParent() + ": could not be created (" + e.getMessage() + ")");
        }
        runner.write(file, render(when, rows));
        runner.say("Release log: releases/" + file.getFileName());
        return file;
    }

    /**
     * Reads a log back — module, version, stage, both verdicts and every error block.
     *
     * <p>The table is the contract, and reading it back rather than keeping state beside it is what lets
     * {@code --status} run a week later, from a different machine, on a log somebody else's release wrote.
     * Columns are found by their header name, so a log written before the {@code stage} column existed
     * still reads — as a whole release, which is what every one of them was.
     */
    public static List<Row> read(Path log) {
        List<String> lines;
        try {
            lines = Files.readAllLines(log);
        } catch (IOException e) {
            throw new ReleaseRefusal(log + ": could not be read (" + e.getMessage() + ")");
        }
        return parse(lines);
    }

    static List<Row> parse(List<String> lines) {
        Map<String, Integer> column = new HashMap<>();
        Map<String, String> errors = errors(lines);
        Map<String, String> timings = timings(lines);
        List<Row> rows = new ArrayList<>();
        for (String line : lines) {
            // Every section under the release table has its own shape, and one of them is a second table
            // whose rows also start with a module name. The release table is the one before any heading.
            if (line.startsWith("## ")) {
                break;
            }
            if (line.startsWith("| module |")) {
                String[] names = line.split("\\|");
                for (int i = 0; i < names.length; i++) {
                    column.put(names[i].strip(), i);
                }
                continue;
            }
            if (!line.startsWith("| botmaker-")) {
                continue;
            }
            String[] cells = line.split("\\|");
            Module module = Module.byDirectory(cells[1].strip()).orElse(null);
            Version version = Version.parse(cells[2].strip()).orElse(null);
            if (module == null || version == null) {
                continue;
            }
            Stage stage = cell(cells, column, "stage").map(Stage::fromCell).orElse(Stage.TAGGED);
            String dir = module.directory();
            String actions = verdict(cells, column, "actions");
            rows.add(new Row(module, version, stage,
                    errors.getOrDefault(dir + " — release", ""),
                    verdict(cells, column, "jitpack"),
                    linkText(actions),
                    errors.getOrDefault(dir + " — jitpack", ""),
                    errors.getOrDefault(dir + " — actions", ""),
                    timings.getOrDefault(dir, ""),
                    linkTarget(actions)));
        }
        return List.copyOf(rows);
    }

    /**
     * A cell that may be {@code [text](url)}, split back in two — {@link #linkText} and
     * {@link #linkTarget}. A cell that is not a link is its own text and has no target, which is every
     * Actions cell written before 2026-09-19.
     */
    private static final java.util.regex.Pattern LINK =
            java.util.regex.Pattern.compile("\\[(?<text>[^]]*)]\\((?<url>[^)]*)\\)");

    static String linkText(String cell) {
        java.util.regex.Matcher link = LINK.matcher(cell);
        return link.matches() ? link.group("text").strip() : cell;
    }

    static String linkTarget(String cell) {
        java.util.regex.Matcher link = LINK.matcher(cell);
        return link.matches() ? link.group("url").strip() : "";
    }

    /** A verdict cell as the poller wrote it; the placeholders a render supplies read back as unpolled. */
    private static String verdict(String[] cells, Map<String, Integer> column, String name) {
        String text = cell(cells, column, name).orElse("");
        return text.equals("pending") || text.equals("not tagged") || text.startsWith("n/a") ? "" : text;
    }

    private static Optional<String> cell(String[] cells, Map<String, Integer> column, String name) {
        Integer index = column.get(name);
        return index == null || index >= cells.length ? Optional.empty() : Optional.of(cells[index].strip());
    }

    /** {@code **<module> — <column>**} followed by a fenced block, keyed as {@code "<module> — <column>"}. */
    private static Map<String, String> errors(List<String> lines) {
        Map<String, String> errors = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("**botmaker-") || !line.endsWith("**") || i + 1 >= lines.size()
                    || !lines.get(i + 1).equals("```")) {
                continue;
            }
            StringBuilder body = new StringBuilder();
            int j = i + 2;
            for (; j < lines.size() && !lines.get(j).equals("```"); j++) {
                if (!body.isEmpty()) {
                    body.append('\n');
                }
                body.append(lines.get(j));
            }
            errors.put(line.substring(2, line.length() - 2), body.toString());
            i = j;
        }
        return errors;
    }

    /**
     * The {@code ## Timing} table as {@code step -> elapsed}, or empty for a log that has none.
     *
     * <p>Read back so that {@code --status} — which rewrites the whole file — cannot silently drop a
     * measurement it was in no position to take.
     */
    private static Map<String, String> timings(List<String> lines) {
        Map<String, String> timings = new HashMap<>();
        boolean inSection = false;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                inSection = line.strip().equals("## Timing");
                continue;
            }
            if (!inSection || !line.startsWith("| ") || line.startsWith("| step |")
                    || line.startsWith("|---")) {
                continue;
            }
            String[] cells = line.split("\\|");
            if (cells.length > 2) {
                timings.put(cells[1].strip(), cells[2].strip());
            }
        }
        return timings;
    }

    /** What the run itself took, for a caller rewriting a log it did not produce. */
    public static Timing timing(Path log) {
        List<String> lines;
        try {
            lines = Files.readAllLines(log);
        } catch (IOException e) {
            throw new ReleaseRefusal(log + ": could not be read (" + e.getMessage() + ")");
        }
        Map<String, String> timings = timings(lines);
        return new Timing(timings.getOrDefault("verify pass", ""), timings.getOrDefault("total", ""));
    }

    /** The newest log in {@code releases/}, which is what {@code --status} with no argument re-polls. */
    public static Path newest(Path umbrella) {
        Path dir = umbrella.resolve("releases");
        try (var files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".md"))
                    .max(java.util.Comparator.comparing(file -> file.getFileName().toString()))
                    .orElseThrow(() -> new ReleaseRefusal(
                            "no release log in " + dir + " — nothing to re-poll."));
        } catch (IOException e) {
            throw new ReleaseRefusal("no release log in " + dir + " — nothing to re-poll.");
        }
    }
}
